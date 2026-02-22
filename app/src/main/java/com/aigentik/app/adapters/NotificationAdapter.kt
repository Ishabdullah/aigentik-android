package com.aigentik.app.adapters

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator
import com.aigentik.app.core.MessageEngine

// NotificationAdapter v0.3 — fully wired to MessageEngine
// Only processes Google Messages and Samsung Messages
// Skips anything already captured by SmsAdapter
class NotificationAdapter : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationAdapter"

        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )

        // Notification keys for extracting message data
        private const val KEY_TITLE = "android.title"
        private const val KEY_TEXT = "android.text"
        private const val KEY_BIG_TEXT = "android.bigText"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Only handle known messaging apps
        if (sbn.packageName !in MESSAGING_PACKAGES) return

        // Skip group summary notifications — they don't contain message content
        val isGroupSummary = sbn.notification.flags and
            android.app.Notification.FLAG_GROUP_SUMMARY != 0
        if (isGroupSummary) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(KEY_TITLE) ?: return

        // Prefer bigText (full message) over text (possibly truncated)
        val text = extras.getCharSequence(KEY_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(KEY_TEXT)?.toString()
            ?: return

        val timestamp = sbn.postTime

        Log.d(TAG, "Notification from $title in ${sbn.packageName}: ${text.take(50)}")

        // Extract phone number from title if possible
        // Samsung Messages puts number in title, Google Messages puts contact name
        val phoneRegex = Regex("""[\+\d][\d\s\-\(\)]{7,}""")
        val phoneMatch = phoneRegex.find(title)
        val sender = phoneMatch?.value?.filter { it.isDigit() }?.takeLast(10)
            ?: title.filter { it.isDigit() }.takeLast(10).ifEmpty { title }

        // Check deduplication — skip if SMS adapter already processed this
        if (!MessageDeduplicator.isNew(sender, text, timestamp)) {
            Log.d(TAG, "Already captured via SMS — skipping notification")
            return
        }

        val message = Message(
            id = MessageDeduplicator.fingerprint(sender, text, timestamp),
            sender = sender,
            senderName = title,
            body = text,
            timestamp = timestamp,
            channel = Message.Channel.NOTIFICATION
        )

        // Forward to MessageEngine
        MessageEngine.onMessageReceived(message)
        Log.d(TAG, "RCS message forwarded to MessageEngine: ${message.id}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not needed
    }
}
