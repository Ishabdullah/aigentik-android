package com.aigentik.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aigentik.app.R
import com.aigentik.app.adapters.NotificationAdapter
import com.aigentik.app.core.AigentikSettings

// OnboardingActivity v1.1
// Flow: credentials → storage permission → notification access → model setup → main screen
// Notification access required for NotificationListenerService inline reply transport
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val STORAGE_PERMISSION_CODE = 201
    }

    private var tvStatus: TextView? = null
    private var isWaitingForNotificationAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AigentikSettings.init(this)
        setContentView(R.layout.activity_onboarding)

        tvStatus = findViewById(R.id.tvStatus)

        val btnStart = findViewById<Button>(R.id.btnSetup)
            ?: findViewById(android.R.id.button1)

        btnStart?.setOnClickListener {
            val agentName      = findViewById<EditText>(R.id.etAgentName)
                ?.text?.toString()?.trim().orEmpty().ifEmpty { "Aigentik" }
            val ownerName      = findViewById<EditText>(R.id.etOwnerName)
                ?.text?.toString()?.trim().orEmpty()
            val adminNumber    = findViewById<EditText>(R.id.etAdminNumber)
                ?.text?.toString()?.trim().orEmpty()
            val aigentikNumber = findViewById<EditText>(R.id.etAigentikNumber)
                ?.text?.toString()?.trim().orEmpty()
            val gmail          = findViewById<EditText>(R.id.etGmailAddress)
                ?.text?.toString()?.trim().orEmpty()
            val password       = findViewById<EditText>(R.id.etAppPassword)
                ?.text?.toString()?.trim().orEmpty()

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
            tvStatus?.text = "Saved — requesting permissions..."
            requestStoragePermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isWaitingForNotificationAccess) {
            isWaitingForNotificationAccess = false
            if (isNotificationAccessGranted()) {
                tvStatus?.setTextColor(0xFF00FF88.toInt())
                tvStatus?.text = "Notification access granted — continuing..."
                launchModelManager()
            } else {
                tvStatus?.setTextColor(0xFFFFAA00.toInt())
                tvStatus?.text = "Notification access not granted — auto-reply will not work"
                showNotificationAccessDeniedDialog()
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkNotificationAccess()
            return
        }
        val permission = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            checkNotificationAccess()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) checkNotificationAccess()
    }

    private fun checkNotificationAccess() {
        if (isNotificationAccessGranted()) {
            tvStatus?.setTextColor(0xFF00FF88.toInt())
            tvStatus?.text = "All permissions granted — setting up AI model..."
            launchModelManager()
        } else {
            showNotificationAccessDialog()
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val componentName = ComponentName(packageName, NotificationAdapter::class.java.name)
        return flat.split(":").any { entry ->
            try {
                ComponentName.unflattenFromString(entry) == componentName
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun showNotificationAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notification Access Required")
            .setMessage(
                "Aigentik needs Notification Access to automatically reply to your " +
                "SMS and RCS messages.\n\n" +
                "On the next screen:\n" +
                "1. Find Aigentik in the list\n" +
                "2. Toggle it ON\n" +
                "3. Tap Allow when prompted\n" +
                "4. Return to this app\n\n" +
                "Without this, Aigentik can read messages but cannot send replies."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                isWaitingForNotificationAccess = true
                tvStatus?.setTextColor(0xFFFFAA00.toInt())
                tvStatus?.text = "Waiting for notification access..."
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Skip for now") { _, _ ->
                tvStatus?.setTextColor(0xFFFFAA00.toInt())
                tvStatus?.text = "Skipped — auto-reply disabled until granted in Settings"
                launchModelManager()
            }
            .setCancelable(false)
            .show()
    }

    private fun showNotificationAccessDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notification Access Not Granted")
            .setMessage(
                "Auto-reply to SMS and RCS messages will not work without this permission.\n\n" +
                "You can grant it later in:\n" +
                "Settings > Apps > Special app access > Notification access > Aigentik"
            )
            .setPositiveButton("Try Again") { _, _ ->
                isWaitingForNotificationAccess = true
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Continue Anyway") { _, _ ->
                launchModelManager()
            }
            .setCancelable(false)
            .show()
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
