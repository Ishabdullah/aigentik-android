package com.aigentik.app.core

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ConversationTurn v1.0
// Room entity storing one turn of a conversation between Aigentik and a contact.
//
// contactKey: normalized identifier for the contact (phone E.164 for SMS,
//   email address for email). Used to group turns by conversation thread.
// channel: "SMS", "EMAIL", "GVOICE" etc. — allows separate histories per channel
//   if the same person contacts via both SMS and email.
// role: "user" (incoming message) or "assistant" (Aigentik's reply)
// content: message text
// timestamp: epoch millis — used for time-gap detection (new topic) and trimming
//
// Topic-drift handling:
//   MessageEngine loads only turns within HISTORY_WINDOW_MS before building context.
//   If the last turn is older than SESSION_GAP_MS (e.g. 2 hours), the history is
//   treated as a new session and prior context is excluded from the AI prompt.
//   This prevents old unrelated exchanges from confusing the model when a new
//   conversation begins on a different topic.

@Entity(
    tableName = "conversation_history",
    indices = [Index(value = ["contactKey", "channel", "timestamp"])]
)
data class ConversationTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactKey: String,
    val channel: String,     // "SMS", "EMAIL", "GVOICE", "CHAT"
    val role: String,        // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
