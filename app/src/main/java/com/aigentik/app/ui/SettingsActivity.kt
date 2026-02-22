package com.aigentik.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.R
import com.aigentik.app.core.AigentikSettings

// SettingsActivity v0.8 — edit config without reinstalling
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        AigentikSettings.init(this)

        val etAgentName = findViewById<EditText>(R.id.etAgentName)
        val etOwnerName = findViewById<EditText>(R.id.etOwnerName)
        val etAdminNumber = findViewById<EditText>(R.id.etAdminNumber)
        val etAigentikNumber = findViewById<EditText>(R.id.etAigentikNumber)
        val etGmailAddress = findViewById<EditText>(R.id.etGmailAddress)
        val etAppPassword = findViewById<EditText>(R.id.etAppPassword)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnReset = findViewById<Button>(R.id.btnResetSetup)
        val tvStatus = findViewById<TextView>(R.id.tvSaveStatus)

        // Load current values
        etAgentName.setText(AigentikSettings.agentName)
        etOwnerName.setText(AigentikSettings.ownerName)
        etAdminNumber.setText(AigentikSettings.adminNumber)
        etAigentikNumber.setText(AigentikSettings.aigentikNumber)
        etGmailAddress.setText(AigentikSettings.gmailAddress)
        etAppPassword.setText(AigentikSettings.gmailAppPassword)

        btnSave.setOnClickListener {
            val agentName = etAgentName.text.toString().trim().ifEmpty { "Aigentik" }
            val ownerName = etOwnerName.text.toString().trim()
            val adminNumber = etAdminNumber.text.toString().trim()
            val aigentikNumber = etAigentikNumber.text.toString().trim()
            val gmail = etGmailAddress.text.toString().trim()
            val password = etAppPassword.text.toString().trim()

            if (ownerName.isEmpty() || adminNumber.isEmpty() || gmail.isEmpty()) {
                tvStatus.setTextColor(0xFFFF4444.toInt())
                tvStatus.text = "Name, phone, and Gmail are required"
                return@setOnClickListener
            }

            AigentikSettings.saveFromOnboarding(
                agentName, ownerName, adminNumber,
                aigentikNumber, gmail, password
            )

            tvStatus.setTextColor(0xFF00FF88.toInt())
            tvStatus.text = "✅ Settings saved — restart app to apply"
        }

        btnReset.setOnClickListener {
            AigentikSettings.isConfigured = false
            startActivity(Intent(this, OnboardingActivity::class.java))
            finishAffinity()
        }
    }
}
