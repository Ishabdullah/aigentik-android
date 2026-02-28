package com.aigentik.app.core

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// ConversationHistoryDao v1.0
// Room DAO for ConversationTurn. All queries are synchronous (callers use
// background threads in MessageEngine).

@Dao
interface ConversationHistoryDao {

    // Insert a new conversation turn
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(turn: ConversationTurn)

    // Retrieve last N turns for a contact+channel pair, newest first
    @Query("""
        SELECT * FROM conversation_history
        WHERE contactKey = :contactKey AND channel = :channel
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getRecent(contactKey: String, channel: String, limit: Int): List<ConversationTurn>

    // Retrieve turns after a specific timestamp (for session continuity check)
    @Query("""
        SELECT * FROM conversation_history
        WHERE contactKey = :contactKey AND channel = :channel
          AND timestamp >= :sinceMs
        ORDER BY timestamp ASC
        LIMIT :limit
    """)
    fun getSince(contactKey: String, channel: String, sinceMs: Long, limit: Int): List<ConversationTurn>

    // Get the timestamp of the most recent turn for a contact+channel
    @Query("""
        SELECT MAX(timestamp) FROM conversation_history
        WHERE contactKey = :contactKey AND channel = :channel
    """)
    fun getLastTimestamp(contactKey: String, channel: String): Long?

    // Trim old turns: keep only the newest N rows for a contact+channel
    // Called after each insert to prevent unbounded growth
    @Query("""
        DELETE FROM conversation_history
        WHERE contactKey = :contactKey AND channel = :channel
          AND id NOT IN (
            SELECT id FROM conversation_history
            WHERE contactKey = :contactKey AND channel = :channel
            ORDER BY timestamp DESC
            LIMIT :keepCount
          )
    """)
    fun trimHistory(contactKey: String, channel: String, keepCount: Int)

    // Delete all history for a contact (e.g. user command "clear history for John")
    @Query("DELETE FROM conversation_history WHERE contactKey = :contactKey")
    fun clearContact(contactKey: String)

    // Delete all history (full reset)
    @Query("DELETE FROM conversation_history")
    fun clearAll()

    // Count turns for a contact+channel
    @Query("SELECT COUNT(*) FROM conversation_history WHERE contactKey = :contactKey AND channel = :channel")
    fun count(contactKey: String, channel: String): Int
}
