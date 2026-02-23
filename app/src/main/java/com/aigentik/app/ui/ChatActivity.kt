package com.aigentik.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.aigentik.app.R
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.ai.LlamaJNI
import com.aigentik.app.chat.ChatDatabase
import com.aigentik.app.chat.ChatMessage
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.core.ContactEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ChatActivity v0.9.3b — fixed streaming display + empty response handling
// NOTE: Room Flow auto-refreshes UI on every DB update — streaming works by
//       repeatedly updating the same message row with progressively more text
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val STREAM_WORD_DELAY = 45L
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var db: ChatDatabase
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    private lateinit var messageContainer: LinearLayout
    private lateinit var scrollView: NestedScrollView
    private lateinit var etMessage: EditText
    private lateinit var layoutThinking: LinearLayout
    private lateinit var tvThinking: TextView
    private lateinit var tvModelIndicator: TextView
    private lateinit var tvModelLabel: TextView
    private lateinit var btnSend: Button

    private var streamJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        AigentikSettings.init(this)
        db = ChatDatabase.getInstance(this)
        com.aigentik.app.core.ChatBridge.init(db)

        messageContainer = findViewById(R.id.messageContainer)
        scrollView = findViewById(R.id.scrollView)
        etMessage = findViewById(R.id.etMessage)
        layoutThinking = findViewById(R.id.layoutThinking)
        tvThinking = findViewById(R.id.tvThinking)
        tvModelIndicator = findViewById(R.id.tvModelIndicator)
        tvModelLabel = findViewById(R.id.tvModelLabel)
        btnSend = findViewById(R.id.btnSend)

        findViewById<TextView>(R.id.tvChatTitle).text =
            "Chat with ${AigentikSettings.agentName}"
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        btnSend.setOnClickListener { sendMessage() }

        updateModelStatus()
        observeMessages()

        scope.launch {
            val count = withContext(Dispatchers.IO) { db.chatDao().getCount() }
            if (count == 0) insertWelcome()
        }
    }

    private fun updateModelStatus() {
        when (AiEngine.state) {
            AiEngine.State.READY -> {
                tvModelIndicator.text = "🟢"
                tvModelLabel.text = "AI Ready"
                tvModelLabel.setTextColor(0xFF00FF88.toInt())
            }
            AiEngine.State.LOADING -> {
                tvModelIndicator.text = "🟡"
                tvModelLabel.text = "Loading"
                tvModelLabel.setTextColor(0xFFFFAA00.toInt())
            }
            else -> {
                tvModelIndicator.text = "🔴"
                tvModelLabel.text = "Fallback"
                tvModelLabel.setTextColor(0xFFFF4444.toInt())
            }
        }
    }

    // Room Flow — re-renders full list on every DB change
    // This is what makes streaming work — each word update triggers re-render
    private fun observeMessages() {
        scope.launch {
            db.chatDao().getAllMessages().collectLatest { messages ->
                // NOTE: Full re-render on each update is fine for chat sizes
                // For very long histories (500+ msgs) consider partial updates
                messageContainer.removeAllViews()
                messages.forEach { renderMessage(it) }
                scrollToBottom()
            }
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        if (streamJob?.isActive == true) return // prevent double-send while streaming

        etMessage.text.clear()
        btnSend.isEnabled = false

        scope.launch {
            val userMsg = ChatMessage(role = "user", content = text)
            withContext(Dispatchers.IO) { db.chatDao().insert(userMsg) }
            processInput(text)
        }
    }

    private suspend fun processInput(input: String) {
        showThinking("Thinking...")
        updateModelStatus()

        // Insert streaming placeholder
        val placeholderId = withContext(Dispatchers.IO) {
            db.chatDao().insert(ChatMessage(role = "assistant", content = "▋", isStreaming = true))
        }

        // Generate response on IO thread
        val response = withContext(Dispatchers.IO) { generateResponse(input) }

        hideThinking()

        if (response.isBlank()) {
            // Should never happen but handle gracefully
            withContext(Dispatchers.IO) {
                db.chatDao().update(ChatMessage(
                    id = placeholderId,
                    role = "assistant",
                    content = "Sorry, I didn't get a response. Try again.",
                    isStreaming = false
                ))
            }
            btnSend.isEnabled = true
            return
        }

        // Stream word by word
        streamJob = scope.launch {
            val words = response.split(" ")
            val builder = StringBuilder()

            words.forEachIndexed { i, word ->
                builder.append(if (i == 0) word else " $word")

                withContext(Dispatchers.IO) {
                    db.chatDao().update(ChatMessage(
                        id = placeholderId,
                        role = "assistant",
                        content = "$builder ▋",
                        isStreaming = true
                    ))
                }
                delay(STREAM_WORD_DELAY)
            }

            // Final — remove cursor
            withContext(Dispatchers.IO) {
                db.chatDao().update(ChatMessage(
                    id = placeholderId,
                    role = "assistant",
                    content = response,
                    isStreaming = false
                ))
            }

            // Handle clear chat
            if (response.contains("Chat history will be cleared")) {
                delay(1500)
                withContext(Dispatchers.IO) { db.chatDao().clearAll() }
            }

            btnSend.isEnabled = true
            updateModelStatus()
        }
    }

    private fun generateResponse(input: String): String {
        val lower = input.lowercase().trim()
        val agentName = AigentikSettings.agentName
        val ownerName = AigentikSettings.ownerName

        return try {
            when {
                lower == "status" || lower == "check status" -> {
                    val contacts = ContactEngine.getCount()
                    val paused = if (AigentikSettings.isPaused) "⏸ PAUSED" else "✅ ACTIVE"
                    "$agentName Status\n" +
                    "• Service: $paused\n" +
                    "• Contacts: $contacts\n" +
                    "• AI Engine: ${AiEngine.state.name}\n" +
                    "• Model: ${if (AiEngine.isReady()) AiEngine.getModelInfo() else "Not loaded"}\n" +
                    "• Gmail: ${AigentikSettings.gmailAddress}"
                }
                lower == "pause" -> {
                    AigentikSettings.isPaused = true
                    "⏸ $agentName paused. Say 'resume' to start again."
                }
                lower == "resume" -> {
                    AigentikSettings.isPaused = false
                    "▶️ $agentName resumed. Auto-replies are active."
                }
                lower.startsWith("find ") || lower.startsWith("look up ") -> {
                    val name = input.removePrefix("find ").removePrefix("look up ").removePrefix("Find ").trim()
                    val contact = ContactEngine.findContact(name)
                    if (contact != null) {
                        "Found: ${contact.id}\n" +
                        "• Phone: ${contact.phones.firstOrNull() ?: "unknown"}\n" +
                        "• Email: ${contact.emails.firstOrNull() ?: "unknown"}\n" +
                        "• Aliases: ${contact.aliases.joinToString(", ").ifEmpty { "none" }}"
                    } else {
                        "No contact found for \"$name\"."
                    }
                }
                lower == "clear chat" -> "🗑 Chat history will be cleared."
                lower == "help" || lower == "?" -> {
                    "Available commands:\n" +
                    "• status — show system status\n" +
                    "• pause / resume — toggle auto-replies\n" +
                    "• find [name] — look up a contact\n" +
                    "• clear chat — clear chat history\n\n" +
                    "Or just talk naturally — I'll use AI to understand you."
                }
                AiEngine.isReady() -> {
                    // Full AI generation
                    val systemMsg = "You are $agentName, a personal AI assistant for $ownerName. " +
                        "Keep responses short and helpful — 1 to 3 sentences unless a list is needed. " +
                        "Do not use markdown formatting."
                    val prompt = LlamaJNI.getInstance().buildChatPrompt(systemMsg, input)
                    val raw = LlamaJNI.getInstance().generate(prompt, 300)

                    // NOTE: Strip all ChatML tags and whitespace artifacts
                    raw.replace("<|im_end|>", "")
                       .replace("<|im_start|>", "")
                       .replace(Regex("<\\|.*?\\|>"), "")
                       .trim()
                       .ifEmpty { "I'm here! Try asking me something or type 'help'." }
                }
                else -> {
                    "I heard you — load an AI model in Settings → Manage AI Model " +
                    "for natural conversation. Type 'help' for commands."
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message?.take(80)}"
        }
    }

    private fun renderMessage(msg: ChatMessage) {
        val layout = if (msg.role == "user")
            R.layout.item_message_user else R.layout.item_message_assistant
        val view = LayoutInflater.from(this).inflate(layout, messageContainer, false)
        view.findViewById<TextView>(R.id.tvMessageText).text = msg.content
        view.findViewById<TextView>(R.id.tvTimestamp).text =
            timeFormat.format(Date(msg.timestamp))
        messageContainer.addView(view)
    }

    private fun showThinking(text: String) {
        layoutThinking.visibility = View.VISIBLE
        tvThinking.text = text
    }

    private fun hideThinking() {
        layoutThinking.visibility = View.GONE
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun insertWelcome() {
        scope.launch {
            withContext(Dispatchers.IO) {
                db.chatDao().insert(ChatMessage(
                    role = "assistant",
                    content = "👋 Hi! I'm ${AigentikSettings.agentName}. " +
                              "Type 'help' to see commands or just talk to me naturally."
                ))
            }
        }
    }

    override fun onDestroy() {
        streamJob?.cancel()
        super.onDestroy()
    }
}
