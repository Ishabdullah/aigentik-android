# Aigentik Android — Complete Forensic Code Audit
**Generated:** 2026-03-02
**Auditor:** Ground-up live codebase analysis (zero reliance on prior reports or changelogs)
**Scope:** All Kotlin source files, C++ JNI layer, XML layouts/themes/colors, auth, email, core engines, Room DBs
**Version audited:** v1.6.2 (versionCode 70)

---

## 1. Executive Summary

Aigentik is an on-device AI personal assistant for Android that handles Gmail, SMS/RCS auto-reply, and chat via a local LLM (llama.cpp JNI). The codebase is architecturally sound and demonstrates serious engineering effort: coroutine hygiene has improved in recent versions, JNI safety is handled properly, and the email pipeline is well-structured.

**Two active user-facing regressions exist and require immediate attention:**

1. **User message text is unreadable in both light AND dark mode.** This is a definitive color resource misconfiguration in `item_message_user.xml`. Root cause and exact fix documented below.
2. **Email actions silently fail until service is fully initialized.** When a user types any Gmail command immediately after app launch (before `AigentikService.initAllEngines()` completes), `MessageEngine.appContext` is null and all Gmail actions return "Gmail not initialized — restart app."

Beyond these, the audit identifies 6 high-priority issues (thread safety gaps, resource leaks, dead code with latent crash risk), 8 medium issues, and several architectural and performance observations.

---

## 2. System Architecture Overview (from live code)

```
App Entry:
  MainActivity.onCreate()
    → AigentikSettings.isConfigured?
      NO  → OnboardingActivity
      YES → ChatActivity (finish() — MainActivity is a launcher only)

ChatActivity:
  → starts AigentikService (foreground)
  → wires MessageEngine.chatNotifier → ChatBridge.post() → Room
  → inits ContactEngine (IO thread)
  → observes ChatDatabase.getAllMessages() Flow → renders messages

AigentikService:
  → GoogleAuthManager.initFromStoredAccount()
  → ContactEngine.init(), RuleEngine.init()
  → AiEngine.loadModel() (async, IO)
  → EmailMonitor.init(), EmailRouter.init()
  → GmailHistoryClient.primeHistoryId() (async)
  → MessageEngine.configure()
  → ConnectionWatchdog.start()

Inbound message paths:
  Gmail notification → NotificationAdapter → EmailMonitor.onGmailNotification()
                     → GmailHistoryClient / GmailApiClient → processEmail()
                     → MessageEngine.onMessageReceived(EMAIL)

  SMS/RCS notification → NotificationAdapter.onNotificationPosted()
                       → NotificationReplyRouter.register()
                       → MessageEngine.onMessageReceived(NOTIFICATION)

  Chat (owner) → ChatActivity.sendMessage()
               → MessageEngine.onMessageReceived(CHAT)

MessageEngine routing:
  CHAT → always trusted → handleAdminCommand()
  NOTIFICATION/EMAIL → check admin session → handleAdminCommand() or handlePublicMessage()

  handleAdminCommand():
    Fast-path: parseSimpleCommandPublic() + looksLikeCommand()
      genuine conversation → AiEngine.generateChatReply()
      command → AiEngine.interpretCommand() → action dispatch

  handlePublicMessage():
    RuleEngine check → ContactEngine lookup → AiEngine.generateSmsReply/EmailReply()
    → NotificationReplyRouter.sendReply() or EmailRouter.routeReply()

AI inference:
  AiEngine → LlamaJNI.generate() → llama_jni.cpp (nativeGenerate)
           → llama.cpp C++ → Snapdragon NPU/CPU (arm64-v8a)

Persistence:
  Room DBs: ChatDatabase, ContactDatabase, RuleDatabase, ConversationHistoryDatabase
  SharedPreferences: AigentikSettings, GmailHistoryClient (historyId), ChannelManager
```

---

## 3. Full Flow Analysis

### Launch → Model → Chat → Execution → Render

1. **`MainActivity.onCreate()`** — calls `AigentikSettings.init(this)` then `ThemeHelper.applySavedTheme()` **before** `super.onCreate()`. This is correct (AppCompatDelegate must be set before super). Redirects immediately to `ChatActivity`, calls `finish()`. All other methods in `MainActivity` are dead code (never called from `onCreate()`).

2. **`ChatActivity.onCreate()`** — initializes `ChatDatabase`, `ChatBridge`, wires `MessageEngine.chatNotifier`, dispatches `ContactEngine.init()` to IO. Calls `startForegroundService(AigentikService)`. Calls `checkPermissionsAndStart()` which calls `startAigentik()` → starts service.

3. **`AigentikService.initAllEngines()`** — runs on IO coroutine. Sequential: settings → ChatBridge → ContactEngine → RuleEngine → ChannelManager → AiEngine (model load, blocks IO thread for 5-30s) → EmailMonitor → EmailRouter → primeHistoryId (async child) → MessageEngine.configure(). Full startup could take 30-60 seconds on first model load.

