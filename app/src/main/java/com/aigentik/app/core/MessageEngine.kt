package com.aigentik.app.core

import android.util.Log
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.email.EmailMonitor
import com.aigentik.app.email.EmailRouter
import com.aigentik.app.email.GmailClient
import com.aigentik.app.sms.SmsRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// MessageEngine v1.1
// - Channel toggle commands (stop/start sms/email/gvoice/all)
// - Reply routing: SMS via SmsRouter, email via EmailRouter
// - Chat notification callback — notifications appear in chat history
// - check_email, get_contact_phone, send_email command handlers
// - Keyword fallback for unrecognized commands before AI chat
object MessageEngine {

    private const val TAG = "MessageEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var adminNumber  = ""
    private var ownerName    = "Ish"
    private var agentName    = "Aigentik"
    private var ownerNotifier: ((String) -> Unit)? = null

    // chatNotifier posts messages into Room DB so they appear in chat history
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

    private fun notify(message: String) {
        ownerNotifier?.invoke(message)
        chatNotifier?.invoke(message)
    }

    fun onMessageReceived(message: Message) {
        Log.i(TAG, "Message from ${message.sender} via ${message.channel}")
        if (AigentikSettings.isPaused) {
            Log.i(TAG, "System paused — ignoring")
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

        // Channel toggle — check before AI interpretation
        val channelToggle = ChannelManager.parseToggleCommand(lower)
        if (channelToggle != null) {
            val (channel, enable) = channelToggle
            if (lower.contains("all") || lower.contains("everything")) {
                ChannelManager.Channel.values().forEach { ch ->
                    if (enable) ChannelManager.enable(ch) else ChannelManager.disable(ch)
                }
                val verb = if (enable) "enabled" else "paused"
                notify("${if (enable) "✅" else "⏸"} All channels $verb:\n${ChannelManager.statusSummary()}")
            } else {
                if (enable) ChannelManager.enable(channel) else ChannelManager.disable(channel)
                val verb = if (enable) "enabled" else "paused"
                notify("${if (enable) "✅" else "⏸"} ${channel.name} $verb.\n${ChannelManager.statusSummary()}")
            }
            return
        }

        if (lower == "channels" || lower.contains("channel status")) {
            notify("📡 Channel Status:\n${ChannelManager.statusSummary()}")
            return
        }

        try {
            val result = AiEngine.interpretCommand(input)
            Log.i(TAG, "Command: ${result.action} target=${result.target}")

            when (result.action) {

                "find_contact" -> {
                    val target = result.target ?: run { notify("Who are you looking for?"); return }
                    val matches = ContactEngine.findAllByName(target)
                    when {
                        matches.isEmpty() -> {
                            val exact = ContactEngine.findContact(target)
                                ?: ContactEngine.findByRelationship(target)
                            notify(
                                if (exact != null) "📒 Found:\n${ContactEngine.formatContact(exact)}"
                                else "No contact found for \"$target\"."
                            )
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

                "get_contact_phone", "contact_phone", "phone_number" -> {
                    val target = result.target ?: run { notify("Who are you looking for?"); return }
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                        ?: ContactEngine.findAllByName(target).firstOrNull()
                    if (contact != null) {
                        val phones = contact.phones.joinToString(", ").ifEmpty { "no phone on file" }
                        val emails = contact.emails.joinToString(", ").ifEmpty { "no email on file" }
                        notify("📒 ${contact.name ?: target}:\n📞 $phones\n📧 $emails")
                    } else {
                        notify("No contact found for \"$target\". Try 'find $target'.")
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
                    val ai = if (AiEngine.isReady()) "online" else "offline"
                    val contacts = ContactEngine.getCount()
                    val channels = ChannelManager.statusSummary()
                    notify("✅ $agentName Status:\n🤖 AI: $ai\n👥 Contacts: $contacts\n📡 Channels:\n$channels")
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
                    SmsRouter.send(toNumber, naturalMsg)
                    val name = contact?.name ?: target
                    val preview = naturalMsg.take(100)
                    notify("✅ Text sent to $name:\n\"$preview\"")
                }

                "send_email" -> {
                    val target = result.target ?: run { notify("Who should I email?"); return }
                    val content = result.content ?: run { notify("What should I say?"); return }
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                    val toEmail = contact?.emails?.firstOrNull() ?: target
                    scope.launch {
                        val body = AiEngine.generateEmailReply(
                            contact?.name, toEmail,
                            "Message from $ownerName", content,
                            contact?.relationship, contact?.instructions
                        )
                        val subject = "Hi from $ownerName"
                        val sent = GmailClient.sendEmail(toEmail, subject, body)
                        val name = contact?.name ?: target
                        notify(if (sent) "✅ Email sent to $name" else "❌ Failed to email $name")
                    }
                }

                "check_email", "read_email", "list_email" -> {
                    val running = EmailMonitor.isRunning()
                    val status = if (running) "active ✅" else "stopped ❌"
                    val connected = if (GmailClient.isConnected()) "connected" else "disconnected"
                    notify("📧 Email monitor: $status\nGmail: $connected\nNew emails appear here automatically.\nChannels:\n${ChannelManager.statusSummary()}")
                }

                "sync_contacts" -> {
                    notify("✅ Contacts: ${ContactEngine.getCount()} loaded")
                }

                else -> {
                    // Keyword fallback before general AI chat
                    val lower2 = input.lowercase()
                    when {
                        lower2.contains("email") && (lower2.contains("check") ||
                            lower2.contains("read") || lower2.contains("inbox")) -> {
                            val running = EmailMonitor.isRunning()
                            val st = if (running) "active ✅" else "stopped ❌"
                            notify("📧 Email monitor: $st\nNew emails appear here automatically.\n${ChannelManager.statusSummary()}")
                        }

                        (lower2.contains("number") || lower2.contains("phone")) &&
                            (lower2.contains("what") || lower2.contains("get") ||
                             lower2.contains("find") || lower2.contains("whats")) -> {
                            val name = lower2
                                .replace("what's", "")
                                .replace("whats", "")
                                .replace("what is", "")
                                .replace("get", "")
                                .replace("find", "")
                                .replace("phone", "")
                                .replace("number", "")
                                .replace("'s", "")
                                .replace("?", "")
                                .trim()
                            if (name.isNotEmpty()) {
                                val contact = ContactEngine.findContact(name)
                                    ?: ContactEngine.findByRelationship(name)
                                    ?: ContactEngine.findAllByName(name).firstOrNull()
                                if (contact != null) {
                                    val phones = contact.phones.joinToString(", ").ifEmpty { "no phone on file" }
                                    notify("📒 ${contact.name ?: name}: $phones")
                                } else {
                                    notify("No contact found for \"$name\".")
                                }
                            } else {
                                notify("Who's number are you looking for?")
                            }
                        }

                        lower2.startsWith("text ") || lower2.startsWith("send ") -> {
                            val fallback = AiEngine.parseSimpleCommandPublic(input)
                            if (fallback.action == "send_sms" && fallback.target != null) {
                                val contact = ContactEngine.findContact(fallback.target)
                                    ?: ContactEngine.findByRelationship(fallback.target)
                                val toNumber = contact?.phones?.firstOrNull() ?: fallback.target
                                val msg = AiEngine.generateSmsReply(
                                    contact?.name, toNumber,
                                    fallback.content ?: input,
                                    contact?.relationship, contact?.instructions
                                )
                                SmsRouter.send(toNumber, msg)
                                val name = contact?.name ?: fallback.target
                                notify("✅ Text sent to $name")
                            } else {
                                notify("Who should I text, and what should I say?")
                            }
                        }

                        else -> {
                            // Genuine conversation — use AI
                            if (AiEngine.isReady()) {
                                val reply = AiEngine.generateSmsReply(
                                    ownerName, adminNumber, input, null, null
                                )
                                notify(reply)
                            } else {
                                notify("Try: status, channels, find [name], text [name] [msg], stop/start sms/email/gvoice")
                            }
                        }
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
                val sender = contact.name ?: message.sender
                val preview = message.body.take(100)
                notify("🚨 URGENT: $sender mentioned your name!\n\"$preview\"")
            }

            if (shouldAutoReply) {
                val reply = AiEngine.generateSmsReply(
                    message.senderName ?: contact.name,
                    message.sender,
                    message.body,
                    contact.relationship,
                    contact.instructions
                )

                // Route by channel — NOTIFICATION/RCS goes to SmsRouter not EmailRouter
                when (message.channel) {
                    Message.Channel.SMS          -> SmsRouter.send(message.sender, reply)
                    Message.Channel.NOTIFICATION -> SmsRouter.send(message.sender, reply)
                    Message.Channel.EMAIL        -> EmailRouter.routeReply(message.sender, reply)
                    else                         -> SmsRouter.send(message.sender, reply)
                }

                val sender = contact.name ?: message.sender
                val inbound = message.body.take(60)
                val outbound = reply.take(80)
                notify("💬 Auto-replied to $sender:\nThey said: \"$inbound\"\nSent: \"$outbound\"")
            } else {
                val sender = contact.name ?: message.sender
                val preview = message.body.take(100)
                val ch = message.channel
                notify("💬 New message from $sender [$ch]:\n\"$preview\"\n\nSay \"always reply to $sender\" to auto-reply")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Public message failed: ${e.message}")
        }
    }

    fun sendReply(toNumber: String, body: String) {
        SmsRouter.send(toNumber, body)
    }
}
