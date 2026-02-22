package com.aigentik.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aigentik.app.R
import com.aigentik.app.core.AigentikService
import com.aigentik.app.core.AigentikSettings

// MainActivity v0.7 — checks onboarding, requests permissions, starts service
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AigentikSettings.init(this)

        // First launch — go to onboarding
        if (!AigentikSettings.isConfigured) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        val agentName = AigentikSettings.agentName
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        tvVersion.text = "v0.7"

        // Check and request permissions
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            tvStatus.text = "Requesting permissions..."
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            startAigentik(agentName)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val agentName = AigentikSettings.agentName
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            val tvStatus = findViewById<TextView>(R.id.tvStatus)
            if (allGranted) {
                startAigentik(agentName)
            } else {
                tvStatus.text = "⚠️ Some permissions denied.\n$agentName may have limited functionality."
                startAigentik(agentName)
            }
        }
    }

    private fun startAigentik(agentName: String) {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "Starting $agentName..."
        startForegroundService(Intent(this, AigentikService::class.java))
        tvStatus.text = "✅ $agentName is running\n\nMonitoring Gmail + Google Voice"
    }
}
