# Aigentik-Android Executive Code Audit

## Executive Summary
Aigentik-Android is a sophisticated, privacy-focused AI personal assistant for Android. It leverages a local Large Language Model (LLM) via `llama.cpp` to process and reply to SMS, RCS, and Gmail messages entirely on-device. The architecture is robust, utilizing modern Android components like Room for persistence, Coroutines for concurrency, and a `NotificationListenerService` to bypass traditional SMS permission hurdles.

While the core functionality is impressive and technically sound, several critical and high-priority issues were identified—primarily related to thread safety (Main thread database access) and potential edge-case failures in the AI and JNI layers.

---

## Technical Audit by Component

### 1. UI & Onboarding Flow
*   **What Works:** The flow from `MainActivity` to `OnboardingActivity` to `ModelManagerActivity` is clean. `ChatActivity` provides a responsive, real-time interface using Room `Flow`.
*   **Issues:**
    *   **Critical:** `MainActivity` triggers `ContactEngine.syncAndroidContacts()` on the Main thread. This method performs blocking Room database inserts, which will cause the app to crash on modern Android versions (cannot access DB on main thread).
    *   **Medium:** `OnboardingActivity` saves phone numbers with `takeLast(10)`, which may fail for international users or non-standard formatting.

### 2. AI Engine & JNI Layer
*   **What Works:** `AiEngine` provides a well-structured abstraction for LLM interactions. The warm-up phase prevents first-reply lag. `LlamaJNI` correctly implements a `ReentrantLock` for thread safety.
*   **Issues:**
    *   **High:** `llama_jni.cpp` uses `env->NewStringUTF(result.c_str())`. Since LLMs generate text token-by-token, `llama_token_to_piece` might return partial UTF-8 sequences. If `result` contains invalid UTF-8 (e.g., a multi-byte character split across pieces), `NewStringUTF` will crash the JNI thread.
    *   **Medium:** `AiEngine.extractJsonValue` uses a simple regex that will fail if the AI returns JSON with escaped quotes or complex structures. This would break command interpretation.

### 3. Message Processing & Routing
*   **What Works:** The `NotificationReplyRouter` is an ingenious solution for RCS/SMS replies without `SEND_SMS` permission. `MessageEngine` handles the complex logic of differentiating admin vs. public messages and gating destructive actions.
*   **Issues:**
    *   **Medium:** `NotificationAdapter.resolveSender` falls back to the notification title string if a contact is not found. If the title is "Mom" and no contact exists, the sender ID becomes "Mom", potentially causing duplicate or messy contact entries in `ContactEngine`.

### 4. Gmail Integration
*   **What Works:** Moving from Pub/Sub to a notification-triggered History API (`EmailMonitor` + `GmailHistoryClient`) is a significant improvement for privacy and efficiency. It avoids polling while remaining entirely on-device.
*   **Issues:**
    *   **Low:** `GmailApiClient.deleteAllMatching` performs one HTTP call per email to trash. This is inefficient for large volumes; `batchModify` should be used instead.
    *   **Low:** `EmailMonitor.isProcessing` flag is set inside a coroutine launch, creating a small race condition window where multiple notifications could trigger redundant fetches.

### 5. Data Persistence
*   **What Works:** Migration from legacy JSON to Room is well-handled in `ContactEngine` and `RuleEngine`.
*   **Issues:**
    *   **High:** `ContactEngine.persistContact` is called synchronously from various parts of the app. While currently used within `Dispatchers.IO` in the main message flow, its usage in `syncAndroidContacts` (Main thread) is a fatal bug.

---

## Cross-Verification Against Actual Codebase (v1.5.2)

### Claim 1: "MainActivity triggers ContactEngine.syncAndroidContacts() on the Main thread"
**Status: INCORRECT LOCATION — actual bug was in ChatActivity.kt**

In v1.5.x, `MainActivity.onCreate()` is dead code for normal flow. It immediately detects the first-run state and calls `startActivity(OnboardingActivity)` or `startActivity(ChatActivity)` then `finish()`. The `setupDashboard()` method referenced in the old audit is never called.

The **actual main-thread ContactEngine bug** was in `ChatActivity.onCreate()` at line 79:
```kotlin
ContactEngine.init(applicationContext)  // ← on Main thread
```
`ContactEngine.init()` calls `loadFromRoom()` → `dao?.getAll()` (Room query) and `syncAndroidContacts()` → `persistContact()` → `dao?.insert()` (Room write). Both are main-thread Room access. However, `loadFromRoom()` and `persistContact()` both have `try/catch (e: Exception)` wrappers, so Android's `IllegalStateException` is silently swallowed rather than crashing the app.

