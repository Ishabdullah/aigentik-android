package com.aigentik.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// AiEngine v0.9.1 — uses LlamaJNI for fully offline AI
// Falls back to template responses if model not loaded
object AiEngine {

    private const val TAG = "AiEngine"

    private var agentName = "Aigentik"
    private var ownerName = "Ish"
    private val llama = LlamaJNI.getInstance()

    enum class State {
        NOT_LOADED,   // Model file not found
        LOADING,      // Currently loading
        READY,        // Model loaded and ready
        ERROR         // Failed to load
    }

    @Volatile var state = State.NOT_LOADED
        private set

    fun configure(agentName: String, ownerName: String) {
        this.agentName = agentName
        this.ownerName = ownerName
    }

    // Load model from path
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (state == State.LOADING) return@withContext false

        state = State.LOADING
        Log.i(TAG, "Loading model: $modelPath")

        val success = llama.loadModel(modelPath)
        state = if (success) {
            Log.i(TAG, "Model ready — ${llama.getModelInfo()}")
            State.READY
        } else {
            Log.e(TAG, "Model load failed")
            State.ERROR
        }
        success
    }

    fun isReady() = state == State.READY

    fun getModelInfo(): String = if (isReady()) llama.getModelInfo() else "Not loaded"

    // Generate SMS reply
    suspend fun generateSmsReply(
        senderName: String?,
        senderPhone: String,
        message: String,
        relationship: String?,
        instructions: String?
    ): String = withContext(Dispatchers.IO) {
        val signature = "\n\n— $agentName, personal agent of $ownerName. " +
            "If you need to reach $ownerName urgently, " +
            "include \"$ownerName\" in your message."

        if (!isReady()) {
            Log.w(TAG, "Model not loaded — using fallback reply")
            return@withContext fallbackSmsReply(senderName, senderPhone) + signature
        }

        val instructionText = instructions?.let { "IMPORTANT: $it. " } ?: ""
        val relText = relationship?.let { "Relationship: $it. " } ?: ""

        val systemMsg = "You are $agentName, an AI personal assistant for $ownerName. " +
            "Reply to a text message sent to $ownerName from " +
            "${senderName ?: senderPhone}. " +
            "$relText$instructionText" +
            "Be concise and natural — this is a text message. " +
            "Do NOT add a signature. Reply with message text only."

        val userMsg = "Reply to: \"$message\" from ${senderName ?: senderPhone}"

        val prompt = llama.buildChatPrompt(systemMsg, userMsg)
        val reply = llama.generate(prompt, 200).trim()

        if (reply.isEmpty()) fallbackSmsReply(senderName, senderPhone) + signature
        else reply + signature
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
            "For urgent matters include \"$ownerName\" in your subject."

        if (!isReady()) {
            return@withContext fallbackEmailReply(fromName, fromEmail) + signature
        }

        val instructionText = instructions?.let { "IMPORTANT: $it. " } ?: ""
        val relText = relationship?.let { "Relationship: $it. " } ?: ""

        val systemMsg = "You are $agentName, an AI personal assistant for $ownerName. " +
            "Reply to an email sent to $ownerName from ${fromName ?: fromEmail}. " +
            "$relText$instructionText" +
            "Be professional and natural. Do NOT add a signature."

        val userMsg = "Subject: $subject\nBody: ${body.take(600)}\n\nWrite a reply."

        val prompt = llama.buildChatPrompt(systemMsg, userMsg)
        val reply = llama.generate(prompt, 400).trim()

        if (reply.isEmpty()) fallbackEmailReply(fromName, fromEmail) + signature
        else reply + signature
    }

    // Interpret natural language admin command
    suspend fun interpretCommand(commandText: String): CommandResult =
        withContext(Dispatchers.IO) {
            if (!isReady()) {
                return@withContext parseSimpleCommand(commandText)
            }

            val systemMsg = "You interpret commands for an AI assistant. " +
                "Return ONLY valid JSON: " +
                "{\"action\":\"string\",\"target\":\"string or null\"," +
                "\"content\":\"string or null\"} " +
                "Actions: send_sms, find_contact, never_reply_to, always_reply_to, " +
                "list_contacts, sync_contacts, add_rule, remove_rule, list_rules, " +
                "status, pause_all, resume_all, unknown. " +
                "Examples: " +
                "\"text mom I love her\" -> {\"action\":\"send_sms\",\"target\":\"mom\",\"content\":\"I love her\"} " +
                "\"find Mike\" -> {\"action\":\"find_contact\",\"target\":\"Mike\"} " +
                "\"never reply to spammer\" -> {\"action\":\"never_reply_to\",\"target\":\"spammer\"}"

            val userMsg = "Command: \"$commandText\""
            val prompt = llama.buildChatPrompt(systemMsg, userMsg)

            try {
                val raw = llama.generate(prompt, 100).trim()
                val clean = raw.replace(Regex("```json|```|<\\|im_end\\|>.*"), "").trim()
                parseCommandJson(clean)
            } catch (e: Exception) {
                Log.w(TAG, "Command parse failed: ${e.message}")
                parseSimpleCommand(commandText)
            }
        }

    // Detect message tone
    suspend fun detectTone(message: String): String = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext "neutral"
        val systemMsg = "Detect tone. Reply ONE word only: " +
            "casual, formal, urgent, friendly, angry, or neutral."
        val prompt = llama.buildChatPrompt(systemMsg, message)
        llama.generate(prompt, 5).trim().lowercase().ifEmpty { "neutral" }
    }

    // Simple rule-based command parser used as fallback when model not loaded
    // Public so MessageEngine can use it as keyword fallback
    fun parseSimpleCommandPublic(text: String): CommandResult = parseSimpleCommand(text)

    private fun parseSimpleCommand(text: String): CommandResult {
        val lower = text.lowercase().trim()
        return when {
            lower.startsWith("text ") || lower.startsWith("send text") -> {
                val parts = lower.removePrefix("text ").removePrefix("send text ").trim().split(" ", limit = 2)
                CommandResult("send_sms", parts.getOrNull(0), parts.getOrNull(1), false)
            }
            lower.startsWith("email ") || lower.startsWith("send email") -> {
                val parts = lower.removePrefix("email ").removePrefix("send email ").trim().split(" ", limit = 2)
                CommandResult("send_email", parts.getOrNull(0), parts.getOrNull(1), false)
            }
            lower.contains("check") && lower.contains("email") ->
                CommandResult("check_email", null, null, false)
            lower.contains("number") || (lower.contains("phone") && lower.contains("what")) -> {
                val name = lower.replace(Regex("what.?s|what is|get|phone|number|'s|whats"), "").trim()
                CommandResult("get_contact_phone", name.ifEmpty { null }, null, false)
            }
            lower.startsWith("find ") || lower.startsWith("look up ") ->
                CommandResult("find_contact", lower.removePrefix("find ").removePrefix("look up ").trim(), null, false)
            lower.contains("never reply") ->
                CommandResult("never_reply_to", lower.substringAfter("never reply to ").trim(), null, false)
            lower.contains("always reply") ->
                CommandResult("always_reply_to", lower.substringAfter("always reply to ").trim(), null, false)
            lower == "status" || lower == "check status" ->
                CommandResult("status", null, null, false)
            lower.contains("sync") && lower.contains("contact") ->
                CommandResult("sync_contacts", null, null, false)
            lower == "pause" || lower.contains("pause all") ->
                CommandResult("pause_all", null, null, false)
            lower == "resume" || lower.contains("resume all") ->
                CommandResult("resume_all", null, null, false)
            else -> CommandResult("unknown", null, text, false)
        }
    }

    private fun parseCommandJson(json: String): CommandResult {
        val action = extractJsonValue(json, "action") ?: "unknown"
        val target = extractJsonValue(json, "target")
        val content = extractJsonValue(json, "content")
        return CommandResult(action, target, content, false)
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1)?.takeIf {
            it.isNotBlank() && it != "null"
        }
    }

    // Fallback replies when model not loaded
    private fun fallbackSmsReply(senderName: String?, phone: String): String {
        return "Hi ${senderName ?: "there"}, $agentName here — " +
               "personal AI assistant for $ownerName. " +
               "$ownerName will get back to you soon."
    }

    private fun fallbackEmailReply(fromName: String?, fromEmail: String): String {
        return "Hi ${fromName ?: "there"},\n\n" +
               "This is $agentName, $ownerName's personal AI assistant. " +
               "I've received your email and will pass it along.\n\n" +
               "Thank you for reaching out."
    }

    data class CommandResult(
        val action: String,
        val target: String?,
        val content: String?,
        val confirmRequired: Boolean
    )
}
