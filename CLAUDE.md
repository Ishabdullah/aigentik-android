# Aigentik Android — Claude Code Context
You are continuing development of Aigentik — a privacy-first Android AI assistant app. Here is the complete project context.

## PROJECT OVERVIEW
- App: Aigentik Android (com.aigentik.app)
- Repo: ~/aigentik-android (local Termux) + GitHub (builds via Actions)
- Current version: v1.4.1 (versionCode 51)
- Developer environment: Samsung S24 Ultra, Termux only — NO Android Studio, NO local Gradle builds
- All builds happen via GitHub Actions → APK downloaded and sideloaded

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
- Scopes requested: gmail.modify, gmail.send, contacts.readonly
- No scope for cloud infra (Pub/Sub was added and removed — never add it back)

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
- Google Voice SMS forwarded as Gmail → parsed and replied to via email thread
- Gmail natural language interface: chat/SMS commands → AiEngine.interpretCommand() → Gmail API actions
- No cloud backend, no Firebase SDK, no polling loops — everything on-device
- AI runs fully offline (llama.cpp JNI bridge)
- historyId cursor stored in SharedPreferences — primed from Gmail profile API on start, advanced per notification

## KEY FILES
- app/src/main/java/com/aigentik/app/core/AigentikService.kt — main foreground service (v1.5)
- app/src/main/java/com/aigentik/app/core/AigentikSettings.kt — SharedPreferences wrapper
- app/src/main/java/com/aigentik/app/core/MessageEngine.kt — AI command processor (v1.3)
- app/src/main/java/com/aigentik/app/core/Message.kt — unified message object (has subject field)
- app/src/main/java/com/aigentik/app/core/ChannelManager.kt — channel state tracker
- app/src/main/java/com/aigentik/app/core/RuleEngine.kt — message filtering rules
- app/src/main/java/com/aigentik/app/core/ContactEngine.kt — contact database + Android sync
- app/src/main/java/com/aigentik/app/core/MessageDeduplicator.kt — SMS/notification dedup
- app/src/main/java/com/aigentik/app/core/PhoneNormalizer.kt — E.164 phone formatting
- app/src/main/java/com/aigentik/app/core/ChatBridge.kt — service→Room DB bridge
- app/src/main/java/com/aigentik/app/auth/GoogleAuthManager.kt — OAuth2 manager (v1.7)
- app/src/main/java/com/aigentik/app/auth/AdminAuthManager.kt — remote admin auth
- app/src/main/java/com/aigentik/app/auth/DestructiveActionGuard.kt — destructive action confirmation (word-by-word code extraction)
- app/src/main/java/com/aigentik/app/email/GmailApiClient.kt — Gmail REST API via OkHttp
- app/src/main/java/com/aigentik/app/email/EmailMonitor.kt — notification-triggered Gmail fetch (v4.0)
- app/src/main/java/com/aigentik/app/email/EmailRouter.kt — routes email replies
- app/src/main/java/com/aigentik/app/email/GmailHistoryClient.kt — Gmail History API + historyId storage (v1.1)
- app/src/main/java/com/aigentik/app/adapters/NotificationAdapter.kt — NotificationListenerService (v1.3)
- app/src/main/java/com/aigentik/app/adapters/SmsAdapter.kt — SMS BroadcastReceiver
- app/src/main/java/com/aigentik/app/adapters/NotificationReplyRouter.kt — inline reply PendingIntent
- app/src/main/java/com/aigentik/app/ai/AiEngine.kt — AI inference + command parser (CommandResult has query field)
- app/src/main/java/com/aigentik/app/ai/LlamaJNI.kt — JNI wrapper for llama.cpp
- app/src/main/java/com/aigentik/app/ui/MainActivity.kt — dashboard
- app/src/main/java/com/aigentik/app/ui/ChatActivity.kt — chat interface
- app/src/main/java/com/aigentik/app/ui/SettingsActivity.kt — settings + Google sign-in
- app/src/main/java/com/aigentik/app/ui/OnboardingActivity.kt — first run setup
- app/src/main/java/com/aigentik/app/ui/ModelManagerActivity.kt — model download/load
- app/src/main/java/com/aigentik/app/chat/ChatDatabase.kt — Room database singleton
- app/src/main/java/com/aigentik/app/chat/ChatMessage.kt — Room entity
- app/src/main/java/com/aigentik/app/chat/ChatDao.kt — Room DAO
- app/src/main/java/com/aigentik/app/system/BootReceiver.kt — auto-start after reboot
- app/src/main/java/com/aigentik/app/system/ConnectionWatchdog.kt — OAuth session monitor
- app/src/main/java/com/aigentik/app/system/BatteryOptimizationHelper.kt — battery settings
- app/src/main/cpp/llama_jni.cpp — JNI bridge C++ (Q8_0 KV, 8k ctx, 6 threads)
- app/src/main/cpp/CMakeLists.txt — CMake build for llama.cpp
- app/google-services.json — Firebase/Google OAuth config (OAuth only, no Firebase SDK)
- .github/workflows/build.yml — CI build pipeline

