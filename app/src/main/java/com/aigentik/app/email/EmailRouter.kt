package com.aigentik.app.email

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.aigentik.app.sms.SmsRouter

// EmailRouter v1.0
// Routes replies back to correct channel:
//   - GVoice SMS → reply via GVoice email thread
//   - Regular email → reply via SMTP preserving subject thread
//   - Direct SMS → routed via SmsRouter (separate)
object EmailRouter {

    private const val TAG = "EmailRouter"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // GVoice context: messageId → GoogleVoiceMessage
    private val gvoiceContextMap = mutableMapOf<String, GmailClient.GoogleVoiceMessage>()

    // Regular email context: messageId → ParsedEmail
    private val emailContextMap = mutableMapOf<String, GmailClient.ParsedEmail>()

    fun storeGVoiceContext(messageId: String, gvm: GmailClient.GoogleVoiceMessage) {
        gvoiceContextMap[messageId] = gvm
        if (gvoiceContextMap.size > 200) gvoiceContextMap.remove(gvoiceContextMap.keys.first())
    }

    fun storeEmailContext(messageId: String, email: GmailClient.ParsedEmail) {
        emailContextMap[messageId] = email
        if (emailContextMap.size > 200) emailContextMap.remove(emailContextMap.keys.first())
    }

    // Main reply router — called by MessageEngine.replySender
    // Determines channel from stored context then dispatches
    fun routeReply(senderIdentifier: String, replyText: String) {
        // Check GVoice context first (phone number)
        val gvm = gvoiceContextMap.values.find { gv ->
            gv.senderPhone.filter { it.isDigit() }.takeLast(10) ==
            senderIdentifier.filter { it.isDigit() }.takeLast(10)
        }
        if (gvm != null) {
            scope.launch {
                val sent = GmailClient.replyToGoogleVoiceText(gvm, replyText)
                Log.i(TAG, if (sent) "GVoice reply sent to ${gvm.senderPhone}"
                           else "GVoice reply FAILED to ${gvm.senderPhone}")
            }
            return
        }

        // Check regular email context
        val email = emailContextMap.values.find { e ->
            e.fromEmail.lowercase() == senderIdentifier.lowercase()
        }
        if (email != null) {
            scope.launch {
                val sent = GmailClient.replyToEmail(email.fromEmail, email.subject, replyText)
                Log.i(TAG, if (sent) "Email reply sent to ${email.fromEmail}"
                           else "Email reply FAILED to ${email.fromEmail}")
            }
            return
        }

        // Fallback: treat as phone number, send direct SMS
        // NOTE: If this triggers for an RCS message it means MessageEngine
        //       sent an EMAIL channel message here — check channel routing
        Log.w(TAG, "No email context for $senderIdentifier — falling back to SMS")
        SmsRouter.send(senderIdentifier, replyText)
    }

    // Owner notification — send email to self
    fun notifyOwner(message: String) {
        scope.launch { GmailClient.notifyOwner(message) }
    }

    // Legacy GVoice-only reply (kept for compatibility)
    fun replyViaGVoice(senderPhone: String, replyText: String) {
        routeReply(senderPhone, replyText)
    }
}
