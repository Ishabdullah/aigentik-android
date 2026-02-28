package com.aigentik.app.core

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

// ContactDao v1.0
// Room DAO for ContactEntity. All queries are synchronous (called from background
// threads in ContactEngine — callers already use Dispatchers.IO or hold locks).

@Dao
interface ContactDao {

    // Insert a new contact; replace on primary key conflict (e.g. during migration)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(contact: ContactEntity)

    // Insert a list of contacts at once (used during JSON migration)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(contacts: List<ContactEntity>)

    // Update an existing contact record
    @Update
    fun update(contact: ContactEntity)

    // Delete by primary key
    @Query("DELETE FROM contacts WHERE id = :id")
    fun deleteById(id: String)

    // Return all contacts (loaded into memory on init)
    @Query("SELECT * FROM contacts")
    fun getAll(): List<ContactEntity>

    // Count total contacts
    @Query("SELECT COUNT(*) FROM contacts")
    fun count(): Int
}
