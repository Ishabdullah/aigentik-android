# Aigentik Android — Claude Code Context

You are continuing development of Aigentik — a privacy-first Android AI assistant app. Here is the complete project context.

## PROJECT OVERVIEW
- App: Aigentik Android (com.aigentik.app)
- Repo: ~/aigentik-android (local Termux) + GitHub (builds via Actions)
- **Current version: v1.4.5 (versionCode 55)**
- Developer environment: Samsung S24 Ultra, Termux only — NO Android Studio, NO local Gradle builds
- All builds happen via GitHub Actions → APK downloaded and sideloaded

---

## PRIVACY POLICY — STRICT ENFORCEMENT REQUIRED

These policies are non-negotiable. Claude MUST refuse any implementation that violates them.

### 1. Local Processing Only
- All AI inference runs on-device via llama.cpp (C++ NDK)
- No cloud AI APIs (OpenAI, Gemini, Anthropic, etc.) — ever
- Email, SMS, and contact data never leave the device for processing

### 2. No Cloud Dependencies
- **Forbidden**: Google Cloud Pub/Sub, Firebase Realtime DB, Firestore, Cloud Functions
- **Forbidden**: Any cloud relay, message queue, or webhook for triggering app actions
- **Forbidden**: Third-party analytics, crash reporting (Crashlytics, Sentry, etc.)
- **Allowed**: Direct OAuth2 REST API calls from device to Google (Gmail, Contacts APIs)
- **Allowed**: google-services.json (OAuth config only — no Firebase SDK)

### 3. Data Privacy
- All message content, contact data, and AI responses stay on-device
- No logging to external services, no telemetry, no usage analytics
- SharedPreferences and Room DB are the only persistence layers

### 4. OAuth2 / API Access Rules
- OAuth2 is used to authenticate with Google APIs (Gmail, Contacts) only
- All API calls are direct device→Google REST calls (OkHttp)
- Scopes: gmail.modify, gmail.send (contacts.readonly was in scope list but People API not used yet)
- No scope for cloud infra (Pub/Sub was added in v1.4.0 and removed in v1.4.1 — never add it back)

### 5. Notification Handling
- Gmail trigger: Gmail app notification → NotificationListenerService → OAuth2 REST fetch
- SMS/RCS trigger: NotificationListenerService → inline reply
- No polling loops, no cloud relay — same pattern for all channels

### 6. No Telemetry or Analytics
- No Firebase Analytics, Google Analytics, Mixpanel, or similar
- No remote error reporting services

### 7. Strict Enforcement
- Claude must refuse cloud workflows unless the developer explicitly approves an exception
- If Claude implements something that violates these policies, it must immediately flag it
- When in doubt, choose the on-device approach

---

## ARCHITECTURE

- On-device AI inference via llama.cpp (C++ NDK, arm64-v8a)
- Model: GGUF format stored on device /sdcard or internal storage
- SMS/RCS auto-reply via NotificationListenerService (Samsung Messages inline reply)
- Gmail monitoring via Gmail app notification → NotificationAdapter → EmailMonitor → Gmail REST API (OAuth2)
- Google Voice SMS forwarded as Gmail → parsed (subject line parsing) and replied via email thread
- Gmail natural language interface: chat/SMS commands → AiEngine.interpretCommand() → Gmail API actions
- No cloud backend, no Firebase SDK, no polling loops — everything on-device
- AI runs fully offline (llama.cpp JNI bridge)
- historyId cursor stored in SharedPreferences — primed from Gmail profile API on start, advanced per notification
- **Chat routes through MessageEngine** — ChatActivity.sendMessage() → MessageEngine.onMessageReceived(CHAT) → response via chatNotifier → ChatBridge.post() → Room DB → collectLatest Flow

---

## KEY FILES

