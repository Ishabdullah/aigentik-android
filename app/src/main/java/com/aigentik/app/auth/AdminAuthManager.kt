package com.aigentik.app.auth

import android.util.Log
import com.aigentik.app.core.AigentikSettings
import java.security.MessageDigest

// AdminAuthManager v1.0
// Handles admin authentication for remote command execution
//
// Admin login format (SMS, GVoice email, Gmail):
//   Admin: Ish
//   Password: yourpassword
//   <command on next line(s)>
//
// Chat screen in app: always trusted, no login needed
//
// Session management:
//   - Remote session expires after 30 min of inactivity
//   - Each command resets the timer
//   - Destructive actions always require password regardless of session
//
// Password storage:
//   - SHA-256 hashed in AigentikSettings (EncryptedSharedPreferences)
//   - Never stored in plaintext
object AdminAuthManager {

    private const val TAG = "AdminAuthManager"
    private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes

    // Active remote sessions — key = channel identifier (phone/email)
    private val activeSessions = mutableMapOf<String, Long>()

    // Destructive action keywords — always require password confirmation
    private val DESTRUCTIVE_KEYWORDS = listOf(
        "delete", "remove", "erase", "wipe", "clear",
        "spam", "unsubscribe", "bulk", "all emails", "all contacts",
        "send to all", "mass text", "broadcast"
    )

    // Parse admin credentials from message body
    // Returns Pair(username, password) if found, null otherwise
    data class AdminCredentials(val username: String, val password: String, val command: String)

    fun parseAdminMessage(body: String): AdminCredentials? {
        val lines = body.trim().lines().map { it.trim() }

        // Look for Admin: and Password: lines (case-insensitive)
        val adminLine = lines.firstOrNull {
            it.lowercase().startsWith("admin:") || it.lowercase().startsWith("admin :")
        }
        val passwordLine = lines.firstOrNull {
            it.lowercase().startsWith("password:") || it.lowercase().startsWith("password :")
        }

        if (adminLine == null || passwordLine == null) return null

        val username = adminLine.substringAfter(":").trim()
        val password = passwordLine.substringAfter(":").trim()

        // Command is everything after the Admin/Password lines
        val adminLineIdx = lines.indexOf(adminLine)
        val passwordLineIdx = lines.indexOf(passwordLine)
        val commandStartIdx = maxOf(adminLineIdx, passwordLineIdx) + 1
        val command = lines.drop(commandStartIdx).joinToString("\n").trim()

        if (username.isBlank() || password.isBlank()) return null

        return AdminCredentials(username, password, command)
    }

    // Authenticate admin credentials
    // Returns true if username matches ownerName and password hash matches
    fun authenticate(credentials: AdminCredentials, channelKey: String): Boolean {
        val ownerName = AigentikSettings.ownerName
        val storedHash = AigentikSettings.adminPasswordHash

        if (storedHash.isBlank()) {
            Log.w(TAG, "No admin password set — remote admin disabled")
            return false
        }

        val nameMatch = credentials.username.trim().equals(ownerName.trim(), ignoreCase = true)
        val passwordHash = hashPassword(credentials.password)
        val passwordMatch = passwordHash == storedHash

        return if (nameMatch && passwordMatch) {
            // Start/refresh session
            activeSessions[channelKey] = System.currentTimeMillis()
            Log.i(TAG, "Admin authenticated on channel: $channelKey")
            true
        } else {
            Log.w(TAG, "Admin auth failed — name=$nameMatch password=$passwordMatch")
            false
        }
    }

    // Check if channel has an active session
    fun hasActiveSession(channelKey: String): Boolean {
        val lastAuth = activeSessions[channelKey] ?: return false
        val elapsed = System.currentTimeMillis() - lastAuth
        return if (elapsed < SESSION_TIMEOUT_MS) {
            activeSessions[channelKey] = System.currentTimeMillis() // refresh
            true
        } else {
            activeSessions.remove(channelKey)
            Log.i(TAG, "Session expired for: $channelKey")
            false
        }
    }

    // Check if command is destructive and needs confirmation
    fun isDestructiveCommand(command: String): Boolean {
        val lower = command.lowercase()
        return DESTRUCTIVE_KEYWORDS.any { lower.contains(it) }
    }

    // Verify password for destructive action confirmation
    fun verifyPassword(password: String): Boolean {
        val storedHash = AigentikSettings.adminPasswordHash
        if (storedHash.isBlank()) return false
        return hashPassword(password) == storedHash
    }

    // SHA-256 hash of password
    fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun clearSession(channelKey: String) {
        activeSessions.remove(channelKey)
    }

    fun clearAllSessions() {
        activeSessions.clear()
        Log.i(TAG, "All admin sessions cleared")
    }
}
