# Aigentik Android — Claude Code Context

You are continuing development of Aigentik — a privacy-first Android AI assistant app. Here is the complete project context.

## PROJECT OVERVIEW
- App: Aigentik Android (com.aigentik.app)
- Repo: ~/aigentik-android (local Termux) + GitHub (builds via Actions)
- **Current version: v1.6.1 (versionCode 69)**
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

### v1.6.1 — Fix chat LLM crash: fast-path, generateChatReply, think stripping, timeout (current, 2026-03-02)
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

### v1.6.0 — Stability hardening: thread safety, race fix, JNI hygiene, chat history (2026-03-02)
Four architectural correctness fixes — no features added, no regressions:

1. **ContactEngine.kt v0.6 — Thread-safe in-memory cache**: Replaced `mutableListOf<Contact>()`
   (ArrayList, not thread-safe) with `@Volatile CopyOnWriteArrayList<Contact>`. `loadFromRoom()`
   now does atomic reference replacement (`contacts = CopyOnWriteArrayList(newList)`) instead
   of `clear()+addAll()` — eliminates the empty-list window visible to concurrent readers.
   Reads (find/filter/size) are lock-free; writes (add) are copy-on-write thread-safe.
2. **EmailMonitor.kt v4.1 — AtomicBoolean for isProcessing**: Replaced `@Volatile Boolean`
   (check-then-set race) with `AtomicBoolean` + `compareAndSet(false, true)` in
   `onGmailNotification()`. Only one notification can win the CAS; all others return early.
   `finally { isProcessing.set(false) }` moved to the `scope.launch {}` wrapper so it fires
   unconditionally even if processing throws. Removed redundant `isProcessing = true` and
   `finally` blocks from `processFromHistory()` and `processUnread()`.
3. **llama_jni.cpp v1.6 — toJavaString() JNI exception hygiene**: Added
   `ExceptionCheck()+ExceptionClear()` before every fallback `NewStringUTF("")` call that
   follows a failed allocation (NewByteArray, FindClass, NewObject). Per JNI spec, calling
   most JNI functions with a pending exception is undefined behaviour. Added null-check for
   `FindClass` return value and null-check for `charset` before `DeleteLocalRef`.
   Inference path and toJavaString call site are unchanged.
4. **MessageEngine.kt v1.9 — Chat conversation history**: The "genuine conversation" else
   branch in `handleAdminCommand()` now persists exchanges to `ConversationHistoryDatabase`
   (contactKey="owner", channel="CHAT"). Loads last `CONTEXT_WINDOW_TURNS` (6) turns before
   generating reply; records user input and stripped AI reply after. Applies only to
   `channel == CHAT` (not remote admin commands). `historyDao` null-safe — early messages
   before service starts are handled gracefully (load returns [], record is no-op).
- Build: versionCode 68, versionName 1.6.0

### v1.5.5 — Medium/low priority improvements: JSON parsing, Gmail batch, KV cache, theme (2026-03-01)
Four improvements from the v1.5.4 code audit backlog:

1. **AiEngine.kt v1.5 — Robust JSON command parsing**: Replaced `extractJsonValue()` regex with
   `org.json.JSONObject` parsing in `parseCommandJson()`. Old regex failed on escaped quotes,
   numeric values, and JSON null. `optString(key, "")` handles all edge cases. Falls back to
   `parseSimpleCommand()` on `JSONException` (malformed/truncated LLM output).
2. **GmailApiClient.kt v1.4 — Batch trash instead of per-email calls**: `deleteAllMatching()`
   now collects all matching IDs first (unchanged pagination), then sends one `batchModify` POST
   per 1000 IDs (`addLabelIds:["TRASH"], removeLabelIds:["INBOX"]`). O(N) HTTP calls → O(⌈N/1000⌉).
3. **llama_jni.cpp v1.4 — KV cache prefix reuse**: Tokenize first, compare against
   `g_last_prompt_tokens`, and if ≥50 common tokens found: trim KV cache with
   `llama_kv_cache_seq_rm(g_ctx, 0, common_prefix, -1)` and only decode new tokens.
   `interpretCommand()`'s ~700-token system prompt skipped on every subsequent chat message.
   `g_last_prompt_tokens` cleared on model load/unload. Fallback to `resetContext()` for fresh
   starts and mismatched prompts. `toJavaString()` crash fix untouched.
4. **ThemeHelper ordering — all 8 activities**: Moved `AigentikSettings.init(this)` +
   `ThemeHelper.applySavedTheme()` before `super.onCreate()` in all activities. AppCompatDelegate
   must be configured before it initializes in `super.onCreate()` or theme only applies on next
   recreation. Also added `AigentikSettings.init(this)` + import to RuleManagerActivity.
- Build: versionCode 67, versionName 1.5.5

### v1.5.4 — Deep audit verification + MaterialAlertDialogBuilder fix (2026-03-01)
Second-pass deep verification audit confirmed all v1.5.3 crash fixes are fully correct.
Traced complete execution paths for all four crash scenarios.

1. **SettingsHubActivity** — `AlertDialog.Builder(this)` → `MaterialAlertDialogBuilder(this)`.
   Project convention requires Material dialog builder with Material3 theme; AppCompat
   AlertDialog can cause theme-resolution issues. Same fix as OnboardingActivity in v1.5.2.
2. **code-audit.md** — Added "New Issues Discovered During v1.5.3 Deep Audit" section and
   "v1.5.3 Deep Audit Verification Summary" confirming all crashes resolved. Documented
   pre-existing issues (ContactEngine thread safety, EmailMonitor race, toJavaString OOM edge).
- Build: versionCode 66, versionName 1.5.4


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
