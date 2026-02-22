package com.aigentik.app.email

import android.util.Log
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator
import com.aigentik.app.core.MessageEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

// GmailClient — handles Gmail IMAP polling and Google Voice email parsing
// Replaces the Node.js gmail.js module
// NOTE: Full IMAP implementation added in v0.7
// For now provides the data models and parsing logic
object GmailClient {

    private const val TAG = "GmailClient"

    // Google Voice email markers
    private const val GV_SUBJECT_PREFIX = "New text message from"
    private const val GV_FOOTER_MARKER = "To respond to this text message"

    // Known footer fragments to strip
    private val FOOTER_MARKERS = listOf(
        "To respond to this text message",
        "YOUR ACCOUNT",
        "HELP CENTER",
        "HELP FORUM",
        "visit Google Voice",
        "(c) Google",
        "Google LLC"
    )

    // Data class for parsed Google Voice message
    data class GoogleVoiceMessage(
        val senderName: String,
        val senderPhone: String,
        val body: String,
        val replyToEmail: String,
        val originalSubject: String
    )

    // Check if email is a Google Voice forwarded text
    fun isGoogleVoiceText(subject: String): Boolean {
        return subject.startsWith(GV_SUBJECT_PREFIX)
    }

    // Parse Google Voice forwarded email into structured object
    // Subject format: "New text message from NAME (XXX) XXX-XXXX"
    fun parseGoogleVoiceEmail(
        subject: String,
        body: String,
        fromEmail: String
    ): GoogleVoiceMessage? {
        val regex = Regex(
            """New text message from (.+?)\s*\((\d{3})\)\s*(\d{3})-(\d{4})"""
        )
        val match = regex.find(subject) ?: return null

        val senderName = match.groupValues[1].trim()
        val senderPhone = match.groupValues[2] +
                          match.groupValues[3] +
                          match.groupValues[4]

        // Strip footer from body
        var cleanBody = body
        for (marker in FOOTER_MARKERS) {
            val idx = cleanBody.indexOf(marker)
            if (idx != -1) {
                cleanBody = cleanBody.substring(0, idx)
            }
        }
        cleanBody = cleanBody
            .replace(Regex("<[^>]*>"), "") // strip HTML
            .replace(Regex("\\s{3,}"), "\n") // normalize whitespace
            .trim()

        if (cleanBody.isEmpty()) return null

        return GoogleVoiceMessage(
            senderName = senderName,
            senderPhone = senderPhone,
            body = cleanBody,
            replyToEmail = fromEmail,
            originalSubject = subject
        )
    }

    // Convert GoogleVoiceMessage to unified Message object
    fun toMessage(gvm: GoogleVoiceMessage): Message? {
        val timestamp = System.currentTimeMillis()

        // Deduplication check
        if (!MessageDeduplicator.isNew(gvm.senderPhone, gvm.body, timestamp)) {
            Log.d(TAG, "Duplicate Google Voice message — skipping")
            return null
        }

        return Message(
            id = MessageDeduplicator.fingerprint(gvm.senderPhone, gvm.body, timestamp),
            sender = gvm.senderPhone,
            senderName = gvm.senderName,
            body = gvm.body,
            timestamp = timestamp,
            channel = Message.Channel.EMAIL
        )
    }

    // Send owner notification via Gmail
    // NOTE: Full SMTP implementation in v0.7
    fun notifyOwner(message: String) {
        Log.i(TAG, "Owner notification: ${message.take(80)}")
        // v0.7 — SMTP send here
    }
}
