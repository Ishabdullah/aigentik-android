package com.aigentik.app.auth

import android.util.Log

// DestructiveActionGuard v1.1
// v1.1: Filter common English words from password candidate tokens.
//   Reduces accidental confirmation risk when the password happens to be a
//   common word (e.g. "yes", "done", "okay"). Common words are excluded from
//   the word-by-word password check; the user's actual code word will still
//   be tried if it isn't in the exclusion list. Admin codes should be unique
//   words or numbers (e.g. "skyfall7", "1984", "xQ9r") — not common nouns.
//
// Intercepts destructive commands and holds them pending password confirmation
//
// Flow:
//   1. Command detected as destructive by AdminAuthManager.isDestructiveCommand()
//   2. Guard stores pending action + channel
//   3. Aigentik replies: "⚠️ Destructive action detected. Reply with your admin password to confirm."
//   4. User replies with password
//   5. If correct → execute stored action → clear pending
//   6. If wrong → reject → clear pending
//
// Pending actions expire after 5 minutes
object DestructiveActionGuard {

    private const val TAG = "DestructiveActionGuard"
    private const val PENDING_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    data class PendingAction(
        val command: String,
        val channelKey: String,
        val timestamp: Long = System.currentTimeMillis(),
        val onConfirmed: suspend () -> String, // returns result message
        val onRejected: () -> String = { "❌ Action cancelled." }
    )

    // One pending action per channel
    private val pendingActions = mutableMapOf<String, PendingAction>()

    // Common English words unlikely to be admin codes.
    // These are excluded from word-by-word password matching to reduce
    // accidental confirmation when a message like "yes please delete" is sent.
    // NOTE: If the user's admin code IS one of these words, they should change it.
    private val COMMON_WORD_EXCLUSIONS = setOf(
        "yes", "no", "ok", "okay", "sure", "please", "done", "just",
        "delete", "confirm", "cancel", "stop", "go", "do", "the",
        "and", "or", "it", "that", "this", "with", "from", "to",
        "move", "trash", "spam", "empty", "all", "mark", "read",
        "my", "your", "their", "our", "its", "me", "him", "her",
        "would", "could", "should", "will", "can", "may", "might"
    )

    // Store a pending destructive action awaiting password confirmation
    fun storePending(channelKey: String, command: String, onConfirmed: suspend () -> String) {
        pendingActions[channelKey] = PendingAction(
            command = command,
            channelKey = channelKey,
            onConfirmed = onConfirmed
        )
        Log.i(TAG, "Pending action stored for $channelKey: ${command.take(60)}")
    }

    // Check if channel has a pending action awaiting confirmation
    fun hasPending(channelKey: String): Boolean {
        val action = pendingActions[channelKey] ?: return false
        val elapsed = System.currentTimeMillis() - action.timestamp
        return if (elapsed < PENDING_TIMEOUT_MS) {
            true
        } else {
            pendingActions.remove(channelKey)
            Log.i(TAG, "Pending action expired for $channelKey")
            false
        }
    }

    // Attempt to confirm pending action with admin code
    // Accepts natural language replies — tries each word as the admin code
    // Supports: "yes delete 1984", "1984", "confirm yes 1984", etc.
    // Returns result message to send back to user
    suspend fun confirmWithPassword(channelKey: String, rawInput: String): String {
        val action = pendingActions[channelKey]
            ?: return "⚠️ No pending action found or it has expired."

        // Try each whitespace-separated token as the admin code.
        // Tokens in the common-word exclusion list are skipped — they are
        // unlikely to be actual admin codes and would increase accidental
        // confirmation risk (e.g. "yes delete" matching password "delete").
        val tokens = rawInput.trim().split(Regex("\\s+"))
        val candidates = tokens.filter { it.lowercase() !in COMMON_WORD_EXCLUSIONS }
        val verified = candidates.any { token -> AdminAuthManager.verifyPassword(token) }

        return if (verified) {
            pendingActions.remove(channelKey)
            Log.i(TAG, "Destructive action confirmed for $channelKey")
            try {
                action.onConfirmed()
            } catch (e: Exception) {
                Log.e(TAG, "Confirmed action failed: ${e.message}")
                "❌ Action failed: ${e.message}"
            }
        } else {
            pendingActions.remove(channelKey)
            Log.w(TAG, "Wrong admin code for pending action on $channelKey")
            "❌ Wrong admin code. Action cancelled for security."
        }
    }

    fun cancelPending(channelKey: String) {
        pendingActions.remove(channelKey)
    }

    // Build the confirmation prompt sent back to user
    fun buildConfirmationPrompt(command: String): String =
        "⚠️ Confirm action:\n\"${command.take(150)}\"\n\n" +
        "Reply with your admin code to confirm (e.g. \"yes delete [code]\"), or anything else to cancel."
}
