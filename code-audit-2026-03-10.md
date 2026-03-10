# Aigentik Android — Code Audit Report
**Date:** 2026-03-10
**Time:** ~current session
**Auditor:** Claude Opus 4.6
**Version Audited:** v1.6.3 (versionCode 71)
**Scope:** Root-cause analysis for two reported crashes:
  1. App closes on first install after LLM model loads
  2. App closes after entering a second message in chat (requires cache clear to reopen)

---

## EXECUTIVE SUMMARY

Two separate but related crash vectors were identified. The primary crash on second message is most likely caused by a combination of **missing Throwable catch in ChatActivity** and **overlapping safety-timeout coroutines**. The first-install crash is caused by an **unthrottled email flood** that saturates the LLM and native memory during cold start. Several additional issues of varying severity were found during the audit.

---

## CRASH 1: App closes on first install after LLM model loads

### Root Cause: Unthrottled Email Processing Flood

**Flow that triggers the crash:**
1. AigentikService.initAllEngines() loads the model (~30-60s)
2. After model load, EmailMonitor.init() is called
3. On first install there is NO stored historyId
4. A Gmail notification triggers EmailMonitor.onGmailNotification()
5. No historyId → falls back to `processUnread()` → fetches up to **10 unread emails** at once
6. Each email calls `MessageEngine.onMessageReceived()` which does `scope.launch { ... }`
7. **All 10 coroutines launch concurrently** — each tries to call `AiEngine.generateEmailReply()`
8. LlamaJNI's ReentrantLock serializes them, but all 10 coroutines are alive holding Message/email objects
9. **Each `nativeGenerate()` call destroys and recreates the 128MB KV context** via `resetContext()`
10. 10 sequential context destroy/create cycles = ~1.3GB of native alloc/free churn
11. Total processing time: 10 × 30-60s = **5-10 minutes** of sustained LLM + memory pressure

