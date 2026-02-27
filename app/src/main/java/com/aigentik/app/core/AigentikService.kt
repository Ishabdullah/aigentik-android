package com.aigentik.app.core

import com.aigentik.app.auth.GoogleAuthManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aigentik.app.R
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.chat.ChatDatabase
import com.aigentik.app.core.ChatBridge
import com.aigentik.app.email.EmailMonitor
import com.aigentik.app.email.EmailRouter
import com.aigentik.app.sms.SmsRouter
import com.aigentik.app.system.ConnectionWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// AigentikService v1.1
// v1.1: ChatBridge init'd here (not just ChatActivity), OAuth replaces app password gate
// v1.0: SmsRouter.init(), ChannelManager.loadFromSettings()
// MessageEngine no longer takes replySender — routing is internal
// chatNotifier wired to post notifications into Room DB via ChatBridge
class AigentikService : Service() {

    companion object {
        const val CHANNEL_ID       = "aigentik_service_channel"
        const val NOTIFICATION_ID  = 1001
        private const val TAG      = "AigentikService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        AigentikSettings.init(this)
        // Restore Google OAuth session if previously signed in
        val oauthRestored = GoogleAuthManager.initFromStoredAccount(this)
        Log.i(TAG, "OAuth session restored: $oauthRestored")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID,
            buildNotification("${AigentikSettings.agentName} starting..."))
        initAllEngines()
    }

    private fun initAllEngines() {
        scope.launch {
            try {
                val agentName   = AigentikSettings.agentName
                val ownerName   = AigentikSettings.ownerName
                val adminNumber = AigentikSettings.adminNumber
                val gmail       = AigentikSettings.gmailAddress

                // Gmail features require either OAuth sign-in or a configured address
                // Service still starts without Gmail — SMS/chat work independently
                if (gmail.isEmpty() && !AigentikSettings.isOAuthSignedIn) {
                    Log.w(TAG, "Gmail not configured — email features disabled")
                    updateNotification("⚠️ Gmail not configured. SMS and chat still active.")
                }

                // Chat bridge — must init before MessageEngine so notifications post to Room DB
                val chatDb = ChatDatabase.getInstance(this@AigentikService)
                ChatBridge.init(chatDb)

                // Core engines
                ContactEngine.init(this@AigentikService)
                RuleEngine.init(this@AigentikService)
                Log.i(TAG, "Engines initialized — ${ContactEngine.getCount()} contacts")

                // Channel states
                ChannelManager.loadFromSettings()

                // SMS sending
                SmsRouter.init(this@AigentikService)

                // AI model
                val modelPath = AigentikSettings.modelPath
                AiEngine.configure(agentName, ownerName)
                if (modelPath.isNotEmpty() && java.io.File(modelPath).exists()) {
                    Log.i(TAG, "Auto-loading model: $modelPath")
                    AiEngine.loadModel(modelPath)
                    Log.i(TAG, "Model state: ${AiEngine.state}")
                } else {
                    Log.w(TAG, "No model — fallback mode")
                }

                // Gmail — OAuth2, notification-driven
                // NOTE: applicationContext captured outside coroutine scope
                // 'this' inside launch{} refers to CoroutineScope, not Service
                val appCtx = applicationContext
                EmailMonitor.init(appCtx)
                EmailRouter.init(appCtx)
                Log.i(TAG, "Email services initialized — waiting for Gmail notifications")

                // MessageEngine — no replySender arg now (routing is internal)
                MessageEngine.configure(
                    adminNumber  = adminNumber,
                    ownerName    = ownerName,
                    agentName    = agentName,
                    ownerNotifier = { message ->
                        EmailRouter.notifyOwner(message)
                        updateNotification(message.take(60))
                    }
                )

                // Wire chat notifier — posts to Room DB so notifications appear in chat
                MessageEngine.chatNotifier = { message ->
                    ChatBridge.post(message)
                }

                ConnectionWatchdog.start(applicationContext)

                val modelStatus = if (AiEngine.isReady()) "AI ready" else "AI fallback"
                updateNotification(
                    "✅ $agentName active — ${ContactEngine.getCount()} contacts — $modelStatus"
                )
                Log.i(TAG, "Aigentik v1.0 fully started")

            } catch (e: Exception) {
                Log.e(TAG, "Init failed: ${e.message}")
                updateNotification("⚠️ Init error: ${e.message?.take(50)}")
            }
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(AigentikSettings.agentName)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Aigentik Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Aigentik background service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) =
        START_STICKY

    override fun onBind(p: Intent?): IBinder? = null

    override fun onDestroy() {
        EmailMonitor.stop()
        ConnectionWatchdog.stop()
        super.onDestroy()
    }
}
