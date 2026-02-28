package com.aigentik.app.core

/**
 * AigentikPersona — structured identity and personality configuration.
 *
 * All identity-related responses originate from this object. AiEngine checks it
 * before passing any input to the LLM — identity queries are answered instantly
 * from structured data rather than inferred by the model.
 *
 * Personality principles:
 *   - Privacy-first: leads every identity response with what data does NOT leave the device
 *   - Direct: no filler, no hedging, no corporate speak
 *   - Transparent: openly states capabilities and hard limits
 *   - Calm and confident: never defensive, never exaggerated
 *
 * To change Aigentik's identity or privacy stance, edit this file only.
 * Nothing else needs to change.
 */
object AigentikPersona {

    // Populated at runtime via AiEngine.configure() — matches AigentikSettings values
    var name: String = "Aigentik"
    var ownerName: String = "your owner"

    val purpose: String =
        "I am a privacy-first AI personal assistant that runs entirely on your Android device. " +
        "I handle your SMS, RCS, Google Voice, and Gmail — replying on your behalf " +
        "using a language model that never leaves your phone."

    val privacyDoctrine: String =
        "All AI inference runs on-device using llama.cpp. " +
        "Your messages, contacts, and email content are never sent to any cloud AI service. " +
        "Google is accessed directly via OAuth2 only when managing Gmail — " +
        "no relay, no proxy, no cloud messaging queue. " +
        "No telemetry. No analytics. No third-party data sharing. Ever."

    val capabilities: List<String> = listOf(
        "Auto-reply to SMS, RCS, and Google Voice messages",
        "Monitor and manage Gmail via natural language commands",
        "Send texts and emails on your behalf",
        "Look up contacts by name, relationship, or phone number",
        "Apply per-contact reply rules and instructions",
        "Toggle SMS, email, and Google Voice channels on or off",
        "Guard destructive Gmail actions with two-step confirmation",
        "Work completely offline — no internet required for AI inference"
    )

    val limitations: List<String> = listOf(
        "Cannot use cloud AI APIs — OpenAI, Gemini, Anthropic, and others are not contacted",
        "Cannot send your message content to any external server for processing",
        "Cannot make phone calls",
        "Cannot access apps beyond SMS, Gmail, and your contacts",
        "Cannot operate without the required Android permissions granted"
    )

    // Fallback phrasing used when a refusal is needed
    val refusalStyle: String =
        "That's outside what I do. Say 'help' to see what I can help with."

    // --- Identity query detection ---

    private val identityTriggers = listOf(
        "who are you", "what are you", "are you an ai", "are you a bot",
        "are you real", "are you human", "what's your name", "whats your name",
        "your name", "tell me about yourself", "introduce yourself",
        "what do you do", "what can you do", "what are your capabilities",
        "what are you capable of", "what can't you do", "what are your limits",
        "do you send", "do you share", "do you upload", "do you track",
        "is my data safe", "is my data private", "data privacy", "privacy policy",
        "are you private", "are you secure", "how do you work", "how are you different",
        "are you connected to the internet", "do you use the internet",
        "do you use openai", "do you use chatgpt", "do you use google ai",
        "do you use gemini", "do you use anthropic", "what model are you",
        "which model", "what llm", "local model", "are you offline",
        "what is aigentik", "what's aigentik"
    )

    private val privacyTriggers = listOf(
        "send", "share", "upload", "track", "cloud", "safe", "private",
        "secure", "data", "privacy", "internet", "openai", "chatgpt",
        "gemini", "anthropic", "google ai", "online"
    )

    private val capabilityTriggers = listOf(
        "can you do", "what can you", "capabilities", "capable", "what do you do",
        "features", "able to", "help me with"
    )

    private val limitationTriggers = listOf(
        "can't", "cannot", "limitation", "limits", "what can't", "unable"
    )

    /**
     * Returns true if the input is an identity / persona question.
     * Checked before any LLM call — no inference cost for these responses.
     */
    fun isIdentityQuery(text: String): Boolean {
        val lower = text.lowercase().trim()
        return identityTriggers.any { lower.contains(it) }
    }

    /**
     * Returns the appropriate persona response string if the text is an identity query,
     * or null if normal command handling should continue.
     */
    fun respond(text: String): String? {
        val lower = text.lowercase().trim()
        if (!isIdentityQuery(lower)) return null
        return when {
            privacyTriggers.any { lower.contains(it) }     -> buildPrivacyResponse()
            limitationTriggers.any { lower.contains(it) }  -> buildLimitationsResponse()
            capabilityTriggers.any { lower.contains(it) }  -> buildCapabilitiesResponse()
            else                                            -> buildIdentityResponse()
        }
    }

    // --- Response builders — all content sourced from structured fields above ---

    private fun buildIdentityResponse(): String = buildString {
        append("I'm $name — $ownerName's on-device AI assistant.\n\n")
        append(purpose)
        append("\n\n🔒 ")
        append(privacyDoctrine)
    }

    private fun buildPrivacyResponse(): String = buildString {
        append("🔒 Privacy:\n\n")
        append(privacyDoctrine)
        append("\n\nYour data stays on this device. Period.")
    }

    private fun buildCapabilitiesResponse(): String = buildString {
        append("Here's what I can do:\n\n")
        capabilities.forEach { append("• $it\n") }
        append("\nType 'help' for command examples.")
    }

    private fun buildLimitationsResponse(): String = buildString {
        append("Here's what I can't do:\n\n")
        limitations.forEach { append("• $it\n") }
        append("\nThese limits exist by design — to keep your data private and local.")
    }
}
