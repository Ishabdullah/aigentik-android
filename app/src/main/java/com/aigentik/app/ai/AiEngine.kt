package com.aigentik.app.ai

import android.util.Log
import com.aigentik.app.core.AigentikPersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// AiEngine v1.3
// v1.3: Null-safe generate() calls — JNI nativeGenerate() can return null (e.g. OOM
//   on the C++ side). Previously .trim() on a null return caused NPE in generateSmsReply
//   and generateEmailReply (no inner try/catch), silently failing the chat response.
//   All three generate() call sites now use ?.trim() ?: "" with a Throwable catch so
//   native-side errors produce a safe fallback instead of propagating.
//   Added Log.d before each generate() call for crash-triage visibility in logcat.
//   AigentikPersona.name/ownerName now synced in configure() so persona responses
//   reflect the actual agent/owner names set during onboarding.
// v1.2: Temperature + top-p sampling for all AI generation calls.
//   - SMS/email replies use temperature=0.7, topP=0.9 (natural, varied)
//   - interpretCommand() uses temperature=0.0 (greedy) for deterministic JSON
//   - getStateLabel() now shows "Native lib error" when .so failed to load,
//     distinct from "Not loaded" (model not yet selected)
// v1.1 Changes:
//   - warmUp() called after load — primes JIT and KV cache so first reply is fast
//   - WARMING state added — dashboard shows loading progress accurately
//   - SMS max tokens 200 → 256, email 400 → 512 (uses 8k context properly)
//   - Email body intake 600 → 2000 chars (more context for better replies)
//   - getStateLabel() for dashboard display
object AiEngine {

    private const val TAG = "AiEngine"

    private var agentName = "Aigentik"
    private var ownerName = "Ish"
    private val llama = LlamaJNI.getInstance()

    enum class State { NOT_LOADED, LOADING, WARMING, READY, ERROR }

    @Volatile var state = State.NOT_LOADED
        private set

    fun configure(agentName: String, ownerName: String) {
        this.agentName = agentName
        this.ownerName = ownerName
        // Sync persona fields so AigentikPersona.respond() uses correct names
        AigentikPersona.name      = agentName
        AigentikPersona.ownerName = ownerName
    }

    // Load model then warm up — called by AigentikService on startup
    // NOT on first message — ensures first reply has no cold-start delay
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (state == State.LOADING || state == State.WARMING) {
            Log.w(TAG, "Load already in progress")
            return@withContext false
        }
        state = State.LOADING
        Log.i(TAG, "Loading model: $modelPath")

        val loaded = llama.loadModel(modelPath)
        if (!loaded) {
            Log.e(TAG, "Model load failed")
            state = State.ERROR
            return@withContext false
        }

