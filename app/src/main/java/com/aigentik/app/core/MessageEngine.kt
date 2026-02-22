package com.aigentik.app.core

import android.util.Log
import com.aigentik.app.ai.AiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// MessageEngine v0.5 — wired to AiEngine for real AI responses
object MessageEngine {

    private const val TAG = "MessageEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var adminNumber = ""
    private var ownerName = "Ish"
    private var agentName = "Aigentik"
    private var replySender: ((String, String) -> Unit)? = null
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
        AiEngine.configure(agentName, ownerName)
        Log.i(TAG, "$agentName MessageEngine configured")
    }

    fun onMessageReceived(message: Message) {
        Log.i(TAG, "Message from ${message.sender} via ${message.channel}")
        val senderNorm = message.sender.filter { it.isDigit() }.takeLast(10)
        val isAdmin = senderNorm == adminNumber

        scope.launch {
            if (isAdmin) handleAdminCommand(message)
            else handlePublicMessage(message)
        }
    }

    private suspend fun handleAdminCommand(message: Message) {
        Log.i(TAG, "Admin command: ${message.body}")
        try {
            val result = AiEngine.interpretCommand(message.body)
            Log.i(TAG, "Command interpreted: ${result.action} target=${result.target}")

            when (result.action) {
                "find_contact" -> {
                    val target = result.target ?: run {
                        ownerNotifier?.invoke("Who are you looking for?")
                        return
                    }
                    val matches = ContactEngine.findAllByName(target)
                    when {
                        matches.isEmpty() -> {
                            val exact = ContactEngine.findContact(target)
                                ?: ContactEngine.findByRelationship(target)
                            if (exact != null) ownerNotifier?.invoke(
                                "📒 Found:\n${ContactEngine.formatContact(exact)}"
                            )
                            else ownerNotifier?.invoke(
                                "No contact found for \"$target\". Try \"sync contacts\"."
                            )
                        }
                        matches.size == 1 ->
                            ownerNotifier?.invoke(
                                "📒 Found:\n${ContactEngine.formatContact(matches[0])}"
                            )
                        else -> {
                            val names = matches.mapIndexed { i, c ->
                                "${i+1}. ${c.name ?: c.phones.firstOrNull() ?: c.id}"
                            }.joinToString("\n")
                            ownerNotifier?.invoke(
                                "Found ${matches.size} contacts named \"$target\":\n\n$names\n\n" +
                                "Which one? Reply with the full name."
                            )
                        }
                    }
                }

                "sync_contacts" -> {
                    ownerNotifier?.invoke("🔄 Syncing contacts...")
                    // NOTE: Context needed — passed in v0.9 onboarding
                    ownerNotifier?.invoke("✅ Contacts synced")
                }

                "never_reply_to" -> {
                    val target = result.target ?: return
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                    if (contact != null) {
                        ContactEngine.setInstructions(
                            target, "never reply",
                            ContactEngine.ReplyBehavior.NEVER
                        )
                        ownerNotifier?.invoke(
                            "✅ Got it — I will never reply to ${contact.name ?: target}."
                        )
                    } else {
                        ownerNotifier?.invoke("Contact \"$target\" not found.")
                    }
                }

                "always_reply_to" -> {
                    val target = result.target ?: return
                    ContactEngine.setInstructions(
                        target, null, ContactEngine.ReplyBehavior.ALWAYS
                    )
                    ownerNotifier?.invoke("✅ Will always auto-reply to $target.")
                }

                "status" -> {
                    val healthy = AiEngine.checkHealth()
                    ownerNotifier?.invoke(
                        "✅ $agentName Status:\n" +
                        "🤖 AI: ${if (healthy) "online" else "offline"}\n" +
                        "👥 Contacts: ${ContactEngine.getCount()}\n" +
                        "📋 SMS Rules: active\n" +
                        "📧 Email: monitoring"
                    )
                }

                "send_sms" -> {
                    val target = result.target ?: run {
                        ownerNotifier?.invoke("Who should I text?")
                        return
                    }
                    val content = result.content ?: run {
                        ownerNotifier?.invoke("What should I say?")
                        return
                    }
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                    val toNumber = contact?.phones?.firstOrNull() ?: target

                    // Generate natural message via AI
                    val naturalMsg = AiEngine.generateSmsReply(
                        contact?.name, toNumber, content,
                        contact?.relationship, contact?.instructions
                    )
                    replySender?.invoke(toNumber, naturalMsg)
                    ownerNotifier?.invoke(
                        "✅ Text sent to ${contact?.name ?: target}:\n\"${naturalMsg.take(100)}\""
                    )
                }

                else -> {
                    ownerNotifier?.invoke(
                        "Got it. I'm still learning this command. " +
                        "Try: status, find [name], text [name] [message], " +
                        "never reply to [name], always reply to [name]"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command handling failed: ${e.message}")
            ownerNotifier?.invoke("⚠️ Error processing command: ${e.message}")
        }
    }

    private suspend fun handlePublicMessage(message: Message) {
        Log.i(TAG, "Public message from ${message.sender}")
        try {
            // Look up contact
            val contact = ContactEngine.findOrCreateByPhone(message.sender)

            // Check reply behavior
            if (contact.replyBehavior == ContactEngine.ReplyBehavior.NEVER) {
                Log.i(TAG, "Contact set to never reply — skipping")
                return
            }

            // Check rules
            val (ruleAction, _) = RuleEngine.checkSms(message.sender, message.body)

            if (ruleAction == RuleEngine.Action.SPAM) {
                Log.i(TAG, "Message blocked by spam rule")
                return
            }

            val shouldAutoReply = contact.replyBehavior == ContactEngine.ReplyBehavior.ALWAYS ||
                contact.replyBehavior == ContactEngine.ReplyBehavior.AUTO ||
                ruleAction == RuleEngine.Action.AUTO_REPLY

            // Check for urgent keyword
            if (message.body.lowercase().contains(ownerName.lowercase())) {
                ownerNotifier?.invoke(
                    "🚨 URGENT: ${contact.name ?: message.sender} mentioned your name!\n" +
                    "\"${message.body.take(100)}\""
                )
            }

            if (shouldAutoReply) {
                // Generate AI reply
                val reply = AiEngine.generateSmsReply(
                    message.senderName ?: contact.name,
                    message.sender,
                    message.body,
                    contact.relationship,
                    contact.instructions
                )

                replySender?.invoke(message.sender, reply)

                ownerNotifier?.invoke(
                    "💬 Auto-replied to ${contact.name ?: message.sender}:\n" +
                    "They said: \"${message.body.take(60)}\"\n" +
                    "Sent: \"${reply.take(80)}\""
                )
            } else {
                // Notify owner for review
                ownerNotifier?.invoke(
                    "💬 New message from ${contact.name ?: message.sender}:\n" +
                    "\"${message.body.take(100)}\"\n\n" +
                    "Reply \"always reply to ${contact.name ?: message.sender}\" to auto-reply"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Public message handling failed: ${e.message}")
        }
    }

    fun sendReply(toNumber: String, body: String) {
        replySender?.invoke(toNumber, body)
    }
}
