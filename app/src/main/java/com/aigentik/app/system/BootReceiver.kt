package com.aigentik.app.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aigentik.app.core.AigentikService
import com.aigentik.app.core.AigentikSettings

// BootReceiver v0.9 — restarts Aigentik after phone reboot
// Without this the service dies on reboot and never restarts
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val shouldStart = action == Intent.ACTION_BOOT_COMPLETED ||
                          action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                          action == "android.intent.action.QUICKBOOT_POWERON"

        if (!shouldStart) return

        AigentikSettings.init(context)

        if (!AigentikSettings.isConfigured) {
            Log.i(TAG, "Aigentik not configured — skipping auto-start")
            return
        }

        Log.i(TAG, "Boot detected — starting ${AigentikSettings.agentName}")

        try {
            val serviceIntent = Intent(context, AigentikService::class.java)
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "AigentikService started via boot receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service on boot: ${e.message}")
        }
    }
}
