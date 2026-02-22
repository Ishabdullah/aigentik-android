package com.aigentik.app.system

import android.util.Log
import com.aigentik.app.email.EmailMonitor
import com.aigentik.app.email.GmailClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ConnectionWatchdog v0.9 — monitors Gmail connection health
// Automatically reconnects if connection drops
// Runs every 5 minutes
object ConnectionWatchdog {

    private const val TAG = "ConnectionWatchdog"
    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    // Track consecutive failures for backoff
    private var failureCount = 0
    private const val MAX_FAILURES = 5

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "ConnectionWatchdog started")

        scope.launch {
            while (isActive && isRunning) {
                delay(CHECK_INTERVAL_MS)
                checkAndReconnect()
            }
        }
    }

    private suspend fun checkAndReconnect() {
        try {
            if (!GmailClient.isConnected()) {
                failureCount++
                Log.w(TAG, "Gmail disconnected — attempting reconnect (attempt $failureCount)")

                if (failureCount > MAX_FAILURES) {
                    // Exponential backoff — wait longer after repeated failures
                    val backoffMs = minOf(failureCount * 60_000L, 30 * 60_000L)
                    Log.w(TAG, "Too many failures — backing off ${backoffMs/1000}s")
                    delay(backoffMs)
                }

                val reconnected = GmailClient.connect()
                if (reconnected) {
                    failureCount = 0
                    Log.i(TAG, "Gmail reconnected successfully")
                    // Restart email monitor
                    EmailMonitor.stop()
                    EmailMonitor.start()
                } else {
                    Log.e(TAG, "Gmail reconnect failed")
                }
            } else {
                // Connection healthy
                if (failureCount > 0) {
                    Log.i(TAG, "Connection restored after $failureCount failures")
                    failureCount = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog check failed: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "ConnectionWatchdog stopped")
    }
}