4. **Model load race**: `ChatActivity` can receive and send messages before `AigentikService.initAllEngines()` completes. `MessageEngine.chatNotifier` is wired in `ChatActivity.onCreate()` so chat responses flow correctly. But `MessageEngine.appContext` is null until `configure()` is called (step 3 completes). Any Gmail commands during this window fail silently with "Gmail not initialized".

5. **User sends message** → `ChatActivity.sendMessage()`:
   - Saves user message to Room (IO thread)
   - Tries `resolveLocalCommand()` — synchronous, no IO, handles status/find/pause/resume/help/clear
   - If no local match → creates `Message(channel=CHAT)` → calls `MessageEngine.onMessageReceived()` on IO
   - Launches 120-second safety timeout coroutine

6. **`MessageEngine.onMessageReceived()`** → `scope.launch(IO)` → acquires wake lock → `handleAdminCommand()` (CHAT is always trusted).

7. **`handleAdminCommand()`**:
   - Channel toggle check (ChannelManager.parseToggleCommand)
   - Persona check (AigentikPersona.respond) — instant, no LLM
   - Fast-path: `parseSimpleCommandPublic()` + `looksLikeCommand()`
     - Pure conversation → `generateChatReply()` (1 LLM call, 512 tokens)
     - Command-like → `interpretCommand()` (1 LLM call, 120 tokens, JSON output)
   - `when(result.action)` dispatch → Gmail API / contact ops / etc.

8. **Gmail API call** (e.g., `gmail_list_unread`) → `GmailApiClient.listUnreadSummary()` → `GoogleAuthManager.getFreshToken()` → `GoogleAuthUtil.getToken()` (blocking IO, may show consent dialog) → OkHttp HTTP calls to `gmail.googleapis.com` → parse JSON.

9. **Response** → `notify(response)` → `chatNotifier?.invoke(message)` → `ChatBridge.post()` → `ChatDatabase.chatDao().insert()` (Room IO).

10. **`ChatActivity.observeMessages()`** — `collectLatest` on `chatDao().getAllMessages()` Flow. On DB change, removes all views, re-renders all messages, scrolls to bottom. Checks if last message role == "assistant" → re-enables send button.

---

## 4. Email System Deep Analysis

### 4.1 Trigger Path (Notification → Fetch)

`NotificationAdapter` correctly filters Gmail package notifications, skips group summaries, and calls `EmailMonitor.onGmailNotification()`. The `AtomicBoolean.compareAndSet` guard (v4.1) correctly prevents overlapping fetch coroutines.

**Issue**: If the Gmail app generates multiple individual notifications in rapid succession, only the first `compareAndSet` wins. Subsequent notifications are dropped. Since `historyId` is advanced per successful fetch, emails are not permanently missed — the next notification trigger would re-fetch via History API and catch all. Acceptable behavior.

### 4.2 History API vs. listUnread Fallback

`GmailHistoryClient.getNewInboxMessageIds()` correctly:
- Uses `historyTypes=messageAdded&labelId=INBOX`
- Checks both `INBOX` and `UNREAD` labels on returned messages
- Returns `(messageIds, latestHistoryId)` pair
- Handles 404 (expired historyId) → triggers re-prime

**Potential missed event**: After `primeHistoryId()` runs on service start, there is a window between setting the `historyId` baseline and the first Gmail notification where an email could arrive. This email would be picked up by the History API on the next notification. No event is permanently lost.

**Silent failure on 401/403**: `getNewInboxMessageIds()` returns `Pair(emptyList(), null)` on any non-200, non-404 HTTP error. A 401 (expired token) or 403 (scope revoked) results in zero processing and zero user notification. `EmailMonitor.isRunning()` returns `true` (idle), making the "active ✅" status shown in chat doubly misleading.

### 4.3 Full Email Processing

`GmailApiClient.getFullEmail()` → `extractBodyRecursive()`: recursive MIME traversal with depth limit 10, text/plain preferred, HTML truncated to 16KB before `Html.fromHtml()`. Correct.

**Issue — `listUnreadSummary()` N+1 HTTP calls**: `listUnreadSummary(ctx, 200)` (called by `countUnreadBySender`) issues 1 list call + up to 200 individual `getEmailMetadata()` calls. At ~200ms per call, this is ~40 seconds for a user with 200 unread emails. User sees frozen "Thinking..." with no progress feedback.

**Issue — `sendEmail()` uses deprecated `Session.getDefaultInstance()`**: Returns a shared JVM-global JavaMail session. If two `sendEmail()` calls happen concurrently, both share the same `Session` object, which could cause message property interference. Should use `Session.getInstance(props, null)`.

### 4.4 Reply Routing

`EmailRouter.routeReply()` does O(1) direct `ConcurrentHashMap` lookup by sender key. Correct.

