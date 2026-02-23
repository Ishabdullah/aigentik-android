package com.aigentik.app.email

import android.util.Log
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.MessageEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// EmailMonitor v1.0
// Upgrade from v0.6: IMAP IDLE instead of polling
// Flow:
//   1. Connect to Gmail IMAP
//   2. Open INBOX in IDLE mode — server pushes new mail events
//   3. On event: poll unseen, process, re-enter IDLE
//   4. On connection drop: reconnect with backoff
//   5. Respects ChannelManager — checks GVOICE and EMAIL channel states
//
// NOTE: IDLE requires IMAPFolder from JavaMail/Jakarta Mail
//   If com.sun.mail.imap.IMAPFolder is not available, falls back to 30s poll
//   Both GVoice SMS and regular emails are handled here
object EmailMonitor {

    private const val TAG = "EmailMonitor"
    private const val RECONNECT_DELAY_MS = 10_000L
    private const val FALLBACK_POLL_MS   = 30_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "EmailMonitor starting with IMAP IDLE")
        scope.launch { runIdleLoop() }
    }

    private suspend fun runIdleLoop() {
        var backoff = RECONNECT_DELAY_MS

        while (isRunning) {
            try {
                // Connect
                val connected = GmailClient.connect()
                if (!connected) {
                    Log.w(TAG, "Connection failed — retry in ${backoff}ms")
                    delay(backoff)
                    backoff = minOf(backoff * 2, 300_000L) // cap at 5 min
                    continue
                }
                backoff = RECONNECT_DELAY_MS // reset on success

                // Process any emails that arrived while we were disconnected
                processUnseen()

                // Open IDLE folder
                val folder = GmailClient.openIdleFolder()
                if (folder == null) {
                    Log.w(TAG, "IDLE folder open failed — falling back to poll")
                    delay(FALLBACK_POLL_MS)
                    processUnseen()
                    continue
                }

                // IDLE loop — re-enter after each notification
                while (isRunning && folder.isOpen) {
                    Log.d(TAG, "Entering IDLE...")
                    val newMail = GmailClient.waitForNewMail(folder)
                    if (newMail) {
                        Log.i(TAG, "IDLE notification — processing unseen")
                        processUnseen()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "IDLE loop error: ${e.message} — reconnecting in ${backoff}ms")
                delay(backoff)
                backoff = minOf(backoff * 2, 300_000L)
            }
        }
    }

    private suspend fun processUnseen() {
        GmailClient.pollUnseen { email ->
            Log.i(TAG, "Email from ${email.fromEmail}: ${email.subject.take(50)}")

            when {
                GmailClient.isGoogleVoiceText(email.subject) -> {
                    // Google Voice SMS forwarded as email
                    if (!ChannelManager.isEnabled(ChannelManager.Channel.GVOICE)) {
                        Log.i(TAG, "GVoice channel disabled — skipping")
                        return@pollUnseen
                    }
                    val gvm = GmailClient.parseGoogleVoiceEmail(email)
                    if (gvm != null) {
                        val msg = GmailClient.toMessage(gvm)
                        if (msg != null) {
                            Log.i(TAG, "GVoice from ${gvm.senderName} (${gvm.senderPhone})")
                            EmailRouter.storeGVoiceContext(msg.id, gvm)
                            MessageEngine.onMessageReceived(msg)
                        }
                    }
                }
                else -> {
                    // Regular email
                    if (!ChannelManager.isEnabled(ChannelManager.Channel.EMAIL)) {
                        Log.i(TAG, "Email channel disabled — skipping")
                        return@pollUnseen
                    }
                    val msg = GmailClient.regularEmailToMessage(email)
                    if (msg != null) {
                        Log.i(TAG, "Regular email from ${email.fromEmail}")
                        // Store email context so we can reply properly
                        EmailRouter.storeEmailContext(msg.id, email)
                        MessageEngine.onMessageReceived(msg)
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        GmailClient.disconnect()
        Log.i(TAG, "EmailMonitor stopped")
    }
}
