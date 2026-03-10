# Aigentik Android — Claude Code Context

You are continuing development of Aigentik — a privacy-first Android AI assistant app. Here is the complete project context.

## PROJECT OVERVIEW
- App: Aigentik Android (com.aigentik.app)
- Repo: ~/aigentik-android (local Termux) + GitHub (builds via Actions)
- **Current version: v1.7.0 (versionCode 72)**
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
- `email/GmailApiClient.kt` — Gmail REST API via OkHttp (v1.5 — recursive MIME extraction)
- `email/EmailMonitor.kt` — notification-triggered Gmail fetch (v4.2 — CoroutineExceptionHandler, catch Throwable)
- `email/EmailRouter.kt` — routes email replies (v2.2 — ConcurrentHashMap, sender-keyed context)
- `email/GmailHistoryClient.kt` — Gmail History API + on-device historyId persistence (v1.2)
- `adapters/NotificationAdapter.kt` — NotificationListenerService for SMS/RCS + Gmail triggers (v1.3)
- `adapters/NotificationReplyRouter.kt` — inline reply via PendingIntent (sole outbound SMS/RCS path)
- `ai/AiEngine.kt` — AI inference controller + command parser (v1.6 — generateChatReply, think stripping)
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

### v1.7.0 — Stability: crash fixes from code audit (current, 2026-03-10)
Nine fixes from code-audit-2026-03-10.md:

1. **ChatActivity.kt v1.2 — CRIT: CoroutineExceptionHandler on scope**: Added handler to
   `CoroutineScope(Dispatchers.Main + SupervisorJob())`. Previously any uncaught Error
   (OOM etc.) escaped to Android's UncaughtExceptionHandler → process kill. Now logged.
2. **ChatActivity.kt v1.2 — CRIT: catch(Throwable) in sendMessage()**: Widened from
   `catch(e: Exception)` — OOM and Error subclasses were bypassing the catch entirely.
3. **ChatActivity.kt v1.2 — HIGH: timeoutJob separated**: Safety timeout extracted into
   its own `Job` (`timeoutJob`). Each `sendMessage()` cancels the prior timeout before
   starting a new one — eliminates overlapping 120s timers from multiple messages.
4. **ChatActivity.kt v1.2 — HIGH: pendingUserMessageId fixes false awaitingResponse reset**:
   Room ID of the pending user message is tracked. `observeMessages()` only resets
   `awaitingResponse` when it sees an assistant message AFTER the pending user message —
   prevents email auto-reply notifications (ChatBridge.post) from triggering a premature
   UI reset and allowing message 2 to be sent before message 1's reply arrives.
5. **MessageEngine.kt v2.1 — CRIT: messageMutex serialises all message handlers**: Adds
   `Mutex` wrapping `handleAdminCommand`/`handlePublicMessage` inside `scope.launch`.
   Root cause of first-install crash: 10 concurrent email coroutines all called
   `nativeGenerate → resetContext` (128MB free/alloc each), causing native memory
   fragmentation → SIGABRT or LMK kill. Mutex ensures one handler runs at a time.
   Also widened `handlePublicMessage` catch to `Throwable` + added ownerNotifier call.
6. **AigentikService.kt v1.6 — HIGH: MessageEngine.configure() moved before EmailMonitor.init()**:
   Without this order, Gmail notifications arriving during model load processed emails
   with `wakeLock=null` — Samsung CPU throttle extended inference from 30s to 5+ min,
   widening the memory-pressure crash window.
7. **EmailMonitor.kt v4.3 — HIGH: processUnread capped at 3 emails (was 10)**: On first
   install (no stored historyId) all unread emails hit the fallback path. Capping at 3
   limits the number of queued coroutines; MessageEngine.messageMutex handles the rest.
8. **NotificationAdapter.kt v1.5 — HIGH: ConcurrentHashMap for activeNotifications**: Was
   plain `LinkedHashMap` — ConcurrentModificationException risk under notification bursts.
   Same fix applied to the 4 other maps in v1.6.3; this one was missed.
9. **GmailApiClient.kt v1.6 — MEDIUM: get() and post() wrapped in .use {}**: OkHttp
   Response must be closed to release socket. Previously could leak connections on
   exceptions. postRaw() was fixed in v1.6.3; this extends the fix to get() and post().
10. **ChatDao.kt v0.9.4 — MEDIUM: getAllMessages() capped at 200 rows**: Full-table scan
    on every Room insert caused O(N) Main-thread view re-render. 200-row subquery
    returns the most recent 200 messages in chronological order.
- Build: versionCode 72, versionName 1.7.0

### v1.6.1 — Fix chat LLM crash: fast-path, generateChatReply, think stripping, timeout (2026-03-02)
Four fixes for the double-LLM-call "crash" (45s freeze) when typing general messages in chat:

