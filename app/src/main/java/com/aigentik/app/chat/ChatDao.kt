package com.aigentik.app.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ChatDao v0.9.4 — database access for chat messages
// v0.9.4: getAllMessages() capped at 200 most-recent rows (code-audit-2026-03-10 Bug 5).
//   Previously SELECT * with no LIMIT — every Room insert triggered a full-table scan and
//   re-render of ALL messages on the Main thread (removeAllViews + re-inflate). With many
//   email auto-reply notifications accumulated in chat, this becomes O(N) work per message,
//   eventually causing ANR. The subquery pattern (newest 200, then re-sort ASC) returns
//   the most recent 200 in chronological order for display.
@Dao
interface ChatDao {

    // Flow emits new list whenever table changes — auto-updates UI.
    // Capped at 200 most-recent rows to prevent O(N) main-thread re-render with large history.
    @Query("SELECT * FROM (SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT 200) ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int = 50): List<ChatMessage>

    @Insert
    suspend fun insert(message: ChatMessage): Long

    @Update
    suspend fun update(message: ChatMessage)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun getCount(): Int
}
