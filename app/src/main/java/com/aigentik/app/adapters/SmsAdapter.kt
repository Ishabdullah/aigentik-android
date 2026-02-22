package com.aigentik.app.adapters

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator

// SmsAdapter — receives traditional SMS messages
// Converts to unified Message object
// Marks fingerprint so NotificationAdapter skips duplicates
class SmsAdapter : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsAdapter"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Combine multi-part SMS into single message
        val sender = messages[0].displayOriginatingAddress
        val body = messages.joinToString("") { it.messageBody }
        val timestamp = messages[0].timestampMillis

        Log.d(TAG, "SMS received from $sender: ${body.take(50)}")

        // Check deduplication — register fingerprint
        if (!MessageDeduplicator.isNew(sender, body, timestamp)) {
            Log.d(TAG, "Duplicate SMS detected — skipping")
            return
        }

        // Build unified message
        val message = Message(
            id = MessageDeduplicator.fingerprint(sender, body, timestamp),
            sender = sender,
            senderName = null, // Resolved later by ContactEngine
            body = body,
            timestamp = timestamp,
            channel = Message.Channel.SMS
        )

        // NOTE: v0.3 — forward to MessageEngine for processing
        Log.d(TAG, "SMS message ready for processing: ${message.id}")
    }
}
