package com.aigentik.app.adapters

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.aigentik.app.core.ChannelManager
import com.aigentik.app.core.Message
import com.aigentik.app.core.MessageDeduplicator
import com.aigentik.app.core.MessageEngine
import com.aigentik.app.core.PhoneNormalizer

// SmsAdapter v1.0 — receives direct SMS via BroadcastReceiver
// NOTE: Receives SMS from the Aigentik phone number (non-Google-Voice number)
// GVoice texts are handled via EmailMonitor (arrive as Gmail forwarded emails)
// This handles SMS to the direct SIM number only
//
// IMPORTANT: Samsung Messages is still the default SMS app
//   Samsung handles storage and UI — we intercept via broadcast
//   We can RECEIVE because BROADCAST_SMS permission is declared
//   We can SEND via SmsManager without being default app
class SmsAdapter : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsAdapter"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Check SMS channel is enabled
        if (!ChannelManager.isEnabled(ChannelManager.Channel.SMS)) {
            Log.i(TAG, "SMS channel disabled — ignoring")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group by sender (multipart SMS)
        val grouped = messages.groupBy { it.originatingAddress ?: "" }

        for ((sender, parts) in grouped) {
            if (sender.isBlank()) continue

            val body = parts.joinToString("") { it.messageBody ?: "" }
            val timestamp = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

            // Normalize to E.164 before any processing or comparison
            val normalizedSender = PhoneNormalizer.toE164(sender)
            Log.i(TAG, "SMS from $sender → normalized: $normalizedSender body: ${body.take(50)}")

            if (!MessageDeduplicator.isNew(normalizedSender, body, timestamp)) {
                Log.d(TAG, "Duplicate SMS — skipping")
                continue
            }

            val message = Message(
                id = MessageDeduplicator.fingerprint(normalizedSender, body, timestamp),
                sender = normalizedSender,
                senderName = null,
                body = body,
                timestamp = timestamp,
                channel = Message.Channel.SMS
            )

            MessageEngine.onMessageReceived(message)
        }
    }
}