**Fix applied (v1.5.3):** Moved `ContactEngine.init()` to `scope.launch(Dispatchers.IO) { ... }` in `ChatActivity.onCreate()`.

### Claim 2: "NewStringUTF will crash the JNI thread" (confirmed, root cause of LLM chat crash)
**Status: CONFIRMED — root cause of Crash 2 (LLM chat crashes on "Hello")**

The claim correctly identifies the mechanism, but understates the severity. `env->NewStringUTF(result.c_str())` at `llama_jni.cpp` line 207 does not merely "crash the JNI thread" — it calls `abort()`, terminating the **entire process**. No Kotlin `try/catch (e: Throwable)` can intercept an OS-level signal from `abort()`.

Root cause chain:
1. LLM generates standard UTF-8 bytes including 4-byte sequences (emoji, chars ≥ U+10000)
2. `llama_token_to_piece()` produces raw bytes that are valid standard UTF-8
3. `NewStringUTF()` requires Modified UTF-8 — 4-byte sequences are invalid
4. JNI calls `abort()` → process dies → no Kotlin exception, no logcat entry from app

Warm-up succeeds (4 ASCII tokens). `generateSmsReply()` at temperature=0.7 quickly produces non-BMP output → immediate crash.

**Fix applied (v1.5.3):** Added `toJavaString()` helper that creates a Java byte array from raw bytes and constructs a `String` via `new String(bytes, "UTF-8")`. This handles all Unicode including supplementary characters. Replaced `return env->NewStringUTF(result.c_str())` with `return toJavaString(env, result)`.

### Claim 3: "extractJsonValue regex fails on escaped quotes"
**Status: CORRECT — low impact**

The regex `\"$key\"\\s*:\\s*\"([^\"]+)\"` does not match escaped quotes inside values. However, the impact is low because `parseSimpleCommand()` provides a solid keyword-based fallback for all common actions, and the LLM (with temperature=0.0) rarely produces escaped quotes in its JSON output for this use case.

**Fix: Not applied** — fallback coverage is adequate. A proper JSON parser would be a future improvement.

### Claim 4: "ContactEngine.persistContact called synchronously"
**Status: PARTIALLY CORRECT**

`persistContact()` is correctly called on `Dispatchers.IO` in the main message processing flow (`MessageEngine` → IO scope). The only remaining synchronous call path was via `ChatActivity.onCreate()` → `ContactEngine.init()` → `syncAndroidContacts()` → `persistContact()`, which is fixed in Claim 1.

### Claim 5: "GmailApiClient.deleteAllMatching one HTTP call per email"
**Status: CORRECT — low priority**

Not fixed in this audit cycle. Batch API optimization is low priority relative to crash fixes.

---

## Additional Issues Discovered During Cross-Verification

### Issue A: SettingsHubActivity.addDivider() crashes on launch (CRITICAL)
**Root cause:** `setBackgroundResource(android.R.attr.listDivider)` passes an attribute ID (`0x010100a2`) as a drawable resource ID to `setBackgroundResource()`. The method calls `resources.getDrawable(id)`, which throws `Resources.NotFoundException` because attribute IDs are not drawable resource IDs.

The `setBackgroundColor(0x1AFFFFFF.toInt())` call immediately after it never executes — it's unreachable dead code inside the `apply { }` block after the crash.

**Fix applied (v1.5.3):** Removed `setBackgroundResource(android.R.attr.listDivider)`. The `setBackgroundColor()` call is sufficient and was already the intended behavior.

### Issue B: AiEngine.warmUp() catches Exception, not Throwable
**Root cause:** `warmUp()` used `catch (e: Exception)` which does not intercept `OutOfMemoryError`, `StackOverflowError`, or other `Error` subclasses that can occur during LLM inference. An uncaught `Error` in `warmUp()` would propagate out of `loadModel()`, setting the engine state to `ERROR` and failing the load.

**Fix applied (v1.5.3):** Widened to `catch (e: Throwable)` for consistency with the rest of the engine's error handling.

### Issue C: Main-thread ContactEngine init (corrected location — see Claim 1 above)
The original audit incorrectly attributed this to `MainActivity`. The actual source was `ChatActivity.onCreate()` line 79. Fixed as part of the Claim 1 resolution.

