# Aigentik Android — Claude Code Context

You are continuing development of Aigentik — a privacy-first Android AI assistant app. Here is the complete project context.

## PROJECT OVERVIEW
- App: Aigentik Android (com.aigentik.app)
- Repo: ~/aigentik-android (local Termux) + GitHub (builds via Actions)
- **Current version: v1.5.1 (versionCode 62)**
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
- SMS/RCS handled exclusively via Samsung Messages notification → NotificationListenerService → inline reply
  - Aigentik does NOT require SEND_SMS permission or default messaging app status
  - Aigentik can only REPLY to received messages — it cannot initiate new SMS/RCS threads
  - If inline reply fails (notification dismissed), owner is notified; no SmsManager fallback
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
- `core/MessageEngine.kt` — AI command processor (v1.6)
- `core/Message.kt` — unified message object (has `subject` field for email)
- `core/ChannelManager.kt` — channel state tracker (SMS, GVOICE, EMAIL)
- `core/RuleEngine.kt` — message filtering rules (persist to files/rules/)
- `core/ContactEngine.kt` — contact database + Android contacts sync
- `core/MessageDeduplicator.kt` — SMS/notification dedup
- `core/PhoneNormalizer.kt` — E.164 phone formatting
- `core/ChatBridge.kt` — service→Room DB bridge (posts assistant responses to chat)
- `core/ContactEntity.kt` — Room entity for Contact (v1.4.6+)
- `core/ContactDao.kt` — DAO for Contact Room operations
- `core/ContactDatabase.kt` — Room database singleton (contact_database)
- `core/RuleEntity.kt` — Room entity for Rule (v1.4.6+)
- `core/RuleDao.kt` — DAO for Rule Room operations
- `core/RuleDatabase.kt` — Room database singleton (rule_database)
- `core/ConversationTurn.kt` — Room entity for per-contact conversation history
- `core/ConversationHistoryDao.kt` — DAO for conversation history operations
- `core/ConversationHistoryDatabase.kt` — Room DB singleton (conversation_history_database)
- `auth/GoogleAuthManager.kt` — OAuth2 manager (v1.8)
- `auth/AdminAuthManager.kt` — remote admin auth (30-min sessions)
- `auth/DestructiveActionGuard.kt` — two-step confirmation for Gmail destructive actions
- `email/GmailApiClient.kt` — Gmail REST API via OkHttp (v1.3)
- `email/EmailMonitor.kt` — notification-triggered Gmail fetch (v4.0, no polling)
- `email/EmailRouter.kt` — routes email replies and owner notifications
- `email/GmailHistoryClient.kt` — Gmail History API + on-device historyId persistence (v1.2)
- `adapters/NotificationAdapter.kt` — NotificationListenerService for SMS/RCS + Gmail triggers (v1.3)
- `adapters/NotificationReplyRouter.kt` — inline reply via PendingIntent (sole outbound SMS/RCS path)
- `ai/AiEngine.kt` — AI inference controller + command parser (v1.3 — null-safe generate() calls)
- `core/AigentikPersona.kt` — structured identity/persona layer (integrated v1.4.10 — intercepts identity queries before LLM)
- `ai/LlamaJNI.kt` — JNI wrapper for llama.cpp
- `ui/MainActivity.kt` — launcher (redirects to Onboarding or Chat)
- `ui/ChatActivity.kt` — chat interface (v1.1 — gear icon, no drawer)
- `ui/SettingsHubActivity.kt` — settings navigation hub (v1.0, replaces drawer)
- `ui/SettingsActivity.kt` — settings + Google sign-in (v2.3 — back button header)
- `ui/OnboardingActivity.kt` — first run setup (v2.1 — auto-advance after sign-in)
- `ui/ModelManagerActivity.kt` — model download/load/switch (lists all downloaded .gguf)
- `ui/RuleManagerActivity.kt` — GUI for managing SMS + email routing rules
- `ui/AiDiagnosticActivity.kt` — native lib status, model info, inference benchmark
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

### v1.5.1 — UI Modernization: Settings Hub, auto-advance onboarding (current, 2026-02-28)
Modern navigation pattern — replaces left drawer with settings gear icon and clean hub screen.

