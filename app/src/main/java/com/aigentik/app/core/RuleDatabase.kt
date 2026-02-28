package com.aigentik.app.core

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// RuleDatabase v1.0
// Room database for SMS and email routing rules.
// Replaces sms_rules.json and email_rules.json in RuleEngine < v0.5.
//
// Migration: RuleEngine.init() performs one-time import from JSON files on
// first launch after upgrade. JSON files renamed to *.migrated afterwards.

@Database(entities = [RuleEntity::class], version = 1, exportSchema = false)
abstract class RuleDatabase : RoomDatabase() {

    abstract fun ruleDao(): RuleDao

    companion object {
        private const val DB_NAME = "rule_database"

        @Volatile private var INSTANCE: RuleDatabase? = null

        fun getInstance(context: Context): RuleDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RuleDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration() // Safe: migrated from JSON on init
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
