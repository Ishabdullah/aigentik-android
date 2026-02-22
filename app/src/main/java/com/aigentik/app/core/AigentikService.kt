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
import com.aigentik.app.system.ConnectionWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// AigentikService v0.9 — production ready with watchdog + error handling
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
            try {
                val agentName = AigentikSettings.agentName
                val ownerName = AigentikSettings.ownerName
                val adminNumber = AigentikSettings.adminNumber
                val gmail = AigentikSettings.gmailAddress
                val password = AigentikSettings.gmailAppPassword

                if (gmail.isEmpty() || password.isEmpty()) {
                    Log.e(TAG, "Gmail not configured — cannot start")
                    updateNotification("⚠️ Gmail not configured. Open app to set up.")
                    return@launch
                }

                // Init engines
                ContactEngine.init(this@AigentikService)
                RuleEngine.init(this@AigentikService)
                Log.i(TAG, "Engines initialized — ${ContactEngine.getCount()} contacts")

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
                        updateNotification(message.take(60))
                    }
                )

                // Auto-load AI model if previously configured
                val modelPath = AigentikSettings.modelPath
                if (modelPath.isNotEmpty() && java.io.File(modelPath).exists()) {
                    Log.i(TAG, "Auto-loading model: $modelPath")
                    AiEngine.loadModel(modelPath)
                } else {
                    Log.w(TAG, "No model configured 2014 using fallback replies")
                }

                // Start Gmail monitoring
                EmailMonitor.start()

                // Start connection watchdog
                ConnectionWatchdog.start()

                updateNotification(
                    "✅ $agentName active — ${ContactEngine.getCount()} contacts"
                )
                Log.i(TAG, "$agentName v0.9 fully started")

            } catch (e: Exception) {
                Log.e(TAG, "Startup error: ${e.message}")
                updateNotification("⚠️ Startup error: ${e.message?.take(50)}")
            }
        }
    }

    private fun updateNotification(message: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(message))
        } catch (e: Exception) {
            Log.w(TAG, "Could not update notification: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If service was killed and restarted by system re-init engines
        if (intent == null) {
            Log.i(TAG, "Service restarted by system — reinitializing")
            initAllEngines()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "AigentikService destroyed")
        ConnectionWatchdog.stop()
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
            setShowBadge(false)
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
            .setOnlyAlertOnce(true)
            .build()
}
