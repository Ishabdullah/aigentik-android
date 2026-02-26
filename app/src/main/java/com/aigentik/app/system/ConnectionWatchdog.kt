package com.aigentik.app.system

import android.content.Context
import android.util.Log
import com.aigentik.app.auth.GoogleAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ConnectionWatchdog v2.0
// REMOVED: GmailClient IMAP reconnect logic — no longer needed
// EmailMonitor v2 is notification-driven, no persistent connection to watch
//
// Now watches:
//   - OAuth2 token validity (Google Sign-In session)
//   - Logs warning if token lost so user knows to re-authenticate
//
// Runs every 30 minutes — very low overhead
object ConnectionWatchdog {

    private const val TAG = "ConnectionWatchdog"
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "ConnectionWatchdog started — OAuth2 session monitor")
        scope.launch {
            while (isActive && isRunning) {
                delay(CHECK_INTERVAL_MS)
                checkOAuthSession()
            }
        }
    }

    private fun checkOAuthSession() {
        val ctx = appContext ?: return
        val signedIn = GoogleAuthManager.isSignedIn(ctx)
        if (!signedIn) {
            // NOTE: Token expired or user signed out
            // User needs to re-authenticate in Settings
            // We log but do NOT auto-retry — that would require storing password
            Log.w(TAG, "⚠️ Google OAuth session lost — user must re-sign-in in Settings")
        } else {
            Log.d(TAG, "OAuth session healthy")
        }
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "ConnectionWatchdog stopped")
    }
}