- `core/AigentikService.kt` — main foreground service (v1.5)
- `core/AigentikSettings.kt` — SharedPreferences wrapper
- `core/MessageEngine.kt` — AI command processor (v1.3)
- `core/Message.kt` — unified message object (has `subject` field for email)
- `core/ChannelManager.kt` — channel state tracker (SMS, GVOICE, EMAIL)
- `core/RuleEngine.kt` — message filtering rules (persist to files/rules/)
- `core/ContactEngine.kt` — contact database + Android contacts sync
- `core/MessageDeduplicator.kt` — SMS/notification dedup
- `core/PhoneNormalizer.kt` — E.164 phone formatting
- `core/ChatBridge.kt` — service→Room DB bridge (posts assistant responses to chat)
- `auth/GoogleAuthManager.kt` — OAuth2 manager (v1.7)
- `auth/AdminAuthManager.kt` — remote admin auth (30-min sessions)
- `auth/DestructiveActionGuard.kt` — two-step confirmation for Gmail destructive actions
- `email/GmailApiClient.kt` — Gmail REST API via OkHttp (v1.2)
- `email/EmailMonitor.kt` — notification-triggered Gmail fetch (v4.0, no polling)
- `email/EmailRouter.kt` — routes email replies and owner notifications
- `email/GmailHistoryClient.kt` — Gmail History API + on-device historyId persistence (v1.1)
- `adapters/NotificationAdapter.kt` — NotificationListenerService for RCS + Gmail triggers (v1.3)
- `adapters/SmsAdapter.kt` — SMS BroadcastReceiver
- `adapters/NotificationReplyRouter.kt` — inline reply via PendingIntent
- `ai/AiEngine.kt` — AI inference controller + command parser (CommandResult has `query` field)
- `ai/LlamaJNI.kt` — JNI wrapper for llama.cpp
- `ui/MainActivity.kt` — dashboard
- `ui/ChatActivity.kt` — chat interface (v1.0 — routes through MessageEngine, not standalone LLM)
- `ui/SettingsActivity.kt` — settings + Google sign-in
- `ui/OnboardingActivity.kt` — first run setup
- `ui/ModelManagerActivity.kt` — model download/load
- `chat/ChatDatabase.kt` — Room database singleton
- `chat/ChatMessage.kt` — Room entity
- `chat/ChatDao.kt` — Room DAO
- `system/BootReceiver.kt` — auto-start after reboot
- `system/ConnectionWatchdog.kt` — OAuth session monitor
- `system/BatteryOptimizationHelper.kt` — battery settings
- `cpp/llama_jni.cpp` — JNI bridge (Q8_0 KV, 8k ctx, 6 threads, warm-up on load)
- `cpp/CMakeLists.txt` — CMake build for llama.cpp
- `google-services.json` — Firebase/Google OAuth config (OAuth only, no Firebase SDK)
- `.github/workflows/build.yml` — CI build pipeline

---

## CHANGE LOG

### v1.4.5 — Fix chat crash (current, 2026-02-27)
Fixed three bugs in ChatActivity that caused crashes and silent response failures when using chat:

1. **`CoroutineScope` without `SupervisorJob`** — Plain `Job()` means any exception in one child
   coroutine (sendMessage or observeMessages) cascades and cancels the whole scope → app crash.
   Fixed: `CoroutineScope(Dispatchers.Main + SupervisorJob())`.
2. **`MessageEngine.chatNotifier` never set by ChatActivity** — chatNotifier is set by
   AigentikService on startup. If service wasn't running yet, or hadn't initialized, notify()
   calls were silently dropped. Chat appeared to work but responses never appeared.
   Fixed: ChatActivity.onCreate() now sets `MessageEngine.chatNotifier = { ChatBridge.post(it) }`.
3. **`ContactEngine.init()` never called from ChatActivity** — Local commands like "status"
   and "find [name]" call ContactEngine methods. Without init(), these threw NPE, which (before
   SupervisorJob fix) crashed the whole scope.
   Fixed: `ContactEngine.init(applicationContext)` in onCreate().
4. **No error handling in sendMessage()** — Added try-catch around the full sendMessage body.
   Errors now show as an assistant message in chat instead of silently crashing.
5. **scope.cancel() missing from onDestroy()** — Scope kept running after activity destroyed.
   Fixed: scope.cancel() added to onDestroy().

- Build: versionCode 55, versionName 1.4.5

### v1.4.4 — Chat routes through MessageEngine (2026-02-27, superseded by v1.4.5 same session)
Complete rewrite of ChatActivity. Root cause: old ChatActivity had generateResponse() that called
LlamaJNI.generate() directly with a generic "you are a personal AI assistant" prompt, bypassing
MessageEngine and all Gmail/SMS/contacts tools entirely. No crash fix was included in v1.4.4;
v1.4.5 added the crash fixes on top.
- Build: versionCode 54, versionName 1.4.4

### v1.4.3 — Email NL keyword fixes (2026-02-27)
Fixed MessageEngine and AiEngine so email queries from chat actually show emails:
- **MessageEngine.kt** — keyword fallback `else` block was matching email queries but only
  returning "Email monitor: active ✅" status instead of actual emails. Replaced with
  `GmailApiClient.listUnreadSummary(ctx, 20)`. Added `GoogleAuthManager.isSignedIn(ctx)` check
  before any Gmail API call — shows clear "Not signed in" error instead of empty result.
  Added "how many" as an unread count trigger keyword.
