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

// GmailApiClient v1.4 — Gmail REST API via OkHttp
// v1.4: deleteAllMatching() now uses batchModify instead of one POST per email.
//   Old approach: N HTTP calls (one /messages/{id}/trash per matched email).
//   New approach: collect all matching IDs across pages, then one batchModify call per
//   1000 IDs (Gmail API limit). batchModify with TRASH + remove INBOX is equivalent
//   to trashing. Reduces network overhead from O(N) to O(ceil(N/1000)) calls.
// v1.3: Improved error propagation — lastError tracks specific failure reasons
//   (no token, auth error 401/403, API error, network error). Callers can check
//   lastError to show actionable messages. Added checkTokenHealth() which calls
//   users.getProfile to verify token validity without side effects.
// v1.2: GVoice improvements, HTML stripping, group text detection.
//
// Uses OAuth2 Bearer token from GoogleAuthManager
// No google-api-services-gmail dependency needed — direct REST calls
//
// API base: https://gmail.googleapis.com/gmail/v1/users/me/
// Auth: Authorization: Bearer <token>
object GmailApiClient {

    private const val TAG = "GmailApiClient"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    private const val GV_SUBJECT_PREFIX       = "New text message from"
    private const val GV_GROUP_PREFIX         = "New group text message"
    private const val GV_FOOTER_MARKER        = "To respond to this text message"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // Last error from any API call — callers can read this for specific diagnostics
    @Volatile var lastError: String? = null
        private set

    // --- Core HTTP helpers ---

    private suspend fun get(context: Context, url: String): JsonObject? =
        withContext(Dispatchers.IO) {
            val token = GoogleAuthManager.getFreshToken(context)
            if (token == null) {
                val reason = GoogleAuthManager.lastTokenError ?: "token unavailable"
                lastError = "No Gmail token: $reason"
                Log.e(TAG, "GET $url — no token: $reason")
                return@withContext null
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            try {
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) {
                    val respBody = resp.body?.string()?.take(200) ?: ""
                    val code = resp.code
                    Log.e(TAG, "GET $url → $code: $respBody")
                    lastError = when (code) {
                        401 -> "Gmail auth expired (401) — re-sign-in required"
                        403 -> "Gmail access denied (403) — check permissions"
                        404 -> "Gmail resource not found (404)"
                        429 -> "Gmail rate limit exceeded (429) — try again later"
                        else -> "Gmail API error ($code)"
                    }
                    return@withContext null
                }
                lastError = null
                JsonParser.parseString(resp.body?.string()).asJsonObject
            } catch (e: Exception) {
                Log.e(TAG, "GET failed: ${e.message}")
                lastError = "Network error: ${e.message?.take(80)}"
                null
            }
        }

