package com.aigentik.app.email

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// EmailRouter v2.2 — routes replies via GmailApiClient (OAuth2 REST)
// v2.2: Thread-safe context maps + correct most-recent-sender lookup.
//   1. gvoiceContextMap and emailContextMap changed from plain mutableMapOf()
//      (LinkedHashMap, NOT thread-safe) to ConcurrentHashMap. Multiple coroutines
//      (EmailMonitor notification handler, MessageEngine reply) can access these maps
//      concurrently — ConcurrentHashMap prevents ConcurrentModificationException.
//   2. Context is now keyed by SENDER (phone digits for GVoice, email.lowercase() for email)
//      instead of messageId. Old key: storeEmailContext(messageId, email) → routeReply()
//      searched values and found the FIRST (oldest) entry from a given sender. On a
//      multi-turn thread, the second reply was sent to the ORIGINAL email's threadId/messageId
//      instead of the current one. New key: storeEmailContext(fromEmail, email) — same sender
//      overwrites the previous entry, so routeReply() always uses the most recent context.
//   3. CoroutineExceptionHandler added to scope — prevents uncaught errors from reply
//      coroutines reaching Android's default UncaughtExceptionHandler.
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
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Uncaught error in EmailRouter scope: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    )

    // Context stored from AigentikService for API calls
    // NOTE: always use applicationContext, never activity context
    private var appContext: Context? = null

    // GVoice context: last10digits(phone) → GoogleVoiceMessage (most recent per sender)
    // ConcurrentHashMap prevents ConcurrentModificationException from concurrent coroutines
    private val gvoiceContextMap = ConcurrentHashMap<String, GoogleVoiceMessage>()

    // Regular email context: fromEmail.lowercase() → ParsedEmail (most recent per sender)
    // Keyed by sender address so routeReply() always replies to the latest email in thread
    private val emailContextMap = ConcurrentHashMap<String, ParsedEmail>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // Key: last 10 digits of senderPhone for consistent matching regardless of formatting
    fun storeGVoiceContext(senderPhone: String, gvm: GoogleVoiceMessage) {
        val key = senderPhone.filter { it.isDigit() }.takeLast(10)
        gvoiceContextMap[key] = gvm  // overwrites previous entry — keeps most recent
        // Cap at 200 unique senders to prevent unbounded growth
        if (gvoiceContextMap.size > 200) {
            gvoiceContextMap.keys.firstOrNull()?.let { gvoiceContextMap.remove(it) }
        }
    }

    // Key: fromEmail.lowercase() for case-insensitive sender matching
    fun storeEmailContext(fromEmail: String, email: ParsedEmail) {
        val key = fromEmail.trim().lowercase()
        emailContextMap[key] = email  // overwrites previous entry — keeps most recent in thread
        // Cap at 200 unique senders to prevent unbounded growth
        if (emailContextMap.size > 200) {
            emailContextMap.keys.firstOrNull()?.let { emailContextMap.remove(it) }
        }
    }

    // Main reply router — called by MessageEngine via replyToSender()
    fun routeReply(senderIdentifier: String, replyText: String) {
        val ctx = appContext ?: run {
            Log.e(TAG, "No context — cannot route reply to $senderIdentifier")
            return
        }

        // Check GVoice context first — direct lookup by phone digits (O(1), no iteration)
        val gvKey = senderIdentifier.filter { it.isDigit() }.takeLast(10)
        val gvm = if (gvKey.isNotEmpty()) gvoiceContextMap[gvKey] else null
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

        // Check regular email context — direct lookup by email address (O(1), no iteration)
        // Always returns the MOST RECENT email from this sender (storeEmailContext overwrites)
        val emailKey = senderIdentifier.trim().lowercase()
        val email = emailContextMap[emailKey]
        if (email != null) {
            scope.launch {
                val sent = GmailApiClient.replyToEmail(ctx, email, replyText)
                Log.i(TAG,
                    if (sent) "Email reply sent to ${email.fromEmail} (thread: ${email.threadId.take(10)})"
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
