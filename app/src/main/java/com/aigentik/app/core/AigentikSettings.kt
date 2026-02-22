package com.aigentik.app.core

import android.content.Context
import android.content.SharedPreferences

// AigentikSettings v0.7 — persists all user configuration
// Replaces hardcoded values in AigentikService
object AigentikSettings {

    private const val PREFS_NAME = "aigentik_settings"

    // Keys
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_AGENT_NAME = "agent_name"
    private const val KEY_OWNER_NAME = "owner_name"
    private const val KEY_ADMIN_NUMBER = "admin_number"
    private const val KEY_AIGENTIK_NUMBER = "aigentik_number"
    private const val KEY_GMAIL_ADDRESS = "gmail_address"
    private const val KEY_GMAIL_APP_PASSWORD = "gmail_app_password"
    private const val KEY_AUTO_REPLY = "auto_reply_default"
    private const val KEY_PAUSED = "paused"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // First run check
    var isConfigured: Boolean
        get() = prefs.getBoolean(KEY_CONFIGURED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONFIGURED, value).apply()

    var agentName: String
        get() = prefs.getString(KEY_AGENT_NAME, "Aigentik") ?: "Aigentik"
        set(value) = prefs.edit().putString(KEY_AGENT_NAME, value).apply()

    var ownerName: String
        get() = prefs.getString(KEY_OWNER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OWNER_NAME, value).apply()

    var adminNumber: String
        get() = prefs.getString(KEY_ADMIN_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADMIN_NUMBER, value).apply()

    var aigentikNumber: String
        get() = prefs.getString(KEY_AIGENTIK_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AIGENTIK_NUMBER, value).apply()

    var gmailAddress: String
        get() = prefs.getString(KEY_GMAIL_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GMAIL_ADDRESS, value).apply()

    var gmailAppPassword: String
        get() = prefs.getString(KEY_GMAIL_APP_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GMAIL_APP_PASSWORD, value).apply()

    var autoReplyDefault: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY, value).apply()

    var isPaused: Boolean
        get() = prefs.getBoolean(KEY_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAUSED, value).apply()

    // Save all settings at once from onboarding
    fun saveFromOnboarding(
        agentName: String,
        ownerName: String,
        adminNumber: String,
        aigentikNumber: String,
        gmailAddress: String,
        gmailAppPassword: String
    ) {
        prefs.edit()
            .putString(KEY_AGENT_NAME, agentName)
            .putString(KEY_OWNER_NAME, ownerName)
            .putString(KEY_ADMIN_NUMBER, adminNumber.filter { it.isDigit() }.takeLast(10))
            .putString(KEY_AIGENTIK_NUMBER, aigentikNumber.filter { it.isDigit() }.takeLast(10))
            .putString(KEY_GMAIL_ADDRESS, gmailAddress)
            .putString(KEY_GMAIL_APP_PASSWORD, gmailAppPassword)
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (ownerName.isBlank()) errors.add("Owner name is required")
        if (adminNumber.isBlank()) errors.add("Your phone number is required")
        if (gmailAddress.isBlank()) errors.add("Gmail address is required")
        if (gmailAppPassword.isBlank()) errors.add("Gmail app password is required")
        return errors
    }
}
