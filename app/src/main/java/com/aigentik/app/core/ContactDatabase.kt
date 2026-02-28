package com.aigentik.app.core

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// ContactDatabase v1.0
// Room database for contact intelligence data.
// Replaces contacts.json (JSON-backed storage in ContactEngine < v0.5).
//
// Migration note: ContactEngine.init() performs a one-time migration from
// contacts.json → Room on first launch after upgrade. The JSON file is renamed
// to contacts.json.migrated after successful import so it won't be re-imported.

@Database(entities = [ContactEntity::class], version = 1, exportSchema = false)
@TypeConverters(StringListConverter::class)
abstract class ContactDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao

    companion object {
        private const val DB_NAME = "contact_database"

        @Volatile private var INSTANCE: ContactDatabase? = null

        fun getInstance(context: Context): ContactDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ContactDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration() // Safe: migrated from JSON on init
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
