package com.aigentik.app.core

// MessageDeduplicator — prevents double processing when message
// arrives via both SMS and RCS notification channels
object MessageDeduplicator {

    // Keep fingerprints for last 500 messages
    private val seen = ArrayDeque<String>(500)

    // Generate fingerprint from sender + body + minute window
    // Using minute window so slight timestamp differences don't break dedup
    fun fingerprint(sender: String, body: String, timestamp: Long): String {
        val minute = timestamp / 60000 // Round to nearest minute
        return "${sender.filter { it.isDigit() }.takeLast(10)}_${body.trim().take(50)}_$minute"
    }

    // Returns true if message is new, false if already seen
    fun isNew(sender: String, body: String, timestamp: Long): Boolean {
        val fp = fingerprint(sender, body, timestamp)
        return if (fp in seen) {
            false
        } else {
            if (seen.size >= 500) seen.removeFirst()
            seen.addLast(fp)
            true
        }
    }

    fun clear() = seen.clear()
}
