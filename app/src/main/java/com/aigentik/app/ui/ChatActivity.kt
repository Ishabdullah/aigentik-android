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
import androidx.core.view.GravityCompat
import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout
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
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        private const val PERMISSION_REQUEST_CODE  = 100
    }

    // SupervisorJob — exceptions in one child coroutine don't cascade to others
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

    // True while waiting for MessageEngine to post a response via ChatBridge
    private var awaitingResponse = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AigentikSettings.init(this)
        ThemeHelper.applySavedTheme()
        setContentView(R.layout.activity_chat)
        db = ChatDatabase.getInstance(this)
        com.aigentik.app.core.ChatBridge.init(db)

        // Wire chatNotifier so MessageEngine responses appear in chat even if
        // AigentikService isn't running yet (service also sets this on startup)
        com.aigentik.app.core.MessageEngine.chatNotifier = { com.aigentik.app.core.ChatBridge.post(it) }

        // ContactEngine needed for status/find local commands
        ContactEngine.init(applicationContext)

        drawerLayout     = findViewById(R.id.drawerLayout)
        navView          = findViewById(R.id.navView)
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

        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        setupNavigation()
        updateDrawerHeader()
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

    private fun setupNavigation() {
        navView.setNavigationItemSelectedListener { menuItem ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.nav_chat -> { /* Already here */ }
                R.id.nav_model -> startActivity(Intent(this, ModelManagerActivity::class.java))
                R.id.nav_rules -> startActivity(Intent(this, RuleManagerActivity::class.java))
                R.id.nav_channels -> {
                    // Start SettingsActivity but maybe with a specific scroll position?
                    // For now just open settings
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                R.id.nav_diagnostic -> startActivity(Intent(this, AiDiagnosticActivity::class.java))
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_about -> {
                    val version = BuildConfig.VERSION_NAME
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Aigentik")
                        .setMessage("Version $version\n\nPrivacy-first on-device AI assistant.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            true
        }
    }

    private fun updateDrawerHeader() {
        val header = navView.getHeaderView(0)
        header.findViewById<TextView>(R.id.tvDrawerAgentName).text = AigentikSettings.agentName
        header.findViewById<TextView>(R.id.tvDrawerVersion).text = "v${BuildConfig.VERSION_NAME}"
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty() || awaitingResponse) return

        etMessage.text.clear()
        btnSend.isEnabled = false
        awaitingResponse  = true
        showThinking("Thinking...")

        scope.launch {
            try {
                // Save user message
                withContext(Dispatchers.IO) {
                    db.chatDao().insert(ChatMessage(role = "user", content = text))
                }

                // Fast local commands — no service required
                val local = resolveLocalCommand(text.lowercase().trim(), text)
                if (local != null) {
                    withContext(Dispatchers.IO) {
                        db.chatDao().insert(ChatMessage(role = "assistant", content = local))
                    }
                    awaitingResponse = false
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

                // Safety timeout — re-enable after 45s if chatNotifier never fires
                delay(45_000)
                if (awaitingResponse) {
                    awaitingResponse = false
                    hideThinking()
                    btnSend.isEnabled = true
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "sendMessage error: ${e.message}", e)
                withContext(Dispatchers.IO) {
                    db.chatDao().insert(ChatMessage(role = "assistant",
                        content = "⚠️ Error: ${e.message?.take(120) ?: "unknown error"}"))
                }
                awaitingResponse = false
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

                // When MessageEngine posts a response via ChatBridge, the last message
                // will be role="assistant" — that's our signal to re-enable the UI
                if (awaitingResponse && messages.lastOrNull()?.role == "assistant") {
                    awaitingResponse = false
                    hideThinking()
                    btnSend.isEnabled = true
                }
            }
        }
    }

    // Fast commands resolved on-device without going through MessageEngine
    private fun resolveLocalCommand(lower: String, original: String): String? {
        val agentName = AigentikSettings.agentName
        val ownerName = AigentikSettings.ownerName
        return when {
            lower == "status" || lower == "check status" -> {
                val contacts = ContactEngine.getCount()
                "$agentName Status\n" +
                "• Service: ${if (AigentikSettings.isPaused) "⏸ PAUSED" else "✅ ACTIVE"}\n" +
                "• Contacts: $contacts\n" +
                "• AI: ${AiEngine.state.name}\n" +
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
                val name = original
                    .removePrefix("find ").removePrefix("look up ")
                    .removePrefix("Find ").trim()
                val contact = ContactEngine.findContact(name)
                if (contact != null)
                    "📒 ${contact.id}\n• Phone: ${contact.phones.firstOrNull() ?: "none"}\n" +
                    "• Email: ${contact.emails.firstOrNull() ?: "none"}"
                else "No contact found for \"$name\"."
            }
            lower == "clear chat" -> {
                scope.launch {
                    delay(1500)
                    withContext(Dispatchers.IO) { db.chatDao().clearAll() }
                }
                "🗑 Chat history will be cleared."
            }
            lower == "help" || lower == "?" -> {
                "📧 Gmail: \"check emails\", \"how many unread\", \"delete from X\", \"label X as Y\"\n" +
                "💬 SMS: \"text Mom I'll be late\"\n" +
                "📒 Contacts: \"find Sarah\", \"what's Dad's number\"\n" +
                "📡 Channels: \"pause email\", \"resume sms\", \"channel status\"\n" +
                "⚙️ System: status, pause, resume, clear chat"
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
                    content = "👋 Hi! I'm ${AigentikSettings.agentName}.\n\n" +
                              "I can manage your Gmail, send texts, look up contacts, and more.\n" +
                              "Type 'help' to see what I can do."
                ))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
