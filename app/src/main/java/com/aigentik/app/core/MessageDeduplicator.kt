package com.aigentik.app.core

// MessageDeduplicator v1.1
// v1.1: TTL-based dedup — timestamp removed from fingerprint to handle
//   SMS broadcast vs notification timing gap (can span a minute boundary).
//   Self-reply prevention via markSent/wasSentRecently: tracks Aigentik's own
//   outgoing text so Samsung's post-reply notification update (which shows our
//   reply as the "latest message") is not processed as a new incoming message.
object MessageDeduplicator {

    private const val INCOMING_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private const val SENT_TTL_MS     = 5 * 60 * 1000L // 5 minutes

    // fingerprint → first-seen timestamp
    private val seen = mutableMapOf<String, Long>()

    // sent reply text (trimmed, first 100 chars) → sent timestamp
    private val sentTexts = mutableMapOf<String, Long>()

    // Fingerprint is sender + body WITHOUT timestamp.
    // Prevents false misses when the same message arrives via SmsAdapter
    // (carrier timestamp) AND NotificationAdapter (sbn.postTime) with
    // timestamps that happen to span a minute boundary.
    fun fingerprint(sender: String, body: String): String {
        val normalizedSender = sender.filter { it.isDigit() }.takeLast(10)
        return "${normalizedSender}_${body.trim().take(50)}"
    }

    // Legacy overload — timestamp ignored, kept for SmsAdapter message ID compat
    @Suppress("UNUSED_PARAMETER")
    fun fingerprint(sender: String, body: String, timestamp: Long): String =
        fingerprint(sender, body)

    // Returns true if this is a new message, false if already seen within TTL
    fun isNew(sender: String, body: String, timestamp: Long): Boolean {
        val fp = fingerprint(sender, body)
        val now = System.currentTimeMillis()

        // Periodic cleanup
        if (seen.size > 200) seen.entries.removeIf { now - it.value > INCOMING_TTL_MS }

        val firstSeen = seen[fp]
        return if (firstSeen != null && now - firstSeen < INCOMING_TTL_MS) {
            false
        } else {
            seen[fp] = now
            true
        }
    }

    // Called by NotificationReplyRouter after a successful inline reply send.
    // Stores the sent reply body so wasSentRecently() can detect Samsung's
    // post-reply notification update (which shows our reply as the latest message).
    fun markSent(body: String) {
        val now = System.currentTimeMillis()
        sentTexts[body.trim().take(100)] = now
        if (sentTexts.size > 100) sentTexts.entries.removeIf { now - it.value > SENT_TTL_MS }
    }

    // Returns true if this text was sent by Aigentik recently.
    // Used by NotificationAdapter to skip self-reply loop.
    fun wasSentRecently(body: String): Boolean {
        val sentTime = sentTexts[body.trim().take(100)] ?: return false
        return System.currentTimeMillis() - sentTime < SENT_TTL_MS
    }

    fun clear() {
        seen.clear()
        sentTexts.clear()
    }
}
