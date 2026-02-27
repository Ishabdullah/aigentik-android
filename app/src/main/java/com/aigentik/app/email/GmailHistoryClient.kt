package com.aigentik.app.email

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// GmailHistoryClient v1.1 — fetch only new INBOX messages since a known historyId
//
// Why History API instead of listUnread():
//   listUnread() fetches everything unread — may include old emails
//   History API fetches only messages ADDED since a specific point in time
//   This means we process only genuinely new arrivals, never re-process old ones
//
// historyTypes=messageAdded — we only care about new messages, not label changes
// labelId=INBOX — filter to inbox only (ignores sent, draft, spam additions)
//
// v1.1: Added on-device historyId persistence (SharedPreferences, no cloud relay)
//   primeHistoryId() — fetches current historyId from Gmail profile API on first init
//   On restart: stored historyId used to catch emails that arrived while app was off
object GmailHistoryClient {

    private const val TAG  = "GmailHistoryClient"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    private const val PREF_FILE       = "gmail_history"
    private const val PREF_HISTORY_ID = "history_id"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedHistoryId: String? = null

    // --- Persistent historyId storage ---

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun loadHistoryId(context: Context): String? {
        cachedHistoryId = prefs(context).getString(PREF_HISTORY_ID, null)
        return cachedHistoryId
    }

    fun getHistoryId(): String? = cachedHistoryId

    fun saveHistoryId(context: Context, historyId: String) {
        if (historyId == cachedHistoryId) return
        cachedHistoryId = historyId
        prefs(context).edit().putString(PREF_HISTORY_ID, historyId).apply()
        Log.d(TAG, "historyId saved: $historyId")
    }

    // --- Initial prime ---

    // Call once on service start to establish a baseline historyId.
    // Uses Gmail profile API: GET .../me/profile → { historyId, emailAddress, ... }
    // If a historyId is already stored, skips the API call (preserves continuity).
    // On restart we process only emails that arrived since last shutdown.
    suspend fun primeHistoryId(context: Context) = withContext(Dispatchers.IO) {
        loadHistoryId(context)
        if (cachedHistoryId != null) {
            Log.i(TAG, "Using stored historyId=$cachedHistoryId")
            return@withContext
        }

        // No stored historyId — fetch current from Gmail profile
        val token = GoogleAuthManager.getFreshToken(context) ?: run {
            Log.w(TAG, "primeHistoryId: not signed in — skipping")
            return@withContext
        }
        val req = Request.Builder()
            .url("$BASE/profile")
            .header("Authorization", "Bearer $token")
            .build()
        try {
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Profile API HTTP ${resp.code}")
                return@withContext
            }
            val json = JsonParser.parseString(resp.body?.string()).asJsonObject
            val historyId = json.get("historyId")?.asString
            if (historyId != null) {
                saveHistoryId(context, historyId)
                Log.i(TAG, "historyId primed from Gmail profile: $historyId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "primeHistoryId error: ${e.message}")
        }
    }

    // --- History fetch ---

    // Returns list of Gmail message IDs added to INBOX since startHistoryId.
    // Also returns the latest historyId from the response for advancing the cursor.
    // Returns Pair(messageIds, latestHistoryId).
    // On 404 (historyId purged by Gmail): returns Pair(emptyList, null) — caller resets via primeHistoryId.
    suspend fun getNewInboxMessageIds(
        context: Context,
        startHistoryId: String
    ): Pair<List<String>, String?> = withContext(Dispatchers.IO) {

        val token = GoogleAuthManager.getFreshToken(context)
            ?: return@withContext Pair(emptyList(), null)

        val url = "$BASE/history" +
            "?startHistoryId=$startHistoryId" +
            "&historyTypes=messageAdded" +
            "&labelId=INBOX" +
            "&maxResults=20"

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        try {
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(200)
                // 404 means historyId is too old (purged by Gmail) — caller should reset
                if (resp.code == 404) {
                    Log.w(TAG, "historyId $startHistoryId is expired — needs reset")
                    return@withContext Pair(emptyList<String>(), null)
                }
                Log.e(TAG, "History API HTTP ${resp.code}: $errBody")
                return@withContext Pair(emptyList(), null)
            }

            val json = JsonParser.parseString(resp.body?.string()).asJsonObject

            // The response historyId is always the latest, even if no new messages
            val latestHistoryId = json.get("historyId")?.asString

            val historyArray = json.getAsJsonArray("history")
            if (historyArray == null || historyArray.size() == 0) {
                Log.d(TAG, "No new history entries since $startHistoryId")
                return@withContext Pair(emptyList(), latestHistoryId)
            }

            val ids = mutableListOf<String>()
            for (entry in historyArray) {
                val added = entry.asJsonObject.getAsJsonArray("messagesAdded") ?: continue
                for (item in added) {
                    val msgObj = item.asJsonObject.getAsJsonObject("message") ?: continue
                    val labels = msgObj.getAsJsonArray("labelIds")

                    // Only process messages that are in INBOX and still UNREAD
                    // UNREAD check prevents reprocessing if another client already read it
                    val inInbox  = labels?.any { it.asString == "INBOX"  } == true
                    val isUnread = labels?.any { it.asString == "UNREAD" } == true

                    if (inInbox && isUnread) {
                        val msgId = msgObj.get("id")?.asString ?: continue
                        ids.add(msgId)
                    }
                }
            }

            Log.i(TAG, "History: ${ids.size} new INBOX+UNREAD message(s) since $startHistoryId → $latestHistoryId")
            Pair(ids.distinct(), latestHistoryId)

        } catch (e: Exception) {
            Log.e(TAG, "History fetch error: ${e.message}")
            Pair(emptyList(), null)
        }
    }
}
