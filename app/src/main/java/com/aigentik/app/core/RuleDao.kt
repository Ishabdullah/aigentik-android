package com.aigentik.app.core

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

// RuleDao v1.0
// Room DAO for RuleEntity. All queries are synchronous (called from RuleEngine
// which already runs on background threads).

@Dao
interface RuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rule: RuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rules: List<RuleEntity>)

    @Update
    fun update(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    fun deleteById(id: String)

    @Query("DELETE FROM rules WHERE ruleType = :ruleType AND (id = :identifier OR LOWER(description) LIKE '%' || LOWER(:identifier) || '%')")
    fun deleteByIdentifier(ruleType: String, identifier: String)

    // Load all SMS rules ordered by insertion order (newest first = lowest rowid DESC actually,
    // but we use id as timestamp-based so order by id DESC gives newest first)
    @Query("SELECT * FROM rules WHERE ruleType = 'sms' ORDER BY rowid ASC")
    fun getAllSmsRules(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE ruleType = 'email' ORDER BY rowid ASC")
    fun getAllEmailRules(): List<RuleEntity>

    // Increment matchCount for a rule
    @Query("UPDATE rules SET matchCount = matchCount + 1 WHERE id = :id")
    fun incrementMatchCount(id: String)
}
