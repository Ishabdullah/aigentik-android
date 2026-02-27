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

// GoogleAuthManager v1.3
// — Fixed keystore SHA-1: e67661285f6c279d1434c5662c1e174e32679d80
// — Uses Web client ID for requestIdToken (required for OAuth flow)
// — Android client ID registered in Google Cloud for SHA-1 verification
// — Scopes: gmail.readonly + contacts.readonly (sensitive, not restricted)
// — gmail.modify and gmail.send are RESTRICTED scopes that cause silent
//   code 10 (DEVELOPER_ERROR) on unverified apps — removed until verified
// — gmail.send requested incrementally via SEND_SCOPES when actually needed
object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"

    // Sign-in scopes — sensitive only (work with test users on unverified apps)
    // RESTRICTED scopes (gmail.modify, gmail.send) cause code 10 on unverified apps
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/contacts.readonly"
    )

    // Scopes needed for sending — requested incrementally after sign-in succeeds
    val SEND_SCOPES = listOf(
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

    // Build GoogleSignInClient — sensitive scopes only
    // NOTE: requestIdToken requires Web application client ID, not Android client ID
    // Android client ID is registered in Google Cloud for SHA-1 verification only
    fun buildSignInClient(context: Context): GoogleSignInClient {
        val webClientId = context.getString(R.string.google_server_client_id)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestScopes(
                Scope(SCOPES[0]), // gmail.readonly
                Scope(SCOPES[1])  // contacts.readonly
            )
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    // Get fresh OAuth2 token for read-only operations — auto-refreshes if expired
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

    // Get fresh OAuth2 token with send permission — for email sending operations
    // Requests gmail.send incrementally; may trigger consent prompt
    suspend fun getFreshSendToken(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val account = signedInAccount ?: GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                Log.e(TAG, "No signed-in account")
                return@withContext null
            }
            val androidAccount = account.account
                ?: Account(account.email, "com.google")
            val allScopes = SCOPES + SEND_SCOPES
            val scope = "oauth2:${allScopes.joinToString(" ")}"
            val token = GoogleAuthUtil.getToken(context, androidAccount, scope)
            Log.d(TAG, "Send token obtained (length=${token.length})")
            token
        } catch (e: Exception) {
            Log.e(TAG, "Send token fetch failed: ${e.message}")
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
