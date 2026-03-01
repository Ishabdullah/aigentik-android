package com.aigentik.app.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aigentik.app.R
import com.aigentik.app.adapters.NotificationAdapter
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.AigentikSettings
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException

// OnboardingActivity v2.0
// - Removed all app password fields and references
// - Added Sign in with Google (optional at setup, can do later in Settings)
// - Matches new dark theme
// - Flow: basic info → Google sign-in (optional) → permissions → model setup
class OnboardingActivity : AppCompatActivity() {

    private companion object {
        const val RC_SIGN_IN = 9001
        const val STORAGE_PERMISSION_CODE = 201
    }

    private var tvStatus: TextView? = null
    private var tvGoogleStatus: TextView? = null
    private var btnGoogleSignIn: Button? = null
    private var isWaitingForNotificationAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AigentikSettings.init(this)
        ThemeHelper.applySavedTheme()
        setContentView(R.layout.activity_onboarding)

        tvStatus       = findViewById(R.id.tvStatus)
        tvGoogleStatus = findViewById(R.id.tvGoogleAccountStatus)
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)

        // Update Google sign-in status if already signed in
        refreshGoogleUI()

        // Google sign-in button
        btnGoogleSignIn?.setOnClickListener {
            android.util.Log.d("Onboarding", "Button clicked")
            val client = GoogleAuthManager.buildSignInClient(this)
            startActivityForResult(client.signInIntent, RC_SIGN_IN)
        }

        // Start Aigentik button
        findViewById<Button>(R.id.btnSetup)?.setOnClickListener {
            val agentName      = findViewById<EditText>(R.id.etAgentName)
                ?.text?.toString()?.trim().orEmpty().ifEmpty { "Aigentik" }
            val ownerName      = findViewById<EditText>(R.id.etOwnerName)
                ?.text?.toString()?.trim().orEmpty()
            val adminNumber    = findViewById<EditText>(R.id.etAdminNumber)
                ?.text?.toString()?.trim().orEmpty()
            val aigentikNumber = findViewById<EditText>(R.id.etAigentikNumber)
                ?.text?.toString()?.trim().orEmpty()

            if (ownerName.isEmpty() || adminNumber.isEmpty()) {
                tvStatus?.setTextColor(0xFFFF4444.toInt())
                tvStatus?.text = "Your name and phone number are required"
                return@setOnClickListener
            }

            // Save settings — no app password, Gmail address auto-filled from OAuth
            val gmailAddress = GoogleAuthManager.getSignedInEmail(this) ?: ""
            AigentikSettings.saveFromOnboarding(
                agentName    = agentName,
                ownerName    = ownerName,
                adminNumber  = adminNumber,
                aigentikNumber = aigentikNumber,
                gmailAddress = gmailAddress,
                gmailAppPassword = "" // removed — OAuth only
            )

            tvStatus?.setTextColor(0xFF00D4FF.toInt())
            tvStatus?.text = "Saved — requesting permissions..."
            requestStoragePermission()
        }
    }

    // Handle Google Sign-In result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            android.util.Log.d("Onboarding", "onActivityResult: resultCode=$resultCode")
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    android.util.Log.i("Onboarding", "Success: ${account.email}")
                    GoogleAuthManager.onSignInSuccess(this, account)
                    AigentikSettings.isOAuthSignedIn = true
                    account.email?.let { AigentikSettings.gmailAddress = it }
                    refreshGoogleUI()
                    tvStatus?.setTextColor(0xFF00D4FF.toInt())
                    tvStatus?.text = "✅ Signed in as ${account.email}"
                    // Auto-advance: if owner name + phone are filled, save and proceed
                    tryAutoAdvance()
                } else {
                    android.util.Log.e("Onboarding", "Sign-in failed: account is null")
                    tvStatus?.setTextColor(0xFFFFAA00.toInt())
                    tvStatus?.text = "Google sign-in failed: no account received"
                }
            } catch (e: ApiException) {
                android.util.Log.e("Onboarding", "=== ERROR ===")
                android.util.Log.e("Onboarding", "Code: ${e.statusCode}")
                android.util.Log.e("Onboarding", "Msg: ${e.status.statusMessage}")
                android.util.Log.e("Onboarding", "Resolution: ${e.status.resolution}")
                android.util.Log.e("Onboarding", "GMS: ${GoogleSignInStatusCodes.getStatusCodeString(e.statusCode)}", e)
                
                Toast.makeText(this, "Error ${e.statusCode}: ${e.status.statusMessage}", Toast.LENGTH_LONG).show()
                
                tvStatus?.setTextColor(0xFFFFAA00.toInt())
                tvStatus?.text = "Google sign-in failed (${e.statusCode}) — check Logcat"
            } catch (e: Exception) {
                android.util.Log.e("Onboarding", "Sign-in unexpected error", e)
                tvStatus?.setTextColor(0xFFFFAA00.toInt())
                tvStatus?.text = "Google sign-in unexpected error: ${e.message}"
            }
        }
    }

    private fun tryAutoAdvance() {
        val ownerName   = findViewById<EditText>(R.id.etOwnerName)?.text?.toString()?.trim().orEmpty()
        val adminNumber = findViewById<EditText>(R.id.etAdminNumber)?.text?.toString()?.trim().orEmpty()

        if (ownerName.isEmpty() || adminNumber.isEmpty()) {
            tvStatus?.setTextColor(0xFFFFAA00.toInt())
            tvStatus?.text = "Fill in your name and phone, then tap Get Started."
            return
        }

        // Fields are filled and Google sign-in succeeded — save and proceed
        val agentName      = findViewById<EditText>(R.id.etAgentName)
            ?.text?.toString()?.trim().orEmpty().ifEmpty { "Aigentik" }
        val aigentikNumber = findViewById<EditText>(R.id.etAigentikNumber)
            ?.text?.toString()?.trim().orEmpty()
        val gmailAddress   = com.aigentik.app.auth.GoogleAuthManager.getSignedInEmail(this) ?: ""

        AigentikSettings.saveFromOnboarding(
            agentName      = agentName,
            ownerName      = ownerName,
            adminNumber    = adminNumber,
            aigentikNumber = aigentikNumber,
            gmailAddress   = gmailAddress,
            gmailAppPassword = ""
        )

        tvStatus?.setTextColor(0xFF00D4FF.toInt())
        tvStatus?.text = "Saved — requesting permissions..."
        requestStoragePermission()
    }

    private fun refreshGoogleUI() {
        val email = GoogleAuthManager.getSignedInEmail(this)
        if (email != null) {
            tvGoogleStatus?.text = "✅ Signed in as $email"
            tvGoogleStatus?.setTextColor(0xFF00D4FF.toInt())
            btnGoogleSignIn?.visibility = View.GONE
        } else {
            tvGoogleStatus?.text = "Not signed in — Gmail features disabled until signed in"
            tvGoogleStatus?.setTextColor(0xFF7BA7CC.toInt())
            btnGoogleSignIn?.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        if (isWaitingForNotificationAccess) {
            isWaitingForNotificationAccess = false
            if (isNotificationAccessGranted()) {
                tvStatus?.setTextColor(0xFF00D4FF.toInt())
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
            tvStatus?.setTextColor(0xFF00D4FF.toInt())
            tvStatus?.text = "All set — launching AI model setup..."
            launchModelManager()
        } else {
            showNotificationAccessDialog()
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val componentName = ComponentName(packageName, NotificationAdapter::class.java.name)
        return flat.split(":").any { entry ->
            try { ComponentName.unflattenFromString(entry) == componentName }
            catch (e: Exception) { false }
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
                "4. Return to this app"
            )
            .setPositiveButton("Open Settings") { _, _ ->
                isWaitingForNotificationAccess = true
                tvStatus?.setTextColor(0xFFFFAA00.toInt())
                tvStatus?.text = "Waiting for notification access..."
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Skip for now") { _, _ ->
                tvStatus?.setTextColor(0xFFFFAA00.toInt())
                tvStatus?.text = "Skipped — auto-reply disabled until granted"
                launchModelManager()
            }
            .setCancelable(false)
            .show()
    }

    private fun showNotificationAccessDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notification Access Not Granted")
            .setMessage(
                "Auto-reply will not work without this permission.\n\n" +
                "Grant it later in:\n" +
                "Settings > Apps > Special app access > Notification access > Aigentik"
            )
            .setPositiveButton("Try Again") { _, _ ->
                isWaitingForNotificationAccess = true
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Continue Anyway") { _, _ -> launchModelManager() }
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
