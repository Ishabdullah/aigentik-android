package com.aigentik.app.core

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ContactEngine v0.4 — port of contacts.js + contacts-sync.js
// Reads Android contacts + maintains Aigentik contact intelligence
object ContactEngine {

    private const val TAG = "ContactEngine"

    enum class ReplyBehavior { AUTO, NEVER, ALWAYS, REVIEW }

    data class Contact(
        val id: String,
        var name: String?,
        val phones: MutableList<String> = mutableListOf(),
        val emails: MutableList<String> = mutableListOf(),
        val aliases: MutableList<String> = mutableListOf(),
        var relationship: String? = null,
        var type: String = "unknown",
        var notes: String? = null,
        var instructions: String? = null,
        var replyBehavior: ReplyBehavior = ReplyBehavior.AUTO,
        var contactCount: Int = 0,
        val source: String = "auto"
    )

    private val contacts = mutableListOf<Contact>()
    private lateinit var contactsFile: File

    fun init(context: Context) {
        contactsFile = File(context.filesDir, "contacts.json")
        loadContacts()
        syncAndroidContacts(context)
        Log.i(TAG, "ContactEngine ready — ${contacts.size} contacts loaded")
    }

    // Find contact by phone, email, name or alias
    fun findContact(identifier: String): Contact? {
        val normPhone = identifier.filter { it.isDigit() }.takeLast(10)
        val lower = identifier.lowercase().trim()

        return contacts.find { c ->
            c.phones.any { it.filter { d -> d.isDigit() }.takeLast(10) == normPhone } ||
            c.emails.any { it.lowercase() == lower } ||
            c.name?.lowercase() == lower ||
            c.name?.lowercase()?.contains(lower) == true ||
            c.aliases.any { it.lowercase() == lower } ||
            c.aliases.any { it.lowercase().contains(lower) }
        }
    }

    // Find by relationship label (e.g. "boss", "wife", "mom")
    fun findByRelationship(relationship: String): Contact? {
        val rel = relationship.lowercase().trim()
        return contacts.find { it.relationship?.lowercase() == rel }
    }

    // Find ALL contacts matching a name — for disambiguation
    fun findAllByName(name: String): List<Contact> {
        val lower = name.lowercase().trim()
        return contacts.filter { c ->
            c.name?.lowercase()?.contains(lower) == true ||
            c.aliases.any { it.lowercase().contains(lower) }
        }
    }

    // Find or create by phone number
    fun findOrCreateByPhone(phone: String): Contact {
        return findContact(phone) ?: Contact(
            id = "contact_${System.currentTimeMillis()}",
            name = null,
            source = "sms"
        ).also {
            it.phones.add(phone)
            contacts.add(it)
            saveContacts()
        }
    }

    // Set per-contact reply instructions
    fun setInstructions(identifier: String, instructions: String?, behavior: ReplyBehavior?) {
        val contact = findContact(identifier) ?: findByRelationship(identifier) ?: return
        instructions?.let { contact.instructions = it }
        behavior?.let { contact.replyBehavior = it }
        saveContacts()
        Log.i(TAG, "Instructions set for ${contact.name}: $instructions / $behavior")
    }

    // Format contact info for display
    fun formatContact(contact: Contact): String {
        val lines = mutableListOf<String>()
        contact.name?.let { lines.add("👤 $it") }
        contact.relationship?.let { lines.add("🔗 $it") }
        if (contact.phones.isNotEmpty()) lines.add("📱 ${contact.phones.first()}")
        if (contact.emails.isNotEmpty()) lines.add("✉️ ${contact.emails.first()}")
        contact.notes?.let { lines.add("📝 $it") }
        return lines.joinToString("\n")
    }

    // Sync from Android contacts database
    fun syncAndroidContacts(context: Context): Int {
        var added = 0
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            ) ?: return 0

            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    val normPhone = number.filter { c -> c.isDigit() }.takeLast(10)
                    if (normPhone.length < 7) continue

                    val existing = findContact(normPhone)
                    if (existing == null) {
                        val contact = Contact(
                            id = "contact_${System.currentTimeMillis()}_$added",
                            name = name,
                            source = "android_contacts"
                        )
                        contact.phones.add(number)
                        contact.aliases.add(name.lowercase())
                        contacts.add(contact)
                        added++
                    } else {
                        if (existing.name == null) existing.name = name
                        if (!existing.aliases.contains(name.lowercase())) {
                            existing.aliases.add(name.lowercase())
                        }
                    }
                }
            }

            if (added > 0) saveContacts()
            Log.i(TAG, "Android contacts synced — $added new contacts added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Android contacts: ${e.message}")
        }
        return added
    }

    fun getCount() = contacts.size

    private fun loadContacts() {
        try {
            if (!contactsFile.exists()) return
            val arr = JSONArray(contactsFile.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val contact = Contact(
                    id = obj.getString("id"),
                    name = obj.optString("name").ifEmpty { null },
                    relationship = obj.optString("relationship").ifEmpty { null },
                    type = obj.optString("type", "unknown"),
                    notes = obj.optString("notes").ifEmpty { null },
                    instructions = obj.optString("instructions").ifEmpty { null },
                    replyBehavior = ReplyBehavior.valueOf(
                        obj.optString("replyBehavior", "AUTO")
                    ),
                    contactCount = obj.optInt("contactCount", 0),
                    source = obj.optString("source", "auto")
                )
                val phones = obj.optJSONArray("phones")
                if (phones != null) {
                    for (j in 0 until phones.length()) contact.phones.add(phones.getString(j))
                }
                val emails = obj.optJSONArray("emails")
                if (emails != null) {
                    for (j in 0 until emails.length()) contact.emails.add(emails.getString(j))
                }
                val aliases = obj.optJSONArray("aliases")
                if (aliases != null) {
                    for (j in 0 until aliases.length()) contact.aliases.add(aliases.getString(j))
                }
                contacts.add(contact)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load contacts: ${e.message}")
        }
    }

    private fun saveContacts() {
        try {
            val arr = JSONArray()
            contacts.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name ?: "")
                    put("relationship", c.relationship ?: "")
                    put("type", c.type)
                    put("notes", c.notes ?: "")
                    put("instructions", c.instructions ?: "")
                    put("replyBehavior", c.replyBehavior.name)
                    put("contactCount", c.contactCount)
                    put("source", c.source)
                    put("phones", JSONArray(c.phones))
                    put("emails", JSONArray(c.emails))
                    put("aliases", JSONArray(c.aliases))
                })
            }
            contactsFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Could not save contacts: ${e.message}")
        }
    }
}
