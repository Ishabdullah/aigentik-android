package com.aigentik.app.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aigentik.app.R
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ConnectionWatchdog v2.2
// v2.2: Enhanced OAuth monitoring — now checks THREE conditions:
//   1. Google Sign-In session (isSignedIn)
//   2. Gmail scope grant status (gmailScopesGranted / hasPendingScopeResolution)
//   3. Immediate check after SettingsActivity sign-in via checkNow()
//   Posts different notifications for "session lost" vs "scope consent needed".
//   Added checkNow() for immediate check after sign-in (fixes the delay issue
//   where the notification wouldn't dismiss until the next 30-min check).
// v2.1: Posts high-priority notification when OAuth token is lost.
// v2.0: REMOVED GmailClient IMAP reconnect logic.
//
// Runs every 30 minutes — very low overhead
object ConnectionWatchdog {

    private const val TAG                  = "ConnectionWatchdog"
    private const val CHECK_INTERVAL_MS    = 30 * 60 * 1000L
    private const val ALERT_CHANNEL_ID     = "aigentik_auth_alert"
    private const val REAUTH_NOTIFICATION_ID = 2001
    private const val SCOPE_NOTIFICATION_ID  = 2002

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var appContext: Context? = null
    private var reauthNotificationPosted = false
    private var scopeNotificationPosted = false

    fun start(context: Context) {
        appContext = context.applicationContext
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "ConnectionWatchdog started — OAuth2 session + scope monitor")
        createAlertChannel(context.applicationContext)
        scope.launch {
            while (isActive && isRunning) {
                delay(CHECK_INTERVAL_MS)
                checkOAuthSession()
            }
        }
    }

    // Immediate check — call after sign-in success in SettingsActivity
    // to dismiss notifications without waiting for the next 30-min cycle
    fun checkNow() {
        scope.launch { checkOAuthSession() }
    }

    private fun checkOAuthSession() {
        val ctx = appContext ?: return
        val signedIn = GoogleAuthManager.isSignedIn(ctx)

        if (!signedIn) {
            Log.w(TAG, "Google OAuth session lost — user must re-sign-in")
            if (!reauthNotificationPosted) {
                postReauthNotification(ctx)
                reauthNotificationPosted = true
            }
            // Dismiss scope notification if sign-in is lost entirely
            if (scopeNotificationPosted) {
                dismissNotification(ctx, SCOPE_NOTIFICATION_ID)
                scopeNotificationPosted = false
            }
        } else {
            // Session exists — clear reauth notification
            if (reauthNotificationPosted) {
                dismissNotification(ctx, REAUTH_NOTIFICATION_ID)
                reauthNotificationPosted = false
                Log.i(TAG, "OAuth session restored — reauth notification dismissed")
            }

            // Check scope status
            if (GoogleAuthManager.hasPendingScopeResolution()) {
                Log.w(TAG, "Gmail scope consent needed — user must grant permissions")
                if (!scopeNotificationPosted) {
                    postScopeNotification(ctx)
                    scopeNotificationPosted = true
                }
            } else if (GoogleAuthManager.gmailScopesGranted) {
                Log.d(TAG, "OAuth session + Gmail scopes healthy")
                if (scopeNotificationPosted) {
                    dismissNotification(ctx, SCOPE_NOTIFICATION_ID)
                    scopeNotificationPosted = false
                    Log.i(TAG, "Gmail scopes granted — scope notification dismissed")
                }
            } else {
                Log.d(TAG, "OAuth session healthy, scope status unknown (not yet checked)")
            }
        }
    }

    // Post a high-priority notification directing the user to re-authenticate.
    // Tapping opens SettingsActivity where the Google Sign-In button is visible.
    private fun postReauthNotification(ctx: Context) {
        val settingsIntent = Intent(ctx, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, REAUTH_NOTIFICATION_ID, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
            .setContentTitle("Aigentik: Sign-in Required")
            .setContentText("Google session expired. Tap to re-sign-in and restore Gmail features.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your Google OAuth session has expired. Gmail auto-reply and email commands are paused until you re-sign-in.\n\nTap to open Settings → Sign in with Google."))
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false) // Keep until manually dismissed or session restored
            .setOngoing(false)
            .build()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(REAUTH_NOTIFICATION_ID, notification)
        Log.i(TAG, "Reauth notification posted")
    }

    // Post notification when Gmail scopes need consent
    private fun postScopeNotification(ctx: Context) {
        val settingsIntent = Intent(ctx, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, SCOPE_NOTIFICATION_ID, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
            .setContentTitle("Aigentik: Gmail Permissions Needed")
            .setContentText("Tap to grant Gmail access so email features work correctly.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Aigentik needs your permission to read and send Gmail on your behalf. Tap to open Settings and grant Gmail permissions."))
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(SCOPE_NOTIFICATION_ID, notification)
        Log.i(TAG, "Scope consent notification posted")
    }

    private fun dismissNotification(ctx: Context, notificationId: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(notificationId)
    }

    // High-priority channel for actionable alerts (distinct from the low-priority service channel)
    private fun createAlertChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALERT_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Aigentik Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Important alerts requiring user action (e.g. re-authentication)"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "ConnectionWatchdog stopped")
    }
}