1. **Onboarding auto-advance** — `OnboardingActivity.kt` v2.1: After Google sign-in success,
   `tryAutoAdvance()` checks if owner name and phone are filled. If yes, auto-saves settings
   and chains through permissions → model → chat without requiring "Get Started" tap.
   Button text changed from "Create Account" to "Get Started".
2. **Drawer removed from ChatActivity** — `ChatActivity.kt` v1.1: Removed `DrawerLayout`,
   `NavigationView`, `setupNavigation()`, `updateDrawerHeader()`. Layout flattened to plain
   `LinearLayout`. Hamburger menu replaced with gear icon (`ic_settings.xml`) that opens
   `SettingsHubActivity`. Send button now uses green circle background (`send_button_bg.xml`).
3. **SettingsHubActivity created** — `SettingsHubActivity.kt` v1.0 + layout: Clean list screen
   with 7 clickable rows: Profile & Account, Google Account, Appearance, AI Model, Message
   Rules, AI Diagnostic, About. Dynamic subtitles show current state (owner name, email,
   theme, model info). Back arrow returns to chat. Registered in AndroidManifest.
4. **Back navigation on all sub-activities** — Added header bar with back arrow (`ic_arrow_back.xml`)
   and title to: SettingsActivity, ModelManagerActivity, RuleManagerActivity, AiDiagnosticActivity.
   Each layout wrapped in outer LinearLayout with header bar + divider above ScrollView.
   Each Activity's `onCreate()` wires `btnBack.setOnClickListener { finish() }`.
5. **New drawables** — `ic_arrow_back.xml` (Material back arrow, 24dp), `ic_settings.xml`
   (Material gear, 24dp), `send_button_bg.xml` (green circle for send button).
6. **Cleanup** — `drawer_menu.xml` and `nav_header.xml` emptied (no longer referenced).
- Build: versionCode 62, versionName 1.5.1

### v1.4.10 — Fix chat/NL crash: null-safe generate(), AigentikPersona integrated (2026-02-28)
Three defensive fixes for chat message and natural language instruction crashes:

1. **`AiEngine.kt` v1.3 — Null-safe `llama.generate()` calls**:
   - `nativeGenerate()` is a JNI external function; it can return null (e.g. OOM on C++ side).
   - Previously all three call sites (`generateSmsReply`, `generateEmailReply`, `interpretCommand`)
     called `.trim()` directly on the return value → NullPointerException.
   - `generateSmsReply`/`generateEmailReply` had no inner try/catch, so the NPE propagated
     out of the suspend function and into `handleAdminCommand`'s catch block — user saw
     "⚠️ Error: null" instead of a real response, or the app crashed.
   - Fix: all three sites now use `?.trim() ?: ""` with a `catch (e: Throwable)` wrapping
     the `generate()` call. Null/error → empty string → fallback response used instead.
   - Added `Log.d(TAG, "invoking llama.generate()")` before each call for crash-triage.
2. **`MessageEngine.kt` v1.8 — `catch (e: Throwable)` in `handleAdminCommand`**:
   - Previous `catch (e: Exception)` did not catch `OutOfMemoryError`, `StackOverflowError`,
     or other `Error` subclasses that can occur during LLM inference.
   - When an uncaught `Error` escaped the try block, `chatNotifier` was never called, leaving
     the chat UI stuck in "Thinking..." for 45 seconds then silently timing out.
   - Fix: widened to `catch (e: Throwable)`. Error message always delivered to chat.
   - Added `Log.i` at entry and before `interpretCommand`/`generateSmsReply` calls.
3. **`AigentikPersona` integrated into `handleAdminCommand`**:
   - `AigentikPersona` was defined but never called. Identity/privacy/capability queries
     ("who are you", "what can you do", "is my data safe", etc.) triggered full LLM inference
     unnecessarily — wasted 5-30s and risked inference errors.
   - Fix: `AigentikPersona.respond(input)` checked first in `handleAdminCommand`. If it
     returns non-null, the response is delivered immediately without going through the AI.
   - Also fixed `AiEngine.configure()` to sync `AigentikPersona.name`/`ownerName` so persona
     responses use the actual agent/owner names from settings (were stuck at defaults).
