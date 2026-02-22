package com.aigentik.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aigentik.app.R
import com.aigentik.app.core.AigentikService
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.core.ContactEngine
import com.aigentik.app.system.BatteryOptimizationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MainActivity v0.9 — battery optimization + full dashboard
class MainActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        private const val PERMISSION_REQUEST_CODE = 100
        private const val BATTERY_OPT_REQUEST_CODE = 101
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val activityLog = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AigentikSettings.init(this)

        if (!AigentikSettings.isConfigured) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        setupDashboard()
        checkPermissionsAndStart()
    }

    private fun setupDashboard() {
        val agentName = AigentikSettings.agentName
        findViewById<TextView>(R.id.tvAppName).text = "🤖 $agentName"
        findViewById<TextView>(R.id.tvVersion).text = "v0.9"

        updateStats()

        // Pause button
        val btnPause = findViewById<Button>(R.id.btnPause)
        btnPause.text = if (AigentikSettings.isPaused) "▶️ Resume $agentName"
                        else "⏸ Pause $agentName"

        btnPause.setOnClickListener {
            val paused = AigentikSettings.isPaused
            AigentikSettings.isPaused = !paused
            btnPause.text = if (!paused) "▶️ Resume $agentName" else "⏸ Pause $agentName"
            addActivity(if (!paused) "⏸ $agentName paused" else "▶️ $agentName resumed")
        }

        // Settings
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Sync contacts
        findViewById<Button>(R.id.btnSyncContacts).setOnClickListener {
            addActivity("🔄 Syncing contacts...")
            scope.launch {
                val added = ContactEngine.syncAndroidContacts(this@MainActivity)
                updateStats()
                addActivity("✅ Sync done — $added new contacts added")
            }
        }

        // Prompt battery optimization if needed
        if (BatteryOptimizationHelper.shouldShowPrompt(this)) {
            addActivity("⚠️ Tap to disable battery optimization")
            findViewById<Button>(R.id.btnPause).setOnLongClickListener {
                val intent = BatteryOptimizationHelper.getOptimizationSettingsIntent(this)
                startActivityForResult(intent, BATTERY_OPT_REQUEST_CODE)
                true
            }
        }

        // Refresh stats every 10s
        scope.launch {
            while (true) {
                updateStats()
                delay(10_000)
            }
        }
    }

    private fun updateStats() {
        try {
            val paused = AigentikSettings.isPaused
            val agentName = AigentikSettings.agentName

            findViewById<TextView>(R.id.tvContactCount).text =
                ContactEngine.getCount().toString()

            val tvStatus = findViewById<TextView>(R.id.tvStatus)
            tvStatus.text = if (paused) "⏸ Paused" else "✅ $agentName Active"
            tvStatus.setTextColor(
                if (paused) 0xFFFFAA00.toInt() else 0xFF00FF88.toInt()
            )

            findViewById<TextView>(R.id.tvGmailStatus).text =
                "📧 Gmail: ${AigentikSettings.gmailAddress.take(28)}"

            val battOk = !BatteryOptimizationHelper.shouldShowPrompt(this)
            findViewById<TextView>(R.id.tvRcsStatus).text =
                if (battOk) "🔋 Battery: unrestricted ✅"
                else "🔋 Battery: restricted ⚠️ (hold Pause to fix)"

        } catch (e: Exception) { }
    }

    private fun addActivity(entry: String) {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        activityLog.add(0, "[$time] $entry")
        if (activityLog.size > 10) activityLog.removeAt(activityLog.size - 1)
        try {
            findViewById<TextView>(R.id.tvActivityLog).text =
                activityLog.joinToString("\n")
        } catch (e: Exception) { }
    }

    private fun checkPermissionsAndStart() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), PERMISSION_REQUEST_CODE
            )
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
