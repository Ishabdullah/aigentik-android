package com.aigentik.app.core

// Message — unified platform-agnostic message object
// All channels (SMS, RCS via notification) produce this same object
// AI engine only ever sees Message objects — never channel-specific data
data class Message(
    val id: String,               // Unique fingerprint for deduplication (gmailId for email)
    val sender: String,           // Phone number or email address
    val senderName: String?,      // Contact name if known
    val body: String,             // Message content
    val timestamp: Long,          // Unix timestamp
    val channel: Channel,         // Where it came from
    val threadId: String? = null, // For threading replies (email threadId)
    val subject: String?  = null  // Email subject — used for generateEmailReply()
) {
    enum class Channel {
        SMS,          // Traditional SMS
        NOTIFICATION, // RCS intercepted via notification listener
        EMAIL,        // Email — regular Gmail or Google Voice forwarded
        CHAT          // In-app chat screen — always admin trusted, no auth needed
    }
}
