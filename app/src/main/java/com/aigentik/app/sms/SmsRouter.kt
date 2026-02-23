package com.aigentik.app.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

// SmsRouter v1.0 — sends SMS via SmsManager
// NOTE: Does NOT require Aigentik to be default SMS app for SENDING
//   SmsManager.sendTextMessage works from any app with SEND_SMS permission
//   Only RECEIVING requires default SMS app status
//   For receiving: we use SmsAdapter BroadcastReceiver (registered in manifest)
object SmsRouter {

    private const val TAG = "SmsRouter"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun send(toNumber: String, body: String) {
        val ctx = appContext ?: run {
            Log.e(TAG, "SmsRouter not initialized — call init() first")
            return
        }

        // Format to E.164 — carriers reject 10-digit numbers without country code
        // NOTE: Gemini audit found this was causing silent carrier rejection
        val e164Number = toE164(toNumber)
        Log.i(TAG, "SMS to $toNumber → formatted as $e164Number")

        try {
            val smsManager = ctx.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(body)

            if (parts.size == 1) {
                smsManager.sendTextMessage(e164Number, null, body, null, null)
                Log.i(TAG, "SMS sent to $e164Number")
            } else {
                smsManager.sendMultipartTextMessage(e164Number, null, parts, null, null)
                Log.i(TAG, "Multipart SMS (${parts.size} parts) sent to $e164Number")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed to $e164Number: ${e.message}")
        }
    }

    // Convert any phone number format to E.164 (+1XXXXXXXXXX for US)
    // Handles: 10-digit, 11-digit with 1, formatted with dashes/parens/spaces
    private fun toE164(number: String): String {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+1$digits"           // 10-digit US → +1XXXXXXXXXX
            digits.length == 11 && digits.startsWith("1") -> "+$digits"  // 1XXXXXXXXXX → +1XXXXXXXXXX
            digits.startsWith("+") -> number             // already E.164
            else -> "+1$digits"                          // best-effort US assumption
        }
    }
}