**GVoice reply routing**: `buildGVoiceMessage()` sets `sender = gvm.senderPhone`. `routeReply()` looks up `senderIdentifier.filter{isDigit}.takeLast(10)`. `storeGVoiceContext()` stores by `senderPhone.filter{isDigit}.takeLast(10)`. Keys match. Correct.

**Issue — `EmailRouter.appContext` null before service init**: Same root cause as `MessageEngine.appContext`. `sendEmailDirect()` returns false if called before `AigentikService` runs `EmailRouter.init(appCtx)`.

### 4.5 Command Parsing

`interpretCommand()` calls `llama.generate(prompt, 120, temp=0.0f)` for JSON output. 120 tokens fits the JSON output (~35-40 tokens). The `<think></think>` prefill suppresses Qwen3 thinking. Correct.

**Issue — `parseSimpleCommand()` dead code branch**: Two `lower.contains("check") && lower.contains("email")` patterns exist. The second (returning `check_email`) can NEVER be reached because the first (returning `gmail_list_unread`) always matches first. If the LLM returns `check_email`, the user sees monitor status instead of their inbox.

---

## 5. Chat Rendering / Theme Analysis — ROOT CAUSE CONFIRMED

### 5.1 User Message Text — Unreadable in BOTH Themes

**File**: `app/src/main/res/layout/item_message_user.xml:17`
**Function**: `renderMessage()` in `ChatActivity.kt:292`
**Root Cause**: `android:textColor="@color/aigentik_on_primary"` is semantically designed to be "text ON TOP OF the primary color button/surface." The user bubble background is NOT the primary color, it is `@color/bubble_user` (a neutral surface). Result:

| Mode       | Bubble background              | Text color (`aigentik_on_primary`) | Contrast         |
|------------|-------------------------------|-------------------------------------|------------------|
| Light mode | `#F1F3F4` (very light gray)   | `#FFFFFF` (white)                  | **1.04:1 — invisible** |
| Dark mode  | `#1E1E1E` (near-black)        | `#000000` (black)                  | **1.19:1 — invisible** |

Both modes fail WCAG AA minimum 4.5:1 contrast ratio for normal text. **This is the confirmed root cause of invisible user message text in both light and dark mode.**

**Fix recommendation (do NOT implement — description only)**:
- Option A (simplest): Change `item_message_user.xml` `android:textColor` from `@color/aigentik_on_primary` to `?android:attr/textColorPrimary`. This resolves to near-black in light mode and near-white in dark mode — correct contrast for neutral bubble backgrounds.
- Option B: Add a dedicated `bubble_user_text` color resource (`#1A1A1A` in `values/colors.xml`, `#EEEEEE` in `values-night/colors.xml`) and use it in `item_message_user.xml`.
- Option C: Change `bubble_user` drawable solid fill to `@color/aigentik_primary` (black in light, white in dark). Then `aigentik_on_primary` (white/black) would be the correct contrast for that surface — giving user bubbles the primary color scheme.

### 5.2 Assistant Message Text — Correct

`item_message_assistant.xml` uses `android:textColor="?android:attr/textColorPrimary"` which correctly adapts to both modes. No issue.

### 5.3 Theme Application Order — Correct

All 8 activities call `AigentikSettings.init(this)` + `ThemeHelper.applySavedTheme()` before `super.onCreate()`. AppCompatDelegate.setDefaultNightMode() runs before the Activity inflates any views. Correct.

### 5.4 `android:windowLightStatusBar` Hardcoded True

**File**: `app/src/main/res/values/themes.xml:12`
`<item name="android:windowLightStatusBar">true</item>` is in the default (day) theme with no `values-night/themes.xml` to override for dark mode. In dark mode, dark status bar icons on a dark background. Low-severity on Samsung hardware (OneUI handles this), but architecturally incorrect.

---

## 6. Crash Risk Assessment

### 6.1 CONFIRMED CRASH RISK — Room on Main Thread (Dead Code, Latent)

**File**: `MainActivity.kt:108`
**Function**: `setupDashboard()` → `btnSyncContacts.setOnClickListener`
```kotlin
scope.launch { // scope = CoroutineScope(Dispatchers.Main) — WRONG
    val added = ContactEngine.syncAndroidContacts(this@MainActivity)
    // syncAndroidContacts → persistContact → dao?.insert() = Room on main thread → CRASH
}
```
`ContactEngine.syncAndroidContacts()` calls `persistContact()` → `dao?.insert()` synchronously. `MainActivity.scope` is `Dispatchers.Main`. Room throws `IllegalStateException` for main-thread DB access.

**Currently unreachable**: `MainActivity.onCreate()` calls `finish()` immediately; `setupDashboard()` is never called. If future code removes the `finish()` call, this crash manifests.

### 6.2 CONFIRMED CRASH RISK — `nativeGenerate` Null Return Through Non-Null Binding

