package com.aigentik.app.email

import android.util.Log
import com.aigentik.app.core.MessageEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// EmailMonitor v0.6 — polls Gmail every 30 seconds
// Routes Google Voice texts to MessageEngine
// Routes regular emails to MessageEngine
object EmailMonitor {

    private const val TAG = "EmailMonitor"
    private const val POLL_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "EmailMonitor starting — polling every 30s")

        scope.launch {
            // Initial connection
            GmailClient.connect()

            while (isActive && isRunning) {
                try {
                    poll()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                    // Reconnect on error
                    try { GmailClient.connect() } catch (ce: Exception) { }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun poll() {
        GmailClient.pollUnseen { email ->
            Log.i(TAG, "Processing email from ${email.fromEmail}: ${email.subject.take(50)}")

            if (GmailClient.isGoogleVoiceText(email.subject)) {
                // Google Voice forwarded text
                val gvm = GmailClient.parseGoogleVoiceEmail(email)
                if (gvm != null) {
                    val message = GmailClient.toMessage(gvm)
                    if (message != null) {
                        Log.i(TAG, "GVoice text from ${gvm.senderName} (${gvm.senderPhone})")
                        // Store reply method so MessageEngine can respond
                        EmailRouter.storeGVoiceContext(message.id, gvm)
                        MessageEngine.onMessageReceived(message)
                    }
                }
            } else {
                // Regular email — NOTE: full email handling in v0.7
                Log.i(TAG, "Regular email from ${email.fromEmail} — queued")
            }
        }
    }

    fun stop() {
        isRunning = false
        GmailClient.disconnect()
        Log.i(TAG, "EmailMonitor stopped")
    }
}