- Build: versionCode 60, versionName 1.4.10

### v1.4.9 — Fix email notification crash + rule manager crash (2026-02-28)
Two runtime crashes fixed:

1. **`MessageEngine.kt` v1.7 — EMAIL channel fix in `handlePublicMessage()`**:
   - Was calling `ContactEngine.findOrCreateByPhone(sender)` for EMAIL channel — email address
     treated as a phone number, creating wrong contact data.
   - Was calling `RuleEngine.checkSms()` for EMAIL channel — email messages checked against
     SMS rules instead of email rules, could miss applicable email rules.
   - Fix: Added EMAIL-specific branches: `findOrCreateByEmail()` for contact lookup and
     `checkEmail(subject, body)` for rule checking.
   - Added `CoroutineExceptionHandler` to MessageEngine scope — prevents any uncaught
     coroutine exception from crashing the app process.
2. **`ContactEngine.kt` — Added `findOrCreateByEmail()`**:
   - New method parallel to `findOrCreateByPhone()`: looks up by email, creates with
     `source = "email"` and adds to emails list if not found.
3. **`RuleEngine.kt` v0.6 — Async DAO writes**:
   - `addSmsRule()`, `addEmailRule()`, `removeRule()` were calling `dao.insert()` /
     `dao.deleteByIdentifier()` synchronously on the calling thread.
   - `RuleManagerActivity` calls these from Button click listeners (main thread) →
     `IllegalStateException: Cannot access database on the main thread`.
   - Fix: Added `ioScope` (Dispatchers.IO + SupervisorJob). All three methods now dispatch
     DAO writes to `ioScope.launch { }`. In-memory list updates remain synchronous (main
     thread safe for immediate UI refresh).
- Build: versionCode 59, versionName 1.4.9

### v1.4.8 — Unified SMS/RCS via notification system (2026-02-28)
Removed all direct SMS sending infrastructure. SMS and RCS now handled identically through
the Samsung Messages notification system. Aigentik no longer requires SEND_SMS permission
or default messaging app status.

1. **Removed `SmsAdapter.kt`** — BroadcastReceiver for `SMS_RECEIVED` is no longer needed.
   All incoming SMS/RCS arrive via Samsung Messages notifications (NotificationAdapter).
2. **Removed `SmsRouter.kt`** — `SmsManager`-based SMS sending removed entirely. All outbound
   SMS/RCS go through `NotificationReplyRouter.sendReply()` (inline reply via PendingIntent).
3. **Removed permissions** — `RECEIVE_SMS`, `SEND_SMS`, `READ_SMS` removed from AndroidManifest.
   Also removed `SmsAdapter` receiver registration.
4. **`MessageEngine.kt` v1.6** — All `SmsRouter.send()` calls replaced:
   - `send_sms` action returns: "Sending new messages is not in my capabilities — I can only reply to messages I receive."
   - `replyToSender()` NOTIFICATION fallback: logs warning only (no SmsManager fallback)
   - `handlePublicMessage()` inline reply failure: notifies owner that notification was dismissed
   - Keyword fallback "text"/"send" branch: same capability message as above
   - `sendReply()` public function: logs warning, no-op
5. **`AigentikService.kt`** — Removed `SmsRouter.init()` call and import.
- Build: versionCode 58, versionName 1.4.8

### v1.4.7 — Gmail forensic audit implementation (2026-02-28)
Implemented all items from aigentik-gmail-review.md (Gmail Forensic Audit):

1. **CRITICAL: OAuth scope resolution** — `GoogleAuthManager.kt` v1.8: `getFreshToken()` now
   catches `UserRecoverableAuthException` specifically (was silently caught by generic Exception).
   Stores resolution Intent in `pendingScopeIntent`. Added `scopeResolutionListener` callback,
   `gmailScopesGranted` flag, `lastTokenError` diagnostic string. This was the root cause
   of Gmail being non-functional — the consent dialog for restricted scopes never appeared.
