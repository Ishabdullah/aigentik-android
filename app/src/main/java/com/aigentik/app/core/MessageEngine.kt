package com.aigentik.app.core

import android.content.Context
import android.util.Log
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.auth.AdminAuthManager
import com.aigentik.app.auth.DestructiveActionGuard
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.PhoneNormalizer
import com.aigentik.app.email.EmailMonitor
import com.aigentik.app.email.EmailRouter
import com.aigentik.app.email.GmailApiClient
import com.aigentik.app.sms.SmsRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// MessageEngine v1.4
// v1.4: Room-backed per-contact conversation history for public message handling.
//   Public messages (SMS, email) now include the last CONTEXT_WINDOW_TURNS turns
//   as context when generating AI replies. This allows Aigentik to handle follow-up
//   questions naturally ("What time does it start?" after "Are you coming tonight?").
//
//   Topic-drift prevention: if the last exchange with a contact was >SESSION_GAP_MS
//   ago, history is treated as a new session and prior context is excluded.
//   History trimmed to HISTORY_KEEP_COUNT turns per contact to bound DB growth.
//
//   NOTE: Admin command exchanges are NOT stored in conversation history —
//   only public inbound messages and Aigentik's auto-replies are recorded.
// v1.3: Gmail natural language actions — count, list, search, trash, mark read,
//   mark spam, label, unsubscribe, empty trash. All destructive actions gated
//   by DestructiveActionGuard requiring admin code confirmation.
// v1.2: PARTIAL_WAKE_LOCK acquired around each message handler to prevent
//   Samsung CPU throttling during llama.cpp inference. Max 10-min timeout.
// v1.1: Channel toggles, send_email wired, check_email, keyword fallback
object MessageEngine {

    private const val TAG = "MessageEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var adminNumber  = ""
    private var ownerName    = "Ish"
    private var agentName    = "Aigentik"
    private var ownerNotifier: ((String) -> Unit)? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var appContext: Context? = null

    // chatNotifier posts messages into Room DB so they appear in chat history
    var chatNotifier: ((String) -> Unit)? = null

    // Conversation history DAO — initialized from configure() context
    private var historyDao: ConversationHistoryDao? = null

    fun configure(
        context: Context,
        adminNumber: String,
        ownerName: String,
        agentName: String,
        ownerNotifier: (String) -> Unit,
        wakeLock: android.os.PowerManager.WakeLock? = null
    ) {
        this.appContext    = context.applicationContext
        this.adminNumber   = PhoneNormalizer.toE164(adminNumber)
        this.ownerName     = ownerName
        this.agentName     = agentName
        this.ownerNotifier = ownerNotifier
        this.wakeLock      = wakeLock
        historyDao         = ConversationHistoryDatabase.getInstance(context).historyDao()
        AiEngine.configure(agentName, ownerName)
        Log.i(TAG, "$agentName MessageEngine configured")
    }

    // Stable channel key for destructive action guard:
    // Chat screen always uses "CHAT"; remote channels use sender identifier
    private fun channelKey(message: Message): String =
        if (message.channel == Message.Channel.CHAT) "CHAT" else message.sender

    private fun notify(message: String) {
        ownerNotifier?.invoke(message)
        chatNotifier?.invoke(message)
    }

    // Send reply back through the same channel the message arrived on
    // Used for: admin auth confirmations, destructive action responses
    private fun replyToSender(message: Message, reply: String) {
        when (message.channel) {
            Message.Channel.NOTIFICATION -> {
                val sent = com.aigentik.app.adapters.NotificationReplyRouter.sendReply(
                    message.id, reply
                )
                if (!sent) SmsRouter.send(message.sender, reply)
            }
            Message.Channel.SMS   -> SmsRouter.send(message.sender, reply)
            Message.Channel.EMAIL -> EmailRouter.routeReply(message.sender, reply)
            Message.Channel.CHAT  -> notify(reply)
        }
    }

