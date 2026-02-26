package com.aigentik.app.email

import android.content.Context
import android.util.Base64
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

// GmailApiClient v1.0
// Full Gmail REST API client via OAuth2 — replaces JavaMail IMAP/SMTP
// All operations use OAuth2 token, no app password needed
//
// Capabilities:
//   - listUnread() — get unread emails
//   - sendEmail() — send via Gmail API
//   - deleteEmail() — trash a message
//   - markAsRead() — mark message read
//   - markAsSpam() — move to spam
//   - applyLabel() — add label
//   - searchEmails() — search by query
//   - getEmailBody() — get full email content
//   - replyToEmail() — reply preserving thread
//
// NOTE: Google Voice texts arrive as emails from txt.voice.google.com
//   Subject: "New text message from Name (XXX) XXX-XXXX"
//   We detect and route these separately via isGoogleVoiceText()
object GmailApiClient {

    private const val TAG = "GmailApiClient"
    private const val USER = "me"
    private const val APP_NAME = "Aigentik"

    // GV subject prefix for detection
    private const val GV_SUBJECT_PREFIX = "New text message from"

    private fun buildService(credential: GoogleAccountCredential): Gmail =
        Gmail.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(APP_NAME).build()

    // Get Gmail service — returns null if not authenticated
    private fun getService(context: Context): Gmail? {
        val credential = GoogleAuthManager.getCredential()
        if (credential == null) {
            Log.e(TAG, "No credential — user not signed in")
            return null
        }
        return buildService(credential)
    }

