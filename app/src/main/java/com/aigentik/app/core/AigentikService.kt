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

// AigentikService v0.2 — wires MessageEngine on startup
class AigentikService : Service() {

    companion object {
        const val CHANNEL_ID = "aigentik_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "AigentikService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Aigentik is monitoring..."))
        configureEngine()
        Log.i(TAG, "AigentikService started")
    }

    private fun configureEngine() {
        // NOTE: Load from SharedPreferences in v0.9 onboarding
        // Hardcoded for now — will be user-configurable
        MessageEngine.configure(
            adminNumber = "8602669332",
            ownerName = "Ish",
            agentName = "Aigentik",
            replySender = { number, body ->
                // NOTE: Google Voice email reply in v0.7
                Log.i(TAG, "Reply to $number: ${body.take(50)}")
            },
            ownerNotifier = { message ->
                // NOTE: Gmail notification in v0.7
                Log.i(TAG, "Owner notified: ${message.take(80)}")
                updateNotification(message.take(80))
            }
        )
        Log.i(TAG, "MessageEngine configured")
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
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
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
