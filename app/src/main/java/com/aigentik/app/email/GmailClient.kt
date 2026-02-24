package com.aigentik.app.email

import android.util.Log
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Folder
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.Flags
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.FlagTerm

// GmailClient v1.0
// Changes from v0.6:
// - IMAP IDLE support (push instead of poll — instant delivery, less battery)
// - Regular email reply via SMTP thread
// - 8k context window note for AI prompt sizing
// - Clean separation of IDLE folder from poll folder
//
// NOTE on IMAP IDLE:
//   IDLE is a server-push protocol — server sends EXISTS/EXPUNGE events
//   to the client when new mail arrives. No polling needed.
//   Gmail supports IDLE. We keep a persistent IDLE connection on INBOX.
//   On new mail event we fetch unseen, process, then re-enter IDLE.
//   Fallback to 30s poll if IDLE fails (older JavaMail behavior).
//
// NOTE on Security (future):
//   gmailAppPassword is currently in SharedPreferences.
//   TODO v1.1: Migrate to Android Keystore via EncryptedSharedPreferences
//   or implement OAuth2 via AccountManager for "Sign in with Google" flow.
//   OAuth2 is more secure — no password stored, revokable tokens.
object GmailClient {

    private const val TAG = "GmailClient"
    private const val IMAP_HOST = "imap.gmail.com"
    private const val SMTP_HOST = "smtp.gmail.com"
    private const val IMAP_PORT = 993
    private const val SMTP_PORT = 587
    private const val GV_SUBJECT_PREFIX = "New text message from"

    private val FOOTER_MARKERS = listOf(
        "To respond to this text message",
        "YOUR ACCOUNT", "HELP CENTER", "HELP FORUM",
        "visit Google Voice", "(c) Google", "Google LLC"
    )

    private var gmailAddress = ""
    private var appPassword = ""
    private var session: Session? = null

    // TWO separate store connections to avoid IMAP folder conflict:
    // idleStore  — persistent READ_ONLY connection for IDLE (server push)
    // pollStore  — short-lived READ_WRITE connection opened per-poll only
    // NOTE: Gemini audit found single-store caused "Folder already open"
    //       MessagingException aborting all email processing
    private var idleStore: Store? = null
    private var pollStore: Store? = null
    private var idleFolder: Folder? = null

