package com.aigentik.app.core

import com.aigentik.app.auth.GoogleAuthManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aigentik.app.R
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.chat.ChatDatabase
import com.aigentik.app.core.ChatBridge
import com.aigentik.app.email.EmailMonitor
import com.aigentik.app.email.EmailRouter
import com.aigentik.app.email.GmailPushManager
import com.aigentik.app.sms.SmsRouter
import com.aigentik.app.system.ConnectionWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// AigentikService v1.4
// v1.4: Gmail push notifications via Pub/Sub Watch — near-real-time email trigger
//   GmailPushManager.setup() registers Gmail Watch + Pub/Sub topic/subscription
//   EmailMonitor.startPushPolling() polls every 30s for new Pub/Sub messages
//   Falls back to NotificationAdapter listener if Pub/Sub setup fails
// v1.3: applicationContext passed to MessageEngine for Gmail API actions
// v1.2: PARTIAL_WAKE_LOCK passed to MessageEngine to prevent Samsung CPU throttling
//   during llama.cpp inference. Without it, background inference takes 5+ minutes
//   instead of ~30 seconds. WakeLock is acquired per-message and auto-released.
// v1.1: ChatBridge init'd here, OAuth replaces app password gate
class AigentikService : Service() {

    companion object {
        const val CHANNEL_ID       = "aigentik_service_channel"
        const val NOTIFICATION_ID  = 1001
        private const val TAG      = "AigentikService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AigentikSettings.init(this)
        // Restore Google OAuth session if previously signed in
        val oauthRestored = GoogleAuthManager.initFromStoredAccount(this)
        Log.i(TAG, "OAuth session restored: $oauthRestored")
        // Create wake lock for AI inference — prevents Samsung CPU throttling in background
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aigentik:inference")
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

                // Gmail — OAuth2, push notifications via Pub/Sub + notification fallback
                // NOTE: applicationContext captured outside coroutine scope
                // 'this' inside launch{} refers to CoroutineScope, not Service
                val appCtx = applicationContext
                EmailMonitor.init(appCtx)
                EmailRouter.init(appCtx)

                // Set up Gmail Watch + Pub/Sub for near-real-time push notifications
                // Runs in a separate coroutine so a slow setup doesn't block service start
                // On failure, NotificationAdapter listener continues as fallback
                scope.launch {
                    val pushOk = GmailPushManager.setup(appCtx)
                    if (pushOk) {
                        Log.i(TAG, "Gmail push notifications active (Pub/Sub Watch)")
                        updateNotification("📬 Gmail push active")
                    } else {
                        Log.w(TAG, "Gmail push setup failed — notification-listener fallback only")
                    }
                }

                // Start 30-second Pub/Sub polling loop inside this foreground service
                EmailMonitor.startPushPolling(appCtx)
                Log.i(TAG, "Email push polling started — ${appCtx.packageName}")

                // MessageEngine — context for Gmail API, wakeLock for background inference
                MessageEngine.configure(
                    context      = appCtx,
                    adminNumber  = adminNumber,
                    ownerName    = ownerName,
                    agentName    = agentName,
                    wakeLock     = wakeLock,
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
        EmailMonitor.stopPushPolling()
        EmailMonitor.stop()
        ConnectionWatchdog.stop()
        super.onDestroy()
    }
}
