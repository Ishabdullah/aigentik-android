package com.aigentik.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aigentik.app.R

// AigentikService — Foreground service that keeps Aigentik alive
// Android will not kill this as long as notification is visible
class AigentikService : Service() {

    companion object {
        const val CHANNEL_ID = "aigentik_service_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Aigentik is monitoring..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // NOTE: START_STICKY ensures Android restarts service if killed
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
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