1. **MessageEngine.kt v2.0 — Fast-path eliminates double LLM call**: Added `looksLikeCommand()`
   helper and pre-check using `parseSimpleCommandPublic()` at the start of the `try` block in
   `handleAdminCommand()`. If `parseSimpleCommand` returns `unknown` AND the input has no
   command keywords (email, inbox, trash, find, phone, send, etc.) → genuine conversation →
   calls `generateChatReply()` directly and returns. `interpretCommand()` is never called.
   For inputs with command keywords → falls through to `interpretCommand()` as before.
   Root cause: every chat message ("hello", "how are you") previously called `interpretCommand()`
   (LLM call 1, ~120 tokens greedy) → returned `unknown` → called `generateSmsReply()`
   (LLM call 2, ~256 tokens). Combined: 20-45s. The 45s safety timeout in ChatActivity fired
   first, re-enabling the UI while the LLM was still running — appeared as a crash/freeze.
2. **AiEngine.kt v1.6 — `generateChatReply()`**: New function with chat-appropriate system
   prompt ("Have a natural, helpful conversation. Do not add any signature or sign-off.").
   maxTokens=512, temperature=0.7/topP=0.9. No SMS framing, no SMS signature. Used by:
   (a) the new fast-path for genuine conversation, (b) the `when(result.action) else →` fallback
   block when `interpretCommand()` returns `unknown` for something that looked like a command.
   Previous: both paths used `generateSmsReply()` with "Reply to a text from Ish from Ish"
   framing + "— Aigentik, personal agent of Ish. If you need to reach Ish..." signature —
   inappropriate for the chat UI and confusing for the model.
3. **AiEngine.kt v1.6 — Strip `<think>` blocks in `interpretCommand()`**: Added
   `.replace(Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE)), "")` before
   the existing cleanup regex in `interpretCommand()`. Qwen3 thinking-mode models generate
   `<think>...</think>` blocks before JSON output; with `maxTokens=120` these consume the
   entire budget leaving no JSON. Stripping makes `parseCommandJson` see the actual JSON
   when it fits within the budget. Falls back to `parseSimpleCommand` if still malformed.
4. **ChatActivity.kt — Safety timeout 45s → 120s**: `delay(45_000)` → `delay(120_000)`.
   `generateChatReply()` at 512 tokens can take 25-60s; 45s was too tight and fired during
   normal inference. 120s is a genuine safety net for stuck states, not a race condition.
- Build: versionCode 69, versionName 1.6.1

### v1.6.2 — Fix email hard crash: recursive MIME extraction, Html.fromHtml safety, thread-safe maps (2026-03-02)
Four root-cause fixes for the hard process crash (Android "app has a bug" dialog):

**Root cause:** Multi-turn email thread replies have nested MIME structure:
`multipart/mixed → multipart/alternative → text/plain + text/html`
Old `extractBody()` only looked 1 level deep → missed `text/plain` → fell back to HTML.
HTML body of a thread with history can be hundreds of KB. `Html.fromHtml()` on that
throws `OutOfMemoryError` or `StackOverflowError` — both `Error` subclasses, NOT caught
by `catch (e: Exception)`. EmailMonitor's scope had NO `CoroutineExceptionHandler` →
unhandled Error escaped to Android's default `UncaughtExceptionHandler` → process kill.
Second issue: `emailContextMap` keyed by `messageId`, but `routeReply()` iterated and
found the FIRST (oldest) email from a sender — multi-turn reply used wrong `threadId`.

1. **GmailApiClient.kt v1.5 — Recursive MIME body extraction + HTML truncation**:
   Replaced `extractBody()` (1-level only) with `extractBodyRecursive()` (depth-first,
   max 10 levels). First pass: searches for `text/plain` recursing into `multipart/*`
   before trying HTML. Second pass: HTML fallback now truncates raw HTML to 16 KB before
   `Html.fromHtml()` (prevents OOM on multi-KB thread histories), wraps `fromHtml()` in
   `catch (e: Throwable)` with regex-strip fallback. All extracted bodies capped at 4000
   chars (sufficient for AI context). This directly prevents the crash from large HTML.
2. **EmailMonitor.kt v4.2 — CoroutineExceptionHandler + catch(Throwable)**:
   Added `CoroutineExceptionHandler` to scope — logs the error and resets `isProcessing`.
   Without this, any unhandled `Error` in a launched coroutine killed the process.
   Widened per-email `catch (e: Exception)` → `catch (e: Throwable)` in both
   `processFromHistory()` and `processUnread()`. Updated `storeEmailContext()` /
   `storeGVoiceContext()` calls to pass sender identifier (not messageId) as the key.
