package com.aigentik.app.core

import android.util.Log

// MessageEngine — platform-agnostic message routing core
// Receives unified Message objects from any adapter
// Routes to AI engine, applies rules, sends replies
// NOTE: AI engine connection added in v0.5
object MessageEngine {

    private const val TAG = "MessageEngine"

    // Admin phone number — messages from this number are commands
    private var adminNumber: String = ""
    private var ownerName: String = "Ish"
    private var agentName: String = "Aigentik"

    // Callback for sending replies — set by platform layer
    private var replySender: ((String, String) -> Unit)? = null

    // Callback for notifying owner — set by platform layer  
    private var ownerNotifier: ((String) -> Unit)? = null

    fun configure(
        adminNumber: String,
        ownerName: String,
        agentName: String,
        replySender: (String, String) -> Unit,
        ownerNotifier: (String) -> Unit
    ) {
        this.adminNumber = adminNumber.filter { it.isDigit() }.takeLast(10)
        this.ownerName = ownerName
        this.agentName = agentName
        this.replySender = replySender
        this.ownerNotifier = ownerNotifier
        Log.i(TAG, "$agentName configured. Admin: $adminNumber")
    }

    // Main entry point — called by any adapter with a unified Message
    fun onMessageReceived(message: Message) {
        Log.i(TAG, "Message received via ${message.channel} from ${message.sender}")

        val senderNorm = message.sender.filter { it.isDigit() }.takeLast(10)
        val isAdmin = senderNorm == adminNumber

        if (isAdmin) {
            Log.i(TAG, "Admin command from owner")
            handleAdminCommand(message)
        } else {
            Log.i(TAG, "Public message — routing to AI handler")
            handlePublicMessage(message)
        }
    }

    private fun handleAdminCommand(message: Message) {
        // NOTE: Full NLP command handling added in v0.8
        // For now log and acknowledge
        Log.i(TAG, "Admin command: ${message.body}")
        ownerNotifier?.invoke("Command received: ${message.body.take(50)}")
    }

    private fun handlePublicMessage(message: Message) {
        // NOTE: AI reply generation added in v0.6
        // For now queue for review
        Log.i(TAG, "Public message queued: ${message.body.take(50)}")
        ownerNotifier?.invoke(
            "New message from ${message.senderName ?: message.sender}:\n" +
            "\"${message.body.take(100)}\"\n" +
            "Channel: ${message.channel}"
        )
    }

    // Called by adapters to send a reply
    fun sendReply(toNumber: String, body: String) {
        replySender?.invoke(toNumber, body)
        Log.i(TAG, "Reply sent to $toNumber")
    }
}
