package com.aigentik.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.R
import com.aigentik.app.auth.AdminAuthManager
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.AigentikSettings
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException

// SettingsActivity v2.1
// v2.1: Added buttons to RuleManagerActivity and AiDiagnosticActivity.
// v2.0: Replaced app password with Sign in with Google (OAuth2).
//   Added admin password setup (SHA-256 hashed, stored in EncryptedSharedPreferences).
//   Shows signed-in Google account email.
//   Sign out clears OAuth session.
class SettingsActivity : AppCompatActivity() {

    private val RC_SIGN_IN = 9001

    private lateinit var etAgentName: EditText
    private lateinit var etOwnerName: EditText
    private lateinit var etAdminNumber: EditText
    private lateinit var etAigentikNumber: EditText
    private lateinit var etAdminPassword: EditText
    private lateinit var etAdminPasswordConfirm: EditText
    private lateinit var tvGoogleAccountStatus: TextView
    private lateinit var btnGoogleSignIn: Button
    private lateinit var btnGoogleSignOut: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        AigentikSettings.init(this)

        etAgentName            = findViewById(R.id.etAgentName)
        etOwnerName            = findViewById(R.id.etOwnerName)
        etAdminNumber          = findViewById(R.id.etAdminNumber)
        etAigentikNumber       = findViewById(R.id.etAigentikNumber)
        etAdminPassword        = findViewById(R.id.etAdminPassword)
        etAdminPasswordConfirm = findViewById(R.id.etAdminPasswordConfirm)
        tvGoogleAccountStatus  = findViewById(R.id.tvGoogleAccountStatus)
        btnGoogleSignIn        = findViewById(R.id.btnGoogleSignIn)
        btnGoogleSignOut       = findViewById(R.id.btnGoogleSignOut)
        tvStatus               = findViewById(R.id.tvSaveStatus)

        val btnSave        = findViewById<Button>(R.id.btnSave)
        val btnReset       = findViewById<Button>(R.id.btnResetSetup)
        val btnManageModel = findViewById<Button>(R.id.btnManageModel)

        // Load saved values
        etAgentName.setText(AigentikSettings.agentName)
        etOwnerName.setText(AigentikSettings.ownerName)
        etAdminNumber.setText(AigentikSettings.adminNumber)
        etAigentikNumber.setText(AigentikSettings.aigentikNumber)

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
            showStatus("✅ Settings saved — restart app to apply", success = true)
        }

        btnManageModel?.setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }

        findViewById<Button>(R.id.btnManageRules)?.setOnClickListener {
            startActivity(Intent(this, RuleManagerActivity::class.java))
        }

        findViewById<Button>(R.id.btnAiDiagnostic)?.setOnClickListener {
            startActivity(Intent(this, AiDiagnosticActivity::class.java))
        }

        btnReset.setOnClickListener {
            AigentikSettings.isConfigured = false
            startActivity(Intent(this, OnboardingActivity::class.java))
            finishAffinity()
        }
    }

    // Handle Google Sign-In result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            android.util.Log.d("SettingsActivity", "onActivityResult: resultCode=$resultCode")
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    android.util.Log.i("SettingsActivity", "Success: ${account.email}")
                    GoogleAuthManager.onSignInSuccess(this, account)
                    AigentikSettings.isOAuthSignedIn = true
                    // Store email for display and API use
                    account.email?.let { AigentikSettings.gmailAddress = it }
                    refreshGoogleAccountUI()
                    showStatus("✅ Signed in as ${account.email}", success = true)
                } else {
                    android.util.Log.e("SettingsActivity", "Sign-in failed: account is null")
                    showStatus("❌ Sign-in failed: no account received", success = false)
                }
            } catch (e: ApiException) {
                val statusCode = e.statusCode
                val statusString = GoogleSignInStatusCodes.getStatusCodeString(statusCode)
                android.util.Log.e("SettingsActivity", "Sign-in error: $statusCode ($statusString)")
                android.util.Log.e("SettingsActivity", "Message: ${e.status.statusMessage}")
                android.util.Log.e("SettingsActivity", "Resolution: ${e.status.resolution}", e)
                
                showStatus("❌ Sign-in failed ($statusString) — see Logcat", success = false)
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Sign-in unexpected error", e)
                showStatus("❌ Sign-in unexpected error: ${e.message}", success = false)
            }
        }
    }

    private fun refreshGoogleAccountUI() {
        val email = GoogleAuthManager.getSignedInEmail(this)
        if (email != null) {
            tvGoogleAccountStatus.text = "✅ Signed in as $email"
            tvGoogleAccountStatus.setTextColor(0xFF00D4FF.toInt())
            btnGoogleSignIn.visibility  = View.GONE
            btnGoogleSignOut.visibility = View.VISIBLE
        } else {
            tvGoogleAccountStatus.text = "Not signed in — Gmail features disabled"
            tvGoogleAccountStatus.setTextColor(0xFF7BA7CC.toInt())
            btnGoogleSignIn.visibility  = View.VISIBLE
            btnGoogleSignOut.visibility = View.GONE
        }
    }

    private fun showStatus(msg: String, success: Boolean) {
        tvStatus.setTextColor(if (success) 0xFF00D4FF.toInt() else 0xFFFF4444.toInt())
        tvStatus.text = msg
    }
}
