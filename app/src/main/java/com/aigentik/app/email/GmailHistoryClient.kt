package com.aigentik.app.email

import android.content.Context
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// GmailHistoryClient v1.0 — fetch only new INBOX messages since a known historyId
//
// Why History API instead of listUnread():
//   listUnread() fetches everything unread — may include old emails
//   History API fetches only messages ADDED since a specific point in time
//   This means we process only genuinely new arrivals, never re-process old ones
//
// historyTypes=messageAdded — we only care about new messages, not label changes
// labelId=INBOX — filter to inbox only (ignores sent, draft, spam additions)
object GmailHistoryClient {

    private const val TAG  = "GmailHistoryClient"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Returns list of Gmail message IDs added to INBOX since startHistoryId
    // Also returns the latest historyId from the response for future calls
    // Returns Pair(messageIds, latestHistoryId)
    suspend fun getNewInboxMessageIds(
        context: Context,
        startHistoryId: String
    ): Pair<List<String>, String?> = withContext(Dispatchers.IO) {

        val token = GoogleAuthManager.getFreshToken(context)
            ?: return@withContext Pair(emptyList(), null)

        // historyTypes=messageAdded — only care about new messages landing in inbox
        // labelId=INBOX — skip sent, drafts, labels applied to existing messages
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
                // No changes since startHistoryId — historyId may still have advanced
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