**Why it crashes:**
- The native memory allocator fragments after repeated 128MB alloc/free cycles
- If `llama_init_from_model()` fails internally (not returning null but hitting an mmap/malloc failure), it can trigger `SIGABRT` or `SIGSEGV` at the C++ level — **uncatchable by Kotlin try/catch**
- Android's Low Memory Killer may kill the process: model (~4GB) + context (128MB) + 10 concurrent coroutines + email data + Room + JVM heap
- No wake lock is held during this period (see Bug #5 below), so Samsung may also throttle the CPU, extending the high-memory-pressure window

**File:** `EmailMonitor.kt:158-175` (processUnread), `MessageEngine.kt:208-219` (scope.launch per message), `llama_jni.cpp:110-131` (resetContext)

### Recommended Fix: Rate-limit email processing

**Option A: Sequential processing with a queue (Recommended)**
- In `processUnread()` and `processFromHistory()`, call `processEmail()` and WAIT for the entire MessageEngine pipeline (including LLM generation) to complete before processing the next email
- Use a `Channel<Message>` or a semaphore to ensure only 1 email triggers LLM at a time
- Pros: Eliminates memory pressure from 10 concurrent coroutines; predictable behavior
- Cons: Emails processed slower (sequential); requires architectural change to make processEmail suspend until reply is sent

**Option B: Cap concurrent LLM calls with a Semaphore**
- Add a `Semaphore(1)` in MessageEngine that wraps LLM calls
- Any message that needs LLM acquires the semaphore first
- Pros: Simple to implement; works across all channels
- Cons: Coroutines still alive (holding memory) while waiting for semaphore

**Option C: Limit processUnread to 3 emails**
- Change `listUnread(context, maxResults = 10)` → `maxResults = 3` in processUnread
- Pros: Simplest change (one number)
- Cons: Doesn't fix the architectural issue; misses unread emails; doesn't protect against rapid notification bursts

**Recommendation:** Option A (sequential) is safest. Option B is a good stopgap. Option C alone is insufficient but should be combined with A or B.

---

## CRASH 2: App closes after entering second chat message

### Root Cause: Multiple interacting bugs

This crash has multiple contributing factors. Any one of them could be the direct trigger depending on timing.

### Bug 2a (CRITICAL): ChatActivity.sendMessage catches Exception, not Throwable

**File:** `ChatActivity.kt:194`
```kotlin
} catch (e: Exception) {
```

This catches `Exception` but NOT `Error` subclasses (`OutOfMemoryError`, `StackOverflowError`). If any operation in the sendMessage coroutine throws an `Error` — such as OOM from Room operations under memory pressure, or a propagated native error — it **bypasses the catch block entirely**.

Combined with Bug 2b, this kills the process.

**Fix:** Change to `catch (e: Throwable)`. This is consistent with the hardening already done in MessageEngine (v1.8) and EmailMonitor (v4.2).

**Risk:** None. Throwable catch is strictly more protective. No regression possible.

### Bug 2b (CRITICAL): No CoroutineExceptionHandler on ChatActivity's scope

**File:** `ChatActivity.kt:50`
```kotlin
private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
```

This scope has **no CoroutineExceptionHandler**. When an uncaught exception/error escapes a coroutine launched from this scope, it propagates to the thread's `UncaughtExceptionHandler` — which on Android **kills the process**.

MessageEngine, EmailMonitor, and EmailRouter all have `CoroutineExceptionHandler` on their scopes. ChatActivity is the only component missing one.

**Fix:** Add a CoroutineExceptionHandler:
```kotlin
private val scope = CoroutineScope(
    Dispatchers.Main + SupervisorJob() +
    CoroutineExceptionHandler { _, e ->
        Log.e("ChatActivity", "Uncaught: ${e.javaClass.simpleName}: ${e.message}", e)
    }
)
```

**Risk:** None. Prevents process death from any uncaught error in chat coroutines.

### Bug 2c (HIGH): Safety-timeout coroutines overlap across messages

**File:** `ChatActivity.kt:152-204`

Each call to `sendMessage()` launches a coroutine that includes `delay(120_000)` (120s safety timeout). When the user sends message 1, coroutine A starts with a 120s timeout. If the response arrives quickly (say 10s), `awaitingResponse` is set to false by `observeMessages`. The user sends message 2, coroutine B starts with its own 120s timeout.

**But coroutine A is still alive** — its delay(120s) hasn't expired. After 110 more seconds:
```kotlin
delay(120_000)
if (awaitingResponse) {
    awaitingResponse = false
    hideThinking()
    btnSend.isEnabled = true
}
```

Coroutine A checks `awaitingResponse`. If message 2's response hasn't arrived yet (e.g., LLM is busy with emails), coroutine A's timeout **fires for message 2**, resetting the UI prematurely. This causes:
- UI appears stuck (thinking hidden, but no response yet)
- If the response arrives later, it posts to Room, but `awaitingResponse` is already false — no visible issue
- BUT: If the user sends message 3 immediately after the premature reset, and message 2's response also arrives, there's a UI state mismatch

**More critically:** Multiple overlapping timeout coroutines running on Main thread can interact unexpectedly with the observeMessages Flow, potentially causing UI state inconsistencies that lead to exceptions.

**Fix:** Cancel the previous timeout coroutine when a new message is sent:
```kotlin
private var timeoutJob: Job? = null

// In sendMessage():
timeoutJob?.cancel()
timeoutJob = scope.launch {
    delay(120_000)
    if (awaitingResponse) { ... }
}
```

**Risk:** Low. Cancelling a delay coroutine is safe. Ensures only one timeout is active.

### Bug 2d (HIGH): Email auto-replies trigger false awaitingResponse reset

**File:** `ChatActivity.kt:218-222`
```kotlin
if (awaitingResponse && messages.lastOrNull()?.role == "assistant") {
    awaitingResponse = false
    hideThinking()
    btnSend.isEnabled = true
}
```

When the user sends a chat message (`awaitingResponse = true`), and BEFORE the response arrives, an **email auto-reply notification** is posted via `ChatBridge.post()` → Room insert → Flow emission. The last message in the list is now an "assistant" message (the email notification), so the condition is true and `awaitingResponse` is set to false.

This means:
- The "Thinking..." indicator disappears prematurely
- The send button re-enables
- The user can send another message before the first one's response arrives
- The first response eventually arrives and is silently added to chat (no UI indication)

If the user rapidly sends message 2 while message 1 is still being processed:
- Two MessageEngine coroutines running simultaneously
- Both try to acquire the LLM lock
- Second one blocks → long wait → the 120s timeout from message 1 might fire for message 2

**Fix:** Use a unique request ID. When sending a message, generate a unique ID. When the response arrives (ChatBridge.post), include the request ID. Only reset `awaitingResponse` when the matching response arrives.

Alternative simpler fix: Check that the last assistant message's timestamp is AFTER the user message that triggered the wait.

**Risk:** Medium. Requires some refactoring of the chatNotifier interface.

---

## ADDITIONAL FINDINGS

### Bug 3 (HIGH): Wake lock not held during early email processing

**File:** `AigentikService.kt:128-138`, `MessageEngine.kt:211-212`

MessageEngine.configure() sets the wake lock. But EmailMonitor can fire BEFORE configure() completes (EmailMonitor.init is called at line 105, configure at line 128). When emails are processed in this window, `wakeLock` is null:

```kotlin
val wl = wakeLock  // null
wl?.acquire(...)   // no-op
```

Without a wake lock, Samsung aggressively throttles CPU in background. LLM inference that normally takes 30s takes 5+ minutes, extending the memory-pressure window significantly and increasing the chance of the process being killed.

**Fix:** Move `MessageEngine.configure()` to BEFORE `EmailMonitor.init()` in `initAllEngines()`. Or set the wake lock on MessageEngine earlier.

**Risk:** Low. Just reordering init calls.

### Bug 4 (HIGH): handlePublicMessage catches Exception, not Throwable

**File:** `MessageEngine.kt:942`
```kotlin
} catch (e: Exception) {
    Log.e(TAG, "Public message failed: ${e.message}")
}
```

Inconsistent with `handleAdminCommand` which catches `Throwable` (line 761). If `generateEmailReply()` or `generateSmsReply()` throws an `Error` (OOM from native-side), it bypasses this catch. MessageEngine's `CoroutineExceptionHandler` would catch it and log, but the email reply is silently dropped with no owner notification.

**Fix:** Change to `catch (e: Throwable)` and add a notify() call so the owner knows a reply failed.

**Risk:** None.

### Bug 5 (MEDIUM): observeMessages loads ALL chat messages without limit

**File:** `ChatActivity.kt:209-224`, `ChatDao.kt:14-15`

```kotlin
@Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
fun getAllMessages(): Flow<List<ChatMessage>>
```

Every Room insert triggers a Flow emission that loads the **entire** chat_messages table. On the Main thread, all views are removed and re-inflated. With many messages (accumulated from email auto-replies via ChatBridge.post), this causes:
- Increasing main-thread work per emission
- At 100+ messages: ANR risk (5s main thread block)
- Memory pressure from inflating many views

**Fix:** Add a LIMIT to the query (e.g., last 100 messages), or use a RecyclerView with paging (Room PagingSource). Minimum fix: `SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT 100` reversed in code.

**Risk:** Low for LIMIT approach. Medium for RecyclerView (larger change but better long-term).

### Bug 6 (MEDIUM): activeNotifications in NotificationAdapter not thread-safe

**File:** `NotificationAdapter.kt:38`
```kotlin
private val activeNotifications = mutableMapOf<String, StatusBarNotification>()
```

`onNotificationPosted` and `onNotificationRemoved` can run on different threads. `mutableMapOf()` returns a `LinkedHashMap` which is NOT thread-safe. Under load (rapid notification bursts), this can throw `ConcurrentModificationException`.

This is the same class of bug fixed in v1.6.3 for `DestructiveActionGuard`, `AdminAuthManager`, and `NotificationReplyRouter`. This map was missed.

**Fix:** Change to `ConcurrentHashMap<String, StatusBarNotification>()`.

**Risk:** None. Drop-in replacement.

### Bug 7 (LOW): GmailApiClient.get() potential response body double-read

**File:** `GmailApiClient.kt:80-95`

```kotlin
val resp = http.newCall(req).execute()
if (!resp.isSuccessful) {
    val respBody = resp.body?.string()?.take(200) ?: ""  // reads body
    ...
    return@withContext null
}
lastError = null
JsonParser.parseString(resp.body?.string()).asJsonObject  // reads body again
```

OkHttp response bodies can only be consumed once. On the success path (line 95), `resp.body?.string()` reads the body. But `resp.body` is only read once here (the error path reads it separately and returns). So there's no actual double-read bug in the current code. However, the response is not wrapped in `.use { }` — if an exception occurs between execute() and string(), the connection leaks.

**Note:** postRaw() was fixed in v1.6.3 to use `.use { }`, but get() and post() were not.

**Fix:** Wrap the response in `.use { }`:
```kotlin
http.newCall(req).execute().use { resp ->
    ...
}
```

**Risk:** None. Standard OkHttp best practice.

### Bug 8 (LOW): ConversationHistoryDao synchronous queries lack @WorkerThread annotation

**File:** `ConversationHistoryDao.kt`

All DAO methods are synchronous (not suspend). They're currently called from Dispatchers.IO (correct), but there's no `@WorkerThread` annotation to enforce this at compile time. A future change could accidentally call these from the Main thread, causing Room to throw `IllegalStateException`.

**Fix:** Add `@WorkerThread` annotation to all non-suspend DAO methods, or make them suspend functions.

**Risk:** None for annotation. Low for suspend conversion.

### Bug 9 (LOW): LlamaJNI.isLoaded() reads g_model/g_ctx without holding mutex

**File:** `llama_jni.cpp:274-276`
```cpp
JNIEXPORT jboolean JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeIsLoaded(JNIEnv*, jobject) {
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}
```

This reads two global pointers without holding `g_mutex`. If another thread is in `resetContext()` (which sets `g_ctx = nullptr` then creates a new one), `isLoaded()` could return false transiently. This is a TOCTOU (time-of-check-time-of-use) race.

In practice, this causes `AiEngine.isReady()` to occasionally return false during context reset, leading to a fallback reply instead of an AI reply. Not a crash, but an incorrect behavior.

**Fix:** Acquire the mutex in nativeIsLoaded, or accept the race (current behavior is safe, just occasionally wrong).

**Risk:** Very low. Current behavior degrades gracefully.

---

## RECOMMENDED FIX PRIORITY

| Priority | Bug | File(s) | Effort |
|----------|-----|---------|--------|
| 1 | 2a: catch(Throwable) in sendMessage | ChatActivity.kt:194 | 1 line |
| 2 | 2b: CoroutineExceptionHandler on ChatActivity scope | ChatActivity.kt:50 | 3 lines |
| 3 | 1: Rate-limit email processing (Semaphore or queue) | MessageEngine.kt + EmailMonitor.kt | Medium |
| 4 | 2c: Cancel previous timeout on new message | ChatActivity.kt:152-204 | 5 lines |
| 5 | 2d: Fix awaitingResponse false positive from email notifications | ChatActivity.kt:218-222 | Medium |
| 6 | 3: Move MessageEngine.configure before EmailMonitor.init | AigentikService.kt:105-128 | Reorder |
| 7 | 4: catch(Throwable) in handlePublicMessage | MessageEngine.kt:942 | 1 line |
| 8 | 6: ConcurrentHashMap for activeNotifications | NotificationAdapter.kt:38 | 1 line |
| 9 | 5: Limit message query | ChatDao.kt + ChatActivity.kt | Small |
| 10 | 7: Response .use{} in get()/post() | GmailApiClient.kt | Small |

**Fixes 1-2 are likely sufficient to stop the second-message crash.**
**Fix 3 is needed to stop the first-install crash.**
**Fixes 4-6 prevent the conditions that make crashes 1 and 2 more likely.**

---

## CRASH REPRODUCTION HYPOTHESIS

### Second message crash (most likely sequence):
1. User sends message 1 → sendMessage coroutine A starts
2. If emails are being processed concurrently, ChatBridge.post fires → awaitingResponse set to false (Bug 2d)
3. User sends message 2 → sendMessage coroutine B starts (coroutine A still running its 120s delay)
4. Message 2 → MessageEngine → generateChatReply with conversation history
5. Under memory pressure (model + emails + Room), an OOM or native error occurs
6. Error propagates to sendMessage's catch(Exception) — **NOT caught** (Bug 2a)
7. Error reaches ChatActivity's scope — **no CoroutineExceptionHandler** (Bug 2b)
8. Propagates to Android's UncaughtExceptionHandler → **process killed**

### First install crash (most likely sequence):
1. Model loads (4GB native memory)
2. Gmail notification triggers processUnread → 10 emails fetched
3. All 10 dispatched to MessageEngine simultaneously
4. Each calls generateEmailReply → nativeGenerate → resetContext (128MB alloc/free each)
5. After several context resets, native memory is fragmented
6. One of the resetContext calls fails catastrophically in llama.cpp (not a null return, but a SIGABRT from a failed mmap)
7. Native signal → **process killed** (uncatchable by Kotlin)
8. OR: Android LMK kills the process due to sustained high memory usage (4GB+ for 5+ minutes)

---

## NOTES

- All findings are based on code review only (no runtime testing, no logcat)
- The native crash hypothesis for Crash 1 would need logcat/tombstone analysis to confirm
- The Crash 2 hypothesis could be confirmed by adding `Log.e` inside a `catch(Throwable)` block in sendMessage and checking logcat
- The v1.6.3 hardening (ConcurrentHashMap, catch Throwable) was well-targeted but missed ChatActivity.kt and NotificationAdapter.kt
