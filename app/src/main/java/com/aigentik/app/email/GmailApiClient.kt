package com.aigentik.app.email

import android.content.Context
import android.util.Base64
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

// GmailApiClient v1.1 — Gmail REST API via OkHttp
// Uses OAuth2 Bearer token from GoogleAuthManager
// No google-api-services-gmail dependency needed — direct REST calls
//
// API base: https://gmail.googleapis.com/gmail/v1/users/me/
// Auth: Authorization: Bearer <token>
//
// Supports:
//   listUnread, getFullEmail, sendEmail, replyToEmail,
//   deleteEmail, markAsRead, markAsSpam, searchEmails,
//   deleteAllMatching, replyToGoogleVoiceText
object GmailApiClient {

    private const val TAG = "GmailApiClient"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    private const val GV_SUBJECT_PREFIX = "New text message from"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // --- Core HTTP helpers ---

    private suspend fun get(context: Context, url: String): JsonObject? =
        withContext(Dispatchers.IO) {
            val token = GoogleAuthManager.getFreshToken(context) ?: return@withContext null
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            try {
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "GET $url → ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return@withContext null
                }
                JsonParser.parseString(resp.body?.string()).asJsonObject
            } catch (e: Exception) {
                Log.e(TAG, "GET failed: ${e.message}")
                null
            }
        }

    private suspend fun post(context: Context, url: String, body: String): JsonObject? =
        withContext(Dispatchers.IO) {
            val token = GoogleAuthManager.getFreshToken(context) ?: return@withContext null
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "POST $url → ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return@withContext null
                }
                JsonParser.parseString(resp.body?.string()).asJsonObject
            } catch (e: Exception) {
                Log.e(TAG, "POST failed: ${e.message}")
                null
            }
        }

    // --- Public API ---

    // List unread emails
    suspend fun listUnread(context: Context, maxResults: Int = 20): List<ParsedEmail> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/messages?q=is:unread+in:inbox&maxResults=$maxResults"
            val result = get(context, url) ?: return@withContext emptyList()
            val messages = result.getAsJsonArray("messages") ?: return@withContext emptyList()
            messages.mapNotNull { el ->
                val id = el.asJsonObject.get("id")?.asString ?: return@mapNotNull null
                try { getFullEmail(context, id) } catch (e: Exception) { null }
            }
        }

    // Get full email by Gmail message ID
    suspend fun getFullEmail(context: Context, messageId: String): ParsedEmail? =
        withContext(Dispatchers.IO) {
            val url = "$BASE/messages/$messageId?format=full"
            val msg = get(context, url) ?: return@withContext null

            val headers = msg.getAsJsonObject("payload")
                ?.getAsJsonArray("headers") ?: return@withContext null

            fun header(name: String) = headers.firstOrNull {
                it.asJsonObject.get("name")?.asString?.equals(name, true) == true
            }?.asJsonObject?.get("value")?.asString ?: ""

            val subject  = header("Subject")
            val from     = header("From")
            val to       = header("To")
            val date     = header("Date")
            val msgId    = header("Message-ID").ifBlank { messageId }
            val threadId = msg.get("threadId")?.asString ?: ""
            val labels   = msg.getAsJsonArray("labelIds")
            val isUnread = labels?.any { it.asString == "UNREAD" } == true
            val body     = extractBody(msg.getAsJsonObject("payload"))

            ParsedEmail(
                messageId  = msgId,
                gmailId    = messageId,
                threadId   = threadId,
                fromEmail  = parseEmail(from),
                fromName   = parseName(from),
                toEmail    = to,
                subject    = subject,
                body       = body,
                date       = date,
                isUnread   = isUnread
            )
        }

    // Send email
    suspend fun sendEmail(
        context: Context,
        to: String,
        subject: String,
        body: String,
        threadId: String? = null,
        inReplyTo: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fromEmail = GoogleAuthManager.getSignedInEmail(context)
                ?: return@withContext false

            val props = Properties()
            val session = Session.getDefaultInstance(props, null)
            val mime = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail))
                addRecipient(
                    javax.mail.Message.RecipientType.TO,
                    InternetAddress(to)
                )
                this.subject = subject
                setText(body)
                inReplyTo?.let {
                    setHeader("In-Reply-To", it)
                    setHeader("References", it)
                }
            }

            val baos = java.io.ByteArrayOutputStream()
            mime.writeTo(baos)
            val encoded = Base64.encodeToString(
                baos.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP
            )

            val jsonBody = buildString {
                append("{\"raw\":\"$encoded\"")
                if (threadId != null) append(",\"threadId\":\"$threadId\"")
                append("}")
            }

            val result = post(context, "$BASE/messages/send", jsonBody)
            val success = result != null
            if (success) Log.i(TAG, "Email sent to $to")
            else Log.e(TAG, "sendEmail failed — null response")
            success
        } catch (e: Exception) {
            Log.e(TAG, "sendEmail exception: ${e.message}")
            false
        }
    }

    // Reply to email preserving thread
    suspend fun replyToEmail(context: Context, original: ParsedEmail, replyBody: String): Boolean =
        sendEmail(
            context   = context,
            to        = original.fromEmail,
            subject   = if (original.subject.startsWith("Re:")) original.subject
                        else "Re: ${original.subject}",
            body      = replyBody,
            threadId  = original.threadId,
            inReplyTo = original.messageId
        )

    // Reply to Google Voice text email
    suspend fun replyToGoogleVoiceText(
        context: Context,
        original: ParsedEmail,
        replyText: String
    ): Boolean = replyToEmail(context, original, replyText)

    // Trash a message
    suspend fun deleteEmail(context: Context, gmailId: String): Boolean =
        withContext(Dispatchers.IO) {
            val result = post(context, "$BASE/messages/$gmailId/trash", "{}")
            result != null
        }

    // Mark as read
    suspend fun markAsRead(context: Context, gmailId: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = """{"removeLabelIds":["UNREAD"]}"""
            post(context, "$BASE/messages/$gmailId/modify", body) != null
        }

    // Mark as spam
    suspend fun markAsSpam(context: Context, gmailId: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = """{"addLabelIds":["SPAM"],"removeLabelIds":["INBOX"]}"""
            val result = post(context, "$BASE/messages/$gmailId/modify", body)
            if (result != null) Log.i(TAG, "Marked spam: $gmailId")
            result != null
        }

    // Search emails by Gmail query
    suspend fun searchEmails(
        context: Context,
        query: String,
        maxResults: Int = 10
    ): List<ParsedEmail> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$BASE/messages?q=$encoded&maxResults=$maxResults"
        val result = get(context, url) ?: return@withContext emptyList()
        val messages = result.getAsJsonArray("messages") ?: return@withContext emptyList()
        messages.mapNotNull { el ->
            val id = el.asJsonObject.get("id")?.asString ?: return@mapNotNull null
            try { getFullEmail(context, id) } catch (e: Exception) { null }
        }
    }

    // Delete all emails matching query — DESTRUCTIVE — must go through DestructiveActionGuard
    suspend fun deleteAllMatching(context: Context, query: String): Int =
        withContext(Dispatchers.IO) {
            var count = 0
            var pageToken: String? = null
            do {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "$BASE/messages?q=$encoded&maxResults=100" +
                    (if (pageToken != null) "&pageToken=$pageToken" else "")
                val result = get(context, url) ?: break
                val messages = result.getAsJsonArray("messages") ?: break
                messages.forEach { el ->
                    val id = el.asJsonObject.get("id")?.asString ?: return@forEach
                    post(context, "$BASE/messages/$id/trash", "{}")
                    count++
                }
                pageToken = result.get("nextPageToken")?.asString
            } while (pageToken != null)
            Log.i(TAG, "Trashed $count emails matching: $query")
            count
        }

    // GVoice helpers
    fun isGoogleVoiceText(subject: String): Boolean =
        subject.startsWith(GV_SUBJECT_PREFIX)

    fun parseGoogleVoiceEmail(email: ParsedEmail): GoogleVoiceMessage? {
        val regex = Regex("""New text message from (.+?)\s*\((\d{3})\)\s*(\d{3})-(\d{4})""")
        val match = regex.find(email.subject) ?: return null
        val senderName = match.groupValues[1].trim()
        val phone = "+1${match.groupValues[2]}${match.groupValues[3]}${match.groupValues[4]}"
        return GoogleVoiceMessage(senderName, phone, email.body.trim(), email)
    }

    // --- Helpers ---

    private fun extractBody(payload: JsonObject?): String {
        if (payload == null) return ""
        val mimeType = payload.get("mimeType")?.asString ?: ""
        val data = payload.getAsJsonObject("body")?.get("data")?.asString

        if (mimeType == "text/plain" && data != null) {
            return String(Base64.decode(data, Base64.URL_SAFE))
        }

        val parts = payload.getAsJsonArray("parts") ?: return ""
        for (part in parts) {
            val p = part.asJsonObject
            val pType = p.get("mimeType")?.asString ?: continue
            val pData = p.getAsJsonObject("body")?.get("data")?.asString
            if (pType == "text/plain" && pData != null) {
                return String(Base64.decode(pData, Base64.URL_SAFE))
            }
        }
        // HTML fallback
        for (part in parts) {
            val p = part.asJsonObject
            val pType = p.get("mimeType")?.asString ?: continue
            val pData = p.getAsJsonObject("body")?.get("data")?.asString
            if (pType == "text/html" && pData != null) {
                val html = String(Base64.decode(pData, Base64.URL_SAFE))
                return android.text.Html.fromHtml(
                    html, android.text.Html.FROM_HTML_MODE_COMPACT
                ).toString()
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

// Data classes
data class ParsedEmail(
    val messageId: String,
    val gmailId:   String = "",
    val threadId:  String = "",
    val fromEmail: String,
    val fromName:  String,
    val toEmail:   String = "",
    val subject:   String,
    val body:      String,
    val date:      String = "",
    val isUnread:  Boolean = true
)

data class GoogleVoiceMessage(
    val senderName:    String,
    val senderPhone:   String,
    val body:          String,
    val originalEmail: ParsedEmail
)
