package com.aigentik.app.email

import android.content.Context
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.MessageEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// EmailMonitor v3.0 — dual-mode email processing
//
// Mode 1 — Gmail Push (primary):
//   Gmail Watch → Pub/Sub topic → Android pull subscription → History API → process
//   30-second polling interval on the pull subscription (foreground service coroutine)
//   Near-real-time: average latency ≤ 30s from email arrival
//
// Mode 2 — NotificationAdapter fallback (secondary):
//   Gmail app shows notification → NotificationAdapter sees it → onGmailNotification()
//   Fallback covers: app restart before first push poll, Pub/Sub setup failures
//
// Both modes call processEmail() which is idempotent:
//   - History API already filters to INBOX+UNREAD messages
//   - markAsRead() after processing prevents re-processing from notification fallback
//
// Privacy: all data stays on device, no third-party servers
object EmailMonitor {

    private const val TAG          = "EmailMonitor"
    private const val POLL_INTERVAL_MS = 30_000L  // 30 seconds

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var isProcessing = false
    private var appContext: Context? = null
    private var pushPollingJob: Job? = null

    // Called once by AigentikService.onCreate()
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "EmailMonitor v3 initialized")
    }

    // ─── Push polling (primary trigger) ───────────────────────────────────────

    // Launch 30-second Pub/Sub pull loop inside the foreground service
    // Must be called after GmailPushManager.setup() so historyId is available
    fun startPushPolling(context: Context) {
        pushPollingJob?.cancel()
        val ctx = context.applicationContext
        pushPollingJob = scope.launch {
            Log.i(TAG, "Push polling started (${POLL_INTERVAL_MS / 1000}s interval)")
            while (isActive) {
                try {
                    if (GoogleAuthManager.isSignedIn(ctx)) {
                        // Renew Gmail watch before it expires (7-day lifetime)
                        GmailPushManager.renewIfExpiring(ctx)

                        // Pull pending Pub/Sub notifications
                        val messages = GmailPushManager.pullMessages(ctx)

                        if (messages.isNotEmpty()) {
                            Log.d(TAG, "Pulled ${messages.size} Pub/Sub message(s)")

                            // Pick the highest historyId from this batch
                            // Lower historyIds are subsets of higher ones
                            val latestHistoryId = messages
                                .mapNotNull { it.first }
                                .maxByOrNull { it.toLongOrNull() ?: 0L }

                            val storedHistoryId = GmailPushManager.getHistoryId()
                            if (latestHistoryId != null && storedHistoryId != null) {
                                processFromHistory(ctx, storedHistoryId, latestHistoryId)
                            } else if (storedHistoryId == null) {
                                // No stored historyId — fall back to listing unread
                                Log.w(TAG, "No stored historyId — falling back to listUnread")
                                processUnread(ctx)
                            }

                            // Acknowledge all pulled messages regardless of processing result
                            // Re-delivery on ack failure is acceptable (processEmail is idempotent)
                            val ackIds = messages.map { it.second }
                            GmailPushManager.acknowledge(ctx, ackIds)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Push poll error (non-fatal): ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
            Log.i(TAG, "Push polling stopped")
        }
    }

    fun stopPushPolling() {
        pushPollingJob?.cancel()
        pushPollingJob = null
    }

    // ─── History-based processing ──────────────────────────────────────────────

    // Fetch only new INBOX messages since startHistoryId, process them,
    // then advance the stored historyId to latestHistoryId
    private suspend fun processFromHistory(
        context: Context,
        startHistoryId: String,
        latestHistoryId: String
    ) {
        val (msgIds, updatedHistoryId) = GmailHistoryClient.getNewInboxMessageIds(
            context, startHistoryId
        )

        // If historyId was purged (too old), reset by re-running full setup
        if (updatedHistoryId == null && msgIds.isEmpty()) {
            Log.w(TAG, "historyId expired — re-running watch setup")
            val ok = GmailPushManager.setup(context)
            if (!ok) Log.e(TAG, "Watch re-setup failed")
            return
        }

        if (msgIds.isNotEmpty()) {
            Log.i(TAG, "Processing ${msgIds.size} new email(s) via History API")
            for (msgId in msgIds) {
                try {
                    val email = GmailApiClient.getFullEmail(context, msgId) ?: continue
                    if (!email.isUnread) {
                        Log.d(TAG, "Skipping already-read email: $msgId")
                        continue
                    }
                    // Skip own emails (prevents auto-reply loop)
                    val ownEmail = GoogleAuthManager.getSignedInEmail(context) ?: ""
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

        // Always advance historyId — even if no messages were processed
        val newHistoryId = updatedHistoryId ?: latestHistoryId
        GmailPushManager.saveHistoryId(context, newHistoryId)
    }

    // ─── NotificationAdapter fallback (secondary trigger) ─────────────────────

    // Called by NotificationAdapter when Gmail app posts a notification
    // Acts as a fallback if Pub/Sub poll hasn't fired yet
    fun onGmailNotification(context: Context) {
        if (!GoogleAuthManager.isSignedIn(context)) {
            Log.w(TAG, "Gmail notification received but not signed in — ignoring")
            return
        }
        if (isProcessing) {
            Log.d(TAG, "Already processing — skipping duplicate notification trigger")
            return
        }
        Log.i(TAG, "Gmail notification fallback trigger")
        scope.launch { processUnread(context) }
    }

    // Fallback: fetch and process all unread inbox emails (no historyId required)
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
            // Use gmailId directly for reliable deduplication
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

    fun isRunning(): Boolean = pushPollingJob?.isActive == true
    fun start() { Log.i(TAG, "EmailMonitor v3 — use startPushPolling()") }
    fun stop()  { stopPushPolling() }
}