## CHANGE LOG

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
- **GmailApiClient.kt** — fixed post() for HTTP 204 No Content; added postRaw() for batchDelete;
  added: getEmailMetadata(), listUnreadSummary(), countUnreadBySender(), searchEmailIds(),
  batchMarkRead(), emptyTrash(), getOrCreateLabel(), addLabel(), getUnsubscribeLink()
- **AiEngine.kt** — added query field to CommandResult; expanded interpretCommand() with 11
  new Gmail actions and example JSON; updated parseSimpleCommand() with Gmail patterns
- **MessageEngine.kt v1.3** — added appContext, channelKey() helper; guard check at top of
  handleAdminCommand() for chat-channel confirmations; 11 Gmail action handlers:
  gmail_count_unread, gmail_list_unread, gmail_search, gmail_trash, gmail_trash_all,
  gmail_mark_read, gmail_mark_read_all, gmail_mark_spam, gmail_label, gmail_unsubscribe,
  gmail_empty_trash; EMAIL channel now uses generateEmailReply()
- **DestructiveActionGuard.kt** — updated confirmWithPassword() to extract admin code
  word-by-word (user can say "yes delete 1984" and 1984 is extracted as the code)
- **Message.kt** — added subject field (carries email subject for better reply generation)
- Build: versionCode 50, versionName 1.4.0

### v1.3.8 — App icon update (2026-02-26)
Updated launcher icon to Aigentik brand image, using mipmap launcher icons.

### v1.3.3 AUDIT AND FIXES (2026-02-26)
Full codebase audit by Claude Code identified and fixed these issues:

#### CRITICAL FIXES
1. **Service startup gate removed** — AigentikService.kt blocked startup when gmailAppPassword
   was empty. OAuth2 users had no app password → service never initialized. Now service always
   starts; Gmail features just disabled if not signed in. SMS/chat work independently.
2. **SEND_SMS permission added** — SmsRouter.send() uses SmsManager.sendTextMessage() but
   SEND_SMS was never declared in AndroidManifest.xml. Would crash at runtime. Fixed.
3. **ChatBridge initialized in service** — ChatBridge.init(db) was only called from ChatActivity.
   If user never opened chat before receiving a message, all notifications silently failed.
   Now initialized in AigentikService.onCreate() before MessageEngine.

#### HIGH-PRIORITY FIXES
4. **send_email command wired** — MessageEngine.kt had a TODO stub that always returned false.
   Now calls EmailRouter.sendEmailDirect() via GmailApiClient.
5. **Gmail notifications routed correctly** — NotificationAdapter now detects com.google.android.gm
   and routes to EmailMonitor.onGmailNotification() instead of treating it like SMS.

#### CLEANUP FIXES
6. Version bumped to versionCode 43 / versionName 1.3.3
7. strings.xml SHA-1 comment corrected to fixed keystore SHA-1
8. constraintlayout version fixed from 1.0.1 (2017 alpha) to 2.1.4
9. gmailAppPassword marked @Deprecated — use OAuth2 instead
10. Settings validation updated — no longer blocks on empty gmail if OAuth signed in

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
  GVoice SMS? → parse GVoiceMessage → MessageEngine.onMessageReceived()
  Regular email → buildEmailMessage() → MessageEngine.onMessageReceived()
        ↓
MessageEngine → AiEngine.generateEmailReply() → GmailApiClient.reply()
        ↓
