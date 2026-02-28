# Aigentik

**Your personal AI agent — running entirely on your Android device.**

Aigentik is a fully offline, on-device AI assistant for Android. It monitors your SMS, RCS, Google Voice, and Gmail — replying intelligently on your behalf using a locally-hosted language model. No cloud inference. No subscriptions. Your data never leaves your phone.

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Getting Started](#getting-started)
- [Chat Interface](#chat-interface)
- [Command Reference](#command-reference)
- [Auto-Reply Behavior](#auto-reply-behavior)
- [Contact Intelligence](#contact-intelligence)
- [Rule Engine](#rule-engine)
- [Channel Management](#channel-management)
- [Remote Admin](#remote-admin)
- [Architecture](#architecture)
- [Building from Source](#building-from-source)
- [Privacy Policy](#privacy-policy)

---

## Features

| Feature | Description |
|---|---|
| 🤖 **On-Device AI** | Powered by llama.cpp with GGUF models — fully offline inference, no API keys |
| 💬 **SMS / RCS Auto-Reply** | Receives SMS and RCS via Samsung Messages notifications, replies via inline reply — no default messaging app required |
| 📞 **Google Voice** | Detects GVoice-forwarded emails and replies through the correct Gmail thread |
| 📧 **Gmail Management** | Full natural-language Gmail control via OAuth2 REST API |
| 👥 **Contact-Aware** | Syncs Android contacts, applies per-contact reply rules and instructions |
| 🔀 **Smart Routing** | Routes replies back through the channel they arrived on (SMS/RCS inline, email thread) |
| 📡 **Channel Control** | Toggle SMS, GVoice, and Email monitoring on/off via natural language |
| 🔒 **Private by Design** | Zero cloud inference, zero telemetry, zero third-party data sharing |
| ⚡ **Always On** | Foreground service with boot receiver — restarts automatically after reboot |
| 🔋 **Wake Lock Aware** | Acquires partial wake lock during inference to prevent Samsung CPU throttling |

---

## Requirements

- Android 8.0+ (API 26 minimum)
- arm64-v8a device (Snapdragon 8 Gen 2 / Gen 3 recommended for speed)
- 6–8 GB RAM recommended for 4B+ parameter models
- Google account with OAuth2 sign-in
- Google Voice account (optional — for GVoice SMS routing)
- A GGUF model file stored on the device

---

## Getting Started

### 1. Install the APK

Download the latest APK from [Releases](../../releases) and sideload it. Enable "Install from unknown sources" in Settings if prompted.

### 2. Run the Setup Wizard

On first launch, the onboarding wizard collects:

- Your name and chosen agent name
- Your phone number (used to identify your own commands remotely)
- Gmail address for monitoring

### 3. Sign in with Google

Go to **Settings → Sign in with Google**. Aigentik requests these scopes:

- `gmail.modify` — read, trash, label, mark emails
- `gmail.send` — send and reply to emails

OAuth2 tokens are stored securely on-device in SharedPreferences. No app password needed.

### 4. Load an AI Model

Go to **AI Model** and either download a recommended model or browse to a `.gguf` file already on your device.

**Recommended starting point:**
```
Qwen3-1.7B-Q4_K_M.gguf  (~1.1 GB)
```

Larger models produce better replies but require more RAM and take longer to load.

### 5. Grant Permissions

Aigentik requires:

| Permission | Purpose |
|---|---|
| `READ_CONTACTS` | Sync contact names for replies |
| `POST_NOTIFICATIONS` | Show service status notification |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Receive SMS/RCS and Gmail notifications via Samsung Messages |
| `RECEIVE_BOOT_COMPLETED` | Auto-restart after reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Stay alive in background |

> **No SMS permissions required.** Aigentik does not need `SEND_SMS`, `RECEIVE_SMS`, or default messaging app status. All SMS and RCS handling goes through Samsung Messages notifications.

---

## Chat Interface

Open the **Chat** screen to talk to Aigentik directly. The AI Ready indicator in the top bar shows the current model state:

| Indicator | Meaning |
|---|---|
| 🟢 AI Ready | Model loaded — full natural language understanding |
| 🟡 Loading | Model is loading or warming up |
| 🔴 Fallback | Model not loaded — keyword-only command matching |

All messages are stored in a local Room database and persist across sessions.

---

## Command Reference

Aigentik processes commands through multiple layers in order:

1. **Local commands** — resolved instantly in the chat screen without involving MessageEngine
2. **Channel toggle commands** — parsed by ChannelManager via natural language
3. **AI-interpreted commands** — sent to AiEngine.interpretCommand() which uses the LLM to extract intent and return structured JSON
4. **Keyword fallback** — used when the AI model is offline; pattern-matches common phrases directly

---

### Local Commands (instant, no AI required)

These are resolved directly in the chat screen before anything else.

| What you type | What happens |
|---|---|
| `status` / `check status` | Shows agent name, service state, contact count, AI state, Gmail address |
| `pause` | Suspends all auto-replies |
| `resume` | Restores all auto-replies |
| `find [name]` / `look up [name]` | Looks up a contact by name and shows phone and email |
| `clear chat` | Clears all chat history from the local database |
| `help` / `?` | Shows a summary of supported commands |

---

### Gmail Commands (natural language)

These use the AI model to interpret intent and call the Gmail REST API via OAuth2. **Requires Google sign-in.**

Destructive actions (trash, empty trash, spam, unsubscribe) require admin code confirmation before executing.

#### Reading

| Natural language examples | Action |
|---|---|
| `how many unread emails` / `any new emails` | Count unread emails, grouped by sender |
| `check my emails` / `list unread` / `what emails haven't I read` / `check my inbox` / `could you check my emails` | List up to 20 unread emails with sender and subject |
| `show emails from amazon` / `emails from john` | Search emails by sender or keyword |

#### Organizing

| Natural language examples | Action |
|---|---|
| `mark emails from google as read` | Mark matching emails as read |
| `mark all emails as read` | Mark all unread inbox emails as read |
| `label amazon emails as shopping` | Apply a Gmail label (creates label if it doesn't exist) |

#### Cleaning (requires admin confirmation)

| Natural language examples | Action |
|---|---|
| `delete that email from john` | Trash the most recent matching email |
| `delete all emails from newsletters` | Trash all emails matching a sender/query |
| `mark that amazon email as spam` | Move email to spam folder |
| `unsubscribe from newsletters.com` | Find unsubscribe link + trash all their emails |
| `empty trash` | **Permanently delete** all emails in Trash |

---

### Email Sending

| Natural language examples | Action |
|---|---|
| `email John saying the meeting is confirmed` | Send email to John's address on file |

> **SMS/RCS sending is not supported.** Aigentik can only reply to messages it receives — it cannot initiate new SMS or RCS threads. If you ask Aigentik to "text" someone, it will reply: *"Sending new messages is not in my capabilities — I can only reply to messages I receive."*

---

### Contact Commands

| Natural language examples | Action |
|---|---|
| `find Sarah` / `look up Mike` | Find a contact and show all details |
| `what's Dad's number` / `get Sarah's phone` / `Mom's number` | Look up phone number by name or relationship |
| `never reply to spam caller` | Set contact to NEVER auto-reply |
| `always reply to DJ` | Set contact to ALWAYS auto-reply regardless of rules |
| `sync contacts` | Re-sync contacts from Android contacts database |

---

### Channel Commands (natural language)

Enable or disable message channels. Aigentik understands many phrasings for each.

**Trigger words:** `start`, `stop`, `enable`, `disable`, `pause`, `resume`, `turn on`, `turn off`

**Channel words:**
- SMS: `sms`, `text`, `phone`, `direct`
- Google Voice: `voice`, `gvoice`, `google voice`
- Email: `email`, `mail`, `gmail`
- All channels: `all`, `everything`

| Example commands |
|---|
| `stop sms` / `pause text messages` / `turn off direct messages` |
| `start google voice` / `enable gvoice` / `resume voice` |
| `stop email` / `disable gmail` / `pause mail` |
| `stop all` / `pause everything` |
| `start all` / `resume everything` |
| `channels` / `channel status` |

---

### System Commands

| Command | Description |
|---|---|
| `status` | Full system status — AI state, contacts, channel states |
| `channels` / `channel status` | Channel enable/disable state for SMS, GVoice, Email |
| `pause` / `resume` | Pause or resume all auto-replies globally |

---

### Keyword Fallback (AI model offline)

When the model is not loaded (🔴 Fallback mode), Aigentik falls back to direct pattern matching. Most common commands still work:

| Pattern | Action |
|---|---|
| `email [name] [message]` / `send email [name] [message]` | Send email |
| `how many unread emails` / `any new emails` | Count unread |
| `check emails` / `list unread` / `check inbox` | List unread emails |
| `mark all as read` | Mark all inbox as read |
| `mark [sender] as spam` | Mark spam |
| `unsubscribe from [sender]` | Unsubscribe |
| `empty trash` | Empty trash |
| `[name]'s number` / `what's [name]'s phone` | Contact lookup |
| `find [name]` / `look up [name]` | Contact lookup |
| `never reply to [name]` | Set NEVER behavior |
| `always reply to [name]` | Set ALWAYS behavior |
| `pause` / `pause all` | Pause all |
| `resume` / `resume all` | Resume all |

---

## Auto-Reply Behavior

Aigentik automatically replies to incoming messages through these channels:

### SMS and RCS (via NotificationListenerService)
- Detects notifications from Samsung Messages (covers both SMS and RCS in a single path)
- Replies using the notification's `RemoteInput` action — **no `SEND_SMS` permission needed, no default messaging app required**
- If the notification was dismissed before the reply could be sent, the owner is notified instead
- Respects channel state (`ChannelManager.SMS`)

### Gmail (notification-triggered, OAuth2 REST)
- Detects Gmail app notifications via NotificationListenerService
- Fetches new emails using Gmail History API (delta from last known historyId)
- Falls back to listing unread messages if no historyId is stored
- Detects Google Voice forwarded SMS from subject line (`New text message from [Name] ([number])`)
- Detects Google Voice group texts (`New group text message`)
- Strips GVoice footer and HTML from message body before passing to AI
- Replies via Gmail thread (preserving thread context for GVoice routing)

### Reply generation

The AI generates channel-appropriate replies:

| Channel | Style | Max tokens |
|---|---|---|
| SMS / RCS | Concise, conversational. Signed as agent. | 256 |
| Email | Professional, full paragraph. Signed as agent. | 512 |
| Chat | Conversational response or action result | Variable |

Each reply includes a signature identifying Aigentik as the owner's AI assistant and noting that the owner can be reached if "urgent" is included in the message.

---

## Contact Intelligence

ContactEngine syncs your Android contacts database on startup and maintains an on-device `contacts.json` store with Aigentik-specific metadata.

### Per-contact fields

| Field | Description |
|---|---|
| `name` | Display name from Android contacts |
| `phones` | List of phone numbers |
| `emails` | List of email addresses |
| `aliases` | Additional name variants for fuzzy matching |
| `relationship` | Label like `mom`, `boss`, `wife` — used for NL lookups |
| `notes` | Free-form notes |
| `instructions` | Per-contact AI instructions (e.g. "always respond formally") |
| `replyBehavior` | `AUTO` (default), `ALWAYS`, `NEVER`, `REVIEW` |

### Lookup matching

Contacts can be found by phone number, email address, full name, partial name, alias, or relationship label. When a command like "text Mom" is given, Aigentik looks up the contact with `relationship = "mom"` and uses their phone number.

---

## Rule Engine

RuleEngine evaluates incoming messages against a saved rule list before the auto-reply decision.

### Rule conditions

**SMS rules:**

| Condition type | Matches when |
|---|---|
| `from_number` | Sender's phone number matches |
| `message_contains` | Message body contains a keyword |
| `any` | Either number or body matches |

**Email rules:**

| Condition type | Matches when |
|---|---|
| `from` | Sender email contains value |
| `domain` | Sender email domain matches |
| `subject_contains` | Subject contains keyword |
| `body_contains` | Body contains keyword |
| `promotional` | Auto-detected: unsubscribe link, no-reply, newsletter, etc. |
| `any` | From, subject, or body matches |

### Rule actions

| Action | Effect |
|---|---|
| `AUTO_REPLY` | Force auto-reply regardless of other settings |
| `SPAM` | Silently discard — no reply, no notification |
| `REVIEW` | Notify owner but do not auto-reply |
| `DEFAULT` | Use per-contact `replyBehavior` setting |

Rules are evaluated in order, newest first. First match wins. Rules persist to `files/rules/sms_rules.json` and `files/rules/email_rules.json`.

---

## Remote Admin

Aigentik can receive admin commands over SMS or email when you're away from the device. Remote channels require authentication; the chat screen is always trusted.

### Authentication format

```
Admin: [your name]
Password: [your admin password]
[command]
```

Sessions last 30 minutes. After authentication, subsequent messages in the same session do not need credentials.

You can combine auth and command in one message:

```
Admin: Ish
Password: mypassword
check my emails
```

### Destructive action confirmation

Destructive Gmail operations (trash, empty trash, spam, unsubscribe) require a second confirmation message with your admin code:

```
yes delete [code]
yes empty [code]
yes spam [code]
yes unsubscribe [code]
```

Any other reply cancels the pending action.

---

## Architecture

```
Incoming message
       │
       ├─ SMS / RCS ─────────── NotificationAdapter (NotificationListenerService)
       └─ Email / GVoice ─────── EmailMonitor (notification-triggered OAuth2 fetch)
                                       │
                                       ▼
                                MessageEngine
                           (admin commands + public auto-reply)
                            ┌────────┴────────────┐
                            │                     │
                      handleAdminCommand    handlePublicMessage
                            │                     │
                      AiEngine.interpretCommand()  AiEngine.generate()
                      (LLM → structured intent)   (LLM → reply text)
                            │                     │
              ┌─────────────┴─────────┐           │
              │                       │           │
         GmailApiClient          EmailRouter      │
         (REST + OAuth2)               │          │
              │                        └─────────┘
              │                   channel routing:
              │                   NOTIFICATION → NotificationReplyRouter (inline reply)
              │                   EMAIL        → GmailApiClient.reply()
              ▼
         ChatBridge → Room DB → ChatActivity (live Flow)
```

### Key components

| Component | File | Role |
|---|---|---|
| `AigentikService` | `core/AigentikService.kt` | Main foreground service — orchestrates all engines |
| `MessageEngine` | `core/MessageEngine.kt` | Routes commands, calls AI, dispatches replies |
| `AiEngine` | `ai/AiEngine.kt` | Manages LLM lifecycle, generates replies and parses commands |
| `LlamaJNI` | `ai/LlamaJNI.kt` | JNI bridge to llama.cpp C++ inference engine |
| `GmailApiClient` | `email/GmailApiClient.kt` | Gmail REST API — list, read, send, trash, label, search |
| `EmailMonitor` | `email/EmailMonitor.kt` | Notification-triggered Gmail fetch and routing |
| `GmailHistoryClient` | `email/GmailHistoryClient.kt` | Gmail History API delta fetch with on-device historyId storage |
| `EmailRouter` | `email/EmailRouter.kt` | Routes email replies and owner notifications |
| `NotificationAdapter` | `adapters/NotificationAdapter.kt` | NotificationListenerService for SMS/RCS and Gmail triggers |
| `NotificationReplyRouter` | `adapters/NotificationReplyRouter.kt` | Sends inline replies via notification `RemoteInput` PendingIntent |
| `ContactEngine` | `core/ContactEngine.kt` | Android contact sync + Aigentik contact intelligence |
| `ChannelManager` | `core/ChannelManager.kt` | Channel enable/disable state with persistence |
| `RuleEngine` | `core/RuleEngine.kt` | Message filtering rules with persistence |
| `ChatBridge` | `core/ChatBridge.kt` | Posts service-layer responses to Room DB for chat display |
| `GoogleAuthManager` | `auth/GoogleAuthManager.kt` | OAuth2 sign-in, token refresh, session restore |
| `AdminAuthManager` | `auth/AdminAuthManager.kt` | Remote admin authentication with 30-min sessions |
| `DestructiveActionGuard` | `auth/DestructiveActionGuard.kt` | Two-step confirmation for irreversible Gmail actions |

### Native layer

`llama_jni.cpp` bridges Kotlin to llama.cpp:

- **Context:** 8,192 token context window
- **KV cache:** Q8_0 quantized — reduces memory pressure on mobile
- **Threads:** 6 threads for inference
- **Warm-up:** Fires a 4-token prompt after model load to prime JIT and KV cache, reducing first-reply latency
- **Prompt format:** `<|im_start|>system ... <|im_start|>user ... <|im_start|>assistant`

---

## Building from Source

All builds happen via GitHub Actions. There is no local Gradle build (Termux environment only).

### Prerequisites

- GitHub repository with Actions enabled
- GitHub Secrets:
  - `DEBUG_KEYSTORE_BASE64` — base64-encoded fixed debug keystore
  - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

### Build flow

```bash
# Push to main → GitHub Actions triggers build.yml
git push origin main

# Actions will:
# 1. Restore fixed keystore from secret
# 2. Clone llama.cpp and compile for arm64-v8a via CMake
# 3. Run Gradle assembleRelease
# 4. Sign APK with fixed keystore
# 5. Upload APK as artifact
```

### Toolchain

| Tool | Version |
|---|---|
| Gradle | 8.9 |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Java | 17 |
| NDK | r27b (27.2.12479018) |
| CMake | 3.22.1 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |
| ABI | arm64-v8a only |

### Key dependencies

| Library | Purpose |
|---|---|
| llama.cpp (NDK) | On-device LLM inference |
| Room 2.6.1 | Chat history persistence |
| OkHttp 4.12.0 | Gmail REST API calls |
| Gson 2.10.1 | JSON parsing |
| JavaMail 1.6.2 | MIME message building for email send |
| Google Identity / Credentials | OAuth2 sign-in |

---

## Privacy Policy

Aigentik is built on a strict no-cloud policy. The following is enforced in code, not just policy:

| Policy | Implementation |
|---|---|
| **No cloud inference** | llama.cpp runs entirely on-device via NDK. No API calls to any AI service. |
| **No telemetry** | Zero analytics, crash reporting, or usage tracking. |
| **No data transmission** | Message content never leaves the device except to reply via SMS/Gmail (which you explicitly configured). |
| **No cloud relay** | Gmail is accessed directly via OAuth2 REST from the device. No Pub/Sub, no webhook relay, no proxy. |
| **No third-party AI** | No OpenAI, Anthropic, Google Gemini, or any other hosted model is ever contacted. |
| **OAuth2 only** | Google authentication uses OAuth2 with short-lived tokens. No app passwords or stored credentials. |
| **On-device storage** | Contacts, rules, chat history, and settings are stored in Android internal storage (`filesDir`). |

---

## Roadmap

- [x] On-device LLM inference (llama.cpp arm64-v8a)
- [x] SMS / RCS auto-reply via Samsung Messages notification + inline reply
- [x] Google Voice forwarding detection and routing
- [x] Gmail notification-triggered monitoring (no polling)
- [x] Gmail History API delta fetch
- [x] Full Gmail management via natural language
- [x] Channel toggle commands
- [x] OAuth2 Google Sign-In
- [x] Contact intelligence (sync, relationships, per-contact instructions)
- [x] Rule engine (SMS + email filtering)
- [x] Destructive action guard (two-step confirmation)
- [x] Remote admin authentication over SMS/email
- [x] Wake lock for background inference (Samsung CPU throttle prevention)
- [x] Per-contact instruction setting via chat command
- [x] Multi-model hot-swap
- [ ] Scheduled outbound messages
- [ ] Release keystore for Play Store distribution

---

## License

Personal use only. All rights reserved © Ismail Abdullah 2026.

---

*Built on a Samsung Galaxy S24 Ultra · Powered by llama.cpp · Made by Ish*
