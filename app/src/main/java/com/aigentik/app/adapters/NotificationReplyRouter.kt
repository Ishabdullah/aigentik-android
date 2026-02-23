package com.aigentik.app.adapters

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// NotificationReplyRouter v1.0
// Sends replies via the messaging app's own inline reply PendingIntent
// This is the correct architecture for RCS + SMS without being default SMS app
//
// How inline reply works:
//   1. Messaging app posts notification with a Notification.Action that has RemoteInput
//   2. We find that action, build a reply Intent with RemoteInput results bundle
//   3. We fire the PendingIntent — the messaging app receives it and sends the message
//   4. The messaging app handles network, encryption, RCS transport, delivery receipts
//
// NOTE: We never touch SmsManager for NOTIFICATION channel messages
// SmsManager is only used for direct admin-initiated sends where we have a phone number
object NotificationReplyRouter {

    private const val TAG = "NotificationReplyRouter"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Map: messageId → (StatusBarNotification, packageName)
    private val pendingReplies = mutableMapOf<String, Pair<StatusBarNotification, String>>()

    // Reply key candidates per package
    private val REPLY_KEYS = mapOf(
        "com.samsung.android.messaging" to listOf("reply", "replyText", "android.intent.extra.text"),
        "com.google.android.apps.messaging" to listOf("reply_text", "reply", "android.intent.extra.text")
    )
    private val FALLBACK_KEYS = listOf("reply", "reply_text", "replyText", "android.intent.extra.text")

    fun register(messageId: String, sbn: StatusBarNotification, packageName: String) {
        pendingReplies[messageId] = Pair(sbn, packageName)
        if (pendingReplies.size > 100) {
            pendingReplies.remove(pendingReplies.keys.first())
        }
    }

    // Called by MessageEngine when a reply needs to be sent for a NOTIFICATION channel message
    fun sendReply(messageId: String, replyText: String): Boolean {
        val (sbn, packageName) = pendingReplies[messageId] ?: run {
            Log.w(TAG, "No pending notification for messageId: $messageId")
            return false
        }

        val action = findReplyAction(sbn.notification) ?: run {
            Log.w(TAG, "No reply action found in notification from $packageName")
            return false
        }

        val remoteInput = findRemoteInput(action, packageName) ?: run {
            Log.w(TAG, "No RemoteInput found in reply action from $packageName")
            return false
        }

        return try {
            val replyIntent = Intent()
            val results = Bundle()
            results.putString(remoteInput.resultKey, replyText)
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, results)

            action.actionIntent.send(null, replyIntent, null)

            Log.i(TAG, "Inline reply sent via ${packageName}: ${replyText.take(50)}")
            pendingReplies.remove(messageId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Inline reply failed: ${e.message}")
            false
        }
    }

    // Find the reply action in a notification — look for action with RemoteInput
    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        return actions.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
    }

    // Find the correct RemoteInput using package-specific key lookup
    private fun findRemoteInput(action: Notification.Action, packageName: String): RemoteInput? {
        val inputs = action.remoteInputs ?: return null
        val keysToTry = REPLY_KEYS[packageName] ?: FALLBACK_KEYS

        // Try package-specific keys first
        for (key in keysToTry) {
            val match = inputs.firstOrNull { it.resultKey == key }
            if (match != null) {
                Log.d(TAG, "Found RemoteInput key '$key' for $packageName")
                return match
            }
        }

        // Last resort — just use the first available RemoteInput
        val fallback = inputs.firstOrNull()
        if (fallback != null) {
            Log.w(TAG, "Using fallback RemoteInput key '${fallback.resultKey}' for $packageName")
        }
        return fallback
    }
}
