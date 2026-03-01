package com.aigentik.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.R
import com.aigentik.app.auth.AdminAuthManager
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.system.ConnectionWatchdog
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// SettingsActivity v2.2
// v2.2: Gmail scope consent flow — after sign-in, attempts getFreshToken() to trigger
//   the Gmail scope consent dialog (UserRecoverableAuthException → resolution Intent).
//   Added "Grant Gmail Permissions" button that appears when signed in but scopes are
//   not yet granted. Added tvGmailScopeStatus showing scope health.
//   Sets GoogleAuthManager.scopeResolutionListener to auto-launch consent when needed.
// v2.1: Added buttons to RuleManagerActivity and AiDiagnosticActivity.
// v2.0: Replaced app password with Sign in with Google (OAuth2).
class SettingsActivity : AppCompatActivity() {

    private val RC_SIGN_IN = 9001
    private val RC_GMAIL_CONSENT = 9002

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var etAgentName: EditText
    private lateinit var etOwnerName: EditText
    private lateinit var etAdminNumber: EditText
    private lateinit var etAigentikNumber: EditText
    private lateinit var etAdminPassword: EditText
    private lateinit var etAdminPasswordConfirm: EditText
    private lateinit var tvGoogleAccountStatus: TextView
    private lateinit var tvGmailScopeStatus: TextView
    private lateinit var btnGoogleSignIn: Button
    private lateinit var btnGoogleSignOut: Button
    private lateinit var btnGrantGmailPerms: Button
    private lateinit var rgTheme: RadioGroup
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        AigentikSettings.init(this)

        etAgentName            = findViewById(R.id.etAgentName)
        etOwnerName            = findViewById(R.id.etOwnerName)
        etAdminNumber          = findViewById(R.id.etAdminNumber)
        etAigentikNumber       = findViewById(R.id.etAigentikNumber)
        tvGoogleAccountStatus  = findViewById(R.id.tvGoogleAccountStatus)
        btnGoogleSignIn        = findViewById(R.id.btnGoogleSignIn)
        btnGoogleSignOut       = findViewById(R.id.btnGoogleSignOut)
        rgTheme                = findViewById(R.id.rgTheme)
        tvStatus               = findViewById(R.id.tvSaveStatus)

        // Load saved values
        etAgentName.setText(AigentikSettings.agentName)
        etOwnerName.setText(AigentikSettings.ownerName)
        etAdminNumber.setText(AigentikSettings.adminNumber)
        etAigentikNumber.setText(AigentikSettings.aigentikNumber)

