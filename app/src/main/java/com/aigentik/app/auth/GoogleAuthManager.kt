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

// GoogleAuthManager v1.2
// — Fixed keystore SHA-1: e67661285f6c279d1434c5662c1e174e32679d80
// — Uses Web client ID for requestIdToken (required for OAuth flow)
// — Android client ID registered in Google Cloud for SHA-1 verification
// — Scopes: gmail.modify, gmail.send, gmail.readonly, contacts.readonly
// — NOTE: calendar.readonly reserved for future use, not requested at sign-in
//   to minimize consent screen scope creep
object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"

    // Gmail OAuth2 scopes — must match Google Cloud consent screen config
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.send",
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/contacts.readonly"
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

    // Build GoogleSignInClient
    // NOTE: requestIdToken requires Web application client ID, not Android client ID
    // Android client ID is registered in Google Cloud for SHA-1 verification only
    fun buildSignInClient(context: Context): GoogleSignInClient {
        val webClientId = context.getString(R.string.google_server_client_id)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestScopes(
                Scope(SCOPES[0]), // gmail.modify
                Scope(SCOPES[1]), // gmail.send
                Scope(SCOPES[2]), // gmail.readonly
                Scope(SCOPES[3])  // contacts.readonly
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

    fun signOut(context: Context, onComplete: () -> Unit = {}) {
        buildSignInClient(context).signOut().addOnCompleteListener {
            signedInAccount = null
            Log.i(TAG, "Signed out")
            onComplete()
        }
    }
}
