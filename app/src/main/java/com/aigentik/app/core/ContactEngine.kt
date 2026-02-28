package com.aigentik.app.core

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import java.io.File

// ContactEngine v0.5
// v0.5: Migrated from JSON-backed storage to Room/SQLite (ContactDatabase).
//   - Better data integrity: atomic writes via Room transaction, no corruption risk
//   - Better performance at scale: indexed queries vs full JSON parse/write
//   - One-time migration: contacts.json imported into Room on first launch, then
//     renamed to contacts.json.migrated (kept as backup, not re-imported)
//   - In-memory cache (contacts list) still used for fast lookups — same as before
//   - All writes go through Room DAO; in-memory list updated in sync
// v0.4: port of contacts.js + contacts-sync.js — reads Android contacts + maintains
//   Aigentik contact intelligence
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

    // In-memory cache loaded from Room on init — fast lookups, Room for persistence
    private val contacts = mutableListOf<Contact>()
    private var dao: ContactDao? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val db = ContactDatabase.getInstance(context)
        dao = db.contactDao()

        // One-time migration from legacy contacts.json → Room
        migrateFromJsonIfNeeded(context)

        // Load all contacts from Room into in-memory cache
        loadFromRoom()
        syncAndroidContacts(context)
        Log.i(TAG, "ContactEngine ready (Room) — ${contacts.size} contacts")
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
            persistContact(it)
        }
    }

    // Find or create by email address
    fun findOrCreateByEmail(email: String): Contact {
        return findContact(email) ?: Contact(
            id = "contact_${System.currentTimeMillis()}",
            name = null,
            source = "email"
        ).also {
            it.emails.add(email)
            contacts.add(it)
            persistContact(it)
        }
    }

    // Set per-contact reply instructions
    fun setInstructions(identifier: String, instructions: String?, behavior: ReplyBehavior?) {
        val contact = findContact(identifier) ?: findByRelationship(identifier) ?: return
        instructions?.let { contact.instructions = it }
        behavior?.let { contact.replyBehavior = it }
        persistContact(contact)
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
        contact.instructions?.let { lines.add("ℹ️ $it") }
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
                        persistContact(contact)
                        added++
                    } else {
                        var changed = false
                        if (existing.name == null) { existing.name = name; changed = true }
                        if (!existing.aliases.contains(name.lowercase())) {
                            existing.aliases.add(name.lowercase()); changed = true
                        }
                        if (changed) persistContact(existing)
                    }
                }
            }

            Log.i(TAG, "Android contacts synced — $added new contacts added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Android contacts: ${e.message}")
        }
        return added
    }

    fun getCount() = contacts.size

    // ─── Private / internal ───────────────────────────────────────────────────

    // Persist a single contact to Room (insert or update)
    private fun persistContact(contact: Contact) {
        try {
            dao?.insert(contact.toEntity())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist contact ${contact.id}: ${e.message}")
        }
    }

    // Load all contacts from Room into in-memory cache
    private fun loadFromRoom() {
        try {
            val entities = dao?.getAll() ?: emptyList()
            contacts.clear()
            contacts.addAll(entities.map { it.toContact() })
            Log.i(TAG, "Loaded ${contacts.size} contacts from Room")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load contacts from Room: ${e.message}")
        }
    }

    // One-time migration: read contacts.json, insert all into Room, rename file
    private fun migrateFromJsonIfNeeded(context: Context) {
        val jsonFile = File(context.filesDir, "contacts.json")
        if (!jsonFile.exists()) return

        Log.i(TAG, "Legacy contacts.json found — migrating to Room...")
        try {
            val arr = JSONArray(jsonFile.readText())
            val entities = mutableListOf<ContactEntity>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val phones = mutableListOf<String>()
                val emails = mutableListOf<String>()
                val aliases = mutableListOf<String>()
                obj.optJSONArray("phones")?.let  { a -> for (j in 0 until a.length()) phones.add(a.getString(j)) }
                obj.optJSONArray("emails")?.let  { a -> for (j in 0 until a.length()) emails.add(a.getString(j)) }
                obj.optJSONArray("aliases")?.let { a -> for (j in 0 until a.length()) aliases.add(a.getString(j)) }
                entities.add(ContactEntity(
                    id           = obj.getString("id"),
                    name         = obj.optString("name").ifEmpty { null },
                    phones       = phones,
                    emails       = emails,
                    aliases      = aliases,
                    relationship = obj.optString("relationship").ifEmpty { null },
                    type         = obj.optString("type", "unknown"),
                    notes        = obj.optString("notes").ifEmpty { null },
                    instructions = obj.optString("instructions").ifEmpty { null },
                    replyBehavior = obj.optString("replyBehavior", "AUTO"),
                    contactCount = obj.optInt("contactCount", 0),
                    source       = obj.optString("source", "auto")
                ))
            }
            dao?.insertAll(entities)
            // Rename original file so migration won't repeat on next launch
            jsonFile.renameTo(File(context.filesDir, "contacts.json.migrated"))
            Log.i(TAG, "Migration complete — ${entities.size} contacts moved to Room")
        } catch (e: Exception) {
            Log.e(TAG, "JSON migration failed: ${e.message}")
        }
    }
}

// ── Extension helpers: Contact ↔ ContactEntity ──────────────────────────────

fun ContactEngine.Contact.toEntity(): ContactEntity = ContactEntity(
    id           = id,
    name         = name,
    phones       = phones.toList(),
    emails       = emails.toList(),
    aliases      = aliases.toList(),
    relationship = relationship,
    type         = type,
    notes        = notes,
    instructions = instructions,
    replyBehavior = replyBehavior.name,
    contactCount = contactCount,
    source       = source
)

fun ContactEntity.toContact(): ContactEngine.Contact = ContactEngine.Contact(
    id           = id,
    name         = name,
    phones       = phones.toMutableList(),
    emails       = emails.toMutableList(),
    aliases      = aliases.toMutableList(),
    relationship = relationship,
    type         = type,
    notes        = notes,
    instructions = instructions,
    replyBehavior = try {
        ContactEngine.ReplyBehavior.valueOf(replyBehavior)
    } catch (e: Exception) {
        ContactEngine.ReplyBehavior.AUTO
    },
    contactCount = contactCount,
    source       = source
)
