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
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// GoogleAuthManager v1.5
// — Fixed keystore SHA-1: e67661285f6c279d1434c5662c1e174e32679d80
// — Uses Web client ID for requestIdToken (required for OAuth flow)
// — Sign-in scopes: contacts.readonly only (sensitive — safe for unverified apps)
// — ALL Gmail scopes are RESTRICTED and cause code 10 on unverified apps
// — Gmail scopes requested incrementally via GoogleAuthUtil.getToken() after sign-in
object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"

    // Sign-in scopes — sensitive only (work with test users on unverified apps)
    // ALL Gmail scopes (including gmail.readonly) are RESTRICTED and cause code 10
    // Only contacts.readonly is sensitive — safe for unverified apps with test users
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/contacts.readonly"
    )

    // Gmail scopes — all restricted, requested incrementally via GoogleAuthUtil.getToken()
    // after sign-in succeeds. This bypasses the sign-in scope check.
    val GMAIL_SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.send"
    )

    private var signedInAccount: GoogleSignInAccount? = null

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
    suspend fun getFreshToken(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val account = signedInAccount ?: GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
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

    fun signOut(context: Context, onComplete: () -> Unit = {}) {
        buildSignInClient(context).signOut().addOnCompleteListener {
            signedInAccount = null
            Log.i(TAG, "Signed out")
            onComplete()
        }
    }
}
