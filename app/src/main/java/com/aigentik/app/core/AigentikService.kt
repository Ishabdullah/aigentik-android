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
import com.aigentik.app.email.EmailMonitor
import com.aigentik.app.email.EmailRouter
import com.aigentik.app.email.GmailClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// AigentikService v0.7 — reads all config from AigentikSettings
class AigentikService : Service() {

    companion object {
        const val CHANNEL_ID = "aigentik_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "AigentikService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        AigentikSettings.init(this)
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("${AigentikSettings.agentName} starting...")
        )
        initAllEngines()
    }

    private fun initAllEngines() {
        scope.launch {
            val agentName = AigentikSettings.agentName
            val ownerName = AigentikSettings.ownerName
            val adminNumber = AigentikSettings.adminNumber
            val gmail = AigentikSettings.gmailAddress
            val password = AigentikSettings.gmailAppPassword

            // Init engines
            ContactEngine.init(this@AigentikService)
            RuleEngine.init(this@AigentikService)

            // Configure Gmail
            GmailClient.configure(gmail, password)

            // Configure MessageEngine
            MessageEngine.configure(
                adminNumber = adminNumber,
                ownerName = ownerName,
                agentName = agentName,
                replySender = { number, body ->
                    EmailRouter.replyViaGVoice(number, body)
                },
                ownerNotifier = { message ->
                    EmailRouter.notifyOwner(message)
                    updateNotification(message.take(80))
                }
            )

            // Start Gmail monitoring
            EmailMonitor.start()

            updateNotification("✅ $agentName monitoring — ${ContactEngine.getCount()} contacts")
            Log.i(TAG, "$agentName v0.7 fully started")
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        EmailMonitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Aigentik Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Aigentik AI Assistant running in background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(AigentikSettings.agentName)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