---

## Priority Suggestions

### 🚨 Critical Priority (Fixed in v1.5.3)
1.  **Settings Crash:** `SettingsHubActivity.addDivider()` crashed with `Resources.NotFoundException` on launch. Fixed by removing `setBackgroundResource(android.R.attr.listDivider)`.
2.  **LLM Chat Crash:** `NewStringUTF(result.c_str())` aborted the process when LLM produced emoji or supplementary Unicode. Fixed with `toJavaString()` byte-array helper.
3.  **Main Thread DB Access:** `ContactEngine.init()` in `ChatActivity.onCreate()` ran Room queries on the main thread. Fixed by dispatching to `Dispatchers.IO`.

### 🟡 High Priority (Fixed in v1.5.3)
1.  **WarmUp exception coverage:** Widened `catch (e: Exception)` to `catch (e: Throwable)` in `AiEngine.warmUp()`.

### 🔵 Medium Priority (Not yet fixed)
1.  **JSON Parsing for AI:** Replace regex-based `extractJsonValue` in `AiEngine.kt` with a proper JSON parser (e.g., `Gson` or `JSONObject`) to handle escaped characters and varied AI outputs.
2.  **Contact Resolution:** Improve `NotificationAdapter.resolveSender` to handle cases where names are ambiguous or not in the contact list more gracefully.

### 🟢 Low Priority / Optional
1.  **Efficiency in Gmail API:** Update `GmailApiClient.deleteAllMatching` to use the `batchModify` or `batchDelete` endpoints to reduce network overhead.
2.  **Race condition in EmailMonitor:** `isProcessing` flag should be set synchronously before the coroutine launch to fully eliminate the race window.
3.  **KV Cache Optimization:** In `llama_jni.cpp`, consider implementing prompt caching (saving the KV state of the system prompt) to significantly speed up generation for multi-turn conversations.
4.  **Theme Polish:** Ensure all activities consistently apply `ThemeHelper.applySavedTheme()` before `super.onCreate` to prevent theme "flashing" during transitions.

---

## New Issues Discovered During v1.5.3 Deep Audit

### Issue D: SettingsHubActivity uses AlertDialog.Builder (MINOR — FIXED)
`SettingsHubActivity.kt` line 76 used `AlertDialog.Builder(this)` (AppCompat) instead of
`MaterialAlertDialogBuilder(this)`. Per project conventions, Material3 theme requires
MaterialAlertDialogBuilder to prevent theme-resolution crashes during dialog inflation.
OnboardingActivity was already fixed for this in v1.5.2; SettingsHubActivity was overlooked.

**Fix applied:** Changed import and constructor to `MaterialAlertDialogBuilder`.

### Issue E: ContactEngine in-memory list not thread-safe (PRE-EXISTING — NOT FIXED)
`ContactEngine.contacts` is `mutableListOf<Contact>()` (an `ArrayList`, not thread-safe).
With the v1.5.3 fix dispatching `init()` to IO, `loadFromRoom()` does `contacts.clear()` then
`contacts.addAll()` on IO thread while `resolveLocalCommand()` reads `contacts` from Main thread.

This is a **pre-existing** issue that was NOT introduced by v1.5.3 — `AigentikService` has
always called `init()` on IO while `NotificationAdapter.resolveSender()` calls `findContact()`
on the notification listener thread. The v1.5.3 fix doesn't worsen this; it fixes the more
critical main-thread Room access. A future fix should use `CopyOnWriteArrayList` or
`synchronized` blocks.

### Issue F: EmailMonitor.isProcessing race window (PRE-EXISTING — NOT FIXED)
Documented in original audit. The `isProcessing` flag is checked before `scope.launch {}` (line 54)
but set inside the launched coroutine (lines 74, 123). Two rapid notifications could both pass
the check and launch concurrent fetch coroutines. Impact is low — worst case is duplicate
email processing, and `markAsRead()` prevents double-replies.

### Issue G: toJavaString() edge case — pending exception after OOM (THEORETICAL — NOT FIXED)
If `env->NewObject()` in `toJavaString()` throws a Java OOM, a pending exception is set.
The subsequent `env->NewStringUTF("")` fallback is technically undefined behavior per JNI spec
when a pending exception exists. In practice, all mainstream Android JVMs handle this gracefully
by returning NULL, which the `result ? result : env->NewStringUTF("")` handles. To be fully
correct, add `env->ExceptionCheck()` + `env->ExceptionClear()` before the fallback. Not a
crash-level risk on any shipping Android version.