- **AiEngine.kt** — added more interpretCommand() examples for natural phrasing:
  "any new emails", "what emails haven't I read", "could you check my emails", "check my inbox".
  Added corresponding parseSimpleCommand() fallback patterns.
- Build: versionCode 53, versionName 1.4.3

### v1.4.2 — Google Voice improvements (2026-02-27)
Applied GVoice parsing improvements from Termux beta version to the Android app:
- **GmailApiClient.kt v1.2** — added group text detection (`New group text message` subject prefix).
  Strip GVoice footer ("To respond to this text message, reply to this email...") before passing
  body to AI — was polluting the AI context. Strip HTML tags from email body. For group texts,
  use fromEmail as the routing identifier since phone number is not in the subject.
- Build: versionCode 52, versionName 1.4.2

### v1.4.1 — Gmail notification-listener trigger (2026-02-27)
REVERT of Pub/Sub approach added in v1.4.0. Replaced with on-device notification pattern.
- **GmailHistoryClient.kt v1.1** — added on-device historyId persistence (SharedPreferences),
  added primeHistoryId() using Gmail profile API (GET .../me/profile → historyId field)
- **EmailMonitor.kt v4.0** — complete rewrite: notification-triggered only, no polling loop.
  Uses GmailHistoryClient when historyId stored (delta fetch), falls back to listUnread().
  isProcessing flag prevents overlapping fetches from rapid notification bursts.
- **AigentikService.kt v1.5** — removed GmailPushManager import, replaced startPushPolling()
  with GmailHistoryClient.primeHistoryId() in a non-blocking coroutine on start.
- **GoogleAuthManager.kt v1.7** — removed pubsub scope from GMAIL_SCOPES.
- **GmailPushManager.kt** — DELETED (Pub/Sub cloud relay violates no-cloud policy).
- Build: versionCode 51, versionName 1.4.1

### v1.4.0 — Gmail NL interface + auto-reply (2026-02-27, partially reverted)
Gmail NL interface (KEPT), Pub/Sub trigger (REVERTED in v1.4.1).
- **GmailApiClient.kt** — added: getEmailMetadata(), listUnreadSummary(), countUnreadBySender(),
  searchEmailIds(), batchMarkRead(), emptyTrash(), getOrCreateLabel(), addLabel(), getUnsubscribeLink()
- **AiEngine.kt** — added query field to CommandResult; 11 new Gmail actions in interpretCommand();
  expanded parseSimpleCommand() with Gmail patterns
- **MessageEngine.kt v1.3** — added appContext, channelKey() helper; 11 Gmail action handlers;
  EMAIL channel now uses generateEmailReply()
- **DestructiveActionGuard.kt** — updated confirmWithPassword() to extract admin code word-by-word
- **Message.kt** — added subject field
- Build: versionCode 50, versionName 1.4.0

### v1.3.8 — App icon update (2026-02-26)
Updated launcher icon to Aigentik brand image, using mipmap launcher icons.
- Build: versionCode ~48, versionName 1.3.8

### v1.3.3 — Full codebase audit and fixes (2026-02-26)
CRITICAL: Service startup gate removed, SEND_SMS permission added, ChatBridge init in service.
HIGH: send_email command wired, Gmail notifications routed correctly.
CLEANUP: version bump, SHA-1 corrected, constraintlayout 2.1.4, gmailAppPassword deprecated.

---

## CHATACTIVITY ARCHITECTURE (v1.0 — current)

ChatActivity does NOT have its own AI pipeline. All messages route through MessageEngine.

```
User types message → sendMessage()
    │
    ├─ resolveLocalCommand() — fast local resolution (no service needed)
    │     status / pause / resume / find / clear chat / help
    │     returns String or null
    │
    └─ (if null) → MessageEngine.onMessageReceived(Message.Channel.CHAT)
                         │
                         ├─ handleAdminCommand() (CHAT is always trusted)
                         │     AiEngine.interpretCommand() → action dispatch
                         │     Gmail API / SMS / contacts / channel toggles
                         │
                         └─ notify(response)
                               │
                               chatNotifier → ChatBridge.post() → Room DB
                                                                      │
                                                              collectLatest Flow
                                                                      │
                                                             observeMessages() → UI re-render
```

