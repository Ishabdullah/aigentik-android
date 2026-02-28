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

// ConnectionWatchdog v2.1
// v2.1: Posts a high-priority user-facing notification when OAuth token is lost.
//   Tapping the notification opens SettingsActivity so the user can re-sign-in.
//   Notification is dismissed once the next check finds the session healthy.
//   We do NOT auto-retry authentication — that would require storing credentials.
// v2.0: REMOVED GmailClient IMAP reconnect logic — EmailMonitor is notification-driven.
//
// Watches:
//   - OAuth2 token validity (Google Sign-In session)
//   - Posts alert notification if token lost; logs healthy status otherwise
//
// Runs every 30 minutes — very low overhead
object ConnectionWatchdog {

    private const val TAG                  = "ConnectionWatchdog"
    private const val CHECK_INTERVAL_MS    = 30 * 60 * 1000L
    private const val ALERT_CHANNEL_ID     = "aigentik_auth_alert"
    private const val REAUTH_NOTIFICATION_ID = 2001

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var appContext: Context? = null
    // Track whether we already posted the reauth notification so we don't spam
    private var reauthNotificationPosted = false

    fun start(context: Context) {
        appContext = context.applicationContext
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "ConnectionWatchdog started — OAuth2 session monitor")
        createAlertChannel(context.applicationContext)
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
            Log.w(TAG, "⚠️ Google OAuth session lost — user must re-sign-in in Settings")
            if (!reauthNotificationPosted) {
                postReauthNotification(ctx)
                reauthNotificationPosted = true
            }
        } else {
            Log.d(TAG, "OAuth session healthy")
            // Clear the notification and flag once session is restored
            if (reauthNotificationPosted) {
                dismissReauthNotification(ctx)
                reauthNotificationPosted = false
                Log.i(TAG, "OAuth session restored — reauth notification dismissed")
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

    private fun dismissReauthNotification(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(REAUTH_NOTIFICATION_ID)
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
