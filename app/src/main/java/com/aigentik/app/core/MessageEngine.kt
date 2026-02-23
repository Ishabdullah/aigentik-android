package com.aigentik.app.core

import android.util.Log
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.email.EmailRouter
import com.aigentik.app.sms.SmsRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// MessageEngine v1.0
// New in this version:
// - Channel toggle commands (stop/start sms/email/gvoice/all)
// - Reply routing: SMS replies go via SmsRouter, email via EmailRouter
// - Chat notification callback — notifications appear in chat history
// - Regular email handling (was NOTE: v0.7 placeholder before)
// - "text [name] [message]" now actually sends SMS via SmsRouter
object MessageEngine {

    private const val TAG = "MessageEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var adminNumber  = ""
    private var ownerName    = "Ish"
    private var agentName    = "Aigentik"
    private var ownerNotifier: ((String) -> Unit)? = null

    // chatNotifier posts messages to the in-app chat Room database
    // Set by ChatActivity or AigentikService on startup
    var chatNotifier: ((String) -> Unit)? = null

    fun configure(
        adminNumber: String,
        ownerName: String,
        agentName: String,
        ownerNotifier: (String) -> Unit
    ) {
        this.adminNumber  = adminNumber.filter { it.isDigit() }.takeLast(10)
        this.ownerName    = ownerName
        this.agentName    = agentName
        this.ownerNotifier = ownerNotifier
        AiEngine.configure(agentName, ownerName)
        Log.i(TAG, "$agentName MessageEngine configured")
    }

    // Notify owner via notification + chat simultaneously
    private fun notify(message: String) {
        ownerNotifier?.invoke(message)
        chatNotifier?.invoke(message)
    }

    fun onMessageReceived(message: Message) {
        Log.i(TAG, "Message from ${message.sender} via ${message.channel}")
        if (AigentikSettings.isPaused) {
            Log.i(TAG, "System paused — ignoring message")
            return
        }
        val senderNorm = message.sender.filter { it.isDigit() }.takeLast(10)
        val isAdmin = senderNorm == adminNumber ||
                      message.sender.lowercase() == AigentikSettings.gmailAddress.lowercase()

        scope.launch {
            if (isAdmin) handleAdminCommand(message)
            else handlePublicMessage(message)
        }
    }

