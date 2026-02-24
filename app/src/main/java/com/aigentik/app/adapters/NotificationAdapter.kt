package com.aigentik.app.adapters

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.ContactEngine
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator
import com.aigentik.app.core.MessageEngine

// NotificationAdapter v1.1
// Fix from v1.0:
//   - onListenerConnected() sets NotificationReplyRouter.appContext immediately on bind
//     This guarantees context is available before any notification arrives
//     Previously context was set in onNotificationPosted() which is TOO LATE —
//     the reply fires before the next notification posts
//   - resolveSender() returns full phone from ContactEngine (not takeLast(10))
//     Prevents "+1" truncation when contact name resolves to stored number
//   - "Google Voice" and "Ish" contact names will resolve correctly after rename
//
// Contact naming convention (for clarity in logs):
//   "Google Voice" = Aigentik's number (used as agent identity)
//   "Ish"          = Admin/owner number (your personal carrier number)
class NotificationAdapter : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationAdapter"

        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )

        private val REPLY_KEYS = mapOf(
            "com.samsung.android.messaging"       to listOf("KEY_DIRECT_REPLY", "reply", "replyText", "android.intent.extra.text"),
            "com.google.android.apps.messaging"   to listOf("reply_text", "reply", "android.intent.extra.text")
        )
        private val FALLBACK_REPLY_KEYS = listOf(
            "KEY_DIRECT_REPLY", "reply", "reply_text", "replyText", "android.intent.extra.text"
        )

        private const val KEY_TITLE    = "android.title"
        private const val KEY_TEXT     = "android.text"
        private const val KEY_BIG_TEXT = "android.bigText"
    }

    private val activeNotifications = mutableMapOf<String, StatusBarNotification>()

    // Called when Android binds our NotificationListenerService
    // This is the CORRECT place to set appContext — guaranteed before any notification
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationReplyRouter.appContext = applicationContext
        Log.i(TAG, "NotificationListenerService connected — appContext set for PendingIntent")
    }

    // Called if Android unbinds the service (rare — usually only on permission revoke)
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationReplyRouter.appContext = null
        Log.w(TAG, "NotificationListenerService disconnected — appContext cleared")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in MESSAGING_PACKAGES) return

        val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (isGroupSummary) return

        if (!ChannelManager.isEnabled(ChannelManager.Channel.SMS)) {
            Log.d(TAG, "SMS channel disabled — skipping notification")
            return
        }

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(KEY_TITLE) ?: return
        val text = extras.getCharSequence(KEY_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(KEY_TEXT)?.toString()
            ?: return

        val timestamp = sbn.postTime

        // Resolve sender to phone number
        val sender = resolveSender(title)
        Log.d(TAG, "Notification from '$title' resolved sender: $sender pkg: ${sbn.packageName}")

        if (!MessageDeduplicator.isNew(sender, text, timestamp)) {
            Log.d(TAG, "Duplicate — already captured via SmsAdapter")
            return
        }

        val dedupKey = MessageDeduplicator.fingerprint(sender, text, timestamp)

        activeNotifications[dedupKey] = sbn
        if (activeNotifications.size > 50) {
            activeNotifications.remove(activeNotifications.keys.first())
        }

        val message = Message(
            id = dedupKey,
            sender = sender,
            senderName = title,
            body = text,
            timestamp = timestamp,
            channel = Message.Channel.NOTIFICATION
        )

        // Register with router — both semantic key (for reply lookup) and sbn.key (for eviction)
        NotificationReplyRouter.register(dedupKey, sbn.notification, sbn.packageName, sbn.key)

        MessageEngine.onMessageReceived(message)
        Log.i(TAG, "RCS/SMS notification → MessageEngine: $dedupKey")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeNotifications.entries.removeIf { it.value.key == sbn.key }
        NotificationReplyRouter.onNotificationRemoved(sbn.key)
    }

    // Resolve notification title to phone number
    // Priority:
    //   1. Direct E.164 / digit extraction from title (title IS a phone number)
    //   2. ContactEngine lookup by name → return stored phone as-is (full E.164)
    //   3. Fallback: return title unchanged (MessageEngine handles it)
    //
    // NOTE: After contact rename:
    //   "Google Voice" → ContactEngine finds stored GV number → returns full E.164
    //   "Ish"          → ContactEngine finds stored carrier number → returns full E.164
    private fun resolveSender(title: String): String {
        // 1. Title looks like a phone number — extract digits
        val phoneRegex = Regex("""[\+\d][\d\s\-\(\)]{7,}""")
        val phoneMatch = phoneRegex.find(title)
        if (phoneMatch != null) {
            val digits = phoneMatch.value.filter { it.isDigit() }
            if (digits.length >= 10) {
                // Return full E.164 — SmsRouter will normalize
                return if (digits.length == 10) "+1$digits" else "+$digits"
            }
        }

        // 2. Title is a contact name — look up stored phone number
        val contact = ContactEngine.findContact(title)
            ?: ContactEngine.findByRelationship(title)
            ?: ContactEngine.findAllByName(title).firstOrNull()

        if (contact != null) {
            // Return the stored phone number exactly as stored
            // ContactEngine stores E.164 from Android contacts
            val phone = contact.phones.firstOrNull()
            if (phone != null) {
                Log.d(TAG, "Resolved '$title' → ${contact.name} → $phone")
                return phone
            }
        }

        // 3. Fallback
        Log.w(TAG, "Could not resolve phone for '$title' — using name as sender ID")
        return title
    }
}
