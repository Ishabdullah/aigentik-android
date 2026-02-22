package com.aigentik.app.adapters

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator
import com.aigentik.app.core.MessageEngine

// SmsAdapter v0.3 — fully wired to MessageEngine
class SmsAdapter : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsAdapter"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody }
        val timestamp = messages[0].timestampMillis

        Log.d(TAG, "SMS from $sender: ${body.take(50)}")

        // Deduplication — register fingerprint first
        // NotificationAdapter will skip if it sees same fingerprint
        if (!MessageDeduplicator.isNew(sender, body, timestamp)) {
            Log.d(TAG, "Duplicate SMS — skipping")
            return
        }

        val message = Message(
            id = MessageDeduplicator.fingerprint(sender, body, timestamp),
            sender = sender,
            senderName = null,
            body = body,
            timestamp = timestamp,
            channel = Message.Channel.SMS
        )

        // Forward to MessageEngine
        MessageEngine.onMessageReceived(message)
        Log.d(TAG, "SMS forwarded to MessageEngine: ${message.id}")
    }
}
