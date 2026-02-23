package com.aigentik.app.chat

import androidx.room.Entity
import androidx.room.PrimaryKey

// ChatMessage v0.9.3 — Room entity for persistent chat history
// role: "user" or "assistant"
// isStreaming: true while AI is still generating (shown with cursor)
// thinkingText: internal reasoning shown during generation
@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val role: String,           // "user" or "assistant"
    val content: String,        // message text
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val thinkingText: String = ""
)
