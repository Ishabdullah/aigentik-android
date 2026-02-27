package com.aigentik.app.email

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// GmailPushManager v1.0 — Gmail API Watch + Google Cloud Pub/Sub push notifications
//
// Flow:
//   1. App calls setup() after OAuth sign-in
//   2. Creates Pub/Sub topic "aigentik-gmail-push" and pull subscription
//   3. Calls Gmail watch() to register topic for INBOX changes
//   4. EmailMonitor polls subscription every 30s via pullMessages()
//   5. Each Pub/Sub message carries a historyId — passed to GmailHistoryClient
//   6. GmailHistoryClient fetches only new INBOX messages since that historyId
//   7. Processed messages are acknowledged and removed from Pub/Sub queue
//
// Watch expires every 7 days — renewIfExpiring() handles automatic renewal
// Falls back gracefully if setup fails — NotificationAdapter listener still works
//
// Google Cloud Console required setup (one-time, see README):
//   1. Enable Pub/Sub API
//   2. Grant gmail-api-push@system.gserviceaccount.com Pub/Sub Publisher role on topic
object GmailPushManager {

    private const val TAG = "GmailPushManager"

    // GCP project must match google-services.json project_id
    private const val PROJECT_ID = "aigentik-android"
    private const val TOPIC_PATH = "projects/$PROJECT_ID/topics/aigentik-gmail-push"
    private const val SUB_PATH   = "projects/$PROJECT_ID/subscriptions/aigentik-gmail-sub"

    private const val PUBSUB_BASE = "https://pubsub.googleapis.com/v1"
    private const val GMAIL_BASE  = "https://gmail.googleapis.com/gmail/v1/users/me"

    // Watch expiry: Gmail guarantees 7 days — renew when within 1 day of expiry
    private const val RENEW_THRESHOLD_MS = 24 * 60 * 60 * 1000L

    private const val PREF_FILE       = "gmail_push"
    private const val PREF_HISTORY_ID = "history_id"
    private const val PREF_WATCH_EXP  = "watch_expiry_ms"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // In-memory cache of last known historyId
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

    // --- One-time setup ---

    // Call after OAuth sign-in and on every service start
    // Returns true if Gmail watch is active and polling can begin
    suspend fun setup(context: Context): Boolean {
        if (!GoogleAuthManager.isSignedIn(context)) {
            Log.w(TAG, "Not signed in — push setup skipped")
            return false
        }
        loadHistoryId(context)

        val topicOk = ensureTopic(context)
        if (!topicOk) {
            Log.e(TAG, "Pub/Sub topic creation failed — check Pub/Sub API is enabled and IAM is set")
            return false
        }
        ensureSubscription(context)

        val historyId = registerWatch(context)
        return if (historyId != null) {
            // Only set initial historyId if we don't have one stored
            // Preserving old historyId ensures we don't miss emails since last run
            if (cachedHistoryId == null) {
                saveHistoryId(context, historyId)
                Log.i(TAG, "Push setup complete — initial historyId=$historyId")
            } else {
                Log.i(TAG, "Push setup complete — using stored historyId=$cachedHistoryId")
            }
            true
        } else {
            Log.e(TAG, "Gmail watch registration failed")
            false
        }
    }

    // Renew Gmail watch if expiring within 1 day
    suspend fun renewIfExpiring(context: Context) {
        val expMs = prefs(context).getLong(PREF_WATCH_EXP, 0L)
        if (expMs == 0L || System.currentTimeMillis() > expMs - RENEW_THRESHOLD_MS) {
            Log.i(TAG, "Gmail watch expiring soon — renewing")
            val historyId = registerWatch(context)
            if (historyId != null) {
                Log.i(TAG, "Watch renewed successfully")
            } else {
                Log.w(TAG, "Watch renewal failed — will retry next poll")
            }
        }
    }

    // --- Pub/Sub topic management ---

    // PUT is idempotent — creates topic if it doesn't exist, 409 = already exists (OK)
    private suspend fun ensureTopic(context: Context): Boolean = withContext(Dispatchers.IO) {
        val token = GoogleAuthManager.getFreshToken(context) ?: return@withContext false
        val req = Request.Builder()
            .url("$PUBSUB_BASE/$TOPIC_PATH")
            .header("Authorization", "Bearer $token")
            .put("{}".toRequestBody("application/json".toMediaType()))
            .build()
        try {
            val code = http.newCall(req).execute().code
            val ok = code in listOf(200, 409) // 409 = already exists
            if (ok) Log.i(TAG, "Pub/Sub topic ready ($code)")
            else Log.e(TAG, "Topic create failed: HTTP $code")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Topic create error: ${e.message}")
            false
        }
    }

