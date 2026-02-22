package com.aigentik.app.email

import android.util.Log
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator
import com.aigentik.app.core.MessageEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Folder
import javax.mail.MessagingException
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.FlagTerm
import javax.mail.Flags
import javax.mail.FetchProfile
import javax.mail.UIDFolder

// GmailClient v0.6 — full IMAP + SMTP implementation
// Handles Gmail monitoring and Google Voice email forwarding
object GmailClient {

    private const val TAG = "GmailClient"
    private const val IMAP_HOST = "imap.gmail.com"
    private const val SMTP_HOST = "smtp.gmail.com"
    private const val IMAP_PORT = 993
    private const val SMTP_PORT = 587

    // Google Voice email markers
    private const val GV_SUBJECT_PREFIX = "New text message from"

    private val FOOTER_MARKERS = listOf(
        "To respond to this text message",
        "YOUR ACCOUNT",
        "HELP CENTER",
        "HELP FORUM",
        "visit Google Voice",
        "(c) Google",
        "Google LLC"
    )

    private var gmailAddress = ""
    private var appPassword = ""
    private var session: Session? = null
    private var store: Store? = null
    private var isRunning = false

    // Startup timestamp — skip emails that arrived before app started
    private val startupTime = System.currentTimeMillis()

    data class ParsedEmail(
        val fromEmail: String,
        val fromName: String?,
        val subject: String,
        val body: String,
        val receivedAt: Long,
        val messageId: String
    )

    data class GoogleVoiceMessage(
        val senderName: String,
        val senderPhone: String,
        val body: String,
        val replyToEmail: String,
        val originalSubject: String
    )

    fun configure(gmailAddress: String, appPassword: String) {
        this.gmailAddress = gmailAddress
        this.appPassword = appPassword
        initSession()
        Log.i(TAG, "GmailClient configured for $gmailAddress")
    }

