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
import com.aigentik.app.core.PhoneNormalizer
import com.aigentik.app.email.EmailMonitor

// NotificationAdapter v1.3
// v1.3: Gmail notifications routed to EmailMonitor (not SMS path)
// v1.2: ALWAYS register with NotificationReplyRouter even if duplicate
//   SmsAdapter captures first → marks seen → notification fires → was being skipped
//   Now: notification always registers for inline reply, only skips MessageEngine
//   PhoneNormalizer used for consistent E.164 before passing to MessageEngine
class NotificationAdapter : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationAdapter"

        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )

        private const val GMAIL_PACKAGE = "com.google.android.gm"

        private const val KEY_TITLE    = "android.title"
        private const val KEY_TEXT     = "android.text"
        private const val KEY_BIG_TEXT = "android.bigText"
    }

    private val activeNotifications = mutableMapOf<String, StatusBarNotification>()

    // Set context IMMEDIATELY when service binds — before any notification arrives
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationReplyRouter.appContext = applicationContext
        Log.i(TAG, "NotificationListenerService connected — appContext set for PendingIntent")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationReplyRouter.appContext = null
        Log.w(TAG, "NotificationListenerService disconnected — appContext cleared")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Gmail notification → route to EmailMonitor (OAuth2 REST fetch)
        // NOT the SMS path — email sender names are not phone numbers
        if (sbn.packageName == GMAIL_PACKAGE) {
            val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            if (isGroupSummary) return
            Log.i(TAG, "Gmail notification detected — triggering EmailMonitor")
            EmailMonitor.onGmailNotification(applicationContext)
            return
        }

        // SMS/RCS messaging apps only
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
        val sender = resolveSender(title)

        Log.d(TAG, "Notification from '$title' resolved sender: $sender pkg: ${sbn.packageName}")

        val dedupKey = MessageDeduplicator.fingerprint(sender, text, timestamp)

        // ALWAYS register with NotificationReplyRouter — even if duplicate
        // SmsAdapter may have already sent to MessageEngine but the inline reply
        // path needs the notification registered to fire Samsung/Google Messages reply
        activeNotifications[dedupKey] = sbn
        if (activeNotifications.size > 50) {
            activeNotifications.remove(activeNotifications.keys.first())
        }
        NotificationReplyRouter.register(dedupKey, sbn.notification, sbn.packageName, sbn.key)

        // Only send to MessageEngine if not already processed by SmsAdapter
        val isNew = MessageDeduplicator.isNew(sender, text, timestamp)
        if (!isNew) {
            Log.d(TAG, "Already processed by SmsAdapter — router registered, skipping MessageEngine")
            return
        }

        val message = Message(
            id = dedupKey,
            sender = sender,
            senderName = title,
            body = text,
            timestamp = timestamp,
            channel = Message.Channel.NOTIFICATION
        )

        MessageEngine.onMessageReceived(message)
        Log.i(TAG, "RCS/SMS notification → MessageEngine: $dedupKey")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeNotifications.entries.removeIf { it.value.key == sbn.key }
        NotificationReplyRouter.onNotificationRemoved(sbn.key)
    }

    // Resolve notification title to E.164 phone number
    private fun resolveSender(title: String): String {
        // 1. Title looks like a phone number — normalize to E.164
        if (PhoneNormalizer.looksLikePhoneNumber(title)) {
            return PhoneNormalizer.toE164(title)
        }

        // 2. Try regex extraction for formatted numbers like "+1 860-661-8466"
        val phoneRegex = Regex("""[\+\d][\d\s\-\(\)\.]{7,}""")
        val phoneMatch = phoneRegex.find(title)
        if (phoneMatch != null) {
            val candidate = phoneMatch.value
            if (PhoneNormalizer.looksLikePhoneNumber(candidate)) {
                return PhoneNormalizer.toE164(candidate)
            }
        }

        // 3. Contact name lookup → return stored E.164
        val contact = ContactEngine.findContact(title)
            ?: ContactEngine.findByRelationship(title)
            ?: ContactEngine.findAllByName(title).firstOrNull()

        if (contact != null) {
            val phone = contact.phones.firstOrNull()
            if (phone != null) {
                val normalized = PhoneNormalizer.toE164(phone)
                Log.d(TAG, "Resolved '$title' → ${contact.name} → $normalized")
                return normalized
            }
        }

        // 4. Fallback
        Log.w(TAG, "Could not resolve phone for '$title' — using name as sender ID")
        return title
    }
}
