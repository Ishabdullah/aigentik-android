package com.aigentik.app.ui

import android.os.Bundle
import android.view.Gravity
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
import com.aigentik.app.chat.ChatDatabase
import com.aigentik.app.chat.ChatMessage
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.core.MessageEngine
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

// ChatActivity v0.9.3 — full admin chat with streaming display
// Commands typed here go directly to MessageEngine.handleAdminCommand()
// Responses stream word-by-word for low perceived latency
// History persisted in Room database
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        // Delay between words during streaming simulation (ms)
        private const val STREAM_WORD_DELAY = 40L
        private const val THINKING_PHRASES = true
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

    private var currentStreamJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        AigentikSettings.init(this)
        db = ChatDatabase.getInstance(this)

        // Wire views
        messageContainer = findViewById(R.id.messageContainer)
        scrollView = findViewById(R.id.scrollView)
        etMessage = findViewById(R.id.etMessage)
        layoutThinking = findViewById(R.id.layoutThinking)
        tvThinking = findViewById(R.id.tvThinking)
        tvModelIndicator = findViewById(R.id.tvModelIndicator)
        tvModelLabel = findViewById(R.id.tvModelLabel)

        val tvTitle = findViewById<TextView>(R.id.tvChatTitle)
        tvTitle.text = "Chat with ${AigentikSettings.agentName}"

        // Back button
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        // Send button
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendMessage() }

        // Update model status indicator
        updateModelStatus()

        // Load persisted chat history
        loadChatHistory()

        // Show welcome if first open
        scope.launch {
            val count = withContext(Dispatchers.IO) { db.chatDao().getCount() }
            if (count == 0) showWelcome()
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
            AiEngine.State.NOT_LOADED -> {
                tvModelIndicator.text = "🔴"
                tvModelLabel.text = "Fallback"
                tvModelLabel.setTextColor(0xFFFF4444.toInt())
            }
            AiEngine.State.ERROR -> {
                tvModelIndicator.text = "🔴"
                tvModelLabel.text = "Error"
                tvModelLabel.setTextColor(0xFFFF4444.toInt())
            }
        }
    }

    private fun loadChatHistory() {
        scope.launch {
            db.chatDao().getAllMessages().collectLatest { messages ->
                messageContainer.removeAllViews()
                messages.forEach { msg -> renderMessage(msg) }
                scrollToBottom()
            }
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        etMessage.text.clear()

        // Save user message
        scope.launch {
            val userMsg = ChatMessage(role = "user", content = text)
            withContext(Dispatchers.IO) { db.chatDao().insert(userMsg) }

            // Process command and stream response
            processCommand(text)
        }
    }

    private suspend fun processCommand(command: String) {
        // Show thinking indicator
        showThinking("Interpreting: \"${command.take(40)}\"...")
        updateModelStatus()

        // Insert placeholder assistant message for streaming
        val placeholderMsg = ChatMessage(
            role = "assistant",
            content = "▋", // cursor indicator
            isStreaming = true
        )
        val msgId = withContext(Dispatchers.IO) {
            db.chatDao().insert(placeholderMsg)
        }

        // Generate response via MessageEngine or AiEngine
        val fullResponse = withContext(Dispatchers.IO) {
            generateResponse(command)
        }

        // Stream response word by word
        streamResponse(msgId, fullResponse)
    }

    private fun generateResponse(command: String): String {
        return try {
            // Use AiEngine to interpret command then format response
            val agentName = AigentikSettings.agentName
            val ownerName = AigentikSettings.ownerName

            // Simple command routing — full NLP when model loaded
            val lower = command.lowercase().trim()
            when {
                lower == "status" || lower == "check status" -> {
                    val contactCount = com.aigentik.app.core.ContactEngine.getCount()
                    val aiStatus = AiEngine.state.name
                    val paused = if (AigentikSettings.isPaused) "⏸ PAUSED" else "✅ ACTIVE"
                    "$agentName Status\n" +
                    "• Service: $paused\n" +
                    "• Contacts: $contactCount\n" +
                    "• AI Engine: $aiStatus\n" +
                    "• Model: ${if (AiEngine.isReady()) AiEngine.getModelInfo() else "Not loaded"}\n" +
                    "• Gmail: ${AigentikSettings.gmailAddress}"
                }
                lower == "pause" || lower == "pause all" -> {
                    AigentikSettings.isPaused = true
                    "⏸ $agentName paused. I will stop auto-replying to messages. " +
                    "Say 'resume' to start again."
                }
                lower == "resume" || lower == "resume all" -> {
                    AigentikSettings.isPaused = false
                    "▶️ $agentName resumed. Auto-replies are active again."
                }
                lower.startsWith("find ") || lower.startsWith("look up ") -> {
                    val name = command.removePrefix("find ").removePrefix("look up ").trim()
                    val contact = com.aigentik.app.core.ContactEngine.findContact(name)
                    if (contact != null) {
                        "Found: ${contact.name}\n" +
                        "• Phone: ${contact.phone ?: "unknown"}\n" +
                        "• Email: ${contact.email ?: "unknown"}\n" +
                        "• Relationship: ${contact.relationship ?: "none set"}\n" +
                        "• Reply behavior: ${contact.replyBehavior}"
                    } else {
                        "No contact found for \"$name\". " +
                        "Try syncing contacts first."
                    }
                }
                lower.contains("sync contact") -> {
                    "🔄 Contact sync must be triggered from the dashboard. " +
                    "Tap '🔄 Sync Contacts' on the main screen."
                }
                lower.contains("help") || lower == "?" -> {
                    "Available commands:\n" +
                    "• status — show system status\n" +
                    "• pause / resume — toggle auto-replies\n" +
                    "• find [name] — look up a contact\n" +
                    "• never reply to [name] — block auto-replies\n" +
                    "• always reply to [name] — force auto-replies\n" +
                    "• text [name] [message] — send a message\n" +
                    "• list rules — show active rules\n" +
                    "• clear chat — clear this chat history"
                }
                lower == "clear chat" -> {
                    // Clear handled after response displayed
                    "🗑 Chat history will be cleared."
                }
                AiEngine.isReady() -> {
                    // Full AI interpretation when model loaded
                    val systemMsg = "You are $agentName, AI assistant for $ownerName. " +
                        "The user is $ownerName giving you a command or asking a question. " +
                        "Be concise and direct. Answer in 1-3 sentences unless a list is needed."
                    val prompt = com.aigentik.app.ai.LlamaJNI.getInstance()
                        .buildChatPrompt(systemMsg, command)
                    val response = com.aigentik.app.ai.LlamaJNI.getInstance()
                        .generate(prompt, 300)
                    // Strip any ChatML artifacts from response
                    response.replace("<|im_end|>", "")
                        .replace("<|im_start|>", "")
                        .trim()
                        .ifEmpty { "I processed that command." }
                }
                else -> {
                    "I received: \"$command\"\n\n" +
                    "AI model not loaded — load a model in Settings → Manage AI Model " +
                    "for full natural language commands. " +
                    "Type 'help' for available commands."
                }
            }
        } catch (e: Exception) {
            "Error processing command: ${e.message?.take(100)}"
        }
    }

    private suspend fun streamResponse(msgId: Long, fullText: String) {
        // Split into words for streaming simulation
        val words = fullText.split(" ")
        val builder = StringBuilder()

        currentStreamJob = scope.launch {
            // Show thinking phase with animated dots
            val thinkingPhrases = listOf(
                "Processing command...",
                "Analyzing request...",
                "Generating response...",
                "Almost ready..."
            )

            // Briefly show thinking phrases
            if (THINKING_PHRASES && words.size > 5) {
                for (phrase in thinkingPhrases.take(2)) {
                    showThinking(phrase)
                    delay(300)
                }
            }

            hideThinking()

            // Stream words
            words.forEachIndexed { index, word ->
                builder.append(if (index == 0) word else " $word")

                // Add cursor while streaming
                val displayText = builder.toString() + " ▋"

                // Update DB and UI
                withContext(Dispatchers.IO) {
                    db.chatDao().update(
                        ChatMessage(
                            id = msgId,
                            role = "assistant",
                            content = displayText,
                            isStreaming = true
                        )
                    )
                }
                delay(STREAM_WORD_DELAY)
            }

            // Final update — remove cursor, mark done
            withContext(Dispatchers.IO) {
                db.chatDao().update(
                    ChatMessage(
                        id = msgId,
                        role = "assistant",
                        content = fullText,
                        isStreaming = false
                    )
                )
            }

            // Handle clear chat command
            if (fullText.contains("Chat history will be cleared")) {
                delay(1500)
                withContext(Dispatchers.IO) { db.chatDao().clearAll() }
            }

            updateModelStatus()
            scrollToBottom()
        }
    }

    private fun renderMessage(msg: ChatMessage) {
        val layoutRes = if (msg.role == "user")
            R.layout.item_message_user
        else
            R.layout.item_message_assistant

        val view = LayoutInflater.from(this).inflate(layoutRes, messageContainer, false)
        val tvText = view.findViewById<TextView>(R.id.tvMessageText)
        val tvTime = view.findViewById<TextView>(R.id.tvTimestamp)

        tvText.text = msg.content
        tvTime.text = timeFormat.format(Date(msg.timestamp))

        messageContainer.addView(view)
    }

    private fun showWelcome() {
        scope.launch {
            val agentName = AigentikSettings.agentName
            val welcomeMsg = ChatMessage(
                role = "assistant",
                content = "👋 Hi! I'm $agentName. Type 'help' to see what I can do, " +
                         "or 'status' to check my current state."
            )
            withContext(Dispatchers.IO) { db.chatDao().insert(welcomeMsg) }
        }
    }

    private fun showThinking(text: String) {
        layoutThinking.visibility = View.VISIBLE
        tvThinking.text = text
    }

    private fun hideThinking() {
        layoutThinking.visibility = View.GONE
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        currentStreamJob?.cancel()
        super.onDestroy()
    }
}