    private fun initSession() {
        val props = Properties().apply {
            put("mail.imap.host", IMAP_HOST)
            put("mail.imap.port", IMAP_PORT.toString())
            put("mail.imap.ssl.enable", "true")
            put("mail.imap.ssl.trust", IMAP_HOST)
            put("mail.smtp.host", SMTP_HOST)
            put("mail.smtp.port", SMTP_PORT.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.ssl.trust", SMTP_HOST)
            put("mail.store.protocol", "imaps")
        }
        session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(gmailAddress, appPassword)
        })
    }

    // Connect to Gmail IMAP
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            store = session?.getStore("imaps")
            store?.connect(IMAP_HOST, gmailAddress, appPassword)
            Log.i(TAG, "Gmail IMAP connected")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gmail IMAP connection failed: ${e.message}")
            false
        }
    }

    // Poll for unseen emails since startup
    suspend fun pollUnseen(onEmail: suspend (ParsedEmail) -> Unit) =
        withContext(Dispatchers.IO) {
            var folder: Folder? = null
            try {
                if (store?.isConnected != true) {
                    connect()
                }

                folder = store?.getFolder("INBOX")
                folder?.open(Folder.READ_WRITE)

                // Only fetch unseen messages
                val unseenMessages = folder?.search(
                    FlagTerm(Flags(Flags.Flag.SEEN), false)
                ) ?: emptyArray()

                Log.i(TAG, "Found ${unseenMessages.size} unseen emails")

                for (msg in unseenMessages) {
                    try {
                        val receivedAt = msg.receivedDate?.time ?: System.currentTimeMillis()

                        // Skip emails that arrived before startup
                        if (receivedAt < startupTime) {
                            msg.setFlag(Flags.Flag.SEEN, true)
                            continue
                        }

                        val fromAddresses = msg.from ?: continue
                        val fromEmail = (fromAddresses[0] as? InternetAddress)?.address ?: continue
                        val fromName = (fromAddresses[0] as? InternetAddress)?.personal

                        // Skip emails from ourselves — loop prevention
                        if (fromEmail.lowercase() == gmailAddress.lowercase()) {
                            msg.setFlag(Flags.Flag.SEEN, true)
                            continue
                        }

                        val subject = msg.subject ?: ""
                        val body = extractBody(msg)

                        // Skip delivery notifications and auto-replies
                        val subjectLower = subject.lowercase()
                        if (subjectLower.contains("delivery") ||
                            subjectLower.contains("out of office") ||
                            subjectLower.contains("auto-reply") ||
                            subjectLower.contains("no need to respond")) {
                            msg.setFlag(Flags.Flag.SEEN, true)
                            continue
                        }

                        val parsed = ParsedEmail(
                            fromEmail = fromEmail,
                            fromName = fromName,
                            subject = subject,
                            body = body,
                            receivedAt = receivedAt,
                            messageId = msg.messageNumber.toString()
                        )

                        // Mark as seen before processing
                        msg.setFlag(Flags.Flag.SEEN, true)
                        onEmail(parsed)

                    } catch (e: Exception) {
                        Log.w(TAG, "Error processing email: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Poll failed: ${e.message}")
            } finally {
                try { folder?.close(true) } catch (e: Exception) { }
            }
        }

    // Check if email is a Google Voice forwarded text
    fun isGoogleVoiceText(subject: String): Boolean =
        subject.startsWith(GV_SUBJECT_PREFIX)

    // Parse Google Voice forwarded email
    fun parseGoogleVoiceEmail(email: ParsedEmail): GoogleVoiceMessage? {
        val regex = Regex(
            """New text message from (.+?)\s*\((\d{3})\)\s*(\d{3})-(\d{4})"""
        )
        val match = regex.find(email.subject) ?: return null

        val senderName = match.groupValues[1].trim()
        val senderPhone = match.groupValues[2] +
                          match.groupValues[3] +
                          match.groupValues[4]

        var cleanBody = email.body
        for (marker in FOOTER_MARKERS) {
            val idx = cleanBody.indexOf(marker)
            if (idx != -1) cleanBody = cleanBody.substring(0, idx)
        }
        cleanBody = cleanBody
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\s{3,}"), "\n")
            .trim()

        if (cleanBody.isEmpty()) return null

        return GoogleVoiceMessage(
            senderName = senderName,
            senderPhone = senderPhone,
            body = cleanBody,
            replyToEmail = email.fromEmail,
            originalSubject = email.subject
        )
    }

    // Convert GoogleVoiceMessage to unified Message
    fun toMessage(gvm: GoogleVoiceMessage): Message? {
        val timestamp = System.currentTimeMillis()
        if (!MessageDeduplicator.isNew(gvm.senderPhone, gvm.body, timestamp)) {
            Log.d(TAG, "Duplicate GVoice message — skipping")
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

    // Reply to Google Voice text via email
    suspend fun replyToGoogleVoiceText(
        gvm: GoogleVoiceMessage,
        replyText: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(gmailAddress))
                setRecipient(
                    javax.mail.Message.RecipientType.TO,
                    InternetAddress(gvm.replyToEmail)
                )
                setSubject("Re: ${gvm.originalSubject}")
                setText(replyText)
            }
            Transport.send(msg)
            Log.i(TAG, "GVoice reply sent to ${gvm.senderName} (${gvm.senderPhone})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send GVoice reply: ${e.message}")
            false
        }
    }

    // Send a regular email
    suspend fun sendEmail(
        toEmail: String,
        subject: String,
        body: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(gmailAddress))
                setRecipient(
                    javax.mail.Message.RecipientType.TO,
                    InternetAddress(toEmail)
                )
                setSubject(subject)
                setText(body)
            }
            Transport.send(msg)
            Log.i(TAG, "Email sent to $toEmail")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send email: ${e.message}")
            false
        }
    }

    // Send notification to owner
    suspend fun notifyOwner(message: String): Boolean =
        sendEmail(gmailAddress, "Aigentik Notification", message)

    // Extract plain text body from email
    private fun extractBody(msg: javax.mail.Message): String {
        return try {
            when {
                msg.isMimeType("text/plain") ->
                    msg.content.toString()
                msg.isMimeType("multipart/*") -> {
                    val mp = msg.content as javax.mail.Multipart
                    val sb = StringBuilder()
                    for (i in 0 until mp.count) {
                        val bp = mp.getBodyPart(i)
                        if (bp.isMimeType("text/plain")) {
                            sb.append(bp.content.toString())
                        }
                    }
                    sb.toString()
                }
                else -> ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract body: ${e.message}")
            ""
        }
    }

    fun disconnect() {
        try {
            store?.close()
            Log.i(TAG, "Gmail disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error: ${e.message}")
        }
    }

    fun isConnected() = store?.isConnected == true
}
