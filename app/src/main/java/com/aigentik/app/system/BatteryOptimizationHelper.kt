package com.aigentik.app.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

// BatteryOptimizationHelper v0.9
// Helps user disable battery optimization for Aigentik
// Without this Samsung will kill the service within minutes
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // Returns intent to open battery optimization settings
    // Activity should start this intent and prompt user to allow
    fun getOptimizationSettingsIntent(context: Context): Intent {
        return try {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not create direct intent — falling back to settings")
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    fun shouldShowPrompt(context: Context): Boolean {
        return !isIgnoringBatteryOptimizations(context)
    }
}
