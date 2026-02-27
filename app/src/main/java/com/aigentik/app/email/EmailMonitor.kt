package com.aigentik.app.email

import android.content.Context
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.MessageEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// EmailMonitor v4.0 — on-device notification-triggered Gmail processing
//
// Trigger: Gmail app posts a notification → NotificationAdapter.onNotificationPosted()
//          detects package=com.google.android.gm → calls onGmailNotification()
//
// Processing path:
//   1. If historyId stored → History API fetch (only new messages since last check)
//   2. If no historyId   → listUnread() fallback (full unread scan, max 10 results)
//   3. historyId is advanced after each successful History API fetch
//   4. historyId is primed from Gmail profile API on service start (AigentikService)
//
// Privacy guarantee:
//   - Triggered only by local Android system notifications (no cloud relay)
//   - All API calls go directly from device to Gmail via OAuth2 Bearer token
//   - No third-party server, no Pub/Sub, no polling loops, no persistent connections
//   - Email content is processed on-device by llama.cpp — never leaves the device
object EmailMonitor {

    private const val TAG = "EmailMonitor"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var isProcessing = false
    private var appContext: Context? = null

    // Called once by AigentikService.onCreate()
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "EmailMonitor v4 initialized — awaiting Gmail notifications")
    }

    // ─── Primary trigger — Gmail notification ─────────────────────────────────

    // Called by NotificationAdapter when Gmail app posts a notification.
    // Uses History API if historyId is stored, otherwise falls back to listUnread().
    // isProcessing flag prevents overlapping fetches from rapid notification bursts.
    fun onGmailNotification(context: Context) {
        if (!GoogleAuthManager.isSignedIn(context)) {
            Log.w(TAG, "Gmail notification received but not signed in — ignoring")
            return
        }
        if (isProcessing) {
            Log.d(TAG, "Already processing — skipping duplicate notification trigger")
            return
        }
        Log.i(TAG, "Gmail notification received — triggering fetch")
        scope.launch {
            val storedHistoryId = GmailHistoryClient.loadHistoryId(context)
            if (storedHistoryId != null) {
                Log.d(TAG, "History API path — historyId=$storedHistoryId")
                processFromHistory(context, storedHistoryId)
            } else {
                Log.d(TAG, "No historyId stored — using listUnread fallback")
                processUnread(context)
            }
        }
    }

    // ─── History-based processing (primary, when historyId available) ──────────

    private suspend fun processFromHistory(context: Context, startHistoryId: String) {
        isProcessing = true
        try {
            val (msgIds, updatedHistoryId) = GmailHistoryClient.getNewInboxMessageIds(
                context, startHistoryId
            )

            // 404 from History API means historyId was purged (too old)
            // Reset by re-priming from Gmail profile
            if (updatedHistoryId == null && msgIds.isEmpty()) {
                Log.w(TAG, "historyId expired — re-priming from Gmail profile")
                GmailHistoryClient.primeHistoryId(context)
                return
            }

            if (msgIds.isNotEmpty()) {
                Log.i(TAG, "Processing ${msgIds.size} new email(s) via History API")
                val ownEmail = GoogleAuthManager.getSignedInEmail(context) ?: ""
                for (msgId in msgIds) {
                    try {
                        val email = GmailApiClient.getFullEmail(context, msgId) ?: continue
                        if (!email.isUnread) {
                            Log.d(TAG, "Skipping already-read email: $msgId")
                            continue
                        }
                        // Skip own emails — prevents auto-reply loop
                        if (email.fromEmail.equals(ownEmail, ignoreCase = true)) {
                            Log.d(TAG, "Skipping own email: $msgId")
                            continue
                        }
                        processEmail(context, email)
                        GmailApiClient.markAsRead(context, email.gmailId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process email $msgId: ${e.message}")
                    }
                }
            }

            // Always advance historyId after a successful History API call
            val newHistoryId = updatedHistoryId ?: startHistoryId
            GmailHistoryClient.saveHistoryId(context, newHistoryId)

        } finally {
            isProcessing = false
        }
    }

    // ─── listUnread fallback (when no historyId stored) ────────────────────────

    private suspend fun processUnread(context: Context) {
        isProcessing = true
        try {
            val emails = GmailApiClient.listUnread(context, maxResults = 10)
            if (emails.isEmpty()) {
                Log.d(TAG, "Fallback: no unread emails")
                return
            }
            Log.i(TAG, "Fallback: processing ${emails.size} unread email(s)")
            val ownEmail = GoogleAuthManager.getSignedInEmail(context) ?: ""
            for (email in emails) {
                try {
                    if (email.fromEmail.equals(ownEmail, ignoreCase = true)) continue
                    processEmail(context, email)
                    GmailApiClient.markAsRead(context, email.gmailId)
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback email processing failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processUnread fallback failed: ${e.message}")
        } finally {
            isProcessing = false
        }
    }

    // ─── Core email processing ─────────────────────────────────────────────────

    private suspend fun processEmail(context: Context, email: ParsedEmail) {
        Log.i(TAG, "Processing email from ${email.fromEmail}: ${email.subject.take(60)}")

        when {
            GmailApiClient.isGoogleVoiceText(email.subject) -> {
                if (!ChannelManager.isEnabled(ChannelManager.Channel.GVOICE)) {
                    Log.i(TAG, "GVoice channel disabled — skipping")
                    return
                }
                val gvm = GmailApiClient.parseGoogleVoiceEmail(email)
                if (gvm != null) {
                    Log.i(TAG, "GVoice SMS from ${gvm.senderName} (${gvm.senderPhone})")
                    val msg = buildGVoiceMessage(gvm)
                    EmailRouter.storeGVoiceContext(msg.id, gvm)
                    MessageEngine.onMessageReceived(msg)
                } else {
                    Log.w(TAG, "GVoice parse failed: ${email.subject}")
                }
            }
            else -> {
                if (!ChannelManager.isEnabled(ChannelManager.Channel.EMAIL)) {
                    Log.i(TAG, "Email channel disabled — skipping")
                    return
                }
                val msg = buildEmailMessage(email)
                EmailRouter.storeEmailContext(msg.id, email)
                MessageEngine.onMessageReceived(msg)
            }
        }
    }

    // ─── Message builders ──────────────────────────────────────────────────────

    private fun buildGVoiceMessage(gvm: GoogleVoiceMessage): com.aigentik.app.core.Message {
        return com.aigentik.app.core.Message(
            id         = gvm.originalEmail.gmailId,
            sender     = gvm.senderPhone,
            senderName = gvm.senderName,
            body       = gvm.body,
            timestamp  = System.currentTimeMillis(),
            channel    = com.aigentik.app.core.Message.Channel.EMAIL,
            threadId   = gvm.originalEmail.threadId,
            subject    = gvm.originalEmail.subject
        )
    }

    private fun buildEmailMessage(email: ParsedEmail): com.aigentik.app.core.Message {
        return com.aigentik.app.core.Message(
            id         = email.gmailId,
            sender     = email.fromEmail,
            senderName = email.fromName.ifBlank { null },
            body       = email.body,
            timestamp  = System.currentTimeMillis(),
            channel    = com.aigentik.app.core.Message.Channel.EMAIL,
            threadId   = email.threadId,
            subject    = email.subject
        )
    }

    // ─── Compat stubs ──────────────────────────────────────────────────────────

    fun isRunning(): Boolean = !isProcessing
    fun start()  { Log.i(TAG, "EmailMonitor v4 — triggered by Gmail app notifications") }
    fun stop()   { Log.i(TAG, "EmailMonitor v4 stopped") }
}
