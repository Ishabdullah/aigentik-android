package com.aigentik.app.email

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// EmailRouter v0.6 — connects MessageEngine reply callbacks to Gmail
// Stores GVoice context so replies go back to the right email thread
object EmailRouter {

    private const val TAG = "EmailRouter"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Map message ID → Google Voice context for replying
    private val gvoiceContextMap = mutableMapOf<String, GmailClient.GoogleVoiceMessage>()

    fun storeGVoiceContext(messageId: String, gvm: GmailClient.GoogleVoiceMessage) {
        gvoiceContextMap[messageId] = gvm
        // Keep map from growing unbounded
        if (gvoiceContextMap.size > 200) {
            val oldest = gvoiceContextMap.keys.first()
            gvoiceContextMap.remove(oldest)
        }
    }

    // Reply to a sender — routes back through Google Voice email
    fun replyViaGVoice(senderPhone: String, replyText: String) {
        // Find GVoice context by phone number
        val gvm = gvoiceContextMap.values.find { gv ->
            gv.senderPhone.filter { it.isDigit() }.takeLast(10) ==
            senderPhone.filter { it.isDigit() }.takeLast(10)
        }

        if (gvm != null) {
            scope.launch {
                val sent = GmailClient.replyToGoogleVoiceText(gvm, replyText)
                if (sent) {
                    Log.i(TAG, "GVoice reply sent to ${gvm.senderName} (${gvm.senderPhone})")
                } else {
                    Log.e(TAG, "Failed to send GVoice reply to ${gvm.senderPhone}")
                }
            }
        } else {
            Log.w(TAG, "No GVoice context found for $senderPhone")
        }
    }

    // Send owner notification
    fun notifyOwner(message: String) {
        scope.launch {
            GmailClient.notifyOwner(message)
        }
    }
}
