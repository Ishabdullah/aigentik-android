package com.aigentik.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aigentik.app.R
import com.aigentik.app.core.AigentikSettings

// OnboardingActivity v0.9.2
// Flow: credentials → storage permission → model setup → main screen
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val STORAGE_PERMISSION_CODE = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AigentikSettings.init(this)
        setContentView(R.layout.activity_onboarding)

        // NOTE: The start button ID comes from activity_onboarding.xml
        // Layout was created in v0.7 with id btnStart
        val btnStart = findViewById<Button>(R.id.btnStart)
            ?: run {
                // Fallback — find any button in layout
                findViewById<Button>(android.R.id.button1)
            }

        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnStart?.setOnClickListener {
            val agentName = (findViewById<EditText>(R.id.etAgentName)
                ?.text?.toString()?.trim()).orEmpty().ifEmpty { "Aigentik" }
            val ownerName = (findViewById<EditText>(R.id.etOwnerName)
                ?.text?.toString()?.trim()).orEmpty()
            val adminNumber = (findViewById<EditText>(R.id.etAdminNumber)
                ?.text?.toString()?.trim()).orEmpty()
            val aigentikNumber = (findViewById<EditText>(R.id.etAigentikNumber)
                ?.text?.toString()?.trim()).orEmpty()
            val gmail = (findViewById<EditText>(R.id.etGmailAddress)
                ?.text?.toString()?.trim()).orEmpty()
            val password = (findViewById<EditText>(R.id.etAppPassword)
                ?.text?.toString()?.trim()).orEmpty()

            if (ownerName.isEmpty() || adminNumber.isEmpty() || gmail.isEmpty()) {
                tvStatus?.setTextColor(0xFFFF4444.toInt())
                tvStatus?.text = "Your name, phone number, and Gmail are required"
                return@setOnClickListener
            }

            AigentikSettings.saveFromOnboarding(
                agentName, ownerName, adminNumber,
                aigentikNumber, gmail, password
            )

            tvStatus?.setTextColor(0xFF00FF88.toInt())
            tvStatus?.text = "✅ Saved — requesting storage permission..."

            requestStoragePermission()
        }
    }

    private fun requestStoragePermission() {
        // Android 13+ uses granular media permissions — no READ_EXTERNAL_STORAGE needed
        // File picker works without it on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launchModelManager()
            return
        }

        val permission = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission)
            == PackageManager.PERMISSION_GRANTED) {
            launchModelManager()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(permission), STORAGE_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Proceed regardless — model can still download via URL without storage permission
        if (requestCode == STORAGE_PERMISSION_CODE) launchModelManager()
    }

    private fun launchModelManager() {
        val intent = Intent(this, ModelManagerActivity::class.java).apply {
            putExtra(ModelManagerActivity.EXTRA_SHOW_SKIP, true)
            putExtra(ModelManagerActivity.EXTRA_FROM_ONBOARDING, true)
        }
        startActivity(intent)
        finish()
    }
}