3. **EmailRouter.kt v2.2 — ConcurrentHashMap + correct sender-keyed context**:
   Changed `gvoiceContextMap` and `emailContextMap` from plain `mutableMapOf()`
   (LinkedHashMap, NOT thread-safe) to `ConcurrentHashMap` — prevents
   `ConcurrentModificationException` from concurrent notification/reply coroutines.
   Context now keyed by sender: `fromEmail.lowercase()` for email, last-10-phone-digits
   for GVoice. Overwriting on same sender = map always holds most recent email per sender.
   `routeReply()` now does O(1) direct map lookup instead of O(N) iteration (which found
   the oldest entry due to LinkedHashMap insertion order). Multi-turn replies now use the
   correct `threadId`/`messageId` from the LATEST email in the thread.
   Added `CoroutineExceptionHandler` to EmailRouter scope.
- Build: versionCode 70, versionName 1.6.2

### v1.6.3 — Stability: thread safety, cold-start Gmail fix, user bubble text, response leak (2026-03-09)
Seven fixes from the forensic code audit (code-audit.md, 2026-03-02):

1. **item_message_user.xml — CRIT: User bubble text now readable in both themes**: Changed
   `android:textColor` from `@color/aigentik_on_primary` (white on light-gray = 1.04:1 contrast;
   black on near-black = 1.19:1 contrast — both invisible) to `?android:attr/textColorPrimary`
   (resolves to near-black in light mode, near-white in dark mode — correct for neutral bubble).
2. **MessageEngine.kt — CRIT: Gmail works immediately on cold start**: Added `initContext(context)`
   function called from `ChatActivity.onCreate()` to set `appContext` before `AigentikService`
   finishes init. Previously, all Gmail commands returned "Gmail not initialized — restart app"
   for the first 30-60 seconds (entire model load window). `configure()` still overwrites on
   service startup — `initContext()` only sets if null (no-op on second call).
3. **DestructiveActionGuard.kt — HIGH: pendingActions thread-safe**: Changed `mutableMapOf()`
   to `ConcurrentHashMap` — multiple IO coroutines (one per channel) can confirm/store
   destructive actions concurrently. Prevents `ConcurrentModificationException`.
4. **AdminAuthManager.kt — HIGH: activeSessions thread-safe**: Same fix — `mutableMapOf()`
   to `ConcurrentHashMap`. `authenticate()` and `hasActiveSession()` called from concurrent
   IO coroutines for simultaneous SMS/email admin sessions.
5. **NotificationReplyRouter.kt — HIGH: reply maps thread-safe**: Both `messageIdToSbnKey`
   and `sbnKeyToEntry` changed from `mutableMapOf()` to `ConcurrentHashMap`. `register()` and
   `onNotificationRemoved()` run on the NotificationListenerService thread; `sendReply()` runs
   on MessageEngine IO thread. Previously racy under load.
6. **GmailApiClient.kt — HIGH: postRaw() response body closed**: Changed
   `http.newCall(req).execute().code` to `.execute().use { it.code }`. OkHttp `Response`
   must be closed to release socket connections. Called in a loop by `emptyTrash()` — was
   leaking connections and would cause `IOException` after batch operations.
7. **MessageEngine.kt — LOW: chatNotifier @Volatile**: Marked `@Volatile` to ensure write
   visibility across CPU cores. Written from `ChatActivity` (Main thread) and `AigentikService`
   (IO thread). Without `@Volatile`, stale reads possible on multi-core ARM.
8. **AigentikService.kt — HIGH: initAllEngines catches Throwable**: Changed
   `catch (e: Exception)` to `catch (e: Throwable)` — consistent with v1.6.2 hardening
   in EmailMonitor/AiEngine. `OutOfMemoryError` during large model load no longer escapes silently.
- Build: versionCode 71, versionName 1.6.3


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
                         │     Fast-path: parseSimpleCommand() + looksLikeCommand()
                         │       genuine conversation → AiEngine.generateChatReply()
                         │       command keywords → AiEngine.interpretCommand() → action dispatch
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
4. **ContactEngine IO-thread init** — ContactEngine.init() dispatched to IO in ChatActivity (v1.5.3).
   AigentikService still calls it on its own IO coroutine scope. Both paths are now IO-safe.
   If both execute near-simultaneously, `loadFromRoom()` does two atomic reference swaps —
   the second wins. Both load identical Room data. `CopyOnWriteArrayList` handles concurrent
   `add()` calls safely. Fully resolved in v1.6.0 (ContactEngine v0.6).
5. **MessageEngine.appContext null if service never ran** — Gmail ops now show specific error
   ("Gmail not initialized — restart app") instead of silence. Could still improve by adding
   MessageEngine.initContext(ctx) from ChatActivity.
6. **Conversation history in chat** — FIXED in v1.6.0 (MessageEngine v1.9). Chat messages
   now persisted to ConversationHistoryDatabase (contactKey="owner", channel="CHAT").
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
