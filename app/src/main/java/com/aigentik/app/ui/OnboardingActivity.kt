package com.aigentik.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.R
import com.aigentik.app.core.AigentikSettings

// OnboardingActivity v0.7 — first launch setup screen
class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val etAgentName = findViewById<EditText>(R.id.etAgentName)
        val etOwnerName = findViewById<EditText>(R.id.etOwnerName)
        val etAdminNumber = findViewById<EditText>(R.id.etAdminNumber)
        val etAigentikNumber = findViewById<EditText>(R.id.etAigentikNumber)
        val etGmailAddress = findViewById<EditText>(R.id.etGmailAddress)
        val etAppPassword = findViewById<EditText>(R.id.etAppPassword)
        val btnSetup = findViewById<Button>(R.id.btnSetup)
        val tvStatus = findViewById<TextView>(R.id.tvSetupStatus)

        btnSetup.setOnClickListener {
            val agentName = etAgentName.text.toString().trim()
                .ifEmpty { "Aigentik" }
            val ownerName = etOwnerName.text.toString().trim()
            val adminNumber = etAdminNumber.text.toString().trim()
            val aigentikNumber = etAigentikNumber.text.toString().trim()
            val gmail = etGmailAddress.text.toString().trim()
            val password = etAppPassword.text.toString().trim()

            // Validate
            val errors = mutableListOf<String>()
            if (ownerName.isEmpty()) errors.add("Your name is required")
            if (adminNumber.isEmpty()) errors.add("Your phone number is required")
            if (gmail.isEmpty()) errors.add("Gmail address is required")
            if (password.isEmpty()) errors.add("Gmail app password is required")

            if (errors.isNotEmpty()) {
                tvStatus.setTextColor(0xFFFF4444.toInt())
                tvStatus.text = errors.joinToString("\n")
                return@setOnClickListener
            }

            // Save settings
            AigentikSettings.saveFromOnboarding(
                agentName = agentName,
                ownerName = ownerName,
                adminNumber = adminNumber,
                aigentikNumber = aigentikNumber,
                gmailAddress = gmail,
                gmailAppPassword = password
            )

            tvStatus.setTextColor(0xFF00FF88.toInt())
            tvStatus.text = "✅ Setup complete! Starting $agentName..."
            btnSetup.isEnabled = false

            // Go to main screen
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
