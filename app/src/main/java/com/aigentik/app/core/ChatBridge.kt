package com.aigentik.app.core

import android.util.Log
import com.aigentik.app.chat.ChatDatabase
import com.aigentik.app.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ChatBridge v1.0 — posts service-layer notifications into Room chat DB
// This is what makes SMS/email notifications appear in the chat history
// AigentikService sets MessageEngine.chatNotifier = { ChatBridge.post(it) }
// ChatActivity observes Room DB — auto-refreshes when new rows appear
object ChatBridge {

    private const val TAG = "ChatBridge"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var db: ChatDatabase? = null

    fun init(database: ChatDatabase) {
        db = database
        Log.i(TAG, "ChatBridge initialized")
    }

    fun post(message: String) {
        val database = db ?: run {
            Log.w(TAG, "ChatBridge not initialized — notification not posted to chat")
            return
        }
        scope.launch {
            try {
                database.chatDao().insert(
                    ChatMessage(
                        role = "assistant",
                        content = message,
                        isStreaming = false
                    )
                )
                Log.d(TAG, "ChatBridge posted: ${message.take(50)}")
            } catch (e: Exception) {
                Log.e(TAG, "ChatBridge post failed: ${e.message}")
            }
        }
    }
}