    private suspend fun post(context: Context, url: String, body: String): JsonObject? =
        withContext(Dispatchers.IO) {
            val token = GoogleAuthManager.getFreshToken(context)
            if (token == null) {
                val reason = GoogleAuthManager.lastTokenError ?: "token unavailable"
                lastError = "No Gmail token: $reason"
                Log.e(TAG, "POST $url — no token: $reason")
                return@withContext null
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) {
                    val respBody = resp.body?.string()?.take(200) ?: ""
                    val code = resp.code
                    Log.e(TAG, "POST $url → $code: $respBody")
                    lastError = when (code) {
                        401 -> "Gmail auth expired (401) — re-sign-in required"
                        403 -> "Gmail access denied (403) — check permissions"
                        429 -> "Gmail rate limit exceeded (429) — try again later"
                        else -> "Gmail API error ($code)"
                    }
                    return@withContext null
                }
                lastError = null
                // 204 No Content — success with empty body (e.g. batchModify)
                if (resp.code == 204) return@withContext JsonObject()
                val bodyStr = resp.body?.string() ?: return@withContext JsonObject()
                if (bodyStr.isBlank()) return@withContext JsonObject()
                JsonParser.parseString(bodyStr).asJsonObject
            } catch (e: Exception) {
                Log.e(TAG, "POST failed: ${e.message}")
                lastError = "Network error: ${e.message?.take(80)}"
                null
            }
        }

    // Returns raw HTTP status code — used for batchDelete which returns 204
    private suspend fun postRaw(context: Context, url: String, body: String): Int =
        withContext(Dispatchers.IO) {
            val token = GoogleAuthManager.getFreshToken(context)
            if (token == null) {
                val reason = GoogleAuthManager.lastTokenError ?: "token unavailable"
                lastError = "No Gmail token: $reason"
                return@withContext -1
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                val code = http.newCall(req).execute().code
                if (code in 200..299) lastError = null
                code
            } catch (e: Exception) {
                Log.e(TAG, "POST raw failed: ${e.message}")
                lastError = "Network error: ${e.message?.take(80)}"
                -1
            }
        }

    // --- Token Health Check ---

    // Calls users.getProfile to verify the token is valid and Gmail API is accessible.
    // Returns the email address if healthy, null if not. Sets lastError on failure.
    suspend fun checkTokenHealth(context: Context): String? = withContext(Dispatchers.IO) {
        val result = get(context, "$BASE/profile")
        result?.get("emailAddress")?.asString
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
    // Uses batchModify (single call per 1000 IDs) instead of one /trash POST per email.
    // batchModify with addLabelIds=TRASH + removeLabelIds=INBOX is equivalent to trashing.
    suspend fun deleteAllMatching(context: Context, query: String): Int =
        withContext(Dispatchers.IO) {
            // Phase 1: collect all matching message IDs across all pages
            val allIds = mutableListOf<String>()
            var pageToken: String? = null
            do {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "$BASE/messages?q=$encoded&maxResults=100" +
                    (if (pageToken != null) "&pageToken=$pageToken" else "")
                val result = get(context, url) ?: break
                val messages = result.getAsJsonArray("messages") ?: break
                messages.forEach { el ->
                    val id = el.asJsonObject.get("id")?.asString ?: return@forEach
                    allIds.add(id)
                }
                pageToken = result.get("nextPageToken")?.asString
            } while (pageToken != null)

            if (allIds.isEmpty()) {
                Log.i(TAG, "deleteAllMatching: no emails found for query: $query")
                return@withContext 0
            }

            // Phase 2: trash all IDs in one batchModify call per 1000 (API limit)
            var trashed = 0
            allIds.chunked(1000).forEach { batch ->
                val body = """{"ids":${gson.toJson(batch)},"addLabelIds":["TRASH"],"removeLabelIds":["INBOX"]}"""
                if (post(context, "$BASE/messages/batchModify", body) != null) trashed += batch.size
            }
            Log.i(TAG, "deleteAllMatching: trashed $trashed emails matching: $query")
            trashed
        }

    // Get a single email with metadata only (From, Subject, Date — no body)
    // Much faster than getFullEmail() for listing/counting purposes
    suspend fun getEmailMetadata(context: Context, messageId: String): ParsedEmail? =
        withContext(Dispatchers.IO) {
            val url = "$BASE/messages/$messageId" +
                "?format=metadata" +
                "&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"
            val msg = get(context, url) ?: return@withContext null
            val headers = msg.getAsJsonObject("payload")
                ?.getAsJsonArray("headers") ?: return@withContext null

            fun header(name: String) = headers.firstOrNull {
                it.asJsonObject.get("name")?.asString?.equals(name, true) == true
            }?.asJsonObject?.get("value")?.asString ?: ""

            val from    = header("From")
            val subject = header("Subject")
            val date    = header("Date")
            val threadId = msg.get("threadId")?.asString ?: ""
            val labels  = msg.getAsJsonArray("labelIds")
            val isUnread = labels?.any { it.asString == "UNREAD" } == true

            ParsedEmail(
                messageId = messageId,
                gmailId   = messageId,
                threadId  = threadId,
                fromEmail = parseEmail(from),
                fromName  = parseName(from),
                toEmail   = "",
                subject   = subject,
                body      = "",
                date      = date,
                isUnread  = isUnread
            )
        }

    // List unread emails with metadata only — fast, no body fetch
    suspend fun listUnreadSummary(context: Context, maxResults: Int = 50): List<ParsedEmail> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/messages?q=is:unread+in:inbox&maxResults=$maxResults"
            val result = get(context, url) ?: return@withContext emptyList()
            val messages = result.getAsJsonArray("messages") ?: return@withContext emptyList()
            messages.mapNotNull { el ->
                val id = el.asJsonObject.get("id")?.asString ?: return@mapNotNull null
                try { getEmailMetadata(context, id) } catch (e: Exception) { null }
            }
        }

    // Count unread emails grouped by sender display name — returns top 15 senders
    suspend fun countUnreadBySender(context: Context): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val emails = listUnreadSummary(context, 200)
            emails.groupingBy {
                it.fromName.ifEmpty { it.fromEmail }.take(35)
            }.eachCount()
        }

    // Search and return message IDs only (no metadata fetch) — for batch ops
    suspend fun searchEmailIds(context: Context, query: String, maxResults: Int = 100): List<String> =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$BASE/messages?q=$encoded&maxResults=$maxResults"
            val result = get(context, url) ?: return@withContext emptyList()
            val messages = result.getAsJsonArray("messages") ?: return@withContext emptyList()
            messages.mapNotNull { it.asJsonObject.get("id")?.asString }
        }

    // Batch mark emails as read via batchModify
    suspend fun batchMarkRead(context: Context, gmailIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (gmailIds.isEmpty()) return@withContext true
            val body = """{"ids":${gson.toJson(gmailIds)},"removeLabelIds":["UNREAD"]}"""
            post(context, "$BASE/messages/batchModify", body) != null
        }

    // Permanently delete all emails in Trash — IRREVERSIBLE
    // Uses batchDelete in chunks of 100 since it returns 204 No Content
    suspend fun emptyTrash(context: Context): Int =
        withContext(Dispatchers.IO) {
            val ids = searchEmailIds(context, "in:trash", 500)
            if (ids.isEmpty()) return@withContext 0
            var deleted = 0
            ids.chunked(100).forEach { batch ->
                val body = """{"ids":${gson.toJson(batch)}}"""
                val code = postRaw(context, "$BASE/messages/batchDelete", body)
                if (code in 200..299) deleted += batch.size
            }
            Log.i(TAG, "emptyTrash: permanently deleted $deleted emails")
            deleted
        }

    // Get or create a label by name — returns label ID
    suspend fun getOrCreateLabel(context: Context, labelName: String): String? =
        withContext(Dispatchers.IO) {
            val labelsResp = get(context, "$BASE/labels") ?: return@withContext null
            val labelsArr  = labelsResp.getAsJsonArray("labels") ?: return@withContext null

            // Try to find existing label (case-insensitive)
            val existing = labelsArr.firstOrNull { el ->
                el.asJsonObject.get("name")?.asString
                    ?.equals(labelName, ignoreCase = true) == true
            }
            if (existing != null) {
                return@withContext existing.asJsonObject.get("id")?.asString
            }

            // Create new label
            val escaped = labelName.replace("\\", "\\\\").replace("\"", "\\\"")
            val body = """{"name":"$escaped","messageListVisibility":"show","labelListVisibility":"labelShow"}"""
            val result = post(context, "$BASE/labels", body)
            result?.get("id")?.asString.also { id ->
                if (id != null) Log.i(TAG, "Created label '$labelName' → $id")
                else Log.e(TAG, "Failed to create label '$labelName'")
            }
        }

    // Add a label to a single email
    suspend fun addLabel(context: Context, gmailId: String, labelId: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = """{"addLabelIds":["$labelId"]}"""
            post(context, "$BASE/messages/$gmailId/modify", body) != null
        }

    // Extract the List-Unsubscribe URL from an email's headers
    // Returns HTTP URL if present, falls back to mailto, or null if none
    suspend fun getUnsubscribeLink(context: Context, gmailId: String): String? =
        withContext(Dispatchers.IO) {
            val url = "$BASE/messages/$gmailId" +
                "?format=metadata&metadataHeaders=List-Unsubscribe"
            val msg = get(context, url) ?: return@withContext null
            val headers = msg.getAsJsonObject("payload")
                ?.getAsJsonArray("headers") ?: return@withContext null
            val header = headers.firstOrNull {
                it.asJsonObject.get("name")?.asString
                    ?.equals("List-Unsubscribe", true) == true
            }?.asJsonObject?.get("value")?.asString ?: return@withContext null

            // Prefer HTTP unsubscribe URL over mailto
            Regex("<(https?://[^>]+)>").find(header)?.groupValues?.get(1)
                ?: Regex("<(mailto:[^>]+)>").find(header)?.groupValues?.get(1)
        }

    // GVoice helpers

    fun isGoogleVoiceText(subject: String): Boolean =
        subject.startsWith(GV_SUBJECT_PREFIX) || subject.startsWith(GV_GROUP_PREFIX)

    fun parseGoogleVoiceEmail(email: ParsedEmail): GoogleVoiceMessage? {
        // Individual text: "New text message from NAME (XXX) XXX-XXXX"
        val individualRegex = Regex("""New text message from (.+?)\s*\((\d{3})\)\s*(\d{3})-(\d{4})""")
        val match = individualRegex.find(email.subject)

        val senderName: String
        val senderPhone: String

        if (match != null) {
            senderName  = match.groupValues[1].trim()
            senderPhone = "+1${match.groupValues[2]}${match.groupValues[3]}${match.groupValues[4]}"
        } else if (email.subject.startsWith(GV_GROUP_PREFIX)) {
            // Group text: "New group text message from NAME" — phone not in subject
            val groupMatch = Regex("""New group text message from (.+)""").find(email.subject)
            senderName  = groupMatch?.groupValues?.get(1)?.trim() ?: "Group"
            senderPhone = email.fromEmail  // use GVoice address as identifier for routing
        } else {
            return null
        }

        // Strip Google Voice footer ("To respond to this text message, reply to this email...")
        var body = email.body
        val footerIdx = body.indexOf(GV_FOOTER_MARKER)
        if (footerIdx != -1) body = body.substring(0, footerIdx)

        // Strip any HTML tags that survive the body extractor
        body = body.replace(Regex("<[^>]*>"), "").trim()

        return GoogleVoiceMessage(senderName, senderPhone, body, email)
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