**Key initialization in ChatActivity.onCreate():**
1. `ChatBridge.init(db)` — so ChatBridge can post to Room
2. `MessageEngine.chatNotifier = { ChatBridge.post(it) }` — wire response path
3. `ContactEngine.init(applicationContext)` — needed for local status/find commands

**Safety timeout:** 45-second timer in sendMessage() re-enables UI if chatNotifier never fires
(e.g. Gmail API fails, service not running). This prevents permanent stuck state.

---

## GMAIL EMAIL TRIGGER FLOW (v1.4.1 — on-device only)

```
Email arrives in Gmail inbox
        ↓
Gmail app shows notification (Android system)
        ↓
NotificationAdapter.onNotificationPosted()
  detects package = com.google.android.gm
        ↓
EmailMonitor.onGmailNotification()
        ↓
 [historyId stored?]
   YES → GmailHistoryClient.getNewInboxMessageIds(historyId)
            → Gmail History API: GET /me/history?startHistoryId=...
            → Returns only NEW INBOX+UNREAD message IDs
            → Advance historyId cursor
   NO  → GmailApiClient.listUnread(maxResults=10)
            → Gmail Messages API: GET /me/messages?q=is:unread in:inbox
        ↓
GmailApiClient.getFullEmail(msgId) for each new ID
        ↓
Skip if: already read, or fromEmail == ownEmail (loop prevention)
        ↓
EmailMonitor.processEmail()
  GVoice SMS? → parse GVoiceMessage → MessageEngine.onMessageReceived(EMAIL)
  GVoice group? → New group text message subject → parse group sender → same path
  Regular email → buildEmailMessage() → MessageEngine.onMessageReceived(EMAIL)
        ↓
MessageEngine → AiEngine.generateEmailReply() → GmailApiClient.reply()
        ↓
GmailApiClient.markAsRead()
```

---

## GMAIL NL INTERFACE — SUPPORTED ACTIONS

All commands require admin auth (or come from chat screen, which is always trusted).
Destructive actions require admin code confirmation before executing.

| Action | Natural language examples | Confirm required |
|---|---|---|
| gmail_count_unread | "how many unread emails", "any new emails" | No |
| gmail_list_unread | "check my emails", "list unread", "check inbox" | No |
| gmail_search | "find emails from amazon", "show emails from John" | No |
| gmail_trash | "delete email from John" | Yes — admin code |
| gmail_trash_all | "delete all emails from promo@co.com" | Yes — admin code |
| gmail_mark_read | "mark emails from google as read" | No |
| gmail_mark_read_all | "mark all emails as read" | No |
| gmail_mark_spam | "mark that amazon email as spam" | Yes — admin code |
| gmail_label | "label amazon emails as shopping" | No |
| gmail_unsubscribe | "unsubscribe from newsletter" | Yes — admin code |
| gmail_empty_trash | "empty trash" | Yes — admin code (permanent!) |

Confirmation UX: "Reply with your admin code to confirm (e.g. 'yes delete [code]')".
User replies naturally ("yes delete 1984") — code extracted word-by-word by DestructiveActionGuard.

---

## GOOGLE SIGN-IN CONFIG

Fixed keystore SHA-1 resolves ApiException code 10.

### Root cause chain:
1. GitHub Actions generates a NEW random debug keystore every build
2. Each build has different SHA-1 → never matches what's registered in Google Cloud
3. Fixed by storing base64-encoded keystore in GitHub secret DEBUG_KEYSTORE_BASE64
4. build.yml decodes and installs it before every build

### Config:
- Fixed keystore SHA-1: `E6:76:61:28:5F:6C:27:9D:14:34:C5:66:2C:1E:17:4E:32:67:9D:80`
- certificate_hash: `e67661285f6c279d1434c5662c1e174e32679d80`
- Android OAuth client: `630924077353-gmv67c8n0lad1q5u9q6v8t41sf79l8uv.apps.googleusercontent.com`
- Web OAuth client: `630924077353-oopagdmapkve24ehb6pjppeph2bf292c.apps.googleusercontent.com`

---

## GOOGLE CLOUD / FIREBASE CONFIG

- Project ID: `aigentik-android`
- Project Number: `630924077353`
- Firebase app ID: `1:630924077353:android:5c58e1d30f7983771f2f38`
- API Key: `AIzaSyAXRjCb1kN40hf43z359ZcFEAz4Sdw8arA`
- OAuth consent screen: External, Testing mode
- Test user: `ismail.t.abdullah@gmail.com`
- APIs enabled: Gmail API, People API, Identity Toolkit API
- **Pub/Sub API: NOT needed — removed from architecture, do not re-enable**

