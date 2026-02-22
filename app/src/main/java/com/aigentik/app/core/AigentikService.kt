package com.aigentik.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aigentik.app.R

// AigentikService v0.4 — initializes all engines on startup
class AigentikService : Service() {

    companion object {
        const val CHANNEL_ID = "aigentik_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "AigentikService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Aigentik starting..."))
        initEngines()
        Log.i(TAG, "AigentikService v0.4 started")
    }

    private fun initEngines() {
        // Init ContactEngine — loads saved contacts + syncs Android contacts
        ContactEngine.init(this)
        Log.i(TAG, "ContactEngine ready — ${ContactEngine.getCount()} contacts")

        // Init RuleEngine — loads saved rules
        RuleEngine.init(this)
        Log.i(TAG, "RuleEngine ready")

        // Configure MessageEngine with all callbacks
        MessageEngine.configure(
            adminNumber = "8602669332",
            ownerName = "Ish",
            agentName = "Aigentik",
            replySender = { number, body ->
                // NOTE: Google Voice email reply in v0.7
                Log.i(TAG, "Reply queued to $number: ${body.take(50)}")
            },
            ownerNotifier = { message ->
                // NOTE: Gmail notification in v0.7
                Log.i(TAG, "Owner notification: ${message.take(80)}")
                updateNotification(message.take(80))
            }
        )

        updateNotification("✅ Aigentik monitoring ${ContactEngine.getCount()} contacts")
        Log.i(TAG, "All engines initialized")
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aigentik Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Aigentik AI Assistant running in background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aigentik")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
