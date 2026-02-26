package com.aigentik.app.email

import android.content.Context
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.MessageEngine
import com.aigentik.app.email.GmailApiClient
import com.aigentik.app.email.GoogleVoiceMessage
import com.aigentik.app.email.ParsedEmail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// EmailMonitor v2.0 — Gmail notification trigger architecture
// REMOVED: IMAP IDLE, JavaMail, polling loops, ConnectionWatchdog dependency
//
// Flow:
//   1. Gmail app receives email → posts notification on phone
//   2. NotificationAdapter sees Gmail notification package
//   3. Calls EmailMonitor.onGmailNotification(context)
//   4. We call GmailApiClient.listUnread() via OAuth2 token
//   5. Process each unread email — GVoice texts or regular email
//   6. Mark processed emails as read
//   7. Done — no persistent connection, no battery drain
//
// Privacy: all data stays on device, Gmail API ↔ phone only
object EmailMonitor {

    private const val TAG = "EmailMonitor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track last processed Gmail historyId to avoid reprocessing
    // Reset on app restart — safe because we mark emails read after processing
    private var lastHistoryId: String? = null

    @Volatile private var isProcessing = false
    private var appContext: Context? = null

    // Called once by AigentikService.onCreate()
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "EmailMonitor initialized — waiting for Gmail notifications")
    }

    // Called by NotificationAdapter when Gmail notification is detected
    // This is the ONLY entry point — no polling, no IDLE
    fun onGmailNotification(context: Context) {
        if (!GoogleAuthManager.isSignedIn(context)) {
            Log.w(TAG, "Gmail notification received but not signed in — ignoring")
            return
        }
        if (isProcessing) {
            Log.d(TAG, "Already processing — skipping duplicate trigger")
            return
        }
        Log.i(TAG, "Gmail notification trigger — fetching unread emails")
        scope.launch { processUnread(context) }
    }

    private suspend fun processUnread(context: Context) {
        isProcessing = true
        try {
            val emails = GmailApiClient.listUnread(context, maxResults = 10)
            if (emails.isEmpty()) {
                Log.d(TAG, "No unread emails found")
                return
            }
            Log.i(TAG, "Processing ${emails.size} unread email(s)")

            for (email in emails) {
                try {
                    processEmail(context, email)
                    // Mark as read after successful processing
                    GmailApiClient.markAsRead(context, email.gmailId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process email ${email.gmailId}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processUnread failed: ${e.message}")
        } finally {
            isProcessing = false
        }
    }

    private suspend fun processEmail(context: Context, email: ParsedEmail) {
        Log.i(TAG, "Email from ${email.fromEmail}: ${email.subject.take(60)}")

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
                    // Store context so EmailRouter can reply to correct thread
                    EmailRouter.storeGVoiceContext(msg.id, gvm)
                    MessageEngine.onMessageReceived(msg)
                } else {
                    Log.w(TAG, "Failed to parse GVoice email: ${email.subject}")
                }
            }
            else -> {
                if (!ChannelManager.isEnabled(ChannelManager.Channel.EMAIL)) {
                    Log.i(TAG, "Email channel disabled — skipping")
                    return
                }
                Log.i(TAG, "Regular email from ${email.fromEmail}")
                val msg = buildEmailMessage(email)
                EmailRouter.storeEmailContext(msg.id, email)
                MessageEngine.onMessageReceived(msg)
            }
        }
    }

    // Build a Message from a Google Voice email
    private fun buildGVoiceMessage(gvm: GoogleVoiceMessage): com.aigentik.app.core.Message {
        return com.aigentik.app.core.Message(
            id          = "gvoice_${gvm.senderPhone}_${System.currentTimeMillis()}",
            sender      = gvm.senderPhone,
            senderName  = gvm.senderName,
            body        = gvm.body,
            timestamp   = System.currentTimeMillis(),
            channel     = com.aigentik.app.core.Message.Channel.EMAIL,
            threadId    = gvm.originalEmail.threadId
        )
    }

    // Build a Message from a regular email
    private fun buildEmailMessage(email: ParsedEmail): com.aigentik.app.core.Message {
        return com.aigentik.app.core.Message(
            id          = "email_${email.fromEmail}_${System.currentTimeMillis()}",
            sender      = email.fromEmail,
            senderName  = email.fromName.ifBlank { null },
            body        = email.body,
            timestamp   = System.currentTimeMillis(),
            channel     = com.aigentik.app.core.Message.Channel.EMAIL,
            threadId    = email.threadId
        )
    }

    // Legacy compat — called by ConnectionWatchdog stub and old code
    fun isRunning(): Boolean = !isProcessing

    // No-op stubs for backward compat with old call sites
    fun start() { Log.i(TAG, "EmailMonitor v2 — notification-driven, no start needed") }
    fun stop()  { Log.i(TAG, "EmailMonitor v2 — no persistent connection to stop") }
}