---

## v1.5.3 Deep Audit Verification Summary

### Crash 1: Settings Menu — RESOLVED ✅
`setBackgroundResource(android.R.attr.listDivider)` removed. Only `setBackgroundColor()` remains.
Layout XML `?android:attr/listDivider` is safe (theme resolution by inflater, not programmatic).
`AlertDialog.Builder` → `MaterialAlertDialogBuilder` for dialog theme consistency.

### Crash 2: LLM Chat — RESOLVED ✅
`toJavaString()` helper replaces `NewStringUTF(result.c_str())`. Full Unicode supported.
Traced complete execution path: ChatActivity → MessageEngine → AiEngine.interpretCommand() →
AiEngine.generateSmsReply() → LlamaJNI.generate() → nativeGenerate() → toJavaString().
All `NewStringUTF()` calls remaining in the file use only ASCII strings (empty, error messages,
model info).

### Warm-up Phase — RESOLVED ✅
`catch(Throwable)` handles OOM and Error subclasses. `toJavaString()` prevents JNI aborts.
Warm-up failure is non-fatal (model still marked READY; first real call is just slower).

### Gmail Processing — NO REGRESSIONS ✅
Email processing paths (`EmailMonitor` → `GmailApiClient` → `MessageEngine`) are unchanged
by v1.5.3. Pre-existing issues (isProcessing race, per-email trash) documented but not worsened.

---

## Conclusion
Aigentik-Android v1.5.3 resolves all identified crash-level issues. The Settings Hub crash
(attr ID misuse), LLM chat crash (JNI Modified UTF-8 violation), and main-thread Room database
access are all fully fixed. The deep audit confirms no regression risks from the v1.5.3 changes.
The remaining issues are pre-existing architectural concerns (thread-safe collections, batch
API optimization) with low practical impact.

---

## v1.5.5 Improvements (Medium and Low Priority)

### 1. AiEngine JSON Parsing — FIXED ✅
**Previous:** `extractJsonValue()` used `Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")` which failed
on escaped quotes inside values, numeric JSON fields, and `null` (JSON null, not the string).

**Fix:** Replaced `extractJsonValue()` and `parseCommandJson()` with `org.json.JSONObject`
parsing. `optString(key, "")` handles all edge cases. Fallback: `JSONException` (malformed JSON,
e.g. LLM output truncated at max_tokens boundary) triggers `parseSimpleCommand(text)` as before.
No new dependencies — `org.json` is part of the Android framework.

### 2. GmailApiClient.deleteAllMatching — FIXED ✅
**Previous:** One `POST /messages/{id}/trash` per matched email — O(N) HTTP calls.

**Fix:** Two-phase approach:
- Phase 1: collect all matching IDs across all pages (unchanged pagination logic)
- Phase 2: single `POST /messages/batchModify` per 1000 IDs with
  `{"addLabelIds":["TRASH"],"removeLabelIds":["INBOX"]}`

Network overhead drops from O(N) to O(⌈N/1000⌉). Already uses the existing `gson.toJson()`
helper and the `post()` HTTP helper (which correctly handles 204 No Content responses).

### 3. llama_jni.cpp KV Prefix Caching — IMPLEMENTED ✅
**Previous:** `resetContext()` was called at the start of every `nativeGenerate()` call,
destroying the KV cache and requiring full prompt prefill (tokenize + decode all tokens).

**Fix:** KV cache prefix reuse:
- Tokenize the new prompt first (before deciding to reset)
- Compare new prompt tokens against `g_last_prompt_tokens` (saved from previous call)
- If common prefix ≥ 50 tokens: call `llama_kv_cache_seq_rm(g_ctx, 0, common_prefix, -1)`
  to trim the KV cache to the common prefix, then decode only the new tokens
- If no useful prefix: `resetContext()` as before (first call, model reload, different prompt)
- `g_last_prompt_tokens` and `g_last_prompt_n` cleared on model load and unload

**Expected impact:** `interpretCommand()` uses a ~700-token system prompt that is identical for
every chat message. First message: full 700-token prefill. Subsequent messages: skip 700 tokens,
only decode the 10-20 token user message. Significant speedup for multi-turn chat.

**Regression risk:** Low. The `llama_kv_cache_seq_rm()` API is stable in llama.cpp. Fallback
to `resetContext()` covers edge cases. The `toJavaString()` crash fix is untouched.

