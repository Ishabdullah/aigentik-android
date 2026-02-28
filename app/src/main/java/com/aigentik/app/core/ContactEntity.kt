package com.aigentik.app.core

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import org.json.JSONArray

// ContactEntity v1.0
// Room entity replacing JSON-backed ContactEngine storage.
// Lists (phones, emails, aliases) are stored as JSON strings via TypeConverters.
// Migration path: ContactEngine.init() checks for legacy contacts.json on first
// launch and imports it into Room before deleting the old file.

@Entity(tableName = "contacts")
@TypeConverters(StringListConverter::class)
data class ContactEntity(
    @PrimaryKey val id: String,
    val name: String?,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val relationship: String?,
    val type: String = "unknown",
    val notes: String?,
    val instructions: String?,
    val replyBehavior: String = "AUTO", // Stored as enum name string
    val contactCount: Int = 0,
    val source: String = "auto"
)

// TypeConverter for List<String> ↔ JSON string stored in the SQLite column
class StringListConverter {
    @TypeConverter
    fun fromList(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    @TypeConverter
    fun toList(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getString(i) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