---

## GMAIL OAUTH SCOPES

- `https://www.googleapis.com/auth/gmail.modify` — read, trash, label, spam, batchModify
- `https://www.googleapis.com/auth/gmail.send` — compose and send
- NOTE: `contacts.readonly` was in earlier scope lists but People API not actively used
- NOTE: pubsub scope was added in v1.4.0 and removed in v1.4.1 — do not add it back

---

## BUILD SYSTEM

- Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Java 17
- NDK r27b (27.2.12479018), CMake 3.22.1
- llama.cpp cloned at build time (cached), compiled via CMake for arm64-v8a
- google-services plugin 4.4.4 applied (project + app level)
- NO Firebase SDK dependencies — only google-services plugin for OAuth config
- JavaMail 1.6.2 kept for MIME message building in GmailApiClient (send via REST)
- ConstraintLayout 2.1.4, OkHttp 4.12.0, Gson 2.10.1, Room 2.6.1

---

## TERMUX LIMITATIONS

- No sudo, no apt install of system packages without pkg
- Python available, pip needs --break-system-packages flag
- Pillow installed for icon generation
- adb available via android-tools package
- Cannot run Gradle locally (no memory/CPU for it on phone)
- All file paths use /data/data/com.termux/files/home/ not /root/ or /home/
- Internal storage accessible at /sdcard/ or /storage/emulated/0/
- **Bash tool (Claude Code) fails with EACCES on /tmp — provide all commands for user to run manually in Termux**

---

## TESTING PLAN (v1.4.5)

### Chat functionality (previously broken — now fixed in v1.4.4 + v1.4.5)
1. Open Chat — should not crash on send
2. Type `status` → should show agent state, contact count, AI state without crash
3. Type `find [contact name]` → should look up contact
4. Type `help` → should show command list instantly
5. Type `check emails` → if signed in: shows unread list. If not: "Not signed in to Google" message
6. Type `how many unread emails` → count by sender
7. Type `text Mom I'll be late` → sends SMS
8. Type any general message (e.g. "hello") → AI reply if model loaded, fallback hint if not

### Gmail trigger (notification-driven)
9. Send email to account → Gmail notification appears → Aigentik fetches and replies
10. Second email: historyId should advance, only new message fetched (check logcat)
11. Google Voice SMS → forward to Gmail → Aigentik detects GVoice subject → replies via thread
12. GVoice group text → `New group text message` subject → parsed correctly

### Auth
13. Settings → Sign in with Google → should succeed (fixed keystore, correct SHA-1)
14. Remote admin: send SMS with `Admin: Ish\nPassword: [pw]\ncheck emails` format

### Destructive actions (require admin code)
15. "delete email from [sender]" → confirm prompt → reply "yes delete [code]" → trashed
16. "empty trash" → confirm prompt → reply "yes empty [code]" → permanently deleted

---

## KNOWN ISSUES / NEXT TASKS

1. **Google Sign-In** — needs end-to-end test with v1.4.5 APK to confirm ApiException code 10 is gone
2. **chatNotifier race** — if AigentikService starts after ChatActivity sets chatNotifier,
   service overwrites with the same lambda. This is fine but worth monitoring if behavior diverges.
3. **ContactEngine double-init** — ContactEngine.init() called from both AigentikService and
   ChatActivity. Second call re-syncs Android contacts, which is harmless but slightly wasteful.
   Low priority.
4. **MessageEngine.appContext null if service never ran** — Gmail ops show "not initialized" in chat
   if AigentikService hasn't started yet. Could add MessageEngine.initContext(ctx) for ChatActivity
   to call, so Gmail works in chat even before first service start.
5. **Per-contact instruction setting via chat** — "always reply formally to John" not yet wired
   as a natural language command (ContactEngine.setInstructions exists, just not hooked up in NL)
6. **Multi-model hot-swap** — only one model can be loaded at a time

---

## DEVELOPER PREFERENCES

- Production-ready code only
- Always ask clarifying questions before writing code
- Explain what you will do and get approval before making changes
- Keep CLAUDE.md updated after every change session
- Note areas of concern in code comments
- No Firebase SDK — privacy-first, on-device only
- No cloud backends, no telemetry, no Pub/Sub (see Privacy Policy above)
- Always give complete copy-paste commands for Termux
- Version bump with every commit
- All Termux paths use /data/data/com.termux/files/home/
