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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MainActivity v0.8 — full dashboard
class MainActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        private const val PERMISSION_REQUEST_CODE = 100
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
        val tvAppName = findViewById<TextView>(R.id.tvAppName)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val btnPause = findViewById<Button>(R.id.btnPause)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnSyncContacts = findViewById<Button>(R.id.btnSyncContacts)

        tvAppName.text = "🤖 $agentName"
        tvVersion.text = "v0.8"

        // Update contact count
        updateStats()

        btnPause.setOnClickListener {
            val paused = AigentikSettings.isPaused
            AigentikSettings.isPaused = !paused
            btnPause.text = if (!paused) "▶️ Resume Aigentik" else "⏸ Pause Aigentik"
            addActivity(if (!paused) "⏸ $agentName paused" else "▶️ $agentName resumed")
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnSyncContacts.setOnClickListener {
            addActivity("🔄 Syncing contacts...")
            scope.launch {
                val added = ContactEngine.syncAndroidContacts(this@MainActivity)
                updateStats()
                addActivity("✅ Sync done — $added new contacts added")
            }
        }

        // Start polling UI updates
        scope.launch {
            while (true) {
                updateStats()
                delay(10000)
            }
        }
    }

    private fun updateStats() {
        try {
            val tvContactCount = findViewById<TextView>(R.id.tvContactCount)
            val tvStatus = findViewById<TextView>(R.id.tvStatus)
            val tvGmailStatus = findViewById<TextView>(R.id.tvGmailStatus)

            tvContactCount.text = ContactEngine.getCount().toString()

            val paused = AigentikSettings.isPaused
            tvStatus.text = if (paused) "⏸ Paused" else "✅ ${AigentikSettings.agentName} Active"
            tvStatus.setTextColor(if (paused) 0xFFFFAA00.toInt() else 0xFF00FF88.toInt())
            tvGmailStatus.text = "📧 Gmail: ${AigentikSettings.gmailAddress}"
        } catch (e: Exception) { }
    }

    private fun addActivity(entry: String) {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        activityLog.add(0, "[$time] $entry")
        if (activityLog.size > 10) activityLog.removeAt(activityLog.size - 1)
        try {
            val tvLog = findViewById<TextView>(R.id.tvActivityLog)
            tvLog.text = activityLog.joinToString("\n")
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
