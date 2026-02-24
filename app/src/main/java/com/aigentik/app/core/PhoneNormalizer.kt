package com.aigentik.app.core

import android.telephony.PhoneNumberUtils
import android.util.Log

// PhoneNormalizer v1.0 — single source of truth for phone number formatting
// Used by: SmsRouter, NotificationAdapter, MessageDeduplicator, ContactEngine
//
// Rules:
//   - Always produce E.164 format: +1XXXXXXXXXX for US numbers
//   - Strip all non-digit characters before processing
//   - 10 digits → assume US → +1XXXXXXXXXX
//   - 11 digits starting with 1 → +1XXXXXXXXXX
//   - Already has + prefix → return as-is if valid
//   - Anything else → return original (let caller handle)
//
// NOTE: PhoneNumberUtils.formatNumberToE164() requires a region hint
//   We default to "US" — works for all North American numbers
object PhoneNormalizer {

    private const val TAG = "PhoneNormalizer"
    private const val DEFAULT_REGION = "US"

    // Normalize to E.164 — always use this before sending or comparing
    fun toE164(raw: String): String {
        if (raw.isBlank()) return raw

        // Try Android's built-in normalizer first
        try {
            val normalized = PhoneNumberUtils.formatNumberToE164(raw, DEFAULT_REGION)
            if (normalized != null) {
                Log.d(TAG, "Normalized '$raw' → '$normalized' via PhoneNumberUtils")
                return normalized
            }
        } catch (e: Exception) {
            Log.w(TAG, "PhoneNumberUtils failed for '$raw': ${e.message}")
        }

        // Manual fallback
        val digits = raw.filter { it.isDigit() }
        val result = when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+${digits}"
            digits.length > 11 -> "+$digits"
            raw.startsWith("+") && digits.length >= 10 -> "+$digits"
            else -> {
                Log.w(TAG, "Could not normalize '$raw' — returning as-is")
                raw
            }
        }
        Log.d(TAG, "Normalized '$raw' → '$result' via fallback")
        return result
    }

    // Compare two numbers ignoring formatting
    // Returns true if they refer to the same number
    fun isSameNumber(a: String, b: String): Boolean {
        val normA = toE164(a)
        val normB = toE164(b)
        if (normA == normB) return true
        // Also compare last 10 digits as final fallback
        val digitsA = a.filter { it.isDigit() }.takeLast(10)
        val digitsB = b.filter { it.isDigit() }.takeLast(10)
        return digitsA.isNotEmpty() && digitsA == digitsB
    }

    // Check if string looks like a phone number (not a contact name)
    fun looksLikePhoneNumber(s: String): Boolean {
        val digits = s.filter { it.isDigit() }
        return digits.length >= 7 && s.filter { !it.isDigit() && it != '+' && it != '-' &&
            it != '(' && it != ')' && it != ' ' }.isEmpty()
    }
}
