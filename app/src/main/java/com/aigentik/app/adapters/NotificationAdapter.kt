package com.aigentik.app.adapters

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator

// NotificationAdapter — intercepts RCS messages via notification system
// Only processes notifications from known messaging apps
// Skips anything already captured by SmsAdapter
class NotificationAdapter : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationAdapter"

        // Only process notifications from these packages
        // NOTE: Add more messaging apps here if needed
        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",  // Google Messages
            "com.samsung.android.messaging"        // Samsung Messages
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Only process known messaging apps
        if (sbn.packageName !in MESSAGING_PACKAGES) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString("android.title") ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: return
        val timestamp = sbn.postTime

        Log.d(TAG, "Messaging notification from $title: ${text.take(50)}")

        // title is usually the sender name or number
        // text is the message body
        val sender = title.filter { it.isDigit() }.takeLast(10).ifEmpty { title }

        // Check deduplication — if SMS adapter already got this, skip it
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

        // NOTE: v0.4 — forward to MessageEngine for processing
        Log.d(TAG, "RCS message ready for processing: ${message.id}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not needed for v0.1
    }
}
