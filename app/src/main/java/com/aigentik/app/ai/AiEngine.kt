package com.aigentik.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// AiEngine v0.5 — connects to local llama.cpp server
// Uses HTTP API (same as Node.js llama.js) — no JNI needed
// llama-server runs as a separate process via Termux
// This keeps the app lightweight and avoids JNI complexity
// NOTE: Model runs in Termux, app communicates via localhost HTTP
object AiEngine {

    private const val TAG = "AiEngine"
    private const val LLAMA_URL = "http://127.0.0.1:8080"
    private const val DEFAULT_MAX_TOKENS = 256
    private const val TIMEOUT_MS = 60000

    private var agentName = "Aigentik"
    private var ownerName = "Ish"
    private var isReady = false

    fun configure(agentName: String, ownerName: String) {
        this.agentName = agentName
        this.ownerName = ownerName
    }

    // Check if llama-server is running
    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$LLAMA_URL/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val healthy = response.contains("ok")
            isReady = healthy
            Log.i(TAG, "llama-server health: $response")
            healthy
        } catch (e: Exception) {
            Log.w(TAG, "llama-server not reachable: ${e.message}")
            isReady = false
            false
        }
    }

    // Generate SMS reply
    suspend fun generateSmsReply(
        senderName: String?,
        senderPhone: String,
        message: String,
        relationship: String?,
        instructions: String?
    ): String = withContext(Dispatchers.IO) {
        val signature = "\n\n— $agentName, personal agent of $ownerName. " +
            "If you need to reach $ownerName urgently, include \"$ownerName\" in your message."

        val instructionText = instructions?.let { "IMPORTANT: $it. " } ?: ""
        val relText = relationship?.let { "Relationship to owner: $it. " } ?: ""

        val systemMsg = "You are $agentName, an AI personal assistant managing " +
            "communications on behalf of $ownerName. " +
            "You are replying to a message sent TO $ownerName from " +
            "${senderName ?: senderPhone}. " +
            "Write a natural, helpful reply as $agentName on behalf of $ownerName. " +
            "$relText$instructionText" +
            "Keep it concise — this is a text message. " +
            "Do NOT add a signature — it will be added automatically. " +
            "Reply with message text only."

        val userMsg = "Reply to this text message received by $ownerName:\n" +
            "From: ${senderName ?: senderPhone}\n" +
            "Message: \"$message\""

        val reply = chat(systemMsg, userMsg, 200)
        reply + signature
    }

    // Generate email reply
    suspend fun generateEmailReply(
        fromName: String?,
        fromEmail: String,
        subject: String,
        body: String,
        relationship: String?,
        instructions: String?
    ): String = withContext(Dispatchers.IO) {
        val signature = "\n\n---\n$agentName | Personal Agent of $ownerName\n" +
            "If you need to reach $ownerName urgently, " +
            "include \"$ownerName\" in your subject line."

        val instructionText = instructions?.let { "IMPORTANT: $it. " } ?: ""
        val relText = relationship?.let { "Relationship to owner: $it. " } ?: ""

        val systemMsg = "You are $agentName, an AI personal assistant managing " +
            "email on behalf of $ownerName. " +
            "You are replying to an email sent TO $ownerName from " +
            "${fromName ?: fromEmail}. " +
            "Write a professional, natural email reply as $agentName. " +
            "$relText$instructionText" +
            "Do NOT add a signature. Reply with email body text only."

        val userMsg = "Reply to this email received by $ownerName:\n" +
            "From: ${fromName ?: fromEmail} <$fromEmail>\n" +
            "Subject: $subject\n" +
            "Body: ${body.take(800)}"

        val reply = chat(systemMsg, userMsg, 400)
        reply + signature
    }

    // Interpret a natural language command from owner
    suspend fun interpretCommand(commandText: String): CommandResult =
        withContext(Dispatchers.IO) {
            val actions = "send_sms, find_contact, set_contact_instructions, " +
                "never_reply_to, always_reply_to, list_contacts, sync_contacts, " +
                "add_rule, remove_rule, list_rules, status, pause_all, " +
                "resume_all, unknown"

            val schema = "{\"action\":\"string\",\"target\":\"string|null\"," +
                "\"content\":\"string|null\",\"confirm_required\":false}"

            val systemMsg = "You interpret natural language commands for an AI assistant. " +
                "Return ONLY valid JSON: $schema Actions: $actions " +
                "Examples: " +
                "\"text mom I love her\"={\"action\":\"send_sms\",\"target\":\"mom\",\"content\":\"I love her\"} " +
                "\"find Mike\"={\"action\":\"find_contact\",\"target\":\"Mike\"} " +
                "\"never reply to spam number\"={\"action\":\"never_reply_to\",\"target\":\"spam number\"} " +
                "\"pause\"={\"action\":\"pause_all\"}"

            val userMsg = "Command: \"$commandText\""

            try {
                val raw = chat(systemMsg, userMsg, 128)
                val clean = raw.replace(Regex("```json|```"), "").trim()
                parseCommandResult(clean)
            } catch (e: Exception) {
                Log.w(TAG, "Command parse failed: ${e.message}")
                CommandResult("unknown", null, commandText, false)
            }
        }

    // Detect tone of a message
    suspend fun detectTone(message: String): String = withContext(Dispatchers.IO) {
        try {
            val systemMsg = "Detect the tone of this message. " +
                "Reply with ONE word only: casual, formal, urgent, friendly, angry, or neutral."
            chat(systemMsg, message, 10).trim().lowercase()
        } catch (e: Exception) {
            "neutral"
        }
    }

    // Core chat function — sends to llama-server HTTP API
    private fun chat(systemMsg: String, userMsg: String, maxTokens: Int): String {
        val url = URL("$LLAMA_URL/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS

            // Build request JSON manually — no extra dependencies needed
            val requestBody = "{" +
                "\"model\":\"local\"," +
                "\"max_tokens\":$maxTokens," +
                "\"messages\":[" +
                "{\"role\":\"system\",\"content\":${jsonString(systemMsg)}}," +
                "{\"role\":\"user\",\"content\":${jsonString(userMsg)}}" +
                "]}"

            conn.outputStream.use { it.write(requestBody.toByteArray()) }

            val response = conn.inputStream.bufferedReader().readText()

            // Parse response — extract content from choices[0].message.content
            val contentStart = response.indexOf("\"content\":\"") + 11
            val contentEnd = response.indexOf("\"", contentStart)
            if (contentStart > 11 && contentEnd > contentStart) {
                return response.substring(contentStart, contentEnd)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .trim()
            }

            Log.w(TAG, "Could not parse response: ${response.take(200)}")
            return "I encountered an issue generating a response."

        } finally {
            conn.disconnect()
        }
    }

    // Escape string for JSON
    private fun jsonString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun parseCommandResult(json: String): CommandResult {
        return try {
            val action = extractJsonValue(json, "action") ?: "unknown"
            val target = extractJsonValue(json, "target")
            val content = extractJsonValue(json, "content")
            val confirm = json.contains("\"confirm_required\":true")
            CommandResult(action, target, content, confirm)
        } catch (e: Exception) {
            CommandResult("unknown", null, null, false)
        }
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1)?.takeIf { it != "null" }
    }

    data class CommandResult(
        val action: String,
        val target: String?,
        val content: String?,
        val confirmRequired: Boolean
    )
}
