package com.aigentik.app.auth

import android.accounts.Account
import android.content.Context
import com.aigentik.app.R
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// GoogleAuthManager v1.0
// Manages Google OAuth2 authentication for Aigentik
// Uses Google Sign-In SDK — no app passwords, no manual tokens
// Token refresh is automatic via GoogleAccountCredential
//
// Scopes granted:
//   - Gmail full access (read, send, delete, labels, spam)
//   - Contacts read (sync with ContactEngine)
//   - Calendar read (future use)
//
// Flow:
//   1. User taps "Sign in with Google" in SettingsActivity
//   2. Google sign-in intent launches
//   3. User picks account and grants permissions
//   4. Account stored — credential available app-wide
//   5. All API calls use credential.token (auto-refreshed)
object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"

    // Gmail OAuth2 scopes
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.send",
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/contacts.readonly",
        "https://www.googleapis.com/auth/calendar.readonly"
    )

    private var signedInAccount: GoogleSignInAccount? = null

    // Initialize from stored account on app start
    // Called by AigentikService.onCreate()
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

    // Called after successful sign-in in SettingsActivity
    fun onSignInSuccess(context: Context, account: GoogleSignInAccount) {
        signedInAccount = account
        Log.i(TAG, "Signed in as: ${account.email}")
    }

    // Build GoogleSignInClient for launching sign-in intent
    fun buildSignInClient(context: Context): GoogleSignInClient {
        val webClientId = context.getString(R.string.google_server_client_id)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestScopes(
                com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/gmail.modify"),
                com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/gmail.send"),
                com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/contacts.readonly")
            )
            .requestScopes(
                com.google.android.gms.common.api.Scope(SCOPES[0]),
                com.google.android.gms.common.api.Scope(SCOPES[1]),
                com.google.android.gms.common.api.Scope(SCOPES[2]),
                com.google.android.gms.common.api.Scope(SCOPES[3]),
                com.google.android.gms.common.api.Scope(SCOPES[4])
            )
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    // Get fresh OAuth2 token — auto-refreshes if expired
    // Must run on IO thread
    suspend fun getFreshToken(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val account = signedInAccount ?: GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                Log.e(TAG, "No signed-in account")
                return@withContext null
            }
            val androidAccount = account.account
                ?: Account(account.email, "com.google")
            val scope = "oauth2:${SCOPES.joinToString(" ")}"
            val token = GoogleAuthUtil.getToken(context, androidAccount, scope)
            Log.d(TAG, "Token obtained (length=${token.length})")
            token
        } catch (e: Exception) {
            Log.e(TAG, "Token fetch failed: ${e.message}")
            null
        }
    }

    fun isSignedIn(context: Context): Boolean =
        GoogleSignIn.getLastSignedInAccount(context) != null

    fun getSignedInEmail(context: Context): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    // Sign out — clears stored account
    fun signOut(context: Context, onComplete: () -> Unit = {}) {
        buildSignInClient(context).signOut().addOnCompleteListener {
            signedInAccount = null
            Log.i(TAG, "Signed out")
            onComplete()
        }
    }
}