**File**: `LlamaJNI.kt:119`
`private external fun nativeGenerate(...): String` — Kotlin declares non-null. In extreme OOM, `toJavaString()`'s final `env->NewStringUTF("")` can return null. Kotlin sees null where non-null expected → NPE. Mitigated in `AiEngine` by `?.trim() ?: ""` null-safe operators, but the binding itself is unsound.

### 6.3 `AigentikService.initAllEngines()` Exception Coverage

**File**: `AigentikService.kt:153`
`catch (e: Exception)` — `OutOfMemoryError` during model load (not uncommon for 4GB+ GGUF on devices under memory pressure) escapes the catch, service enters partial state with no user notification.

### 6.4 `postRaw()` Response Body Never Closed

**File**: `GmailApiClient.kt:158`
`http.newCall(req).execute().code` — `Response` created but never closed. Called in a loop by `emptyTrash()`. Accumulates unclosed connections → eventual `IOException` on subsequent calls.

---

## 7. Concurrency & Thread Safety Analysis

### 7.1 `DestructiveActionGuard.pendingActions` — NOT Thread-Safe

**File**: `DestructiveActionGuard.kt:38`
`mutableMapOf()` (LinkedHashMap) accessed from multiple coroutine threads (MessageEngine IO scope for multiple channels). `ConcurrentModificationException` if two channels confirm/request destructive actions concurrently.
**Fix**: `ConcurrentHashMap<String, PendingAction>()`.

### 7.2 `AdminAuthManager.activeSessions` — NOT Thread-Safe

**File**: `AdminAuthManager.kt:31`
Same issue. `ConcurrentHashMap<String, Long>()`.

### 7.3 `NotificationReplyRouter` Maps — NOT Thread-Safe

**File**: `NotificationReplyRouter.kt:24-25`
`register()` called from `NotificationAdapter` (NotificationListenerService, main thread). `sendReply()` called from `MessageEngine` (IO thread). Concurrent access to `mutableMapOf()` → `ConcurrentModificationException`.
**Fix**: Both maps to `ConcurrentHashMap`.

### 7.4 `ChannelManager.channelState` — NOT Thread-Safe

**File**: `ChannelManager.kt:18`
`isEnabled()` called from Main (chat local commands), IO (MessageEngine). Low collision risk but architecturally unsafe.

### 7.5 `ContactEngine.findOrCreateByPhone/Email()` — Duplicate Creation Race

**File**: `ContactEngine.kt:105-128`
Two concurrent `handlePublicMessage()` calls for the same new sender both see `findContact() == null`, both create contacts with `id = "contact_${System.currentTimeMillis()}"` (same ID if same ms), both call `persistContact()` → Room REPLACE clobbers one → in-memory list has duplicates.
**Fix**: Use `AtomicLong` counter or UUID for contact ID generation.

### 7.6 `MessageEngine.chatNotifier` — Not `@Volatile`

**File**: `MessageEngine.kt:95`
Written by `ChatActivity` (Main) and `AigentikService` (IO). Without `@Volatile`, write visibility is not guaranteed across CPU cores.

### 7.7 `GmailHistoryClient.cachedHistoryId` — Correct

`@Volatile var`. Single writer. Correctly marked.

---

## 8. JNI Safety Review

### 8.1 `toJavaString()` — Correct (v1.6)

The implementation correctly:
- Uses `ExceptionCheck()` + `ExceptionClear()` before every fallback `NewStringUTF("")`
- Null-checks `FindClass` and `NewObject` return values
- Constructs Java `String` via byte array + `new String(bytes, "UTF-8")` to handle 4-byte UTF-8 (emoji, supplementary Unicode)
- Final fallback returns `NewStringUTF("")` (empty, not null)

**One remaining edge case**: `NewStringUTF("")` can itself return null under extreme OOM. Kotlin would receive null through a non-null binding → NPE. Theoretical only.

### 8.2 `g_mutex` Coverage — Correct

All `g_model`/`g_ctx` accessors acquire `std::lock_guard<std::mutex>`. Kotlin-side `ReentrantLock` provides second layer. Safe.

### 8.3 `resetContext()` Per Generate Call — Performance Tradeoff

Every `nativeGenerate()` frees and recreates the llama_context (~200-500ms). KV cache prefix reuse was reverted (API compatibility). Accepted correctness tradeoff.

### 8.4 `nativeGetModelInfo()` Uses `NewStringUTF` Directly

**File**: `llama_jni.cpp:296`
Buffer filled via `snprintf` with only ASCII (numbers, colons, letters). `NewStringUTF()` safe for pure ASCII. Correct.

### 8.5 Prompt Length Check — Correct

`n >= CTX_SIZE - 32` prevents overflow. `CTX_SIZE - 32` = 8160 token limit is conservative. Correct.

---