    // List unread emails — returns list of ParsedEmail
    suspend fun listUnread(context: Context, maxResults: Long = 20): List<ParsedEmail> =
        withContext(Dispatchers.IO) {
            try {
                val service = getService(context) ?: return@withContext emptyList()
                val result = service.users().messages()
                    .list(USER)
                    .setQ("is:unread in:inbox")
                    .setMaxResults(maxResults)
                    .execute()

                val messages = result.messages ?: return@withContext emptyList()
                messages.mapNotNull { msg ->
                    try {
                        getFullEmail(context, msg.id)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch message ${msg.id}: ${e.message}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "listUnread failed: ${e.message}")
                emptyList()
            }
        }

    // Get full email content by ID
    suspend fun getFullEmail(context: Context, messageId: String): ParsedEmail? =
        withContext(Dispatchers.IO) {
            try {
                val service = getService(context) ?: return@withContext null
                val msg = service.users().messages()
                    .get(USER, messageId)
                    .setFormat("full")
                    .execute()

                val headers = msg.payload?.headers ?: return@withContext null
                val subject = headers.firstOrNull { it.name == "Subject" }?.value ?: ""
                val from = headers.firstOrNull { it.name == "From" }?.value ?: ""
                val to = headers.firstOrNull { it.name == "To" }?.value ?: ""
                val date = headers.firstOrNull { it.name == "Date" }?.value ?: ""
                val messageIdHeader = headers.firstOrNull { it.name == "Message-ID" }?.value ?: messageId
                val threadId = msg.threadId ?: ""

                val body = extractBody(msg.payload)

                ParsedEmail(
                    messageId = messageIdHeader,
                    gmailId = messageId,
                    threadId = threadId,
                    fromEmail = parseEmail(from),
                    fromName = parseName(from),
                    toEmail = to,
                    subject = subject,
                    body = body,
                    date = date,
                    isUnread = msg.labelIds?.contains("UNREAD") == true
                )
            } catch (e: Exception) {
                Log.e(TAG, "getFullEmail failed for $messageId: ${e.message}")
                null
            }
        }

    // Send email via Gmail API
    suspend fun sendEmail(
        context: Context,
        to: String,
        subject: String,
        body: String,
        threadId: String? = null,
        inReplyTo: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = getService(context) ?: return@withContext false
            val fromEmail = GoogleAuthManager.getSignedInEmail(context) ?: return@withContext false

            // Build MIME message
            val props = Properties()
            val session = Session.getDefaultInstance(props, null)
            val mimeMessage = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail))
                addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(to))
                this.subject = subject
                setText(body)
                inReplyTo?.let { setHeader("In-Reply-To", it) }
                inReplyTo?.let { setHeader("References", it) }
            }

            // Encode to base64
            val baos = java.io.ByteArrayOutputStream()
            mimeMessage.writeTo(baos)
            val encoded = Base64.encodeToString(baos.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)

            val message = Message().apply {
                raw = encoded
                threadId?.let { this.threadId = it }
            }

            service.users().messages().send(USER, message).execute()
            Log.i(TAG, "Email sent to $to subject='$subject'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendEmail failed: ${e.message}")
            false
        }
    }

    // Reply to an existing email preserving thread
    suspend fun replyToEmail(context: Context, original: ParsedEmail, replyBody: String): Boolean =
        sendEmail(
            context = context,
            to = original.fromEmail,
            subject = if (original.subject.startsWith("Re:")) original.subject
                      else "Re: ${original.subject}",
            body = replyBody,
            threadId = original.threadId,
            inReplyTo = original.messageId
        )

    // Reply to Google Voice text (arrives as email from txt.voice.google.com)
    suspend fun replyToGoogleVoiceText(
        context: Context,
        original: ParsedEmail,
        replyText: String
    ): Boolean = withContext(Dispatchers.IO) {
        // GVoice reply: send back to the same thread
        // GVoice routes it as a text to the original sender
        replyToEmail(context, original, replyText)
    }

    // Delete (trash) email by Gmail ID
    suspend fun deleteEmail(context: Context, gmailId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val service = getService(context) ?: return@withContext false
                service.users().messages().trash(USER, gmailId).execute()
                Log.i(TAG, "Email trashed: $gmailId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "deleteEmail failed: ${e.message}")
                false
            }
        }

    // Mark email as read
    suspend fun markAsRead(context: Context, gmailId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val service = getService(context) ?: return@withContext false
                val request = com.google.api.services.gmail.model.ModifyMessageRequest()
                    .setRemoveLabelIds(listOf("UNREAD"))
                service.users().messages().modify(USER, gmailId, request).execute()
                true
            } catch (e: Exception) {
                Log.e(TAG, "markAsRead failed: ${e.message}")
                false
            }
        }

    // Mark as spam
    suspend fun markAsSpam(context: Context, gmailId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val service = getService(context) ?: return@withContext false
                val request = com.google.api.services.gmail.model.ModifyMessageRequest()
                    .setAddLabelIds(listOf("SPAM"))
                    .setRemoveLabelIds(listOf("INBOX"))
                service.users().messages().modify(USER, gmailId, request).execute()
                Log.i(TAG, "Email marked spam: $gmailId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "markAsSpam failed: ${e.message}")
                false
            }
        }

    // Search emails by query (Gmail search syntax)
    suspend fun searchEmails(context: Context, query: String, maxResults: Long = 10): List<ParsedEmail> =
        withContext(Dispatchers.IO) {
            try {
                val service = getService(context) ?: return@withContext emptyList()
                val result = service.users().messages()
                    .list(USER)
                    .setQ(query)
                    .setMaxResults(maxResults)
                    .execute()
                val messages = result.messages ?: return@withContext emptyList()
                messages.mapNotNull { msg ->
                    try { getFullEmail(context, msg.id) }
                    catch (e: Exception) { null }
                }
            } catch (e: Exception) {
                Log.e(TAG, "searchEmails failed: ${e.message}")
                emptyList()
            }
        }

    // Delete all emails matching a query — DESTRUCTIVE
    suspend fun deleteAllMatching(context: Context, query: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val service = getService(context) ?: return@withContext 0
                var count = 0
                var pageToken: String? = null
                do {
                    val result = service.users().messages()
                        .list(USER).setQ(query).setMaxResults(100)
                        .also { if (pageToken != null) it.pageToken = pageToken }
                        .execute()
                    result.messages?.forEach { msg ->
                        service.users().messages().trash(USER, msg.id).execute()
                        count++
                    }
                    pageToken = result.nextPageToken
                } while (pageToken != null)
                Log.i(TAG, "Deleted $count emails matching: $query")
                count
            } catch (e: Exception) {
                Log.e(TAG, "deleteAllMatching failed: ${e.message}")
                0
            }
        }

    // Check if email is a Google Voice text
    fun isGoogleVoiceText(subject: String): Boolean =
        subject.startsWith(GV_SUBJECT_PREFIX)

    // Parse Google Voice email into structured message
    fun parseGoogleVoiceEmail(email: ParsedEmail): GoogleVoiceMessage? {
        val regex = Regex("""New text message from (.+?)\s*\((\d{3})\)\s*(\d{3})-(\d{4})""")
        val match = regex.find(email.subject) ?: return null
        val senderName = match.groupValues[1].trim()
        val phone = "+1${match.groupValues[2]}${match.groupValues[3]}${match.groupValues[4]}"
        return GoogleVoiceMessage(
            senderName = senderName,
            senderPhone = phone,
            body = email.body.trim(),
            originalEmail = email
        )
    }

    // --- Helpers ---

    private fun extractBody(payload: com.google.api.services.gmail.model.MessagePart?): String {
        if (payload == null) return ""
        // Try plain text first
        if (payload.mimeType == "text/plain" && payload.body?.data != null) {
            return String(Base64.decode(payload.body.data, Base64.URL_SAFE))
        }
        // Try parts
        payload.parts?.forEach { part ->
            if (part.mimeType == "text/plain" && part.body?.data != null) {
                return String(Base64.decode(part.body.data, Base64.URL_SAFE))
            }
        }
        // Try HTML parts as fallback
        payload.parts?.forEach { part ->
            if (part.mimeType == "text/html" && part.body?.data != null) {
                val html = String(Base64.decode(part.body.data, Base64.URL_SAFE))
                return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
            }
        }
        return ""
    }

    private fun parseEmail(from: String): String {
        val match = Regex("<(.+?)>").find(from)
        return match?.groupValues?.get(1) ?: from.trim()
    }

    private fun parseName(from: String): String {
        val match = Regex("^(.+?)<").find(from)
        return match?.groupValues?.get(1)?.trim()?.trim('"') ?: ""
    }
}

// Data classes used by GmailApiClient
data class ParsedEmail(
    val messageId: String,
    val gmailId: String = "",
    val threadId: String = "",
    val fromEmail: String,
    val fromName: String,
    val toEmail: String = "",
    val subject: String,
    val body: String,
    val date: String = "",
    val isUnread: Boolean = true
)

data class GoogleVoiceMessage(
    val senderName: String,
    val senderPhone: String,
    val body: String,
    val originalEmail: ParsedEmail
)