    // PUT subscription — idempotent, 409 = already exists (OK)
    private suspend fun ensureSubscription(context: Context) = withContext(Dispatchers.IO) {
        val token = GoogleAuthManager.getFreshToken(context) ?: return@withContext
        // ackDeadlineSeconds=60 — gives us time to process before re-delivery
        val body = """{"topic":"$TOPIC_PATH","ackDeadlineSeconds":60}"""
        val req = Request.Builder()
            .url("$PUBSUB_BASE/$SUB_PATH")
            .header("Authorization", "Bearer $token")
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            val code = http.newCall(req).execute().code
            if (code in listOf(200, 409)) Log.i(TAG, "Pub/Sub subscription ready ($code)")
            else Log.e(TAG, "Subscription create failed: HTTP $code")
        } catch (e: Exception) {
            Log.e(TAG, "Subscription create error: ${e.message}")
        }
    }

    // --- Gmail Watch ---

    // Register Gmail watch for INBOX — returns starting historyId
    private suspend fun registerWatch(context: Context): String? = withContext(Dispatchers.IO) {
        val token = GoogleAuthManager.getFreshToken(context) ?: return@withContext null
        // labelIds=INBOX — only notify on emails added to inbox (not sent, draft, spam)
        val body = """{"topicName":"$TOPIC_PATH","labelIds":["INBOX"],"labelFilterBehavior":"INCLUDE"}"""
        val req = Request.Builder()
            .url("$GMAIL_BASE/watch")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Watch failed: HTTP ${resp.code} — ${resp.body?.string()?.take(300)}")
                return@withContext null
            }
            val json = JsonParser.parseString(resp.body?.string()).asJsonObject
            val historyId = json.get("historyId")?.asString
            val expiration = json.get("expiration")?.asLong ?: 0L
            if (expiration > 0) {
                prefs(context).edit().putLong(PREF_WATCH_EXP, expiration).apply()
            }
            Log.i(TAG, "Watch registered — historyId=$historyId expiry=${expiration}ms")
            historyId
        } catch (e: Exception) {
            Log.e(TAG, "Watch error: ${e.message}")
            null
        }
    }

    // --- Pub/Sub pull ---

    // Pull pending messages from the subscription
    // Returns list of (historyId, ackId) pairs — historyId may be null if parsing fails
    suspend fun pullMessages(context: Context): List<Pair<String?, String>> =
        withContext(Dispatchers.IO) {
            val token = GoogleAuthManager.getFreshToken(context) ?: return@withContext emptyList()
            val body = """{"maxMessages":10}"""
            val req = Request.Builder()
                .url("$PUBSUB_BASE/$SUB_PATH:pull")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) return@withContext emptyList()
                val bodyStr = resp.body?.string() ?: return@withContext emptyList()
                val json = JsonParser.parseString(bodyStr).asJsonObject
                val messages = json.getAsJsonArray("receivedMessages")
                    ?: return@withContext emptyList()

                messages.mapNotNull { el ->
                    val obj   = el.asJsonObject
                    val ackId = obj.get("ackId")?.asString ?: return@mapNotNull null
                    val data  = obj.getAsJsonObject("message")?.get("data")?.asString

                    // Pub/Sub data is base64-encoded JSON: {"emailAddress":"…","historyId":"…"}
                    val historyId = if (data != null) {
                        try {
                            val decoded = String(Base64.decode(data, Base64.DEFAULT))
                            JsonParser.parseString(decoded).asJsonObject
                                .get("historyId")?.asString
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not parse Pub/Sub data: ${e.message}")
                            null
                        }
                    } else null

                    Pair(historyId, ackId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pull failed: ${e.message}")
                emptyList()
            }
        }

    // Acknowledge messages so Pub/Sub doesn't redeliver them
    suspend fun acknowledge(context: Context, ackIds: List<String>) =
        withContext(Dispatchers.IO) {
            if (ackIds.isEmpty()) return@withContext
            val token = GoogleAuthManager.getFreshToken(context) ?: return@withContext
            // Build ackIds JSON array manually
            val ackJson = ackIds.joinToString("\",\"", "[\"", "\"]")
            val body = """{"ackIds":$ackJson}"""
            val req = Request.Builder()
                .url("$PUBSUB_BASE/$SUB_PATH:acknowledge")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                http.newCall(req).execute()
                Log.d(TAG, "Acknowledged ${ackIds.size} Pub/Sub message(s)")
            } catch (e: Exception) {
                Log.w(TAG, "Acknowledge failed (non-fatal): ${e.message}")
            }
        }
}