## 9. Data Persistence & Room Review

### 9.1 All Room DBs Use `fallbackToDestructiveMigration()`

**Files**: `ChatDatabase.kt`, `ContactDatabase.kt`, `ConversationHistoryDatabase.kt`
All three use `fallbackToDestructiveMigration()` at schema version 1. When a future schema change occurs, ALL data in `ContactDatabase` (contact intelligence) and `ConversationHistoryDatabase` (conversation context) will be silently deleted. For `ChatDatabase`, data loss is acceptable. For the other two, it is not.
**Fix**: Add proper Room migration scripts before any schema version bump on `ContactDatabase` and `ConversationHistoryDatabase`.

### 9.2 `ConversationHistoryDao` — Synchronous Queries

All DAO methods are synchronous (non-suspend). Called from MessageEngine IO scope — correct. If called from any Main context in future, crashes immediately. Appropriate for current usage with explicit thread contract documented.

### 9.3 `ContactEntity` INSERT uses `REPLACE` Strategy

`ContactDao.insert()` with `OnConflictStrategy.REPLACE`. Combined with millisecond-collision ID generation (§7.5), a racing concurrent insert can silently overwrite a valid contact. Low-frequency but real risk.

### 9.4 `ChatDatabase.getAllMessages()` — `collectLatest` Pattern

`collectLatest` on `getAllMessages()` Flow in `ChatActivity.observeMessages()`. If a new DB emission arrives while `renderMessage()` is still inflating views, the previous collection is cancelled. Safe at current message rates. Under high load (many quick bot messages), could drop intermediate renders. Non-critical.

### 9.5 `ContactEngine.persistContact()` Thread Safety

Called from `findOrCreateByPhone/Email()` and `setInstructions()` which are invoked from MessageEngine IO scope — correct. Called from `syncAndroidContacts()` which is invoked from AigentikService IO scope — correct. The dead-code path in `MainActivity.setupDashboard()` would call from Main — crash risk documented in §6.1.

---

## 10. Security & Permissions Review

### 10.1 Admin Password — SHA-256 Without Salt

`AdminAuthManager.hashPassword()` uses SHA-256 with no salt. Vulnerable to rainbow table attacks if the admin code is a common word or number. Low practical risk (single-user app, no data exfiltration). For stronger security, use PBKDF2 or bcrypt.

### 10.2 Admin Authentication — No Brute Force Protection

`AdminAuthManager.authenticate()` has no rate limiting, lockout, or attempt counter. A remote attacker who can send SMS/email could attempt unlimited password guesses. Low practical risk given physical message delivery requirement.

### 10.3 DestructiveActionGuard — One-Chance Confirmation

On wrong admin code, `pendingActions.remove(channelKey)` clears the pending action. User must restart the destructive action flow from scratch. Undocumented behavior — user has no warning they only get one attempt.

### 10.4 `gmailScopesGranted` — In-Memory Only

`@Volatile Boolean` not persisted. Every restart sets it false. Resolved lazily on first `getFreshToken()`. `ConnectionWatchdog` may show spurious "scope needed" notification on restart before first token fetch. Minor UX issue.

### 10.5 No Cloud Exfiltration — Confirmed

All HTTP calls go to `https://gmail.googleapis.com/` only. No Firebase SDK imports. No Pub/Sub, Firestore, analytics, or external AI APIs. Privacy policy enforced in code.

---

## 11. Critical Issues (Must Fix Immediately)

### CRIT-1: User Message Text Invisible in Both Themes