### 4. ThemeHelper Ordering — FIXED ✅
**Previous:** All 8 activities called `super.onCreate()` before `ThemeHelper.applySavedTheme()`.
`AppCompatDelegate.setDefaultNightMode()` must be called before `super.onCreate()` to take
effect on the current activity (the AppCompat delegate is initialized in `super.onCreate()`).
Calling it after means the theme only applies on activity recreation.

**Fix:** Reordered all `onCreate()` methods to:
```
AigentikSettings.init(this)    // loads SharedPreferences — safe before super
ThemeHelper.applySavedTheme()  // sets AppCompatDelegate mode before delegate init
super.onCreate(savedInstanceState)
setContentView(...)
```
Activities fixed: ChatActivity, SettingsHubActivity, SettingsActivity, OnboardingActivity,
ModelManagerActivity, RuleManagerActivity, AiDiagnosticActivity, MainActivity.
Also added `AigentikSettings.init(this)` + import to RuleManagerActivity (was missing — could
throw `UninitializedPropertyAccessException` on `prefs` if launched without a prior activity).

### Remaining Open Issues (Not Fixed in v1.5.5 — all fixed in v1.6.0)

1. **ContactEngine thread safety** — FIXED in v1.6.0 (see below).
2. **EmailMonitor.isProcessing race** — FIXED in v1.6.0 (see below).
3. **toJavaString() OOM edge case** — FIXED in v1.6.0 (see below).
4. **Conversation history in chat** — FIXED in v1.6.0 (see below).

---

## v1.6.0 — Stability Hardening: Thread Safety, Race Fix, JNI Hygiene, Chat History

### 1. ContactEngine Thread Safety — FIXED ✅
**Previous:** `contacts = mutableListOf<Contact>()` — a plain `ArrayList`, not thread-safe.
Concurrent access paths:
- IO thread: `init()` → `loadFromRoom()` (write: clear+addAll) and `syncAndroidContacts()` (add)
- Main thread: `resolveLocalCommand()` reads `contacts` via `findContact()`, `getCount()`
- IO thread: `MessageEngine.handlePublicMessage()` reads and writes contacts

The `clear()+addAll()` sequence in `loadFromRoom()` exposed a window where concurrent readers
could observe an empty list between the clear and the addAll.

**Fix:** Changed to `@Volatile CopyOnWriteArrayList<Contact>`.
- `loadFromRoom()` now does `contacts = CopyOnWriteArrayList(newList)` — atomic reference
  replacement. Readers either see the old complete list or the new complete list, never empty.
- Reads (`find`, `filter`, `size`) are lock-free — no blocking on Main thread.
- Writes (`add` in `findOrCreate*`, `syncAndroidContacts`) use CopyOnWriteArrayList's
  copy-on-write semantics — thread-safe without locking.
- `@Volatile` on the reference ensures the assignment in `loadFromRoom()` is immediately
  visible to all threads (Java memory model visibility guarantee).

**Regression prevention:** CopyOnWriteArrayList has identical API to ArrayList for all
operations used (add, find via iterator, filter). No behavioral change in any code path.

### 2. EmailMonitor isProcessing Race — FIXED ✅
**Previous:** `@Volatile private var isProcessing = false`.
Race window: flag was *checked* in `onGmailNotification()` (on the notification listener thread)
but *set to true* inside the `scope.launch { }` body (on an IO thread). Two rapid Gmail
notifications arriving in quick succession could both pass the `if (isProcessing)` check before
either coroutine ran and set the flag, launching two concurrent fetch coroutines.

**Fix:** Replaced with `private val isProcessing = AtomicBoolean(false)`.
- `onGmailNotification()` now uses `compareAndSet(false, true)` — a single atomic
  read-modify-write operation. Only one caller can transition false→true; all others see
  `true` and return early. No scheduling gap exists.
- The `finally { isProcessing.set(false) }` is in the `scope.launch { }` wrapper, so it
  executes unconditionally even if `processFromHistory` or `processUnread` throws.
- Removed redundant `isProcessing = true` from `processFromHistory()` and `processUnread()`,
  and removed their `finally { isProcessing = false }` blocks (now handled by the wrapper).

**Regression prevention:** `isRunning()` updated to `!isProcessing.get()`. All existing
callers unchanged. Worst-case scenario (duplicate fetch) was prevented before by `markAsRead()`;
the fix eliminates the condition itself.

