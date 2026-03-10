package com.aigentik.app.email

import android.content.Context
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.MessageEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

// EmailMonitor v4.3 — on-device notification-triggered Gmail processing
// v4.3: processUnread() capped at 3 emails (was 10) (code-audit-2026-03-10).
//   On first install there is no stored historyId, so every Gmail notification falls
//   through to the listUnread() fallback. Fetching 10 emails dispatched all 10 to
//   MessageEngine.onMessageReceived() simultaneously. MessageEngine's new messageMutex
//   (v2.1) serialises them, but they were still all alive holding email data + waiting
//   for the mutex. Capping at 3 limits the queue depth for the fallback path.
//   The History API path (primary, after historyId is primed) always fetches only the
//   delta since last check, so no cap needed there.
//
// v4.2: Hard crash prevention.
//   1. CoroutineExceptionHandler added to scope — previously EmailMonitor's scope had no
//      handler, so an unhandled Error (OOM, StackOverflow from Html.fromHtml on large HTML)
//      inside a launched coroutine escaped to Android's default UncaughtExceptionHandler
//      which kills the process. Handler now logs and does not re-throw.
//   2. catch(e: Exception) widened to catch(e: Throwable) in per-email try/catch blocks.
//      OOM and StackOverflowError are Error subclasses — they bypassed catch(Exception).
//   Also updated storeEmailContext/storeGVoiceContext calls to pass sender key (not messageId)
//   so EmailRouter can find the MOST RECENT email per sender for replies.
//
// v4.1: Fixed isProcessing race condition.
//   Previous: @Volatile Boolean checked in onGmailNotification() but set to true INSIDE
//   the launched coroutine. Two rapid notifications could both pass the check (both see
//   false) before either coroutine body ran, launching two concurrent fetch coroutines.
//   Fix: replaced with AtomicBoolean + compareAndSet(false, true) in onGmailNotification().
//   compareAndSet atomically reads+writes in one operation — only one caller can win.
//   The winner sets it true; all others see true and return early. The finally { set(false) }
//   is in the scope.launch wrapper so it always executes even if the coroutine throws.
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

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
            // Catches unhandled errors (OOM, StackOverflow, etc.) from launched coroutines.
            // Without this handler, such errors escape to Android's UncaughtExceptionHandler
            // which kills the entire process — causing the "app has a bug" hard crash dialog.
            Log.e(TAG, "Uncaught error in EmailMonitor scope: ${e.javaClass.simpleName}: ${e.message}", e)
            isProcessing.set(false)  // ensure lock is released even on hard error
        }
    )

    // AtomicBoolean: compareAndSet(false, true) is a single atomic read+write operation.
    // This eliminates the race window that existed with @Volatile Boolean (check then set).
    private val isProcessing = AtomicBoolean(false)
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
        // compareAndSet(false, true): atomically check-and-set in one operation.
        // Only one caller can win (transitions false→true). All others see true and return.
        if (!isProcessing.compareAndSet(false, true)) {
            Log.d(TAG, "Already processing — skipping duplicate notification trigger")
            return
        }
        Log.i(TAG, "Gmail notification received — triggering fetch")
        scope.launch {
            try {
                val storedHistoryId = GmailHistoryClient.loadHistoryId(context)
                if (storedHistoryId != null) {
                    Log.d(TAG, "History API path — historyId=$storedHistoryId")
                    processFromHistory(context, storedHistoryId)
                } else {
                    Log.d(TAG, "No historyId stored — using listUnread fallback")
                    processUnread(context)
                }
            } finally {
                // Always release the lock — even if processFromHistory/processUnread throws.
                isProcessing.set(false)
            }
        }
    }

    // ─── History-based processing (primary, when historyId available) ──────────

    // No try/finally needed here — isProcessing is reset in onGmailNotification's launch wrapper.
    // Any exception from this function propagates to the launch wrapper's finally { isProcessing.set(false) }.
    private suspend fun processFromHistory(context: Context, startHistoryId: String) {
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
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to process email $msgId: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        // Always advance historyId after a successful History API call
        val newHistoryId = updatedHistoryId ?: startHistoryId
        GmailHistoryClient.saveHistoryId(context, newHistoryId)
    }

    // ─── listUnread fallback (when no historyId stored) ────────────────────────

    // No try/finally needed here — isProcessing is reset in onGmailNotification's launch wrapper.
    private suspend fun processUnread(context: Context) {
        // Cap at 3 emails on the fallback path (no historyId stored).
        // On first install all unread emails hit this path simultaneously — 10 would
        // dispatch 10 concurrent MessageEngine coroutines. 3 limits queue depth while
        // still processing the most recent emails. MessageEngine.messageMutex (v2.1)
        // serialises execution but the cap reduces memory from waiting coroutines.
        val emails = GmailApiClient.listUnread(context, maxResults = 3)
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
            } catch (e: Throwable) {
                Log.e(TAG, "Fallback email processing failed: ${e.javaClass.simpleName}: ${e.message}")
            }
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
                    // Key by senderPhone so routeReply() can find the most recent context
                    // for this sender directly, without iterating all entries
                    EmailRouter.storeGVoiceContext(gvm.senderPhone, gvm)
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
                // Key by fromEmail so routeReply() always uses the most recent email
                // from this sender (overwriting the previous entry for same sender)
                EmailRouter.storeEmailContext(email.fromEmail, email)
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

    fun isRunning(): Boolean = !isProcessing.get()
    fun start()  { Log.i(TAG, "EmailMonitor v4 — triggered by Gmail app notifications") }
    fun stop()   { Log.i(TAG, "EmailMonitor v4 stopped") }
}