**File**: `app/src/main/res/layout/item_message_user.xml:17`
**Root cause**: `android:textColor="@color/aigentik_on_primary"` uses the wrong semantic color for a neutral bubble background. White text (#FFFFFF) on light gray (#F1F3F4) in light mode = 1.04:1 contrast. Black text (#000000) on near-black (#1E1E1E) in dark mode = 1.19:1 contrast. Both are invisible.
**Impact**: ALL user-typed messages appear blank. Core usability broken.
**Fix**: Replace `android:textColor="@color/aigentik_on_primary"` with `android:textColor="?android:attr/textColorPrimary"` in `item_message_user.xml`.

---

### CRIT-2: Email Actions Fail Silently on Cold Start

**File**: `MessageEngine.kt` — all `gmail_*` action handlers checking `appContext`
**Root cause**: `MessageEngine.appContext` is null until `AigentikService.initAllEngines()` completes `MessageEngine.configure()`. Model load alone takes 30-60 seconds. Any Gmail command typed before this returns "❌ Gmail not initialized — restart app" with no indication that waiting resolves the issue.
**Impact**: Every Gmail command fails during the first 30-60 seconds after cold launch. New users think Gmail is broken.
**Fix**: Add `MessageEngine.initContext(context: Context)` called from `ChatActivity.onCreate()` with `applicationContext` to provide context immediately, independent of service initialization.

---

## 12. High Priority Issues

### HIGH-1: `DestructiveActionGuard.pendingActions` Not Thread-Safe

**File**: `DestructiveActionGuard.kt:38`
`mutableMapOf()` accessed concurrently from MessageEngine IO scope (multiple channels).
**Impact**: `ConcurrentModificationException` on concurrent destructive action flows.
**Fix**: `ConcurrentHashMap<String, PendingAction>()`.

### HIGH-2: `AdminAuthManager.activeSessions` Not Thread-Safe

**File**: `AdminAuthManager.kt:31`
`mutableMapOf()` with concurrent reads/writes from multiple IO coroutines.
**Impact**: `ConcurrentModificationException` on concurrent admin session checks.
**Fix**: `ConcurrentHashMap<String, Long>()`.

### HIGH-3: `NotificationReplyRouter` Maps Not Thread-Safe

**File**: `NotificationReplyRouter.kt:24-25`
`register()`/`onNotificationRemoved()` on main thread, `sendReply()` on IO thread.
**Impact**: `ConcurrentModificationException` during inline SMS/RCS reply under load.
**Fix**: Both maps to `ConcurrentHashMap`.

### HIGH-4: `postRaw()` Response Body Never Closed

**File**: `GmailApiClient.kt:158`
`http.newCall(req).execute().code` — Response never closed.
**Impact**: Connection pool exhaustion in `emptyTrash()` loop → eventual `IOException`.
**Fix**: `http.newCall(req).execute().use { it.code }`.

### HIGH-5: `AigentikService.initAllEngines()` Catches Only Exception

**File**: `AigentikService.kt:153`
`catch (e: Exception)` misses `OutOfMemoryError` during large model load.
**Impact**: Service enters silent partial state, no user-visible error.
**Fix**: `catch (e: Throwable)`.

### HIGH-6: `check_email` Action Branch Dead Code in `parseSimpleCommand()`

**File**: `AiEngine.kt:381`
Two consecutive `lower.contains("check") && lower.contains("email")` conditions. First returns `gmail_list_unread`, second is unreachable.
**Impact**: If LLM returns `check_email`, user sees monitor status string instead of their inbox.
**Fix**: Remove the dead branch. Ensure `handleAdminCommand`'s `check_email` / `read_email` / `list_email` case calls `listUnreadSummary()` (same as `gmail_list_unread`), or redirect `check_email` to that action.

---

## 13. Medium Priority Issues

### MED-1: `ChannelManager.channelState` Not Thread-Safe

**File**: `ChannelManager.kt:18`
`mutableMapOf()` accessed from Main and IO threads.
**Fix**: `ConcurrentHashMap`.

### MED-2: `ChannelManager.parseToggleCommand()` All-Channels Sentinel Ambiguous

**File**: `ChannelManager.kt:78`
"pause all sms" → returns `Pair(Channel.SMS, false)` (SMS matched first). But MessageEngine sees "all" in text and disables ALL channels. Non-deterministic — user intent of disabling only SMS is ignored.
**Fix**: Return `null` channel (or a `Channel.ALL` enum) when "all/everything" is detected, before checking specific channel names.

### MED-3: `EmailMonitor.isRunning()` Inverted Semantics

**File**: `EmailMonitor.kt:244`
`fun isRunning(): Boolean = !isProcessing.get()` — returns `true` when IDLE, `false` when PROCESSING. When actively fetching email, `isRunning()` returns false, and the MessageEngine shows "stopped ❌."
**Fix**: Rename to `isIdle()` and adjust caller logic, or invert the return value.

### MED-4: Two Separate OkHttpClient Instances

**Files**: `GmailApiClient.kt:54`, `GmailHistoryClient.kt:36`
Two connection pools, two thread pools. Wasteful on a resource-constrained device.
**Fix**: Shared singleton `OkHttpClient`.

### MED-5: `sendEmail()` Uses `Session.getDefaultInstance()` (Shared JVM Session)

**File**: `GmailApiClient.kt:243`
Shared global JavaMail session. Concurrent email sends could interfere.
**Fix**: `Session.getInstance(props, null)`.

### MED-6: `countUnreadBySender()` N+1 HTTP Calls

**File**: `GmailApiClient.kt:424`
1 list call + N metadata calls = up to 201 round-trips ≈ 40 seconds for 200 unread emails.
**Fix**: Use `format=metadata` with `fields` parameter in the list call to embed headers, or cap at maxResults=50.

### MED-7: Contact ID Collision on Same Millisecond

**File**: `ContactEngine.kt:106,120`
`"contact_${System.currentTimeMillis()}"` — same ID if two contacts created within 1ms.
**Fix**: Use `UUID.randomUUID().toString()` or an `AtomicLong` counter.

### MED-8: `LlamaJNI.buildChatPrompt()` Hardcoded to Qwen3 ChatML

**File**: `LlamaJNI.kt:111`
ChatML format breaks inference for LLaMA-3, Mistral, Gemma, Phi models.
**Fix**: Store model family in `AigentikSettings` alongside model path; select prompt template at inference time.

---

## 14. Low Priority Issues

### LOW-1: `android:windowLightStatusBar` Hardcoded True

**File**: `app/src/main/res/values/themes.xml:12`
No `values-night/themes.xml` override. Dark status bar icons in dark mode.
**Fix**: Add `values-night/themes.xml` with `android:windowLightStatusBar = false`.

### LOW-2: Deprecated `gmailAppPassword` Still in `saveFromOnboarding()`

**File**: `AigentikSettings.kt:112`
Method still accepts and stores `gmailAppPassword`. Remove parameter and key in cleanup.

### LOW-3: ~180 Lines of Dead Code in `MainActivity`

**File**: `MainActivity.kt:72-256`
`setupDashboard()`, `startAigentik()`, `checkPermissionsAndStart()`, `addActivity()`, `updateStats()`, and the `delay(10_000)` loop are all unreachable. Remove.

### LOW-4: `MessageEngine.chatNotifier` Not `@Volatile`

**File**: `MessageEngine.kt:95`
Written from two threads (Main via ChatActivity, IO via AigentikService). Should be `@Volatile`.

### LOW-5: WakeLock Not Released in `AigentikService.onDestroy()`

**File**: `AigentikService.kt:187`
If inference is running when service is destroyed, wake lock remains until 10-minute auto-timeout.
**Fix**: Add `if (wakeLock?.isHeld == true) wakeLock?.release()` to `onDestroy()`.

### LOW-6: `postRaw()` Pattern Asymmetric with `post()`

**File**: `GmailApiClient.kt:145`
`post()` calls `resp.body?.string()` to read+close body. `postRaw()` never reads/closes body (see HIGH-4). Asymmetry creates maintenance risk for future developers.

### LOW-7: DestructiveActionGuard One-Chance Confirmation Undocumented

**File**: `DestructiveActionGuard.kt:101`
Wrong code clears the pending action silently. User must restart destructive action from scratch with no warning. Should document in the confirmation prompt: "You have one attempt to confirm."

### LOW-8: `isOAuthSignedIn` Flag Can Drift from Actual Account State

**File**: `AigentikSettings.kt:66`
If Google account is removed from device outside the app, `isOAuthSignedIn = true` but `GoogleAuthManager.isSignedIn()` returns false. Resolved on next `getFreshToken()` call. Minor UX inconsistency.

---

## 15. Architectural Recommendations

### ARCH-1: Provide Gmail Context Early from ChatActivity

Decouple `MessageEngine.appContext` from full service startup. Add `MessageEngine.initContext(applicationContext)` called from `ChatActivity.onCreate()` to enable Gmail features before service completes init.

### ARCH-2: Enforce ConcurrentHashMap for Multi-Thread Objects

Establish a code convention: any `object` accessed from multiple threads MUST use `ConcurrentHashMap` for mutable state. Affects: `DestructiveActionGuard`, `AdminAuthManager`, `NotificationReplyRouter`, `ChannelManager`.

### ARCH-3: Replace Full Re-render with Append-Only in `observeMessages()`

`messageContainer.removeAllViews()` + re-inflate all messages on every DB change is O(N). Track the last rendered message ID and only append new views. Rebuild only on "clear chat" or initial load.

### ARCH-4: Model Format Abstraction

Create a `PromptFormatter` interface with implementations for ChatML (Qwen3), LLaMA-3 Instruct, Mistral Instruct, etc. Store model format type in `AigentikSettings` alongside model path. Select formatter at load time.

### ARCH-5: Introduce Service-Ready StateFlow

`AigentikService` should expose a `StateFlow<ServiceState>` (Initializing, ModelLoading, Ready, Error). `ChatActivity` observes this to show correct status during startup and delay Gmail commands until `Ready`.

---

## 16. Performance Optimization Opportunities

### PERF-1: `countUnreadBySender()` 201 HTTP Calls → 1-2 Calls

Use `messages.list` with `fields=messages(payload/headers)` to embed From/Subject headers in the list response, eliminating individual `getEmailMetadata()` calls.

### PERF-2: `observeMessages()` Full Re-render

With 50+ messages, each new message causes 50+ view inflations. Append-only rendering would be significantly faster and eliminate the flicker/scroll reset.

### PERF-3: `ContactEngine.syncAndroidContacts()` on Every Init

For 500+ contacts, this is a significant operation on every startup. Cache last sync timestamp and only run full sync if >24 hours elapsed.

### PERF-4: `resetContext()` Per Generate Call

~200-500ms overhead per generation. Investigate if `llama_kv_cache_clear()` (simpler than `llama_kv_cache_seq_rm()`) is available in the pinned llama.cpp version and could replace full context recreation.

### PERF-5: Shared OkHttpClient

`GmailApiClient` + `GmailHistoryClient` each maintain their own connection pool. A single shared instance reduces background threads and improves connection reuse.

---

## 17. Long-Term Refactor Suggestions

### REFACTOR-1: Replace LinearLayout Chat with RecyclerView + DiffUtil

`NestedScrollView + LinearLayout` with manual view inflation doesn't scale. A `RecyclerView` with `ListAdapter` (DiffUtil) would only rebind changed items, animate new messages, and handle large histories efficiently.

### REFACTOR-2: Migrate GoogleSignIn to Credential Manager API

`GoogleSignIn` is legacy (deprecated in newer GIS). Migration to `Credential Manager API` (min SDK 28) would future-proof auth. Deferred per project policy — current implementation functional.

### REFACTOR-3: Centralized `GmailReadiness` Sealed Class

Each Gmail handler checks `appContext != null`, `isSignedIn()`, etc. independently. A `checkGmailReadiness()` returning `sealed class GmailReadiness { Ready; NoContext; NotSignedIn; NeedsScopeConsent; TokenError }` would centralize logic and produce consistent user-facing errors.

### REFACTOR-4: `parseSimpleCommand()` as Decision Table

Large `when` with overlapping conditions and dead branches. A list of `(Regex, ActionFactory)` pairs evaluated in priority order would be easier to maintain, test, and extend.

### REFACTOR-5: Co-locate Intent Classification Logic

`looksLikeCommand()` in `MessageEngine` and `parseSimpleCommand()` in `AiEngine` form a split two-stage intent classifier. Both should live in `AiEngine` as a single `classifyIntent()` returning `sealed class Intent { Conversational; Command(keywords) }`.

---

## Summary Table

| ID       | Severity | File                              | Issue                                                             |
|----------|----------|-----------------------------------|-------------------------------------------------------------------|
| CRIT-1   | Critical | `item_message_user.xml`           | User bubble text invisible both themes (aigentik_on_primary bug)  |
| CRIT-2   | Critical | `MessageEngine.kt`                | Gmail commands fail silently — appContext null before service init |
| HIGH-1   | High     | `DestructiveActionGuard.kt`       | `pendingActions` HashMap not thread-safe                          |
| HIGH-2   | High     | `AdminAuthManager.kt`             | `activeSessions` HashMap not thread-safe                          |
| HIGH-3   | High     | `NotificationReplyRouter.kt`      | Reply maps not thread-safe (NLS main vs MessageEngine IO)         |
| HIGH-4   | High     | `GmailApiClient.kt`               | `postRaw()` response body never closed — connection pool leak      |
| HIGH-5   | High     | `AigentikService.kt`              | Init catch(Exception) misses OOM/Error on model load              |
| HIGH-6   | High     | `AiEngine.kt`                     | Dead code branch shadows gmail_list_unread; check_email broken    |
| MED-1    | Medium   | `ChannelManager.kt`               | `channelState` map not thread-safe                                |
| MED-2    | Medium   | `ChannelManager.kt`               | "all" sentinel ambiguous — "stop all sms" disables all channels   |
| MED-3    | Medium   | `EmailMonitor.kt`                 | `isRunning()` has inverted semantics                              |
| MED-4    | Medium   | `GmailApiClient/HistoryClient.kt` | Two separate OkHttpClient instances                               |
| MED-5    | Medium   | `GmailApiClient.kt`               | `Session.getDefaultInstance()` — shared global JavaMail session    |
| MED-6    | Medium   | `GmailApiClient.kt`               | N+1 HTTP calls in `countUnreadBySender()`                        |
| MED-7    | Medium   | `ContactEngine.kt`                | Millisecond-collision contact ID generation                       |
| MED-8    | Medium   | `LlamaJNI.kt`                     | ChatML format hardcoded — wrong for non-Qwen3 models              |
| LOW-1    | Low      | `values/themes.xml`               | `windowLightStatusBar` hardcoded true (no night override)         |
| LOW-2    | Low      | `AigentikSettings.kt`             | Deprecated `gmailAppPassword` still in `saveFromOnboarding()`     |
| LOW-3    | Low      | `MainActivity.kt`                 | ~180 lines dead code unreachable from `onCreate()`                |
| LOW-4    | Low      | `MessageEngine.kt`                | `chatNotifier` var not `@Volatile`                                |
| LOW-5    | Low      | `AigentikService.kt`              | WakeLock not released in `onDestroy()`                            |
| LOW-6    | Low      | `GmailApiClient.kt`               | `postRaw()` asymmetric response close vs `post()` pattern         |
| LOW-7    | Low      | `DestructiveActionGuard.kt`       | One-chance confirmation — undocumented, no warning to user        |
| LOW-8    | Low      | `AigentikSettings.kt`             | `isOAuthSignedIn` flag can drift from actual Google account state  |
