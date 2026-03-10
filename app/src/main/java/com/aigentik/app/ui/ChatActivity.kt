package com.aigentik.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.aigentik.app.BuildConfig
import com.aigentik.app.R
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.chat.ChatDatabase
import com.aigentik.app.chat.ChatMessage
import com.aigentik.app.core.AigentikService
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.core.ContactEngine
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ChatActivity v1.2
// v1.2: Stability hardening (code-audit-2026-03-10 findings):
//   1. CoroutineExceptionHandler added to scope — any uncaught Error (OOM etc.) in a
//      chat coroutine previously reached Android's UncaughtExceptionHandler and killed
//      the process. Handler now logs and does not re-throw (Bug 2b fix).
//   2. catch(e: Exception) widened to catch(e: Throwable) in sendMessage() — OOM and
//      other Error subclasses bypassed the catch block entirely (Bug 2a fix).
//   3. Safety timeout extracted into separate `timeoutJob` — previously the 120s delay
//      lived inside the message-processing coroutine, creating overlapping timeout
//      coroutines when messages were sent in quick succession. Each new sendMessage()
//      cancels the prior timeout before starting a new one (Bug 2c fix).
//   4. pendingUserMessageId tracks the Room ID of the current pending user message.
//      observeMessages() only resets awaitingResponse when it detects an assistant
//      message AFTER the pending user message — prevents email auto-reply notifications
//      (ChatBridge.post) from prematurely resetting the UI and allowing message 2 to
//      be sent before message 1's response arrives (Bug 2d fix).
// v1.1: Gear icon → SettingsHubActivity, no drawer.
class ChatActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        private const val PERMISSION_REQUEST_CODE  = 100
        private const val TAG = "ChatActivity"
    }

    // SupervisorJob — exceptions in one child coroutine don't cascade to others.
    // CoroutineExceptionHandler — catches any Error that escapes the coroutine's own
    // try/catch (e.g. OOM during Room I/O) and logs rather than killing the process.
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
            android.util.Log.e(TAG, "Scope error: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    )
    private lateinit var db: ChatDatabase
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    private lateinit var messageContainer: LinearLayout
    private lateinit var scrollView: NestedScrollView
    private lateinit var etMessage: EditText
    private lateinit var layoutThinking: LinearLayout
    private lateinit var tvThinking: TextView
    private lateinit var tvModelIndicator: TextView
    private lateinit var tvModelLabel: TextView
    private lateinit var btnSend: ImageButton

    // True while waiting for MessageEngine to post a response via ChatBridge
    private var awaitingResponse = false

    // Room-inserted ID of the user message currently awaiting a response.
    // Used by observeMessages() to detect the CORRECT response (not an unrelated
    // email auto-reply notification). Reset to -1L when response arrives or timeout fires.
    private var pendingUserMessageId: Long = -1L

    // Safety timeout job — separate from the send coroutine so it can be cancelled
    // when a new message is sent (prevents overlapping timeouts from prior messages).
    private var timeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AigentikSettings.init(this)
        ThemeHelper.applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        db = ChatDatabase.getInstance(this)
        com.aigentik.app.core.ChatBridge.init(db)

        // Wire chatNotifier so MessageEngine responses appear in chat even if
        // AigentikService isn't running yet (service also sets this on startup)
        com.aigentik.app.core.MessageEngine.chatNotifier = { com.aigentik.app.core.ChatBridge.post(it) }

        // Prime appContext immediately so Gmail commands work before service init completes
        // (service startup with model load can take 30-60s — without this all Gmail ops fail)
        com.aigentik.app.core.MessageEngine.initContext(applicationContext)

        // ContactEngine needed for status/find local commands.
        // Moved off main thread — init() calls loadFromRoom() (Room DAO) which throws
        // IllegalStateException on Android if called from the main thread.
        scope.launch(Dispatchers.IO) { ContactEngine.init(applicationContext) }

        messageContainer = findViewById(R.id.messageContainer)
        scrollView        = findViewById(R.id.scrollView)
        etMessage         = findViewById(R.id.etMessage)
        layoutThinking    = findViewById(R.id.layoutThinking)
        tvThinking        = findViewById(R.id.tvThinking)
        tvModelIndicator  = findViewById(R.id.tvModelIndicator)
        tvModelLabel      = findViewById(R.id.tvModelLabel)
        btnSend           = findViewById(R.id.btnSend)

        val agentName = AigentikSettings.agentName
        findViewById<TextView>(R.id.tvChatTitle).text = agentName

        // Gear icon → Settings Hub
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsHubActivity::class.java))
        }

        btnSend.setOnClickListener { sendMessage() }

        updateModelStatus()
        observeMessages()

        checkPermissionsAndStart()

        scope.launch {
            val count = withContext(Dispatchers.IO) { db.chatDao().getCount() }
            if (count == 0) insertWelcome()
        }
    }

    private fun checkPermissionsAndStart() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startAigentik()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) startAigentik()
    }

    private fun startAigentik() {
        startForegroundService(Intent(this, AigentikService::class.java))
        updateModelStatus()
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty() || awaitingResponse) return

        etMessage.text.clear()
        btnSend.isEnabled = false
        awaitingResponse  = true
        pendingUserMessageId = -1L
        showThinking("Thinking...")

        // Cancel any leftover timeout from the previous message — prevents the prior
        // message's 120s timer from firing mid-way through this message's response.
        timeoutJob?.cancel()
        timeoutJob = null

        scope.launch {
            try {
                // Save user message and capture the auto-generated Room ID.
                // pendingUserMessageId lets observeMessages distinguish the RESPONSE to
                // THIS message from unrelated assistant messages (e.g. email notifications).
                val insertedId = withContext(Dispatchers.IO) {
                    db.chatDao().insert(ChatMessage(role = "user", content = text))
                }
                pendingUserMessageId = insertedId

                // Fast local commands — no service required
                val local = resolveLocalCommand(text.lowercase().trim(), text)
                if (local != null) {
                    withContext(Dispatchers.IO) {
                        db.chatDao().insert(ChatMessage(role = "assistant", content = local))
                    }
                    awaitingResponse = false
                    pendingUserMessageId = -1L
                    hideThinking()
                    btnSend.isEnabled = true
                    return@launch
                }

                // Route to MessageEngine — Gmail, AI, SMS, contacts, channels, etc.
                // Response arrives via chatNotifier → ChatBridge.post() → Room DB → observeMessages()
                val msg = Message(
                    id         = System.currentTimeMillis().toString(),
                    sender     = "chat",
                    senderName = AigentikSettings.ownerName.ifBlank { null },
                    body       = text,
                    timestamp  = System.currentTimeMillis(),
                    channel    = Message.Channel.CHAT
                )
                withContext(Dispatchers.IO) {
                    MessageEngine.onMessageReceived(msg)
                }
                // Response arrives asynchronously via observeMessages — this coroutine exits.
                // The safety timeoutJob (below) re-enables UI if nothing arrives in 120s.

            } catch (e: Throwable) {
                // Widened from Exception to Throwable — OOM and other Error subclasses
                // previously bypassed this catch entirely (Bug 2a fix).
                android.util.Log.e(TAG, "sendMessage error: ${e.javaClass.simpleName}: ${e.message}", e)
                withContext(Dispatchers.IO) {
                    db.chatDao().insert(ChatMessage(role = "assistant",
                        content = "Error: ${e.message?.take(120) ?: "unknown error"}"))
                }
                awaitingResponse = false
                pendingUserMessageId = -1L
                hideThinking()
                btnSend.isEnabled = true
            }
        }

        // Safety timeout — separate job so it can be cancelled by next sendMessage() call.
        // Re-enables UI if chatNotifier never fires (e.g. service not running, Gmail API down).
        // 120s: generateChatReply at 512 tokens takes 25-60s; this is a genuine safety net.
        timeoutJob = scope.launch {
            delay(120_000)
            if (awaitingResponse) {
                awaitingResponse = false
                pendingUserMessageId = -1L
                hideThinking()
                btnSend.isEnabled = true
            }
        }
    }

    // Room Flow — re-renders on every DB change (user message or assistant response)
    private fun observeMessages() {
        scope.launch {
            db.chatDao().getAllMessages().collectLatest { messages ->
                messageContainer.removeAllViews()
                messages.forEach { renderMessage(it) }
                scrollToBottom()
                updateModelStatus()

                // Reset UI only when the response to the CURRENT pending user message arrives.
                // If pendingUserMessageId is set, look for an assistant message that appears
                // AFTER the pending user message in the list — this distinguishes a real reply
                // from an unrelated email auto-reply notification (Bug 2d fix).
                if (awaitingResponse) {
                    val pendingId = pendingUserMessageId
                    val shouldReset = if (pendingId > 0L) {
                        val idx = messages.indexOfLast { it.id == pendingId }
                        idx >= 0 && idx < messages.size - 1 &&
                            messages.drop(idx + 1).any { it.role == "assistant" }
                    } else {
                        // Fallback when no pendingId tracked (e.g. local command path)
                        messages.lastOrNull()?.role == "assistant"
                    }
                    if (shouldReset) {
                        awaitingResponse = false
                        pendingUserMessageId = -1L
                        timeoutJob?.cancel()
                        timeoutJob = null
                        hideThinking()
                        btnSend.isEnabled = true
                    }
                }
            }
        }
    }

    // Fast commands resolved on-device without going through MessageEngine
    private fun resolveLocalCommand(lower: String, original: String): String? {
        val agentName = AigentikSettings.agentName
        return when {
            lower == "status" || lower == "check status" -> {
                val contacts = ContactEngine.getCount()
                "$agentName Status\n" +
                "Service: ${if (AigentikSettings.isPaused) "PAUSED" else "ACTIVE"}\n" +
                "Contacts: $contacts\n" +
                "AI: ${AiEngine.state.name}\n" +
                "Model: ${if (AiEngine.isReady()) AiEngine.getModelInfo() else "Not loaded"}\n" +
                "Gmail: ${AigentikSettings.gmailAddress}"
            }
            lower == "pause" -> {
                AigentikSettings.isPaused = true
                "$agentName paused. Say 'resume' to start again."
            }
            lower == "resume" -> {
                AigentikSettings.isPaused = false
                "$agentName resumed. Auto-replies are active."
            }
            lower.startsWith("find ") || lower.startsWith("look up ") -> {
                val name = original
                    .removePrefix("find ").removePrefix("look up ")
                    .removePrefix("Find ").trim()
                val contact = ContactEngine.findContact(name)
                if (contact != null)
                    "${contact.id}\nPhone: ${contact.phones.firstOrNull() ?: "none"}\n" +
                    "Email: ${contact.emails.firstOrNull() ?: "none"}"
                else "No contact found for \"$name\"."
            }
            lower == "clear chat" -> {
                scope.launch {
                    delay(1500)
                    withContext(Dispatchers.IO) { db.chatDao().clearAll() }
                }
                "Chat history will be cleared."
            }
            lower == "help" || lower == "?" -> {
                "Gmail: \"check emails\", \"how many unread\", \"delete from X\", \"label X as Y\"\n" +
                "SMS: \"text Mom I'll be late\"\n" +
                "Contacts: \"find Sarah\", \"what's Dad's number\"\n" +
                "Channels: \"pause email\", \"resume sms\", \"channel status\"\n" +
                "System: status, pause, resume, clear chat"
            }
            else -> null  // Let MessageEngine handle it
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
                    content = "Hi! I'm ${AigentikSettings.agentName}.\n\n" +
                              "I can manage your Gmail, send texts, look up contacts, and more.\n" +
                              "Type 'help' to see what I can do."
                ))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutJob?.cancel()
        scope.cancel()
    }
}