2. **Gmail scope consent flow** — `SettingsActivity.kt` v2.2: After Google sign-in, automatically
   attempts `getFreshToken()` to trigger Gmail scope consent. Registers `scopeResolutionListener`
   in `onResume()` to auto-launch consent dialog. Handles `RC_GMAIL_CONSENT` activity result.
   Added "Grant Gmail Permissions" button (orange, visible when scopes pending).
   Added `tvGmailScopeStatus` showing scope health. Calls `ConnectionWatchdog.checkNow()`
   after sign-in and scope grant for immediate notification dismissal.
3. **GmailApiClient error propagation** — `GmailApiClient.kt` v1.3: Added `lastError` volatile
   field with HTTP-code-specific messages (401 auth expired, 403 access denied, 429 rate limit).
   `get()`/`post()`/`postRaw()` all set `lastError`. Added `checkTokenHealth()` method calling
   `users.getProfile` to verify token validity without side effects.
4. **GmailHistoryClient silent failure fix** — `GmailHistoryClient.kt` v1.2: `primeHistoryId()`
   now returns `PrimeResult` enum (ALREADY_STORED, PRIMED_FROM_API, NO_TOKEN, API_ERROR,
   NETWORK_ERROR). Added `lastPrimeResult` for diagnostics. Specific error logging for 401/403.
   `AigentikService.kt` updated to log specific prime result.
5. **MessageEngine Gmail error messages** — `MessageEngine.kt` v1.5: Added `requireGmailReady()`
   helper checking context, sign-in, and scope grant status. All 11 Gmail NL actions and keyword
   fallback sections now use this helper. Users see specific messages: "Not signed in",
   "Gmail permissions needed", instead of generic "Gmail not initialized" or silence.
6. **Gmail diagnostic panel** — `AiDiagnosticActivity.kt` v1.1 + layout: Added Gmail Health
   section showing sign-in status, scope grant status, historyId prime status. "Check Gmail
   Token" button calls `checkTokenHealth()` to verify token reaches Gmail API.
7. **ConnectionWatchdog scope monitoring** — `ConnectionWatchdog.kt` v2.2: Now monitors sign-in
   + scope grant status. Separate notifications: "Sign-in Required" (2001) vs "Gmail Permissions
   Needed" (2002). Added `checkNow()` for immediate check after SettingsActivity actions.
8. **Settings layout** — `activity_settings.xml`: Added `tvGmailScopeStatus` and
   `btnGrantGmailPerms` between sign-out and admin password sections.

Items NOT implemented (documented in gmail-review-implementation-progress.md):
- Credentials Manager API migration — future work (current GoogleSignIn works with fix)
- WorkManager deep sync — CONFLICTS with no-polling policy; compliant alternative proposed
- SHA-1 Registry — already fixed via GitHub Actions keystore (DEBUG_KEYSTORE_BASE64)

- Build: versionCode 57, versionName 1.4.7

### v1.4.6 — Full review implementation (2026-02-28)
Implemented all items from aigentik-android-review.md (Gemini CLI review):

1. **Temperature + top-p sampling** — `llama_jni.cpp` and `LlamaJNI.kt`: SMS/email replies
   use temperature=0.7/topP=0.9 for natural varied output; interpretCommand() uses
   temperature=0.0 (greedy) for reliable JSON action parsing.
2. **ConnectionWatchdog notification** — posts high-priority "Sign-in Required" notification
   when OAuth token expires; taps to SettingsActivity; auto-dismisses on recovery.
3. **Room migration: ContactEngine** — `contacts.json` → `ContactDatabase` (Room/SQLite).
   One-time migration on first launch. ContactEntity + ContactDao + ContactDatabase added.
4. **Room migration: RuleEngine** — `sms_rules.json` + `email_rules.json` → `RuleDatabase`.
   One-time migration. RuleEntity + RuleDao + RuleDatabase added.
5. **Contextual memory** — Room-backed per-contact conversation history for SMS + email channels.
   2-hour session gap resets context (topic drift prevention). Trimmed to 20 turns/contact.
   ConversationTurn + ConversationHistoryDao + ConversationHistoryDatabase added.
6. **Rule Manager UI** — new `RuleManagerActivity` + layout. Add/delete SMS and email rules
   with condition type and action selectors. Accessible from Settings.
7. **AI Diagnostic screen** — new `AiDiagnosticActivity` + layout. Shows native lib status,
   model info, runs benchmark, shows tokens/sec and sample output.
