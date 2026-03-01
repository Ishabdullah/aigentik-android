package com.aigentik.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aigentik.app.BuildConfig
import com.aigentik.app.R
import com.aigentik.app.adapters.NotificationAdapter
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.core.AigentikService
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.ContactEngine
import com.aigentik.app.system.BatteryOptimizationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MainActivity v1.1
// Dashboard additions over v0.9.3:
//   - AI state display with color (Not loaded / Loading / Warming / Ready / Error)
//   - Model info line (vocab, ctx, threads, KV, batch) from nativeGetModelInfo
//   - Channel status row — SMS / GVoice / Email green/red indicators
//   - Notification access indicator — tappable, red if not granted
//   - Version from BuildConfig — not hardcoded
//   - onResume refresh — catches permission/setting changes when returning from Settings
class MainActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        private const val PERMISSION_REQUEST_CODE  = 100
        private const val BATTERY_OPT_REQUEST_CODE = 101
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val activityLog = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AigentikSettings.init(this)
        ThemeHelper.applySavedTheme()
        super.onCreate(savedInstanceState)

        if (!AigentikSettings.isConfigured) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // If configured, default to Chat screen
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Refresh every time user returns — catches NLS grant, battery changes
        updateStats()
    }

    private fun setupDashboard() {
        val agentName = AigentikSettings.agentName
        safeSetText(R.id.tvAppName, "🤖 $agentName")
        safeSetText(R.id.tvVersion, "v${BuildConfig.VERSION_NAME}")

        findViewById<Button>(R.id.btnOpenChat).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        val btnPause = findViewById<Button>(R.id.btnPause)
        refreshPauseButton(btnPause)
        btnPause.setOnClickListener {
            AigentikSettings.isPaused = !AigentikSettings.isPaused
            refreshPauseButton(btnPause)
            addActivity(
                if (AigentikSettings.isPaused) "⏸ $agentName paused"
                else "▶️ $agentName resumed"
            )
            updateStats()
        }
        btnPause.setOnLongClickListener {
            if (BatteryOptimizationHelper.shouldShowPrompt(this)) {
                startActivityForResult(
                    BatteryOptimizationHelper.getOptimizationSettingsIntent(this),
                    BATTERY_OPT_REQUEST_CODE
                )
            }
            true
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnSyncContacts).setOnClickListener {
            addActivity("🔄 Syncing contacts...")
            scope.launch {
                val added = ContactEngine.syncAndroidContacts(this@MainActivity)
                updateStats()
                addActivity("✅ Sync done — $added new contacts added")
            }
        }

        // Notification access row — tappable to open settings if not granted
        try {
            findViewById<TextView>(R.id.tvNotificationAccess)?.setOnClickListener {
                if (!isNotificationAccessGranted()) {
                    addActivity("⚙️ Opening notification access settings...")
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
        } catch (e: Exception) {}

        if (BatteryOptimizationHelper.shouldShowPrompt(this)) {
            addActivity("⚠️ Long-press Pause to disable battery optimization")
        }

        updateStats()

        scope.launch {
            while (true) {
                delay(10_000)
                updateStats()
            }
        }
    }

    private fun updateStats() {
        val paused    = AigentikSettings.isPaused
        val agentName = AigentikSettings.agentName

        // Agent running status
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus?.text = if (paused) "⏸ Paused" else "✅ $agentName Active"
        tvStatus?.setTextColor(
            if (paused) 0xFFFFAA00.toInt() else 0xFF00FF88.toInt()
        )

        // Contact count
        safeSetText(R.id.tvContactCount, ContactEngine.getCount().toString())

        // AI model state with color coding
        val aiColor = when (AiEngine.state) {
            AiEngine.State.READY    -> 0xFF00FF88.toInt()
            AiEngine.State.LOADING,
            AiEngine.State.WARMING  -> 0xFFFFAA00.toInt()
            else                    -> 0xFFFF4444.toInt()
        }
        safeSetText(R.id.tvAiStatus, "🤖 AI: ${AiEngine.getStateLabel()}")
        try { findViewById<TextView>(R.id.tvAiStatus)?.setTextColor(aiColor) }
        catch (e: Exception) {}

        // Model info — from nativeGetModelInfo (vocab/ctx/threads/kv/batch)
        safeSetText(R.id.tvModelInfo,
            "📊 ${if (AiEngine.isReady()) AiEngine.getModelInfo() else "No model loaded"}")

        // Channel states
        val channels = listOf(
            "SMS"    to ChannelManager.isEnabled(ChannelManager.Channel.SMS),
            "GVoice" to ChannelManager.isEnabled(ChannelManager.Channel.GVOICE),
            "Email"  to ChannelManager.isEnabled(ChannelManager.Channel.EMAIL)
        )
        safeSetText(R.id.tvChannelStatus,
            "📡 " + channels.joinToString(" | ") { (n, on) -> "${if (on) "🟢" else "🔴"} $n" })

        // Notification listener — critical for RCS inline reply
        val nlsOk = isNotificationAccessGranted()
        safeSetText(R.id.tvNotificationAccess,
            if (nlsOk) "🔔 Notification Access: ✅ Granted"
            else "🔔 Notification Access: ⚠️ NOT GRANTED — tap to fix")
        try {
            findViewById<TextView>(R.id.tvNotificationAccess)
                ?.setTextColor(if (nlsOk) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
        } catch (e: Exception) {}

        // Gmail
        val gmail = AigentikSettings.gmailAddress
        safeSetText(R.id.tvGmailStatus,
            "📧 Gmail: ${gmail.ifEmpty { "Not configured" }.take(30)}")

        // Battery optimization
        val battOk = !BatteryOptimizationHelper.shouldShowPrompt(this)
        safeSetText(R.id.tvRcsStatus,
            if (battOk) "🔋 Battery: Unrestricted ✅"
            else "🔋 Battery: Restricted ⚠️ (long-press Pause)")

        safeSetText(R.id.tvVersion, "v${BuildConfig.VERSION_NAME}")
    }

    private fun refreshPauseButton(btn: Button) {
        btn.text = if (AigentikSettings.isPaused)
            "▶️ Resume ${AigentikSettings.agentName}"
        else
            "⏸ Pause ${AigentikSettings.agentName}"
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: return false
        val target = ComponentName(packageName, NotificationAdapter::class.java.name)
        return flat.split(":").any { entry ->
            try { ComponentName.unflattenFromString(entry) == target }
            catch (e: Exception) { false }
        }
    }

    private fun safeSetText(id: Int, text: String) {
        try { findViewById<TextView>(id)?.text = text }
        catch (e: Exception) {}
    }

    private fun addActivity(entry: String) {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        activityLog.add(0, "[$time] $entry")
        if (activityLog.size > 12) activityLog.removeAt(activityLog.size - 1)
        safeSetText(R.id.tvActivityLog, activityLog.joinToString("\n"))
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
        addActivity("🚀 ${AigentikSettings.agentName} started")
        updateStats()
    }
}