    // On first connect: process emails from last 24h so GVoice texts aren't missed
    // After first poll: only process truly new emails
    // 24h window ensures we catch messages that arrived during reinstall/restart
    private val startupTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)

    data class ParsedEmail(
        val fromEmail: String,
        val fromName: String?,
        val subject: String,
        val body: String,
        val receivedAt: Long,
        val messageId: String,
        // Store reply-to for threading
        val replyToHeader: String? = null
    )

    data class GoogleVoiceMessage(
        val senderName: String,
        val senderPhone: String,
        val body: String,
        val replyToEmail: String,
        val originalSubject: String
    )

    fun configure(address: String, password: String) {
        gmailAddress = address.trim()
        // Strip spaces — app passwords display as "xxxx xxxx xxxx xxxx" but
        // must be passed to IMAP as "xxxxxxxxxxxxxxxx" (no spaces)
        // This was silently failing IMAP auth on every connect attempt
        appPassword = password.replace(" ", "").trim()
        initSession()
        Log.i(TAG, "GmailClient configured for $gmailAddress (password length: ${appPassword.length})")
    }

    private fun initSession() {
        val props = Properties().apply {
            // IMAP
            put("mail.imaps.host", IMAP_HOST)
            put("mail.imaps.port", IMAP_PORT.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.trust", IMAP_HOST)
            // IDLE support
            put("mail.imaps.usesocketchannels", "true")
            // Connection timeout — prevent hanging on Android
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "15000")
            // SMTP
            put("mail.smtp.host", SMTP_HOST)
            put("mail.smtp.port", SMTP_PORT.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.ssl.trust", SMTP_HOST)
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.store.protocol", "imaps")
        }
        session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(gmailAddress, appPassword)
        })
    }

    // Connect poll store — used for short-lived READ_WRITE fetch operations
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            try { pollStore?.close() } catch (e: Exception) { }
            pollStore = session?.getStore("imaps")
            pollStore?.connect(IMAP_HOST, IMAP_PORT, gmailAddress, appPassword)
            Log.i(TAG, "Poll store connected")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Poll store connection failed: ${e.message}")
            false
        }
    }

    // Connect IDLE store — separate persistent READ_ONLY connection
    suspend fun connectIdle(): Boolean = withContext(Dispatchers.IO) {
        try {
            try { idleStore?.close() } catch (e: Exception) { }
            idleStore = session?.getStore("imaps")
            idleStore?.connect(IMAP_HOST, IMAP_PORT, gmailAddress, appPassword)
            Log.i(TAG, "IDLE store connected")
            true
        } catch (e: Exception) {
            Log.e(TAG, "IDLE store connection failed: ${e.message}")
            false
        }
    }

    // Open IDLE folder on IDLE store (READ_ONLY — no conflict with poll)
    suspend fun openIdleFolder(): Folder? = withContext(Dispatchers.IO) {
        try {
            if (idleStore?.isConnected != true) connectIdle()
            // READ_ONLY — IDLE only needs to watch for EXISTS notifications
            val folder = idleStore?.getFolder("INBOX") ?: return@withContext null
            folder.open(Folder.READ_ONLY)
            idleFolder = folder
            Log.i(TAG, "IDLE folder opened READ_ONLY on separate store")
            folder
        } catch (e: Exception) {
            Log.e(TAG, "IDLE folder open failed: ${e.message}")
            null
        }
    }

    // Wait for new mail via IDLE — blocks until server sends notification
    // Returns true if new mail arrived, false on timeout/error
    // NOTE: Gmail IDLE timeout is ~10 minutes — re-enter after each notification
    suspend fun waitForNewMail(folder: Folder, timeoutMs: Long = 600_000L): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // Cast to IMAPFolder for IDLE support
                val imapFolder = folder as? com.sun.mail.imap.IMAPFolder
                if (imapFolder != null) {
                    // NOTE: idle() blocks until server sends EXISTS/EXPUNGE
                    // or timeout (set via mail.imaps.timeout)
                    imapFolder.idle()
                    true
                } else {
                    // Fallback: sleep and return true to trigger poll
                    kotlinx.coroutines.delay(30_000L)
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "IDLE interrupted: ${e.message}")
                false
            }
        }

    // Poll for unseen emails — called after IDLE notification or on reconnect
    suspend fun pollUnseen(onEmail: suspend (ParsedEmail) -> Unit) =
        withContext(Dispatchers.IO) {
            var folder: Folder? = null
            try {
                connect()
                folder = pollStore?.getFolder("INBOX")
                folder?.open(Folder.READ_WRITE)

                val unseen = folder?.search(
                    FlagTerm(Flags(Flags.Flag.SEEN), false)
                ) ?: emptyArray()

                Log.i(TAG, "Unseen emails: ${unseen.size}")

                for (msg in unseen) {
                    try {
                        val receivedAt = msg.receivedDate?.time ?: System.currentTimeMillis()
                        if (receivedAt < startupTime) {
                            msg.setFlag(Flags.Flag.SEEN, true)
                            continue
                        }

                        val fromAddresses = msg.from ?: continue
                        val fromEmail = (fromAddresses[0] as? InternetAddress)?.address ?: continue
                        val fromName = (fromAddresses[0] as? InternetAddress)?.personal

                        if (fromEmail.lowercase() == gmailAddress.lowercase()) {
                            msg.setFlag(Flags.Flag.SEEN, true)
                            continue
                        }

                        val subject = msg.subject ?: ""
                        val subjectLower = subject.lowercase()

                        // Skip auto-replies and notifications
                        if (subjectLower.contains("delivery") ||
                            subjectLower.contains("out of office") ||
                            subjectLower.contains("auto-reply") ||
                            subjectLower.contains("mailer-daemon")) {
                            msg.setFlag(Flags.Flag.SEEN, true)
                            continue
                        }

                        val body = extractBody(msg)

                        // Get Reply-To header for threading
                        val replyToHeader = try {
                            (msg.replyTo?.firstOrNull() as? InternetAddress)?.address
                        } catch (e: Exception) { null }

                        val parsed = ParsedEmail(
                            fromEmail = fromEmail,
                            fromName = fromName,
                            subject = subject,
                            body = body,
                            receivedAt = receivedAt,
                            messageId = msg.messageNumber.toString(),
                            replyToHeader = replyToHeader ?: fromEmail
                        )

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

    fun isGoogleVoiceText(subject: String): Boolean =
        subject.startsWith(GV_SUBJECT_PREFIX)

    fun parseGoogleVoiceEmail(email: ParsedEmail): GoogleVoiceMessage? {
        val regex = Regex("""New text message from (.+?)\s*\((\d{3})\)\s*(\d{3})-(\d{4})""")
        val match = regex.find(email.subject) ?: return null

        val senderName  = match.groupValues[1].trim()
        val senderPhone = match.groupValues[2] + match.groupValues[3] + match.groupValues[4]

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

    fun toMessage(gvm: GoogleVoiceMessage): Message? {
        val ts = System.currentTimeMillis()
        if (!MessageDeduplicator.isNew(gvm.senderPhone, gvm.body, ts)) return null
        return Message(
            id = MessageDeduplicator.fingerprint(gvm.senderPhone, gvm.body, ts),
            sender = gvm.senderPhone,
            senderName = gvm.senderName,
            body = gvm.body,
            timestamp = ts,
            channel = Message.Channel.EMAIL
        )
    }

    fun regularEmailToMessage(email: ParsedEmail): Message? {
        val ts = System.currentTimeMillis()
        if (!MessageDeduplicator.isNew(email.fromEmail, email.body, ts)) return null
        return Message(
            id = MessageDeduplicator.fingerprint(email.fromEmail, email.body, ts),
            sender = email.fromEmail,
            senderName = email.fromName,
            body = "${email.subject}\n\n${email.body}",
            timestamp = ts,
            channel = Message.Channel.EMAIL
        )
    }

    // Reply to Google Voice text via email
    suspend fun replyToGoogleVoiceText(gvm: GoogleVoiceMessage, replyText: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val msg = MimeMessage(session).apply {
                    setFrom(InternetAddress(gmailAddress))
                    setRecipient(
                        javax.mail.Message.RecipientType.TO,
                        InternetAddress(gvm.replyToEmail)
                    )
                    subject = "Re: ${gvm.originalSubject}"
                    setText(replyText)
                }
                Transport.send(msg)
                Log.i(TAG, "GVoice reply sent to ${gvm.senderPhone}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "GVoice reply failed: ${e.message}")
                false
            }
        }

    // Reply to a regular email — preserves threading
    suspend fun replyToEmail(
        toEmail: String,
        originalSubject: String,
        replyText: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(gmailAddress))
                setRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(toEmail))
                // Preserve Re: threading
                subject = if (originalSubject.startsWith("Re:")) originalSubject
                          else "Re: $originalSubject"
                setText(replyText)
            }
            Transport.send(msg)
            Log.i(TAG, "Email reply sent to $toEmail")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Email reply failed: ${e.message}")
            false
        }
    }

    suspend fun sendEmail(toEmail: String, subject: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val msg = MimeMessage(session).apply {
                    setFrom(InternetAddress(gmailAddress))
                    setRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(toEmail))
                    setSubject(subject)
                    setText(body)
                }
                Transport.send(msg)
                Log.i(TAG, "Email sent to $toEmail")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Send email failed: ${e.message}")
                false
            }
        }

    suspend fun notifyOwner(message: String): Boolean =
        sendEmail(gmailAddress, "Aigentik Notification", message)

    private fun extractBody(msg: javax.mail.Message): String {
        return try {
            when {
                msg.isMimeType("text/plain") -> msg.content.toString()
                msg.isMimeType("multipart/*") -> {
                    val mp = msg.content as javax.mail.Multipart
                    val sb = StringBuilder()
                    for (i in 0 until mp.count) {
                        val bp = mp.getBodyPart(i)
                        if (bp.isMimeType("text/plain")) sb.append(bp.content.toString())
                    }
                    sb.toString()
                }
                else -> ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Body extract failed: ${e.message}")
            ""
        }
    }

    fun disconnect() {
        try { idleFolder?.close(false) } catch (e: Exception) { }
        try { idleStore?.close() } catch (e: Exception) { }
        try { pollStore?.close() } catch (e: Exception) { }
        Log.i(TAG, "Gmail disconnected")
    }

    fun isConnected() = idleStore?.isConnected == true || pollStore?.isConnected == true
}