    private suspend fun handleAdminCommand(message: Message) {
        Log.i(TAG, "Admin command: ${message.body}")
        val input = message.body.trim()
        val lower = input.lowercase()

        // Channel toggle commands — check before AI interpretation
        // Patterns: "stop monitoring email", "start sms", "pause google voice", etc.
        val channelToggle = ChannelManager.parseToggleCommand(lower)
        if (channelToggle != null) {
            val (channel, enable) = channelToggle
            // Check for "all" keyword
            if (lower.contains("all") || lower.contains("everything")) {
                ChannelManager.Channel.values().forEach { ch ->
                    if (enable) ChannelManager.enable(ch) else ChannelManager.disable(ch)
                }
                notify(if (enable) "✅ All channels enabled:\n${ChannelManager.statusSummary()}"
                       else "⏸ All channels paused:\n${ChannelManager.statusSummary()}")
            } else {
                if (enable) ChannelManager.enable(channel) else ChannelManager.disable(channel)
                notify("${if (enable) "✅" else "⏸"} ${channel.name} channel " +
                       "${if (enable) "enabled" else "paused"}.\n${ChannelManager.statusSummary()}")
            }
            return
        }

        // Channel status query
        if (lower.contains("channel") || lower == "channels") {
            notify("📡 Channel Status:\n${ChannelManager.statusSummary()}")
            return
        }

        try {
            val result = AiEngine.interpretCommand(input)
            Log.i(TAG, "Command: ${result.action} target=${result.target}")

            when (result.action) {
                "find_contact" -> {
                    val target = result.target ?: run {
                        notify("Who are you looking for?")
                        return
                    }
                    val matches = ContactEngine.findAllByName(target)
                    when {
                        matches.isEmpty() -> {
                            val exact = ContactEngine.findContact(target)
                                ?: ContactEngine.findByRelationship(target)
                            notify(if (exact != null)
                                "📒 Found:\n${ContactEngine.formatContact(exact)}"
                            else
                                "No contact found for \"$target\".")
                        }
                        matches.size == 1 ->
                            notify("📒 Found:\n${ContactEngine.formatContact(matches[0])}")
                        else -> {
                            val names = matches.mapIndexed { i, c ->
                                "${i+1}. ${c.name ?: c.phones.firstOrNull() ?: c.id}"
                            }.joinToString("\n")
                            notify("Found ${matches.size} matching \"$target\":\n$names")
                        }
                    }
                }

                "never_reply_to" -> {
                    val target = result.target ?: return
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                    if (contact != null) {
                        ContactEngine.setInstructions(target, "never reply",
                            ContactEngine.ReplyBehavior.NEVER)
                        notify("✅ Will never reply to ${contact.name ?: target}.")
                    } else {
                        notify("Contact \"$target\" not found.")
                    }
                }

                "always_reply_to" -> {
                    val target = result.target ?: return
                    ContactEngine.setInstructions(target, null, ContactEngine.ReplyBehavior.ALWAYS)
                    notify("✅ Will always auto-reply to $target.")
                }

                "status" -> {
                    notify("✅ $agentName Status:\n" +
                        "🤖 AI: ${if (AiEngine.isReady()) "online" else "offline"}\n" +
                        "👥 Contacts: ${ContactEngine.getCount()}\n" +
                        "📡 Channels:\n${ChannelManager.statusSummary()}")
                }

                "send_sms" -> {
                    val target = result.target ?: run { notify("Who should I text?"); return }
                    val content = result.content ?: run { notify("What should I say?"); return }
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                    val toNumber = contact?.phones?.firstOrNull() ?: target

                    val naturalMsg = AiEngine.generateSmsReply(
                        contact?.name, toNumber, content,
                        contact?.relationship, contact?.instructions
                    )
                    // Send via SMS directly (owner-initiated texts go via SmsRouter)
                    SmsRouter.send(toNumber, naturalMsg)
                    notify("✅ Text sent to ${contact?.name ?: target}:\n\"${naturalMsg.take(100)}\"")
                }

                else -> {
                    // Use AI for natural conversation
                    if (AiEngine.isReady()) {
                        val reply = AiEngine.generateSmsReply(
                            ownerName, adminNumber, input, null, null
                        )
                        notify(reply)
                    } else {
                        notify("Command not recognized. Try: status, channels, " +
                               "stop/start sms/email/gvoice, find [name], text [name] [msg]")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${e.message}")
            notify("⚠️ Error: ${e.message?.take(80)}")
        }
    }

    private suspend fun handlePublicMessage(message: Message) {
        Log.i(TAG, "Public message from ${message.sender} via ${message.channel}")
        try {
            val contact = ContactEngine.findOrCreateByPhone(message.sender)

            if (contact.replyBehavior == ContactEngine.ReplyBehavior.NEVER) {
                Log.i(TAG, "Never-reply contact — skipping")
                return
            }

            val (ruleAction, _) = RuleEngine.checkSms(message.sender, message.body)
            if (ruleAction == RuleEngine.Action.SPAM) {
                Log.i(TAG, "Spam blocked")
                return
            }

            val shouldAutoReply = contact.replyBehavior == ContactEngine.ReplyBehavior.ALWAYS ||
                contact.replyBehavior == ContactEngine.ReplyBehavior.AUTO ||
                ruleAction == RuleEngine.Action.AUTO_REPLY

            if (message.body.lowercase().contains(ownerName.lowercase())) {
                notify("🚨 URGENT: ${contact.name ?: message.sender} mentioned your name!\n" +
                       "\"${message.body.take(100)}\"")
            }

            if (shouldAutoReply) {
                val reply = AiEngine.generateSmsReply(
                    message.senderName ?: contact.name,
                    message.sender,
                    message.body,
                    contact.relationship,
                    contact.instructions
                )

                // Route reply based on message channel
                // NOTE: Gemini audit — NOTIFICATION/RCS must use SmsRouter not EmailRouter
                // EmailRouter has no context for RCS messages and falls back incorrectly
                when (message.channel) {
                    Message.Channel.SMS          -> SmsRouter.send(message.sender, reply)
                    Message.Channel.NOTIFICATION -> SmsRouter.send(message.sender, reply)
                    Message.Channel.EMAIL        -> EmailRouter.routeReply(message.sender, reply)
                    else                         -> SmsRouter.send(message.sender, reply)
                }

                notify("💬 Auto-replied to ${contact.name ?: message.sender}:\n" +
                       "They said: \"${message.body.take(60)}\"\n" +
                       "Sent: \"${reply.take(80)}\"")
            } else {
                notify("💬 New message from ${contact.name ?: message.sender} " +
                       "[${message.channel}]:\n\"${message.body.take(100)}\"\n\n" +
                       "Say \"always reply to ${contact.name ?: message.sender}\" to auto-reply")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Public message failed: ${e.message}")
        }
    }

    fun sendReply(toNumber: String, body: String) {
        SmsRouter.send(toNumber, body)
    }
}
