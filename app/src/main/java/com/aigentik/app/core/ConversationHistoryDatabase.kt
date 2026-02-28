package com.aigentik.app.core

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// ConversationHistoryDatabase v1.0
// Persistent per-contact conversation history for SMS and email channels.
// Allows Aigentik to maintain context across multi-turn exchanges so it can
// handle follow-up questions naturally.
//
// History management:
//   - Max HISTORY_KEEP_COUNT turns per contact+channel (older turns trimmed)
//   - SESSION_GAP_MS: if last turn was >2 hours ago, treat as new session
//     (prevents old topic context from polluting new conversation)
//   - History used only for public message replies (SMS, Email)
//   - Admin/command exchanges NOT stored here (privacy/security boundary)

@Database(entities = [ConversationTurn::class], version = 1, exportSchema = false)
abstract class ConversationHistoryDatabase : RoomDatabase() {

    abstract fun historyDao(): ConversationHistoryDao

    companion object {
        private const val DB_NAME = "conversation_history_database"

        // Keep last 20 turns per contact+channel (10 exchanges)
        const val HISTORY_KEEP_COUNT = 20

        // If last exchange was >2 hours ago, start fresh (topic drift prevention)
        const val SESSION_GAP_MS = 2 * 60 * 60 * 1000L // 2 hours

        // When building AI context, use at most the last 6 turns (3 exchanges)
        // to keep the prompt concise and focused on the current topic
        const val CONTEXT_WINDOW_TURNS = 6

        @Volatile private var INSTANCE: ConversationHistoryDatabase? = null

        fun getInstance(context: Context): ConversationHistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ConversationHistoryDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