        // Load theme selection
        when (AigentikSettings.themeMode) {
            1 -> findViewById<RadioButton>(R.id.rbThemeLight).isChecked = true
            2 -> findViewById<RadioButton>(R.id.rbThemeDark).isChecked = true
            else -> findViewById<RadioButton>(R.id.rbThemeSystem).isChecked = true
        }

        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeLight -> 1
                R.id.rbThemeDark -> 2
                else -> 0
            }
            AigentikSettings.themeMode = mode
            ThemeHelper.applyTheme(mode)
        }

        // Update Google account UI
        refreshGoogleAccountUI()

        // Sign in with Google
        btnGoogleSignIn.setOnClickListener {
            android.util.Log.d("SettingsActivity", "Sign-in button clicked")
            val client = GoogleAuthManager.buildSignInClient(this)
            android.util.Log.d("SettingsActivity", "Client built, starting intent")
            startActivityForResult(client.signInIntent, RC_SIGN_IN)
        }

        // Sign out
        btnGoogleSignOut.setOnClickListener {
            GoogleAuthManager.signOut(this) {
                AigentikSettings.isOAuthSignedIn = false
                runOnUiThread { refreshGoogleAccountUI() }
                showStatus("Signed out of Google", success = true)
            }
        }

        // Grant Gmail Permissions — launches stored scope consent intent
        btnGrantGmailPerms.setOnClickListener {
            val intent = GoogleAuthManager.pendingScopeIntent
            if (intent != null) {
                android.util.Log.d("SettingsActivity", "Launching Gmail scope consent dialog")
                startActivityForResult(intent, RC_GMAIL_CONSENT)
            } else {
                // No stored intent — try getting a token to trigger the exception
                android.util.Log.d("SettingsActivity", "No pending intent — attempting token fetch")
                attemptGmailTokenFetch()
            }
        }

        // Save settings
        btnSave.setOnClickListener {
            val agentName   = etAgentName.text.toString().trim().ifEmpty { "Aigentik" }
            val ownerName   = etOwnerName.text.toString().trim()
            val adminNumber = etAdminNumber.text.toString().trim()
            val aigentikNum = etAigentikNumber.text.toString().trim()

            if (ownerName.isEmpty() || adminNumber.isEmpty()) {
                showStatus("Your name and phone number are required", success = false)
                return@setOnClickListener
            }

            // Save basic settings
            AigentikSettings.agentName      = agentName
            AigentikSettings.ownerName      = ownerName
            AigentikSettings.adminNumber    = adminNumber
            AigentikSettings.aigentikNumber = aigentikNum

            // Save admin password if provided
            val pw1 = etAdminPassword.text.toString()
            val pw2 = etAdminPasswordConfirm.text.toString()
            if (pw1.isNotEmpty() || pw2.isNotEmpty()) {
                if (pw1 != pw2) {
                    showStatus("Passwords do not match", success = false)
                    return@setOnClickListener
                }
                if (pw1.length < 4) {
                    showStatus("Password must be at least 4 characters", success = false)
                    return@setOnClickListener
                }
                AigentikSettings.adminPasswordHash = AdminAuthManager.hashPassword(pw1)
                etAdminPassword.setText("")
                etAdminPasswordConfirm.setText("")
            }

            AigentikSettings.isConfigured = true
            showStatus("Settings saved", success = true)
        }

        btnReset.setOnClickListener {
            AigentikSettings.isConfigured = false
            startActivity(Intent(this, OnboardingActivity::class.java))
            finishAffinity()
        }
    }

    override fun onResume() {
        super.onResume()
        // Register scope resolution listener — auto-launches consent dialog
        // when GoogleAuthManager detects UserRecoverableAuthException
        GoogleAuthManager.scopeResolutionListener = { intent ->
            runOnUiThread {
                android.util.Log.d("SettingsActivity", "Scope resolution listener triggered — launching consent")
                startActivityForResult(intent, RC_GMAIL_CONSENT)
            }
        }
        refreshGoogleAccountUI()
    }

    override fun onPause() {
        super.onPause()
        // Clear listener to avoid leaking activity reference
        GoogleAuthManager.scopeResolutionListener = null
    }

    // Attempt to get a Gmail token — triggers consent flow if scopes not yet granted
    private fun attemptGmailTokenFetch() {
        showStatus("Checking Gmail permissions...", success = true)
        scope.launch {
            val token = GoogleAuthManager.getFreshToken(this@SettingsActivity)
            if (token != null) {
                showStatus("Gmail permissions granted", success = true)
                refreshGoogleAccountUI()
            } else if (GoogleAuthManager.hasPendingScopeResolution()) {
                // UserRecoverableAuthException was caught — scopeResolutionListener
                // should have already launched the consent dialog. If not, update UI.
                refreshGoogleAccountUI()
            } else {
                val error = GoogleAuthManager.lastTokenError ?: "Unknown error"
                showStatus("Gmail token error: $error", success = false)
            }
        }
    }

    // Handle Google Sign-In result and Gmail scope consent result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            RC_SIGN_IN -> handleSignInResult(resultCode, data)
            RC_GMAIL_CONSENT -> handleGmailConsentResult(resultCode)
        }
    }

    private fun handleSignInResult(resultCode: Int, data: Intent?) {
        android.util.Log.d("SettingsActivity", "onActivityResult: resultCode=$resultCode")
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                android.util.Log.i("SettingsActivity", "Success: ${account.email}")
                GoogleAuthManager.onSignInSuccess(this, account)
                AigentikSettings.isOAuthSignedIn = true
                account.email?.let { AigentikSettings.gmailAddress = it }
                refreshGoogleAccountUI()
                showStatus("Signed in as ${account.email} — requesting Gmail permissions...", success = true)
                // Dismiss any "Sign-in Required" notification immediately
                ConnectionWatchdog.checkNow()
                // After sign-in, attempt to get Gmail token to trigger scope consent
                attemptGmailTokenFetch()
            } else {
                android.util.Log.e("SettingsActivity", "Sign-in failed: account is null")
                showStatus("Sign-in failed: no account received", success = false)
            }
        } catch (e: ApiException) {
            val statusCode = e.statusCode
            val statusString = GoogleSignInStatusCodes.getStatusCodeString(statusCode)
            android.util.Log.e("SettingsActivity", "Sign-in error: $statusCode ($statusString)")
            android.util.Log.e("SettingsActivity", "Message: ${e.status.statusMessage}")
            android.util.Log.e("SettingsActivity", "Resolution: ${e.status.resolution}", e)
            showStatus("Sign-in failed ($statusString) — see Logcat", success = false)
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Sign-in unexpected error", e)
            showStatus("Sign-in unexpected error: ${e.message}", success = false)
        }
    }

    private fun handleGmailConsentResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            android.util.Log.i("SettingsActivity", "Gmail scope consent granted by user")
            GoogleAuthManager.onScopeConsentGranted()
            showStatus("Gmail permissions granted", success = true)
            // Dismiss any "Scope Needed" notification immediately
            ConnectionWatchdog.checkNow()
            // Re-fetch token now that scopes are granted
            attemptGmailTokenFetch()
        } else {
            android.util.Log.w("SettingsActivity", "Gmail scope consent denied: resultCode=$resultCode")
            GoogleAuthManager.onScopeConsentDenied()
            showStatus("Gmail permissions denied — email features will not work", success = false)
        }
        refreshGoogleAccountUI()
    }

    private fun refreshGoogleAccountUI() {
        val email = GoogleAuthManager.getSignedInEmail(this)
        if (email != null) {
            tvGoogleAccountStatus.text = "Signed in as $email"
            tvGoogleAccountStatus.setTextColor(0xFF00D4FF.toInt())
            btnGoogleSignIn.visibility  = View.GONE
            btnGoogleSignOut.visibility = View.VISIBLE

            // Gmail scope status
            tvGmailScopeStatus.visibility = View.VISIBLE
            if (GoogleAuthManager.gmailScopesGranted) {
                tvGmailScopeStatus.text = "Gmail: Permissions granted"
                tvGmailScopeStatus.setTextColor(0xFF00FF88.toInt())
                btnGrantGmailPerms.visibility = View.GONE
            } else if (GoogleAuthManager.hasPendingScopeResolution()) {
                tvGmailScopeStatus.text = "Gmail: Permissions needed — tap button below"
                tvGmailScopeStatus.setTextColor(0xFFFFAA00.toInt())
                btnGrantGmailPerms.visibility = View.VISIBLE
            } else {
                tvGmailScopeStatus.text = "Gmail: Checking permissions..."
                tvGmailScopeStatus.setTextColor(0xFF7BA7CC.toInt())
                btnGrantGmailPerms.visibility = View.GONE
            }
        } else {
            tvGoogleAccountStatus.text = "Not signed in — Gmail features disabled"
            tvGoogleAccountStatus.setTextColor(0xFF7BA7CC.toInt())
            btnGoogleSignIn.visibility  = View.VISIBLE
            btnGoogleSignOut.visibility = View.GONE
            tvGmailScopeStatus.visibility = View.GONE
            btnGrantGmailPerms.visibility = View.GONE
        }
    }

    private fun showStatus(msg: String, success: Boolean) {
        tvStatus.setTextColor(if (success) 0xFF00D4FF.toInt() else 0xFFFF4444.toInt())
        tvStatus.text = msg
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }
}
