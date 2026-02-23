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

        try {
            // Split into parts if > 160 chars
            val smsManager = ctx.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(body)

            if (parts.size == 1) {
                smsManager.sendTextMessage(toNumber, null, body, null, null)
                Log.i(TAG, "SMS sent to $toNumber")
            } else {
                smsManager.sendMultipartTextMessage(toNumber, null, parts, null, null)
                Log.i(TAG, "Multipart SMS (${parts.size} parts) sent to $toNumber")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed to $toNumber: ${e.message}")
        }
    }
}