    fun onMessageReceived(message: Message) {
        Log.i(TAG, "Message from ${message.sender} via ${message.channel}")
        if (AigentikSettings.isPaused) {
            Log.i(TAG, "System paused — ignoring")
            return
        }
        // Admin check — chat screen always trusted, remote channels need auth
        val isAdminChannel = message.channel == Message.Channel.CHAT
        val channelKey = message.sender

        // Check for admin login format in message body (remote channels only)
        // Format: Admin: Ish\nPassword: xxxx\n<command>
        if (!isAdminChannel) {
            val creds = AdminAuthManager.parseAdminMessage(message.body)
            if (creds != null) {
                if (AdminAuthManager.authenticate(creds, channelKey)) {
                    if (creds.command.isNotBlank()) {
                        // Auth + command in one message — process command immediately
                        // Route reply back through same channel the message arrived on
                        scope.launch {
                            val authedMessage = message.copy(body = creds.command)
                            handleAdminCommand(authedMessage)
                        }
                    } else {
                        // Auth only — confirm session started
                        val ack = "✅ Admin authenticated. Session active for 30 minutes."
                        notify(ack)
                        replyToSender(message, ack)
                    }
                } else {
                    val fail = "❌ Authentication failed. Check username and password."
                    notify(fail)
                    replyToSender(message, fail)
                }
                return
            }

            // Check if this message is a password confirmation for a pending destructive action
            if (DestructiveActionGuard.hasPending(channelKey)) {
                scope.launch {
                    val result = DestructiveActionGuard.confirmWithPassword(
                        channelKey, message.body.trim()
                    )
                    notify(result)
                    replyToSender(message, result)
                }
                return
            }
        }

        val isAdmin = isAdminChannel || AdminAuthManager.hasActiveSession(channelKey) ||
                      message.sender.lowercase() == AigentikSettings.gmailAddress.lowercase()

        scope.launch {
            // Acquire wake lock for the duration of inference so Samsung doesn't throttle
            // the CPU in background. Auto-times-out after 10 minutes as a safety net.
            val wl = wakeLock
            wl?.acquire(10 * 60 * 1000L)
            try {
                if (isAdmin) handleAdminCommand(message)
                else handlePublicMessage(message)
            } finally {
                if (wl?.isHeld == true) wl.release()
            }
        }
    }

