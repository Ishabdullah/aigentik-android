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

// AigentikService v0.6 — full engine + email wired
class AigentikService : Service() {

    companion object {
        const val CHANNEL_ID = "aigentik_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "AigentikService"

        // NOTE: Load from SharedPreferences in v0.9
        // Temporary hardcoded config — replaced by onboarding
        private const val GMAIL_ADDRESS = "ismail.t.abdullah@gmail.com"
        private const val GMAIL_APP_PASSWORD = "YOUR_APP_PASSWORD_HERE"
        private const val ADMIN_NUMBER = "8602669332"
        private const val OWNER_NAME = "Ish"
        private const val AGENT_NAME = "Aigentik"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Aigentik starting..."))
        initAllEngines()
    }

    private fun initAllEngines() {
        scope.launch {
            // Init contact and rule engines
            ContactEngine.init(this@AigentikService)
            RuleEngine.init(this@AigentikService)

            // Configure Gmail
            GmailClient.configure(GMAIL_ADDRESS, GMAIL_APP_PASSWORD)

            // Configure MessageEngine with email reply callbacks
            MessageEngine.configure(
                adminNumber = ADMIN_NUMBER,
                ownerName = OWNER_NAME,
                agentName = AGENT_NAME,
                replySender = { number, body ->
                    // Route reply through Google Voice email
                    EmailRouter.replyViaGVoice(number, body)
                },
                ownerNotifier = { message ->
                    // Notify owner via Gmail
                    EmailRouter.notifyOwner(message)
                    updateNotification(message.take(80))
                }
            )

            // Start Gmail polling
            EmailMonitor.start()

            updateNotification(
                "✅ Aigentik monitoring — ${ContactEngine.getCount()} contacts"
            )
            Log.i(TAG, "All engines started — Aigentik v0.6 ready")
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        EmailMonitor.stop()
        super.onDestroy()
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
