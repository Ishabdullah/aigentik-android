package com.aigentik.app.adapters

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.ContactEngine
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator
import com.aigentik.app.core.MessageEngine

// NotificationAdapter v1.0
// Upgrade from v0.3: adds inline reply via RemoteInput PendingIntent
//
// Architecture: NotificationListenerService is the sole outbound SMS/RCS transport
// SEND_SMS is no longer used — we invoke the messaging app's own reply mechanism
// This works for SMS, RCS, and any notification-based messaging app
//
// Reply key lookup table — OEM differences:
//   Samsung Messages (com.samsung.android.messaging) → "reply", "replyText"
//   Google Messages (com.google.android.apps.messaging) → "reply_text", "reply"
//   Fallback: try all keys until one works
//
// Sender resolution:
//   1. Try to extract E.164 phone number from notification title
//   2. If title is a contact name, look up in ContactEngine for actual number
//   3. Store notification key → sender mapping for reply routing
class NotificationAdapter : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationAdapter"

        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )

        // RemoteInput key candidates by package — ordered by likelihood
        private val REPLY_KEYS = mapOf(
            "com.samsung.android.messaging" to listOf("reply", "replyText", "android.intent.extra.text"),
            "com.google.android.apps.messaging" to listOf("reply_text", "reply", "android.intent.extra.text")
        )
        private val FALLBACK_REPLY_KEYS = listOf("reply", "reply_text", "replyText", "android.intent.extra.text")

        private const val KEY_TITLE    = "android.title"
        private const val KEY_TEXT     = "android.text"
        private const val KEY_BIG_TEXT = "android.bigText"
    }

    // Store active notifications so we can find reply action later
    // Map: dedup key → StatusBarNotification
    private val activeNotifications = mutableMapOf<String, StatusBarNotification>()

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

        // Resolve sender — try phone number extraction first, then contact lookup
        val sender = resolveSender(title)
        Log.d(TAG, "Notification from '$title' resolved sender: $sender pkg: ${sbn.packageName}")

        if (!MessageDeduplicator.isNew(sender, text, timestamp)) {
            Log.d(TAG, "Duplicate — already captured via SmsAdapter")
            return
        }

        val dedupKey = MessageDeduplicator.fingerprint(sender, text, timestamp)

        // Store notification for reply use
        activeNotifications[dedupKey] = sbn
        // Cap map size
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

        // Register inline reply handler before forwarding to MessageEngine
        NotificationReplyRouter.register(dedupKey, sbn, sbn.packageName)

        MessageEngine.onMessageReceived(message)
        Log.i(TAG, "RCS/SMS notification → MessageEngine: $dedupKey")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Clean up stored notifications when dismissed
        activeNotifications.entries.removeIf { it.value.key == sbn.key }
    }

    // Resolve sender to phone number or best available identifier
    // Priority: E.164 from title → ContactEngine lookup → raw title
    private fun resolveSender(title: String): String {
        // 1. Try to extract phone number directly from title
        val phoneRegex = Regex("""[\+\d][\d\s\-\(\)]{7,}""")
        val phoneMatch = phoneRegex.find(title)
        if (phoneMatch != null) {
            val digits = phoneMatch.value.filter { it.isDigit() }
            if (digits.length >= 10) return digits.takeLast(10)
        }

        // 2. Title is a contact name — look up in ContactEngine for phone number
        val contact = ContactEngine.findContact(title)
            ?: ContactEngine.findByRelationship(title)
            ?: ContactEngine.findAllByName(title).firstOrNull()

        if (contact != null) {
            val phone = contact.phones.firstOrNull()
            if (phone != null) {
                Log.d(TAG, "Resolved '$title' → ${contact.name} → $phone")
                return phone.filter { it.isDigit() }.takeLast(10)
            }
        }

        // 3. Fallback — use title as-is (MessageEngine will handle lookup)
        Log.w(TAG, "Could not resolve phone for '$title' — using name as sender ID")
        return title
    }
}