    private suspend fun handleAdminCommand(message: Message) {
        Log.i(TAG, "Admin command: ${message.body}")

        // Check for pending destructive action confirmation — applies to all channels
        // including chat (where the onMessageReceived guard block is skipped)
        val ck = channelKey(message)
        if (DestructiveActionGuard.hasPending(ck)) {
            val result = DestructiveActionGuard.confirmWithPassword(ck, message.body.trim())
            notify(result)
            replyToSender(message, result)
            return
        }

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

                "set_contact_instructions" -> {
                    val target = result.target ?: run {
                        notify("Who should I set instructions for? Try: \"always reply formally to [name]\"")
                        return
                    }
                    val instructions = result.content ?: run {
                        notify("What instructions should I follow for $target?")
                        return
                    }
                    val contact = ContactEngine.findContact(target)
                        ?: ContactEngine.findByRelationship(target)
                    if (contact != null) {
                        ContactEngine.setInstructions(target, instructions, null)
                        notify("✅ Instructions set for ${contact.name ?: target}:\n\"$instructions\"")
                    } else {
                        notify("Contact \"$target\" not found. Try 'find $target' first.")
                    }
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
                    if (!toEmail.contains("@")) {
                        notify("No email address found for \"$target\". Try 'find $target' first.")
                        return
                    }
                    scope.launch {
                        val body = AiEngine.generateEmailReply(
                            contact?.name, toEmail,
                            "Message from $ownerName", content,
                            contact?.relationship, contact?.instructions
                        )
                        val subject = "From $ownerName"
                        val sent = EmailRouter.sendEmailDirect(toEmail, subject, body)
                        val name = contact?.name ?: target
                        notify(if (sent) "✅ Email sent to $name" else "❌ Failed to email $name — check Google sign-in")
                    }
                }

                "check_email", "read_email", "list_email" -> {
                    val running = EmailMonitor.isRunning()
                    val status = if (running) "active ✅" else "stopped ❌"
                    notify("📧 Email monitor: $status\nGmail: ${if (running) "connected" else "disconnected"}\nNew emails appear here automatically.\nChannels:\n${ChannelManager.statusSummary()}")
                }

                "sync_contacts" -> {
                    notify("✅ Contacts: ${ContactEngine.getCount()} loaded")
                }

                // --- Gmail natural language actions ---

                "gmail_count_unread" -> {
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    notify("📊 Counting unread emails...")
                    val breakdown = GmailApiClient.countUnreadBySender(ctx)
                    if (breakdown.isEmpty()) {
                        notify("✅ No unread emails in inbox!")
                    } else {
                        val total = breakdown.values.sum()
                        val top = breakdown.entries
                            .sortedByDescending { it.value }
                            .take(15)
                            .joinToString("\n") { "  • ${it.key}: ${it.value}" }
                        notify("📬 Unread: $total total\n\nTop senders:\n$top")
                    }
                }

                "gmail_list_unread" -> {
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    notify("📧 Fetching unread emails...")
                    val emails = GmailApiClient.listUnreadSummary(ctx, 20)
                    if (emails.isEmpty()) {
                        notify("✅ No unread emails!")
                    } else {
                        val list = emails.mapIndexed { i, e ->
                            "${i + 1}. ${e.fromName.ifEmpty { e.fromEmail }.take(25)}\n   ${e.subject.take(55)}"
                        }.joinToString("\n")
                        notify("📬 ${emails.size} unread:\n\n$list")
                    }
                }

                "gmail_search" -> {
                    val target = result.target ?: run { notify("What should I search for?"); return }
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    val gmailQuery = result.query
                        ?: if (target.contains("@")) "from:$target" else "from:$target OR subject:$target"
                    notify("🔍 Searching: $gmailQuery")
                    val emails = GmailApiClient.searchEmails(ctx, gmailQuery, 10)
                    if (emails.isEmpty()) {
                        notify("No emails found for \"$target\"")
                    } else {
                        val list = emails.mapIndexed { i, e ->
                            "${i + 1}. ${e.fromName.ifEmpty { e.fromEmail }}\n   ${e.subject.take(60)}\n   ${e.date.take(16)}"
                        }.joinToString("\n\n")
                        notify("📧 ${emails.size} result(s) for \"$target\":\n\n$list")
                    }
                }

                "gmail_trash" -> {
                    val target = result.target ?: run { notify("Which email should I move to trash?"); return }
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    val gmailQuery = result.query ?: "from:$target"
                    val emails = GmailApiClient.searchEmails(ctx, gmailQuery, 3)
                    if (emails.isEmpty()) {
                        notify("No emails found matching \"$target\"")
                        return
                    }
                    val preview = emails.first()
                    val sender = preview.fromName.ifEmpty { preview.fromEmail }
                    val summary = "\"${preview.subject.take(70)}\" from $sender"
                    DestructiveActionGuard.storePending(ck, "Move to trash: $summary") {
                        val ok = GmailApiClient.deleteEmail(ctx, preview.gmailId)
                        if (ok) "🗑 Moved to trash:\n$summary"
                        else "❌ Failed to trash email. Check Google sign-in."
                    }
                    val prompt = "⚠️ Move to trash:\n$summary\n\nReply with your admin code to confirm (e.g. \"yes delete [code]\"), or anything else to cancel."
                    notify(prompt)
                    replyToSender(message, prompt)
                }

                "gmail_trash_all" -> {
                    val target = result.target ?: run { notify("Which sender's emails should I delete?"); return }
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    val gmailQuery = result.query ?: "from:$target"
                    notify("🔍 Searching emails from $target...")
                    val emails = GmailApiClient.searchEmails(ctx, gmailQuery, 10)
                    if (emails.isEmpty()) {
                        notify("No emails found from \"$target\"")
                        return
                    }
                    val senderDisplay = emails.first().fromName.ifEmpty { emails.first().fromEmail }
                    val firstSubject = emails.first().subject.take(50)
                    DestructiveActionGuard.storePending(ck, "Move ALL emails from $senderDisplay to trash") {
                        val count = GmailApiClient.deleteAllMatching(ctx, gmailQuery)
                        "🗑 Moved $count email(s) from $senderDisplay to trash."
                    }
                    val prompt = "⚠️ Move to trash: ALL emails from $senderDisplay\n(e.g. \"$firstSubject\" and ${emails.size - 1} more found so far)\n\nReply with your admin code to confirm (e.g. \"yes delete [code]\"), or anything else to cancel."
                    notify(prompt)
                    replyToSender(message, prompt)
                }

                "gmail_mark_read" -> {
                    val target = result.target ?: run { notify("Which emails should I mark as read?"); return }
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    val gmailQuery = result.query ?: "from:$target is:unread"
                    val emails = GmailApiClient.searchEmails(ctx, gmailQuery, 50)
                    if (emails.isEmpty()) {
                        notify("No unread emails found from \"$target\"")
                        return
                    }
                    val ids = emails.map { it.gmailId }
                    val ok = GmailApiClient.batchMarkRead(ctx, ids)
                    val senderDisplay = emails.first().fromName.ifEmpty { emails.first().fromEmail }
                    notify(if (ok) "✅ Marked ${emails.size} email(s) as read from $senderDisplay"
                           else "❌ Failed to mark emails as read. Check Google sign-in.")
                }

                "gmail_mark_read_all" -> {
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    notify("📬 Fetching unread emails...")
                    val gmailQuery = result.query ?: "is:unread in:inbox"
                    val emails = GmailApiClient.searchEmails(ctx, gmailQuery, 100)
                    if (emails.isEmpty()) {
                        notify("✅ No unread emails!")
                        return
                    }
                    val ids = emails.map { it.gmailId }
                    val ok = GmailApiClient.batchMarkRead(ctx, ids)
                    notify(if (ok) "✅ Marked ${emails.size} email(s) as read"
                           else "❌ Failed to mark emails as read. Check Google sign-in.")
                }

                "gmail_mark_spam" -> {
                    val target = result.target ?: run { notify("Which email should I mark as spam?"); return }
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    val gmailQuery = result.query ?: "from:$target"
                    val emails = GmailApiClient.searchEmails(ctx, gmailQuery, 3)
                    if (emails.isEmpty()) {
                        notify("No emails found matching \"$target\"")
                        return
                    }
                    val preview = emails.first()
                    val sender = preview.fromName.ifEmpty { preview.fromEmail }
                    val summary = "\"${preview.subject.take(70)}\" from $sender"
                    DestructiveActionGuard.storePending(ck, "Mark as spam: $summary") {
                        val ok = GmailApiClient.markAsSpam(ctx, preview.gmailId)
                        if (ok) "🚫 Marked as spam:\n$summary"
                        else "❌ Failed to mark as spam. Check Google sign-in."
                    }
                    val prompt = "⚠️ Mark as spam:\n$summary\n\nReply with your admin code to confirm (e.g. \"yes spam [code]\"), or anything else to cancel."
                    notify(prompt)
                    replyToSender(message, prompt)
                }

                "gmail_label" -> {
                    val target = result.target ?: run { notify("Which emails should I label?"); return }
                    val labelName = result.content ?: run { notify("What label should I add?"); return }
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    val gmailQuery = result.query ?: "from:$target"
                    notify("🏷 Finding emails from $target...")
                    val emails = GmailApiClient.searchEmails(ctx, gmailQuery, 20)
                    if (emails.isEmpty()) {
                        notify("No emails found from \"$target\"")
                        return
                    }
                    val labelId = GmailApiClient.getOrCreateLabel(ctx, labelName)
                    if (labelId == null) {
                        notify("❌ Failed to create label \"$labelName\". Check Google sign-in.")
                        return
                    }
                    var labeled = 0
                    emails.forEach { e -> if (GmailApiClient.addLabel(ctx, e.gmailId, labelId)) labeled++ }
                    val senderDisplay = emails.first().fromName.ifEmpty { emails.first().fromEmail }
                    notify("🏷 Labeled $labeled email(s) from $senderDisplay as \"$labelName\"")
                }

                "gmail_unsubscribe" -> {
                    val target = result.target ?: run { notify("Who should I unsubscribe from?"); return }
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    val gmailQuery = result.query ?: "from:$target"
                    notify("🔍 Finding emails from $target...")
                    val emails = GmailApiClient.searchEmails(ctx, gmailQuery, 5)
                    if (emails.isEmpty()) {
                        notify("No emails found from \"$target\". Can't unsubscribe.")
                        return
                    }
                    val senderDisplay = emails.first().fromName.ifEmpty { emails.first().fromEmail }
                    // Look for unsubscribe link in recent emails
                    var unsubLink: String? = null
                    for (e in emails) {
                        val link = GmailApiClient.getUnsubscribeLink(ctx, e.gmailId)
                        if (link != null) { unsubLink = link; break }
                    }
                    DestructiveActionGuard.storePending(ck,
                        "Unsubscribe from $senderDisplay and move all their emails to trash") {
                        val count = GmailApiClient.deleteAllMatching(ctx, gmailQuery)
                        buildString {
                            if (unsubLink != null) append("🔗 Unsubscribe link (open in browser to complete):\n$unsubLink\n\n")
                            else append("ℹ️ No unsubscribe link found in recent emails.\n\n")
                            append("🗑 Moved $count email(s) from $senderDisplay to trash.")
                        }
                    }
                    val prompt = buildString {
                        append("⚠️ Unsubscribe from $senderDisplay?\n")
                        if (unsubLink != null) append("• Unsubscribe link found ✅\n")
                        else append("• No unsubscribe link found (will trash emails only)\n")
                        append("• Move ${emails.size}+ emails to trash\n\n")
                        append("Reply with your admin code to confirm (e.g. \"yes unsubscribe [code]\"), or anything else to cancel.")
                    }
                    notify(prompt)
                    replyToSender(message, prompt)
                }

                "gmail_empty_trash" -> {
                    val ctx = appContext ?: run { notify("❌ Gmail not initialized — restart app"); return }
                    DestructiveActionGuard.storePending(ck,
                        "PERMANENTLY DELETE all emails in Trash — cannot be undone!") {
                        val count = GmailApiClient.emptyTrash(ctx)
                        "🗑 Permanently deleted $count email(s) from trash."
                    }
                    val prompt = "⚠️ PERMANENTLY DELETE all emails in Trash?\n\n🚨 This CANNOT be undone — emails will be gone forever!\n\nReply with your admin code to confirm (e.g. \"yes empty [code]\"), or anything else to cancel."
                    notify(prompt)
                    replyToSender(message, prompt)
                }

                else -> {
                    // Keyword fallback before general AI chat
                    val lower2 = input.lowercase()
                    when {
                        // Gmail keyword shortcuts — run when AI model is offline or returns unknown
                        lower2.contains("email") && !lower2.contains("mark") &&
                            (lower2.contains("unread") || lower2.contains("how many")) -> {
                            val ctx = appContext
                            if (ctx == null) {
                                notify("📧 Gmail not initialized — restart app")
                            } else if (!GoogleAuthManager.isSignedIn(ctx)) {
                                notify("📧 Not signed in to Google — tap Settings → Sign in with Google to use email features.")
                            } else {
                                val breakdown = GmailApiClient.countUnreadBySender(ctx)
                                if (breakdown.isEmpty()) {
                                    notify("✅ No unread emails!")
                                } else {
                                    val total = breakdown.values.sum()
                                    val top = breakdown.entries
                                        .sortedByDescending { it.value }
                                        .take(15)
                                        .joinToString("\n") { "  • ${it.key}: ${it.value}" }
                                    notify("📬 Unread: $total total\n\nTop senders:\n$top")
                                }
                            }
                        }

                        lower2.contains("empty") && lower2.contains("trash") -> {
                            val ctx = appContext
                            if (ctx != null) {
                                DestructiveActionGuard.storePending(ck,
                                    "PERMANENTLY DELETE all emails in Trash — cannot be undone!") {
                                    val count = GmailApiClient.emptyTrash(ctx)
                                    "🗑 Permanently deleted $count email(s) from trash."
                                }
                                val prompt = "⚠️ PERMANENTLY DELETE all emails in Trash?\n\n🚨 This CANNOT be undone!\n\nReply with your admin code to confirm (e.g. \"yes empty [code]\"), or anything else to cancel."
                                notify(prompt)
                                replyToSender(message, prompt)
                            }
                        }

                        // Broad email query catch — list unread for any email-related phrasing
                        // Covers: "check my email", "what emails haven't I read", "any new emails", etc.
                        lower2.contains("email") && !lower2.contains("mark") &&
                            (lower2.contains("check") || lower2.contains("read") ||
                             lower2.contains("inbox") || lower2.contains("haven't") ||
                             lower2.contains("list") || lower2.contains("show") ||
                             lower2.contains("what") || lower2.contains("tell") ||
                             lower2.contains("new") || lower2.contains("any") ||
                             lower2.contains("status")) -> {
                            val ctx = appContext
                            if (ctx == null) {
                                notify("📧 Gmail not initialized — restart app")
                            } else if (!GoogleAuthManager.isSignedIn(ctx)) {
                                notify("📧 Not signed in to Google — tap Settings → Sign in with Google to use email features.")
                            } else {
                                notify("📧 Fetching unread emails...")
                                val emails = GmailApiClient.listUnreadSummary(ctx, 20)
                                if (emails.isEmpty()) {
                                    notify("✅ No unread emails in inbox.")
                                } else {
                                    val list = emails.mapIndexed { i, e ->
                                        "${i+1}. ${e.fromName.ifEmpty { e.fromEmail }.take(25)}\n   ${e.subject.take(55)}"
                                    }.joinToString("\n")
                                    notify("📬 ${emails.size} unread:\n\n$list")
                                }
                            }
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

    // Normalized contact key for conversation history lookups
    // Phone-based messages: use last 10 digits. Email-based: use email lowercase.
    private fun historyKey(message: Message): String {
        return if (message.channel == Message.Channel.EMAIL) {
            message.sender.lowercase()
        } else {
            message.sender.filter { it.isDigit() }.takeLast(10)
        }
    }

    // Load conversation history for this contact+channel.
    // Returns formatted history lines (oldest first) or empty list if:
    //   - No history exists
    //   - Last exchange was >SESSION_GAP_MS ago (new session / topic drift)
    private fun loadHistory(contactKey: String, channel: String): List<String> {
        val dao = historyDao ?: return emptyList()
        try {
            // Check session gap — if last turn was too long ago, treat as new session
            val lastTs = dao.getLastTimestamp(contactKey, channel) ?: return emptyList()
            val sessionGap = System.currentTimeMillis() - lastTs
            if (sessionGap > ConversationHistoryDatabase.SESSION_GAP_MS) {
                Log.d(TAG, "Session gap ${sessionGap/60000}min for $contactKey — using fresh context")
                return emptyList()
            }

            // Load recent turns within the context window
            val turns = dao.getSince(
                contactKey = contactKey,
                channel    = channel,
                sinceMs    = lastTs - ConversationHistoryDatabase.SESSION_GAP_MS,
                limit      = ConversationHistoryDatabase.CONTEXT_WINDOW_TURNS
            )
            return turns.map { turn ->
                val label = if (turn.role == "user") "Them" else agentName
                "$label: ${turn.content.take(200)}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load history for $contactKey: ${e.message}")
            return emptyList()
        }
    }

    // Record a turn in conversation history and trim if needed
    private fun recordHistory(contactKey: String, channel: String, role: String, content: String) {
        val dao = historyDao ?: return
        try {
            dao.insert(ConversationTurn(
                contactKey = contactKey,
                channel    = channel,
                role       = role,
                content    = content.take(1000) // Cap at 1000 chars per turn
            ))
            // Trim to keep DB size bounded
            dao.trimHistory(contactKey, channel, ConversationHistoryDatabase.HISTORY_KEEP_COUNT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record history: ${e.message}")
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
                // Load conversation history for context (empty if new session)
                val contactKey = historyKey(message)
                val channelName = message.channel.name
                val history = loadHistory(contactKey, channelName)

                // Record the incoming message in history
                recordHistory(contactKey, channelName, "user", message.body)

                // Use email-appropriate reply for EMAIL channel (longer, more professional)
                // SMS/NOTIFICATION get the concise SMS generator
                val reply = if (message.channel == Message.Channel.EMAIL) {
                    AiEngine.generateEmailReply(
                        fromName             = message.senderName,
                        fromEmail            = message.sender,
                        subject              = message.subject ?: "Email",
                        body                 = message.body,
                        relationship         = contact.relationship,
                        instructions         = contact.instructions,
                        conversationHistory  = history
                    )
                } else {
                    AiEngine.generateSmsReply(
                        senderName           = message.senderName ?: contact.name,
                        senderPhone          = message.sender,
                        message              = message.body,
                        relationship         = contact.relationship,
                        instructions         = contact.instructions,
                        conversationHistory  = history
                    )
                }

                // Record Aigentik's reply in history (strip signature for cleaner context)
                val replyForHistory = reply.substringBefore("\n\n—").substringBefore("\n\n---").trim()
                recordHistory(contactKey, channelName, "assistant", replyForHistory)

                // Route by channel
                // NOTIFICATION = RCS/SMS via inline reply (no SEND_SMS needed)
                // SMS = direct SmsManager (admin-initiated or fallback)
                // EMAIL = Gmail REST API reply in thread
                when (message.channel) {
                    Message.Channel.NOTIFICATION -> {
                        val sent = com.aigentik.app.adapters.NotificationReplyRouter.sendReply(
                            message.id, reply
                        )
                        if (!sent) {
                            Log.w(TAG, "Inline reply failed — falling back to SmsRouter")
                            SmsRouter.send(message.sender, reply)
                        }
                    }
                    Message.Channel.SMS          -> SmsRouter.send(message.sender, reply)
                    Message.Channel.EMAIL        -> EmailRouter.routeReply(message.sender, reply)
                    else                         -> SmsRouter.send(message.sender, reply)
                }

                val sender = contact.name ?: message.sender
                val inbound = message.body.take(60)
                val outbound = reply.take(80)
                val channelLabel = if (message.channel == Message.Channel.EMAIL) "📧" else "💬"
                notify("$channelLabel Auto-replied to $sender:\nThey said: \"$inbound\"\nSent: \"$outbound\"")
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
