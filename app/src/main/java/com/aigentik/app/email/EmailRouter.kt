package com.aigentik.app.email

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// EmailRouter v2.1 — routes replies via GmailApiClient (OAuth2 REST)
// v2.1: Removed SmsRouter fallback — SEND_SMS permission removed in v1.4.8.
//   If no email context is found for a sender, logs a warning and drops the reply.
// REMOVED: GmailClient (JavaMail SMTP/IMAP), app password dependency
//
// Reply routing:
//   GVoice SMS → reply via Gmail API to GVoice email thread
//   Regular email → reply via Gmail API preserving thread
//
// Privacy: all sends go Gmail API ↔ phone only, OAuth2 token
object EmailRouter {

    private const val TAG = "EmailRouter"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Context stored from AigentikService for API calls
    // NOTE: always use applicationContext, never activity context
    private var appContext: Context? = null

    // GVoice context: messageId → GoogleVoiceMessage
    private val gvoiceContextMap = mutableMapOf<String, GoogleVoiceMessage>()

    // Regular email context: messageId → ParsedEmail
    private val emailContextMap = mutableMapOf<String, ParsedEmail>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun storeGVoiceContext(messageId: String, gvm: GoogleVoiceMessage) {
        gvoiceContextMap[messageId] = gvm
        // Cap at 200 entries to prevent unbounded growth
        if (gvoiceContextMap.size > 200) gvoiceContextMap.remove(gvoiceContextMap.keys.first())
    }

    fun storeEmailContext(messageId: String, email: ParsedEmail) {
        emailContextMap[messageId] = email
        if (emailContextMap.size > 200) emailContextMap.remove(emailContextMap.keys.first())
    }

    // Main reply router — called by MessageEngine via replyToSender()
    fun routeReply(senderIdentifier: String, replyText: String) {
        val ctx = appContext ?: run {
            Log.e(TAG, "No context — cannot route reply to $senderIdentifier")
            return
        }

        // Check GVoice context first (match by phone number)
        val gvm = gvoiceContextMap.values.find { gv ->
            gv.senderPhone.filter { it.isDigit() }.takeLast(10) ==
            senderIdentifier.filter { it.isDigit() }.takeLast(10)
        }
        if (gvm != null) {
            scope.launch {
                val sent = GmailApiClient.replyToGoogleVoiceText(ctx, gvm.originalEmail, replyText)
                Log.i(TAG,
                    if (sent) "GVoice reply sent to ${gvm.senderPhone}"
                    else "GVoice reply FAILED to ${gvm.senderPhone}"
                )
            }
            return
        }

        // Check regular email context (match by email address)
        val email = emailContextMap.values.find { e ->
            e.fromEmail.lowercase() == senderIdentifier.lowercase()
        }
        if (email != null) {
            scope.launch {
                val sent = GmailApiClient.replyToEmail(ctx, email, replyText)
                Log.i(TAG,
                    if (sent) "Email reply sent to ${email.fromEmail}"
                    else "Email reply FAILED to ${email.fromEmail}"
                )
            }
            return
        }

        // No email context found — cannot send via SMS (SEND_SMS removed in v1.4.8)
        Log.w(TAG, "No email context for $senderIdentifier — reply dropped (no SMS fallback)")
    }

    // Direct email send — used by MessageEngine send_email command
    suspend fun sendEmailDirect(to: String, subject: String, body: String): Boolean {
        val ctx = appContext ?: run {
            Log.e(TAG, "sendEmailDirect: no context available")
            return false
        }
        return GmailApiClient.sendEmail(ctx, to, subject, body)
    }

    // Owner notification — send email to self as status update
    fun notifyOwner(message: String) {
        val ctx = appContext ?: return
        scope.launch {
            val ownerEmail = com.aigentik.app.auth.GoogleAuthManager.getSignedInEmail(ctx)
                ?: return@launch
            GmailApiClient.sendEmail(
                context = ctx,
                to      = ownerEmail,
                subject = "Aigentik Notification",
                body    = message
            )
        }
    }

    // Legacy compat
    fun replyViaGVoice(senderPhone: String, replyText: String) = routeReply(senderPhone, replyText)
}
