package com.aigentik.app.adapters

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.util.Log
import java.util.Locale

// NotificationReplyRouter v1.1
// Fixes: PendingIntent.send() API — correct overload for Android 13+
// Fixes: dual keying by sbn.key (OS key) AND messageId (semantic key)
// Adds: E.164 normalization via PhoneNumberUtils — no more .takeLast(10) truncation
// Adds: action title logging for OEM debugging
// Architecture: sole outbound transport for NOTIFICATION channel messages
// We invoke the messaging app's own reply PendingIntent — never touch SmsManager for RCS
object NotificationReplyRouter {

    private const val TAG = "NotificationReplyRouter"

    // Dual map — semantic messageId → OS sbn.key, OS sbn.key → ReplyEntry
    // Semantic key used by MessageEngine, OS key used for staleness detection
    private val messageIdToSbnKey = mutableMapOf<String, String>()
    private val sbnKeyToEntry     = mutableMapOf<String, ReplyEntry>()

    data class ReplyEntry(
        val notification: Notification,
        val packageName: String,
        val sbnKey: String
    )

    // Package-specific RemoteInput result key candidates — ordered by likelihood
    // NOTE: Log action titles in testing to confirm OEM differences
    private val REPLY_KEYS = mapOf(
        "com.samsung.android.messaging"          to listOf("KEY_DIRECT_REPLY", "reply", "replyText", "android.intent.extra.text"),
        "com.google.android.apps.messaging"      to listOf("reply_text", "reply", "android.intent.extra.text")
    )
    private val FALLBACK_KEYS = listOf("reply", "reply_text", "replyText", "android.intent.extra.text")

    // Application context stored for PendingIntent.send() calls
    // Set by NotificationAdapter.onNotificationPosted() — never null when reply is needed
    var appContext: android.content.Context? = null

    fun register(messageId: String, notification: Notification, packageName: String, sbnKey: String) {
        val entry = ReplyEntry(notification, packageName, sbnKey)
        messageIdToSbnKey[messageId] = sbnKey
        sbnKeyToEntry[sbnKey] = entry

        // Cap map sizes — prevent memory leak
        if (sbnKeyToEntry.size > 100) {
            val oldest = sbnKeyToEntry.keys.first()
            sbnKeyToEntry.remove(oldest)
            messageIdToSbnKey.entries.removeIf { it.value == oldest }
        }
        Log.d(TAG, "Registered reply entry for messageId=$messageId sbnKey=$sbnKey pkg=$packageName")
    }

    // Called when OS removes a notification — evict stale entries immediately
    fun onNotificationRemoved(sbnKey: String) {
        sbnKeyToEntry.remove(sbnKey)
        messageIdToSbnKey.entries.removeIf { it.value == sbnKey }
        Log.d(TAG, "Evicted stale entry for sbnKey=$sbnKey")
    }

    // Main send path — called by MessageEngine for NOTIFICATION channel replies
    fun sendReply(messageId: String, replyText: String): Boolean {
        val sbnKey = messageIdToSbnKey[messageId] ?: run {
            Log.w(TAG, "No sbnKey for messageId=$messageId — notification may have been dismissed")
            return false
        }
        val entry = sbnKeyToEntry[sbnKey] ?: run {
            Log.w(TAG, "No entry for sbnKey=$sbnKey — notification evicted")
            return false
        }

        val action = findReplyAction(entry.notification, entry.packageName) ?: run {
            Log.w(TAG, "No reply action found in notification from ${entry.packageName}")
            return false
        }

        val remoteInput = findRemoteInput(action, entry.packageName) ?: run {
            Log.w(TAG, "No RemoteInput found — pkg=${entry.packageName}")
            return false
        }

        return try {
            // Build reply Intent with RemoteInput results bundle
            val replyIntent = Intent()
            val results = Bundle()
            results.putString(remoteInput.resultKey, replyText)
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, results)

            // Correct PendingIntent.send() overload for Android 13+ (API 33)
            // send(Context?, Int, Intent?) is the right signature here
            val ctx = appContext
            if (ctx != null) {
                action.actionIntent.send(ctx, 0, replyIntent)
            } else {
                // Fallback — try without context (may fail on some Android versions)
                action.actionIntent.send(0, null, null, null, null)
            }

            Log.i(TAG, "Inline reply sent pkg=${entry.packageName} key=${remoteInput.resultKey} text=${replyText.take(60)}")
            // Clean up after successful send
            sbnKeyToEntry.remove(sbnKey)
            messageIdToSbnKey.remove(messageId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Inline reply PendingIntent.send() failed: ${e.message}")
            false
        }
    }

    // Find the reply action — look for action with RemoteInput attached
    // Log all action titles for OEM debugging during testing
    private fun findReplyAction(notification: Notification, packageName: String): Notification.Action? {
        val actions = notification.actions ?: run {
            Log.w(TAG, "Notification has no actions — pkg=$packageName")
            return null
        }

        // Log all action titles so we can debug OEM differences in testing
        actions.forEachIndexed { i, action ->
            val hasRemoteInput = action.remoteInputs?.isNotEmpty() == true
            Log.d(TAG, "Action[$i] title='${action.title}' hasRemoteInput=$hasRemoteInput pkg=$packageName")
        }

        return actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
    }

    // Find the correct RemoteInput using package-specific key lookup table
    private fun findRemoteInput(action: Notification.Action, packageName: String): RemoteInput? {
        val inputs = action.remoteInputs ?: return null
        val keysToTry = REPLY_KEYS[packageName] ?: FALLBACK_KEYS

        for (key in keysToTry) {
            val match = inputs.firstOrNull { it.resultKey == key }
            if (match != null) {
                Log.d(TAG, "Matched RemoteInput key='$key' for pkg=$packageName")
                return match
            }
        }

        // Last resort — use first available RemoteInput and log it for lookup table updates
        val fallback = inputs.firstOrNull()
        if (fallback != null) {
            Log.w(TAG, "No key match for pkg=$packageName — using fallback key='${fallback.resultKey}' — add to REPLY_KEYS table")
        }
        return fallback
    }

    // E.164 normalization — wraps PhoneNumberUtils for safe international number handling
    // ChatGPT correct: .takeLast(10) is fragile for international/country-code contacts
    fun normalizeToE164(number: String, defaultRegion: String = "US"): String {
        if (number.startsWith("+")) return number // already E.164
        val digits = number.filter { it.isDigit() }
        // Use PhoneNumberUtils.formatNumberToE164 if available
        val formatted = PhoneNumberUtils.formatNumberToE164(digits, defaultRegion)
        return formatted ?: when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> {
                Log.w(TAG, "Cannot normalize '$number' to E.164 — using as-is")
                number
            }
        }
    }
}