GmailApiClient.markAsRead()
```

## GMAIL NL INTERFACE — SUPPORTED ACTIONS

All commands require admin authentication. Destructive actions require admin code confirmation.

| Action | Command example | Confirm required |
|---|---|---|
| gmail_count_unread | "how many unread emails" | No |
| gmail_list_unread | "list my emails" | No |
| gmail_search | "find emails from amazon" | No |
| gmail_trash | "delete email from John" | Yes — admin code |
| gmail_trash_all | "delete all emails from promo@co.com" | Yes — admin code |
| gmail_mark_read | "mark email as read" | No |
| gmail_mark_read_all | "mark all emails as read" | No |
| gmail_mark_spam | "mark as spam" | Yes — admin code |
| gmail_label | "label email as Work" | No |
| gmail_unsubscribe | "unsubscribe from newsletter" | Yes — admin code |
| gmail_empty_trash | "empty trash" | Yes — double confirm |

Confirmation UX: Aigentik says "Reply with your admin code to confirm (e.g. 'yes delete [code]')".
User replies naturally ("yes delete 1984") — code is extracted word-by-word.

## GOOGLE SIGN-IN STATUS
Fixed keystore SHA-1 resolves ApiException code 10.

### Root cause chain (for reference):
1. GitHub Actions generates a NEW random debug keystore every build
2. Each build has different SHA-1 → never matches what's registered in Google Cloud
3. Fixed by storing base64-encoded keystore in GitHub secret DEBUG_KEYSTORE_BASE64
4. build.yml decodes and installs it before every build

### Config:
- Fixed keystore SHA-1: E6:76:61:28:5F:6C:27:9D:14:34:C5:66:2C:1E:17:4E:32:67:9D:80
- certificate_hash: e67661285f6c279d1434c5662c1e174e32679d80
- Android OAuth client: 630924077353-gmv67c8n0lad1q5u9q6v8t41sf79l8uv.apps.googleusercontent.com
- Web OAuth client: 630924077353-oopagdmapkve24ehb6pjppeph2bf292c.apps.googleusercontent.com

## GOOGLE CLOUD / FIREBASE CONFIG
- Project ID: aigentik-android
- Project Number: 630924077353
- Firebase app ID: 1:630924077353:android:5c58e1d30f7983771f2f38
- API Key: AIzaSyAXRjCb1kN40hf43z359ZcFEAz4Sdw8arA
- OAuth consent screen: External, Testing mode
- Test user: ismail.t.abdullah@gmail.com
- APIs enabled: Gmail API, People API, Identity Toolkit API
- Pub/Sub API: NOT needed — removed from architecture

## GMAIL OAUTH SCOPES REQUESTED
- https://www.googleapis.com/auth/gmail.modify  (trash, labels, spam, batchModify)
- https://www.googleapis.com/auth/gmail.send    (compose and send)
- https://www.googleapis.com/auth/contacts.readonly
- NOTE: pubsub scope was added in v1.4.0 and removed in v1.4.1 — do not add it back

## BUILD SYSTEM
- Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Java 17
- NDK r27b (27.2.12479018), CMake 3.22.1
- llama.cpp cloned at build time (cached), compiled via CMake for arm64-v8a
- google-services plugin 4.4.4 applied (project + app level)
- NO Firebase SDK dependencies — only google-services plugin for OAuth config
- JavaMail 1.6.2 kept for MIME message building in GmailApiClient (send via REST)
- ConstraintLayout 2.1.4, OkHttp 4.12.0, Gson 2.10.1, Room 2.6.1

## TERMUX LIMITATIONS
- No sudo, no apt install of system packages without pkg
- Python available, pip needs --break-system-packages flag
- Pillow installed for icon generation
- adb available via android-tools package
- Cannot run Gradle locally (no memory/CPU for it on phone)
- All file paths use /data/data/com.termux/files/home/ not /root/ or /home/
- Internal storage accessible at /sdcard/ or /storage/emulated/0/
- Bash tool (Claude Code) fails with EACCES on /tmp — provide all commands for user to run manually

## TESTING PLAN (v1.4.1)
1. Google Sign-In — should work with fixed keystore (test ApiException code)
2. Gmail auto-reply: send email to account → Gmail notification appears → Aigentik replies
3. Gmail NL: type "how many unread emails" in chat → should return count + sender breakdown
4. Gmail NL: type "delete email from [sender]" → confirm prompt → reply with admin code
5. Gmail NL: type "empty trash" → double confirm → permanent deletion
6. GVoice SMS forwarding detection and reply routing via email thread
7. SMS sending (SEND_SMS permission was added in v1.3.3)
8. ChatBridge notifications appearing in chat history without opening chat first
9. historyId advances correctly — check logs after second email notification

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