        Log.i(TAG, "Model loaded — ${llama.getModelInfo()}")
        state = State.WARMING
        warmUp()
        state = State.READY
        Log.i(TAG, "Model ready and warmed up")
        true
    }

    // Warm-up: fire a trivial prompt to prime JIT compilation and KV cache
    // 4 tokens is enough — takes ~2s but saves 2s on the first real message
    private fun warmUp() {
        try {
            Log.i(TAG, "Warming up...")
            val prompt = llama.buildChatPrompt("You are a helpful assistant.", "Hello")
            val result = llama.generate(prompt, 4, temperature = 0.7f, topP = 0.9f)
            Log.i(TAG, "Warm-up done — ${result.length} chars")
        } catch (e: Exception) {
            // Non-fatal — model still works, first call just slower
            Log.w(TAG, "Warm-up failed (non-fatal): ${e.message}")
        }
    }

    fun isReady() = state == State.READY

    fun getModelInfo(): String = if (llama.isLoaded()) llama.getModelInfo() else "Not loaded"

    fun getStateLabel(): String = when {
        !llama.isNativeLibLoaded()   -> "Native lib error"
        state == State.NOT_LOADED    -> "Not loaded"
        state == State.LOADING       -> "Loading..."
        state == State.WARMING       -> "Warming up..."
        state == State.READY         -> "Ready"
        else                         -> "Error"
    }

    // Generate SMS reply — 256 tokens
    // conversationHistory: list of prior turns in chronological order (oldest first)
    //   Each entry is "User: <msg>" or "Assistant: <reply>".
    //   Pass empty list for stateless replies (default behavior).
    suspend fun generateSmsReply(
        senderName: String?,
        senderPhone: String,
        message: String,
        relationship: String?,
        instructions: String?,
        conversationHistory: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val signature = "\n\n— $agentName, personal agent of $ownerName. " +
            "If you need to reach $ownerName urgently, " +
            "include \"$ownerName\" in your message."

        if (!isReady()) {
            Log.w(TAG, "Model not ready — using fallback")
            return@withContext fallbackSmsReply(senderName, senderPhone) + signature
        }

        val systemMsg = "You are $agentName, an AI personal assistant for $ownerName. " +
            "Reply to a text message sent to $ownerName from ${senderName ?: senderPhone}. " +
            (relationship?.let { "Relationship: $it. " } ?: "") +
            (instructions?.let { "IMPORTANT: $it. " } ?: "") +
            "Be concise and natural — this is a text message. " +
            "Do NOT add a signature. Reply with message text only."

        // Build user turn: prepend conversation history if present
        val userTurn = buildString {
            if (conversationHistory.isNotEmpty()) {
                appendLine("Previous conversation:")
                conversationHistory.forEach { appendLine(it) }
                appendLine("---")
            }
            append("Reply to: \"$message\" from ${senderName ?: senderPhone}")
        }

        val prompt = llama.buildChatPrompt(systemMsg, userTurn)
        // temperature=0.7 + topP=0.9: natural, varied SMS replies
        // Null-safe: nativeGenerate() can return null (OOM/native-side error).
        // Catching Throwable ensures native JNI errors don't propagate as NPE.
        Log.d(TAG, "generateSmsReply: invoking llama.generate()")
        val raw = try {
            llama.generate(prompt, 256, temperature = 0.7f, topP = 0.9f)
        } catch (e: Throwable) {
            Log.e(TAG, "generateSmsReply: llama.generate() threw ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val reply = raw?.trim() ?: ""

        if (reply.isEmpty()) fallbackSmsReply(senderName, senderPhone) + signature
        else reply + signature
    }

    // Generate email reply — 512 tokens, up to 2000 char body
    // conversationHistory: prior turns in this email thread (chronological, oldest first)
    suspend fun generateEmailReply(
        fromName: String?,
        fromEmail: String,
        subject: String,
        body: String,
        relationship: String?,
        instructions: String?,
        conversationHistory: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val signature = "\n\n---\n$agentName | Personal Agent of $ownerName\n" +
            "For urgent matters include \"$ownerName\" in your subject."

        if (!isReady()) {
            return@withContext fallbackEmailReply(fromName, fromEmail) + signature
        }

        val systemMsg = "You are $agentName, an AI personal assistant for $ownerName. " +
            "Reply to an email sent to $ownerName from ${fromName ?: fromEmail}. " +
            (relationship?.let { "Relationship: $it. " } ?: "") +
            (instructions?.let { "IMPORTANT: $it. " } ?: "") +
            "Be professional and natural. Do NOT add a signature."

        val userTurn = buildString {
            if (conversationHistory.isNotEmpty()) {
                appendLine("Previous thread context:")
                conversationHistory.forEach { appendLine(it) }
                appendLine("---")
            }
            append("Subject: $subject\nBody: ${body.take(2000)}\n\nWrite a reply.")
        }

        val prompt = llama.buildChatPrompt(systemMsg, userTurn)
        // temperature=0.7 + topP=0.9: professional but not robotic email replies
        // Null-safe: same reasoning as generateSmsReply above.
        Log.d(TAG, "generateEmailReply: invoking llama.generate()")
        val raw = try {
            llama.generate(prompt, 512, temperature = 0.7f, topP = 0.9f)
        } catch (e: Throwable) {
            Log.e(TAG, "generateEmailReply: llama.generate() threw ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val reply = raw?.trim() ?: ""

        if (reply.isEmpty()) fallbackEmailReply(fromName, fromEmail) + signature
        else reply + signature
    }

    // Interpret natural language admin command
    suspend fun interpretCommand(commandText: String): CommandResult =
        withContext(Dispatchers.IO) {
            if (!isReady()) return@withContext parseSimpleCommand(commandText)

            val systemMsg = "You interpret commands for an AI assistant. " +
                "Return ONLY valid JSON with no extra text: " +
                "{\"action\":\"string\",\"target\":\"string or null\",\"content\":\"string or null\",\"query\":\"string or null\"} " +
                "The 'query' field is a Gmail search string (e.g. \"from:amazon is:unread\"). " +
                "Actions: send_sms, send_email, find_contact, get_contact_phone, " +
                "never_reply_to, always_reply_to, set_contact_instructions, " +
                "gmail_count_unread, gmail_list_unread, gmail_search, " +
                "gmail_trash, gmail_trash_all, gmail_mark_read, gmail_mark_read_all, " +
                "gmail_mark_spam, gmail_label, gmail_unsubscribe, gmail_empty_trash, " +
                "check_email, list_contacts, sync_contacts, status, unknown. " +
                "Examples: " +
                "\"how many unread emails\" -> {\"action\":\"gmail_count_unread\",\"target\":null,\"content\":null,\"query\":null} " +
                "\"any new emails\" -> {\"action\":\"gmail_count_unread\",\"target\":null,\"content\":null,\"query\":null} " +
                "\"list my unread emails\" -> {\"action\":\"gmail_list_unread\",\"target\":null,\"content\":null,\"query\":null} " +
                "\"what emails haven't I read\" -> {\"action\":\"gmail_list_unread\",\"target\":null,\"content\":null,\"query\":null} " +
                "\"could you check my emails\" -> {\"action\":\"gmail_list_unread\",\"target\":null,\"content\":null,\"query\":null} " +
                "\"check my inbox\" -> {\"action\":\"gmail_list_unread\",\"target\":null,\"content\":null,\"query\":null} " +
                "\"show emails from amazon\" -> {\"action\":\"gmail_search\",\"target\":\"amazon\",\"content\":null,\"query\":\"from:amazon\"} " +
                "\"delete that email from john\" -> {\"action\":\"gmail_trash\",\"target\":\"john\",\"content\":null,\"query\":\"from:john\"} " +
                "\"delete all emails from newsletters\" -> {\"action\":\"gmail_trash_all\",\"target\":\"newsletters\",\"content\":null,\"query\":\"from:newsletters\"} " +
                "\"mark emails from google as read\" -> {\"action\":\"gmail_mark_read_all\",\"target\":\"google\",\"content\":null,\"query\":\"from:google is:unread\"} " +
                "\"mark that amazon email as spam\" -> {\"action\":\"gmail_mark_spam\",\"target\":\"amazon\",\"content\":null,\"query\":\"from:amazon\"} " +
                "\"label amazon emails as shopping\" -> {\"action\":\"gmail_label\",\"target\":\"amazon\",\"content\":\"shopping\",\"query\":\"from:amazon\"} " +
                "\"unsubscribe from newsletters.com\" -> {\"action\":\"gmail_unsubscribe\",\"target\":\"newsletters.com\",\"content\":null,\"query\":\"from:newsletters.com\"} " +
                "\"empty trash\" -> {\"action\":\"gmail_empty_trash\",\"target\":null,\"content\":null,\"query\":null} " +
                "\"text mom I'll be late\" -> {\"action\":\"send_sms\",\"target\":\"mom\",\"content\":\"I'll be late\",\"query\":null} " +
                "\"what's Sarah's number\" -> {\"action\":\"get_contact_phone\",\"target\":\"Sarah\",\"content\":null,\"query\":null} " +
                "\"always reply formally to John\" -> {\"action\":\"set_contact_instructions\",\"target\":\"John\",\"content\":\"always reply formally\",\"query\":null} " +
                "\"when texting Mom be casual\" -> {\"action\":\"set_contact_instructions\",\"target\":\"Mom\",\"content\":\"be casual and friendly\",\"query\":null}"

            val prompt = llama.buildChatPrompt(systemMsg, "Command: \"$commandText\"")
            try {
                // temperature=0.0 → greedy for deterministic JSON output
                // Command parsing needs reliability over creativity
                // Null-safe: nativeGenerate() can return null; treat as parse failure.
                Log.d(TAG, "interpretCommand: invoking llama.generate()")
                val rawStr = llama.generate(prompt, 120, temperature = 0.0f, topP = 1.0f)
                val raw = rawStr?.trim() ?: return@withContext parseSimpleCommand(commandText)
                val clean = raw.replace(Regex("```json|```|<\\|im_end\\|>.*"), "").trim()
                parseCommandJson(clean)
            } catch (e: Throwable) {
                Log.w(TAG, "Command parse failed (${e.javaClass.simpleName}): ${e.message}")
                parseSimpleCommand(commandText)
            }
        }

    fun parseSimpleCommandPublic(text: String): CommandResult = parseSimpleCommand(text)

    private fun parseSimpleCommand(text: String): CommandResult {
        val lower = text.lowercase().trim()
        return when {
            lower.startsWith("text ") || lower.startsWith("send text") -> {
                val rest = lower.removePrefix("text ").removePrefix("send text ").trim()
                val parts = rest.split(" ", limit = 2)
                CommandResult("send_sms", parts.getOrNull(0), parts.getOrNull(1), false)
            }
            lower.startsWith("email ") || lower.startsWith("send email") -> {
                val rest = lower.removePrefix("email ").removePrefix("send email ").trim()
                val parts = rest.split(" ", limit = 2)
                CommandResult("send_email", parts.getOrNull(0), parts.getOrNull(1), false)
            }
            // Gmail actions — checked before generic "email" keywords
            lower.contains("empty") && lower.contains("trash") ->
                CommandResult("gmail_empty_trash", null, null, false)
            lower.contains("unread") && lower.contains("email") ->
                CommandResult("gmail_count_unread", null, null, false)
            lower.contains("how many") && lower.contains("email") ->
                CommandResult("gmail_count_unread", null, null, false)
            (lower.contains("list") || lower.contains("show")) &&
                (lower.contains("inbox") || lower.contains("email") || lower.contains("unread")) &&
                !lower.contains("from") ->
                CommandResult("gmail_list_unread", null, null, false)
            // Natural language email reading queries — phrased as questions not commands
            lower.contains("email") && (lower.contains("haven't") || lower.contains("not read")) ->
                CommandResult("gmail_list_unread", null, null, false)
            lower.contains("inbox") && !lower.contains("spam") ->
                CommandResult("gmail_list_unread", null, null, false)
            lower.contains("check") && lower.contains("email") ->
                CommandResult("gmail_list_unread", null, null, false)
            lower.contains("new") && lower.contains("email") ->
                CommandResult("gmail_count_unread", null, null, false)
            lower.startsWith("unsubscribe from ") -> {
                val sender = lower.removePrefix("unsubscribe from ").trim()
                CommandResult("gmail_unsubscribe", sender, null, false,
                    "from:$sender")
            }
            lower.contains("unsubscribe") ->
                CommandResult("gmail_unsubscribe", null, null, false)
            lower.contains("mark") && lower.contains("read") && lower.contains("all") ->
                CommandResult("gmail_mark_read_all", null, null, false, "is:unread in:inbox")
            lower.contains("mark") && lower.contains("spam") -> {
                val target = Regex("from ([\\w.@-]+)").find(lower)?.groupValues?.get(1)
                CommandResult("gmail_mark_spam", target, null, false,
                    if (target != null) "from:$target" else null)
            }
            lower.contains("check") && lower.contains("email") ->
                CommandResult("check_email", null, null, false)
            lower.contains("number") || (lower.contains("phone") && lower.contains("what")) -> {
                val name = lower.replace(Regex("what.?s|what is|get|phone|number|'s|whats"), "").trim()
                CommandResult("get_contact_phone", name.ifEmpty { null }, null, false)
            }
            lower.startsWith("find ") || lower.startsWith("look up ") ->
                CommandResult("find_contact",
                    lower.removePrefix("find ").removePrefix("look up ").trim(), null, false)
            lower.contains("never reply") ->
                CommandResult("never_reply_to",
                    lower.substringAfter("never reply to ").trim(), null, false)
            lower.contains("always reply") && !lower.contains("formally") &&
                !lower.contains("casually") && !lower.contains("briefly") ->
                CommandResult("always_reply_to",
                    lower.substringAfter("always reply to ").trim(), null, false)
            // Contact instructions — "always reply formally to X", "when texting X be Y"
            (lower.contains("reply") || lower.contains("texting") || lower.contains("respond")) &&
                (lower.contains("formally") || lower.contains("casually") ||
                 lower.contains("briefly") || lower.contains("professionally") ||
                 lower.contains("friendly") || lower.contains("short") ||
                 lower.contains("long") || lower.contains("formal")) -> {
                // Extract name — heuristic: word after "to" or "for" or last word before instruction
                val name = Regex("(?:to|for|with)\\s+(\\w+)").find(lower)?.groupValues?.get(1)
                    ?: lower.replace(Regex("always|reply|formally|casually|briefly|professionally|friendly|short|long|formal|texting|respond|when|be|to|for|with"), "").trim()
                CommandResult("set_contact_instructions", name.ifEmpty { null }, lower, false)
            }
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
        val action  = extractJsonValue(json, "action") ?: "unknown"
        val target  = extractJsonValue(json, "target")
        val content = extractJsonValue(json, "content")
        val query   = extractJsonValue(json, "query")
        return CommandResult(action, target, content, false, query)
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1)?.takeIf {
            it.isNotBlank() && it != "null"
        }
    }

    private fun fallbackSmsReply(senderName: String?, phone: String): String =
        "Hi ${senderName ?: "there"}, $agentName here — " +
        "personal AI assistant for $ownerName. " +
        "$ownerName will get back to you soon."

    private fun fallbackEmailReply(fromName: String?, fromEmail: String): String =
        "Hi ${fromName ?: "there"},\n\n" +
        "This is $agentName, $ownerName's personal AI assistant. " +
        "I've received your email and will pass it along.\n\nThank you for reaching out."

    data class CommandResult(
        val action: String,
        val target: String?,
        val content: String?,
        val confirmRequired: Boolean,
        val query: String? = null  // Gmail search query (e.g. "from:amazon is:unread")
    )
}