8. **Downloaded Models list** — `ModelManagerActivity` lists all .gguf files in modelsDir
   with Load button per non-active model. Allows switching models without re-downloading.
9. **Contact instructions via chat** — "always reply formally to John" now wired via
   `set_contact_instructions` action through AiEngine → MessageEngine → ContactEngine.setInstructions().
10. **Backup exclusion rules** — `backup_rules.xml` + `data_extraction_rules.xml` prevent
    contacts.json, rules/, models/, and all databases from being backed up to Google/cloud.
11. **DestructiveActionGuard improvements** — common word exclusion list prevents accidental
    confirmation when common English words appear in confirmation message.
12. **MessageDeduplicator improvement** — fingerprint body window 50 → 100 chars.
13. **Native lib error state** — `LlamaJNI.isNativeLibLoaded()` + `AiEngine.getStateLabel()`
    now shows "Native lib error" vs "Not loaded" for better diagnostics.
14. **Version bump**: versionCode 56, versionName 1.4.6

- Build: versionCode 56, versionName 1.4.6

### v1.4.5 — Fix chat crash (2026-02-27)
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

## NAVIGATION ARCHITECTURE (v1.5.1)

```
Chat (home screen)
  └─ Gear icon (top-right) → SettingsHubActivity
       ├─ Profile & Account → SettingsActivity
       ├─ Google Account → SettingsActivity
       ├─ Appearance → SettingsActivity
       ├─ AI Model → ModelManagerActivity
       ├─ Message Rules → RuleManagerActivity
       ├─ AI Diagnostic → AiDiagnosticActivity
       └─ About → AlertDialog
```

All sub-activities have a back arrow header bar. No drawer, no bottom nav.

---

## CHATACTIVITY ARCHITECTURE (v1.1 — current)

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

## TESTING PLAN (v1.4.8)

### Chat functionality
1. Open Chat — should not crash on send
2. Type `status` → should show agent state, contact count, AI state without crash
3. Type `find [contact name]` → should look up contact
4. Type `help` → should show command list instantly
5. Type `check emails` → if signed in: shows unread list. If not: "Not signed in to Google" message
6. Type `how many unread emails` → count by sender
7. Type `text Mom I'll be late` → should reply "Sending new messages is not in my capabilities — I can only reply to messages I receive."
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

1. **Google Sign-In + Gmail scopes** — v1.4.7 fixed the UserRecoverableAuthException silent catch
   that prevented Gmail scope consent. Needs end-to-end test: sign in → consent dialog appears →
   grant → Gmail API works. Check logcat for `GoogleAuthManager: Token obtained`.
2. **SMS inline reply window** — Inline replies only work while Samsung Messages notification is
   active. If the notification was dismissed, the reply fails and owner is notified. This is by
   design (v1.4.8). No fallback exists. If reply-after-dismiss is a problem, consider adding
   a "missed reply" log in AiDiagnosticActivity.
3. **chatNotifier race** — if AigentikService starts after ChatActivity sets chatNotifier,
   service overwrites with the same lambda. This is fine but worth monitoring if behavior diverges.
4. **ContactEngine double-init** — ContactEngine.init() called from both AigentikService and
   ChatActivity. Second call re-syncs Android contacts (and re-runs migrateFromJsonIfNeeded,
   which is a no-op after first migration). Harmless but slightly wasteful. Low priority.
5. **MessageEngine.appContext null if service never ran** — Gmail ops now show specific error
   ("Gmail not initialized — restart app") instead of silence. Could still improve by adding
   MessageEngine.initContext(ctx) from ChatActivity.
6. **Conversation history in chat** — ConversationHistoryDatabase only populated for public
   messages (SMS/email). Chat (admin) messages not stored in history.
7. **Credentials Manager migration** — GoogleSignIn is legacy but works. Migration to
   Credentials Manager API planned for future version (requires minSdk 28).
8. **Gmail scope status persistence** — `gmailScopesGranted` is in-memory only. If the app
   restarts, scopes must be re-verified by calling `getFreshToken()`. This is fine since
   the service calls it during historyId prime, but worth noting.

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