### 3. toJavaString() JNI Exception Hygiene — FIXED ✅
**Previous:** After `NewObject()` failure (OOM during String construction), a pending Java
exception exists. The subsequent `env->NewStringUTF("")` fallback was technically undefined
behaviour per JNI spec when a pending exception is present (only a small set of JNI functions
is safe to call with a pending exception).

**Fix:** Added `ExceptionCheck()` + `ExceptionClear()` before every fallback `NewStringUTF("")`
call that follows a potentially failing allocation:
1. After `NewByteArray()` returns null — clear pending OOM before fallback.
2. After `FindClass("java/lang/String")` returns null — clear + fallback.
3. After `NewObject()` returns null — `if (!result && ExceptionCheck()) ExceptionClear()`.

Also added null-check for `FindClass` return value (was previously assumed non-null).
Added null-check for `charset` before `DeleteLocalRef(charset)`.

**Regression prevention:** Inference path (`nativeGenerate`), context reset, sampler chain,
and the `toJavaString()` call site are all unchanged. Only the error-handling paths inside
`toJavaString()` are modified. Under normal operation (no OOM), code path is identical to v1.5.

### 4. Chat Conversation History — FIXED ✅
**Previous:** `ConversationHistoryDatabase` was only populated for public messages (SMS, email).
Chat (admin/CHAT channel) messages were stored only in `ChatDatabase` for UI display, but NOT
in `ConversationHistoryDatabase`. As a result, the AI had no memory of prior chat exchanges —
every chat message was answered without any conversation context.

**Fix:** In `MessageEngine.handleAdminCommand()`, the "genuine conversation" `else` branch
(where a general chat message triggers `AiEngine.generateSmsReply()`):
1. Calls `loadHistory("owner", "CHAT")` to retrieve the last `CONTEXT_WINDOW_TURNS` (6) turns.
2. Records the user's input with `recordHistory("owner", "CHAT", "user", input)` before generation.
3. Passes the loaded history to `generateSmsReply(..., conversationHistory = chatHistory)`.
4. Records Aigentik's reply with `recordHistory("owner", "CHAT", "assistant", replyForHistory)`.
   Signature stripped before storing (using `substringBefore("\n\n—")`) for cleaner context.

**Guard conditions:**
- Only applies when `message.channel == Message.Channel.CHAT`. Remote admin commands
  (SMS/email with auth) do not get recorded in chat history.
- `historyDao` null-safe: if `MessageEngine.configure()` hasn't been called yet (service not
  running), `loadHistory` returns `emptyList()` and `recordHistory` is a no-op — no crash.
- Session gap (2 hours) and trim (20 turns max) rules from `ConversationHistoryDatabase` apply
  identically to chat, consistent with SMS/email channel behaviour.

**No schema migration required:** `ConversationTurn.channel` already supports "CHAT" as a
value (documented in the entity's comment since v1.4.6). The existing Room schema is unchanged.

**Regression prevention:** This change only adds new logic to the `else` branch that previously
had no history. All other branches (`gmail_*`, `find_contact`, etc.) are unmodified.
`generateSmsReply()` already accepts `conversationHistory: List<String> = emptyList()` so
the added argument is backwards-compatible. No new DB migration needed.

---

## Deferred Issues — v1.6 Deep Audit

No new issues were discovered during v1.6 implementation that require deferral.
The four issues listed under "Remaining Open Issues (Not Fixed in v1.5.5)" above are all
resolved in v1.6.0.

Pre-existing known limitations (not architectural issues, not regressions):
1. **Remote admin + conversation history** — remote admin commands (SMS/email with auth)
   that reach the "genuine conversation" AI branch are not recorded in chat history. These
   are rare (admin sends an unusual command the AI doesn't recognize) and the omission is
   intentional: remote admin sessions are command-control, not persistent conversations.
2. **historyDao race on first chat** — if ChatActivity sends a message before AigentikService
   configures MessageEngine, `historyDao` is null and the exchange is not recorded. This is
   the same pre-existing condition that affected SMS/email history and is acceptable.
3. **ContactEngine init race (theoretical)** — if `init()` is called concurrently from both
   ChatActivity and AigentikService (rare timing), both will call `loadFromRoom()` which
   atomically swaps the reference twice. The second swap wins. In practice both loads produce
   identical data from Room. CopyOnWriteArrayList handles any concurrent `add()` calls safely.
