package com.aigentik.app.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.aigentik.app.R
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// GoogleAuthManager v1.8
// v1.8: CRITICAL FIX — Handle UserRecoverableAuthException in getFreshToken().
//   Gmail scopes (gmail.modify, gmail.send) are RESTRICTED and require explicit
//   user consent via a system-provided "Grant Access" dialog. The old code caught
//   all exceptions silently, preventing the consent dialog from ever appearing.
//   Now: UserRecoverableAuthException is caught specifically, its resolution Intent
//   is stored in pendingScopeIntent, and scopeResolutionListener is invoked to
//   notify the UI (SettingsActivity) so it can launch the consent flow.
//   Also added: gmailScopesGranted flag, hasPendingScopeResolution(), tokenHealthy flag.
// v1.7: removed pubsub scope — Pub/Sub removed, on-device notification listener used instead
// v1.6: added gmail.modify (required for trash/label/spam ops)
object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"

    // Sign-in scopes — sensitive only (work with test users on unverified apps)
    // ALL Gmail scopes (including gmail.readonly) are RESTRICTED and cause code 10
    // Only contacts.readonly is sensitive — safe for unverified apps with test users
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/contacts.readonly"
    )

    // Restricted scopes — requested incrementally via GoogleAuthUtil.getToken() after sign-in
    // This bypasses the sign-in scope restriction for unverified apps
    // User will see a consent prompt on first token request for each new scope
    //
    // gmail.modify — read, trash, label, mark spam, batchModify (superset of gmail.readonly)
    // gmail.send   — compose and send emails
    val GMAIL_SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.send"
    )

    private var signedInAccount: GoogleSignInAccount? = null

    // --- Scope resolution state ---
    // When getFreshToken() encounters a UserRecoverableAuthException, the resolution
    // Intent is stored here. An Activity must launch this Intent to show the
    // "Grant Access" dialog. After the user grants, getFreshToken() will succeed.
    @Volatile var pendingScopeIntent: Intent? = null
        private set

    // Listener notified when scope resolution is needed. SettingsActivity sets this
    // in onResume() and clears in onPause() to avoid leaking activity references.
    var scopeResolutionListener: ((Intent) -> Unit)? = null

    // Tracks whether Gmail scopes have been successfully granted (token obtained)
    @Volatile var gmailScopesGranted: Boolean = false
        private set

    // Last error message from getFreshToken() — for diagnostics
    @Volatile var lastTokenError: String? = null
        private set

    // Initialize from stored account on app start
    fun initFromStoredAccount(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null) {
            signedInAccount = account
            Log.i(TAG, "Restored Google account: ${account.email}")
            return true
        }
        Log.w(TAG, "No stored Google account — sign-in required")
        return false
    }

    // Called after successful sign-in
    fun onSignInSuccess(context: Context, account: GoogleSignInAccount) {
        signedInAccount = account
        Log.i(TAG, "Signed in as: ${account.email}")
    }

    // Build GoogleSignInClient — sensitive scopes only, no restricted Gmail scopes
    // requestIdToken requires Web application client ID (not Android client ID)
    fun buildSignInClient(context: Context): GoogleSignInClient {
        val webClientId = context.getString(R.string.google_server_client_id)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestScopes(Scope(SCOPES[0]))  // contacts.readonly
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    // Get fresh OAuth2 token with Gmail access — requested incrementally
    // GoogleAuthUtil.getToken() can request scopes independently of sign-in
    // This will trigger a consent prompt on first use for Gmail scopes
    // Must run on IO thread
    //
    // CRITICAL: UserRecoverableAuthException is caught specifically.
    // This exception carries an Intent that must be launched by an Activity
    // to show the Google "Grant Access" dialog for restricted Gmail scopes.
    // Without handling this, the app silently fails to get Gmail tokens.
    suspend fun getFreshToken(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val account = signedInAccount ?: GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                lastTokenError = "No signed-in account"
                Log.e(TAG, "No signed-in account")
                return@withContext null
            }
            val androidAccount = account.account
                ?: Account(account.email, "com.google")
            // Request all scopes (sign-in + Gmail) — GoogleAuthUtil handles incremental consent
            val allScopes = SCOPES + GMAIL_SCOPES
            val scope = "oauth2:${allScopes.joinToString(" ")}"
            val token = GoogleAuthUtil.getToken(context, androidAccount, scope)
            Log.d(TAG, "Token obtained (length=${token.length})")
            // Token obtained successfully — Gmail scopes are granted
            gmailScopesGranted = true
            pendingScopeIntent = null
            lastTokenError = null
            token
        } catch (e: UserRecoverableAuthException) {
            // CRITICAL: This exception means the user hasn't granted Gmail scopes yet.
            // The exception carries a resolution Intent that launches the consent dialog.
            // Store it so an Activity (SettingsActivity) can launch it.
            Log.w(TAG, "Gmail scope consent required — storing resolution intent")
            pendingScopeIntent = e.intent
            gmailScopesGranted = false
            lastTokenError = "Gmail permissions not granted — tap Grant Gmail Permissions in Settings"
            // Notify listener (if SettingsActivity is active, it will launch the consent dialog)
            val intent = e.intent
            if (intent != null) {
                scopeResolutionListener?.invoke(intent)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Token fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            lastTokenError = "Token error: ${e.message?.take(100)}"
            gmailScopesGranted = false
            null
        }
    }

    // Called after the user completes the scope consent dialog (Activity result OK)
    // Clears the pending intent so the UI updates accordingly
    fun onScopeConsentGranted() {
        pendingScopeIntent = null
        lastTokenError = null
        Log.i(TAG, "Scope consent granted — pending intent cleared")
    }

    // Called when scope consent is denied or cancelled
    fun onScopeConsentDenied() {
        Log.w(TAG, "Scope consent denied by user")
        lastTokenError = "Gmail permissions denied — required for email features"
    }

    fun hasPendingScopeResolution(): Boolean = pendingScopeIntent != null

    fun isSignedIn(context: Context): Boolean =
        GoogleSignIn.getLastSignedInAccount(context) != null

    fun getSignedInEmail(context: Context): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    fun signOut(context: Context, onComplete: () -> Unit = {}) {
        buildSignInClient(context).signOut().addOnCompleteListener {
            signedInAccount = null
            gmailScopesGranted = false
            pendingScopeIntent = null
            lastTokenError = null
            Log.i(TAG, "Signed out")
            onComplete()
        }
    }
}
