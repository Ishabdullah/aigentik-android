# Aigentik Android — Claude Code Context
You are continuing development of Aigentik — a privacy-first Android AI assistant app. Here is the complete project context:

## PROJECT OVERVIEW
- App: Aigentik Android (com.aigentik.app)
- Repo: ~/aigentik-android (local Termux) + GitHub (builds via Actions)
- Current version: v1.3.3 (versionCode 43)
- Developer environment: Samsung S24 Ultra, Termux only — NO Android Studio, NO local Gradle builds
- All builds happen via GitHub Actions → APK downloaded and sideloaded

## ARCHITECTURE
- On-device AI inference via llama.cpp (C++ NDK, arm64-v8a)
- Model: GGUF format stored on device /sdcard or internal storage
- SMS/RCS auto-reply via NotificationListenerService (Samsung Messages inline reply)
- Gmail monitoring via notification trigger → Gmail REST API (OAuth2)
- Google Voice SMS forwarded as Gmail → parsed and replied to
- No cloud backend, no Firebase SDK, no polling — everything on-device
- AI runs fully offline (llama.cpp JNI bridge)

## KEY FILES
- app/src/main/java/com/aigentik/app/core/AigentikService.kt — main foreground service (v1.1)
- app/src/main/java/com/aigentik/app/core/AigentikSettings.kt — SharedPreferences wrapper
- app/src/main/java/com/aigentik/app/core/MessageEngine.kt — AI command processor
- app/src/main/java/com/aigentik/app/core/Message.kt — unified message object
- app/src/main/java/com/aigentik/app/core/ChannelManager.kt — channel state tracker
- app/src/main/java/com/aigentik/app/core/RuleEngine.kt — message filtering rules
- app/src/main/java/com/aigentik/app/core/ContactEngine.kt — contact database + Android sync
- app/src/main/java/com/aigentik/app/core/MessageDeduplicator.kt — SMS/notification dedup
- app/src/main/java/com/aigentik/app/core/PhoneNormalizer.kt — E.164 phone formatting
- app/src/main/java/com/aigentik/app/core/ChatBridge.kt — service→Room DB bridge
- app/src/main/java/com/aigentik/app/auth/GoogleAuthManager.kt — OAuth2 manager
- app/src/main/java/com/aigentik/app/auth/AdminAuthManager.kt — remote admin auth
- app/src/main/java/com/aigentik/app/auth/DestructiveActionGuard.kt — destructive action confirmation
- app/src/main/java/com/aigentik/app/email/GmailApiClient.kt — Gmail REST API via OkHttp
- app/src/main/java/com/aigentik/app/email/EmailMonitor.kt — notification-triggered Gmail fetch
- app/src/main/java/com/aigentik/app/email/EmailRouter.kt — routes email replies
- app/src/main/java/com/aigentik/app/adapters/NotificationAdapter.kt — NotificationListenerService (v1.3)
- app/src/main/java/com/aigentik/app/adapters/SmsAdapter.kt — SMS BroadcastReceiver
- app/src/main/java/com/aigentik/app/adapters/NotificationReplyRouter.kt — inline reply PendingIntent
- app/src/main/java/com/aigentik/app/ai/AiEngine.kt — AI inference controller
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
- app/google-services.json — Firebase/Google OAuth config
- .github/workflows/build.yml — CI build pipeline

## v1.3.3 AUDIT AND FIXES (2026-02-26)
Full codebase audit by Claude Code identified and fixed these issues:

### CRITICAL FIXES
1. **Service startup gate removed** — AigentikService.kt blocked startup when gmailAppPassword
   was empty. OAuth2 users had no app password → service never initialized. Now service always
   starts; Gmail features just disabled if not signed in. SMS/chat work independently.
2. **SEND_SMS permission added** — SmsRouter.send() uses SmsManager.sendTextMessage() but
   SEND_SMS was never declared in AndroidManifest.xml. Would crash at runtime. Fixed.
3. **ChatBridge initialized in service** — ChatBridge.init(db) was only called from ChatActivity.
   If user never opened chat before receiving a message, all notifications silently failed.
   Now initialized in AigentikService.onCreate() before MessageEngine.

### HIGH-PRIORITY FIXES
4. **send_email command wired** — MessageEngine.kt had a TODO stub that always returned false.
   Now calls EmailRouter.sendEmailDirect() via GmailApiClient. Also validates email address
   before attempting send.
5. **Gmail notifications routed correctly** — NotificationAdapter was treating Gmail notifications
   (com.google.android.gm) the same as SMS. It tried to resolve email sender names as phone
   numbers, which always failed. Now Gmail package detected separately and routes to
   EmailMonitor.onGmailNotification() for proper OAuth2 REST API fetch.

### CLEANUP FIXES
6. **Version bumped** — build.gradle.kts was stuck at versionCode 38 / versionName 1.2.1
   despite git commits claiming v1.3.2. Now correctly at versionCode 43 / versionName 1.3.3.
7. **strings.xml SHA-1 comment corrected** — Had wrong SHA-1 from Gemini (1E:BB:FD:3F...).
   Updated to correct fixed keystore SHA-1 (E6:76:61:28:5F:6C...).
8. **constraintlayout version fixed** — Was 1.0.1 (2017 alpha). Updated to 2.1.4.
9. **gmailAppPassword deprecated** — Marked @Deprecated with message to use OAuth2.
10. **Settings validation updated** — validate() no longer blocks on empty gmail if OAuth signed in.

### WHAT WAS ALREADY WORKING (no changes needed)
- LlamaJNI / llama_jni.cpp — full JNI bridge, Q8_0 KV cache, 8k context
- AiEngine — load, warm, SMS/email reply generation, command parsing
- ContactEngine — Android sync, find/create, per-contact reply behavior
- RuleEngine — SMS + email filtering rules with persistence
- AdminAuthManager — remote auth, SHA-256, 30-min sessions
- DestructiveActionGuard — password confirmation flow
- NotificationReplyRouter — inline reply via PendingIntent (Samsung + Google Messages)
- GmailApiClient — full REST API (list, read, send, reply, delete, GVoice parsing)
- All UI activities — dashboard, chat, settings, onboarding, model manager
- Room chat database — persistent history with streaming display
- Build pipeline — GitHub Actions with fixed keystore, llama.cpp compile

## GOOGLE SIGN-IN STATUS
Still needs testing with v1.3.3. Fixed keystore SHA-1 should resolve ApiException code 10.

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

## GMAIL OAUTH SCOPES REQUESTED
- https://www.googleapis.com/auth/gmail.modify
- https://www.googleapis.com/auth/gmail.send
- https://www.googleapis.com/auth/gmail.readonly
- https://www.googleapis.com/auth/contacts.readonly

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

## TESTING PLAN (v1.3.3)
1. Google Sign-In — should work with fixed keystore (test ApiException code)
2. Gmail notification trigger → EmailMonitor → fetch unread → process
3. GVoice SMS forwarding detection and reply routing via email thread
4. Admin password setup and remote command authentication
5. send_email command via chat (was stubbed, now wired)
6. SMS sending (SEND_SMS permission was missing, now added)
7. ChatBridge notifications appearing in chat history without opening chat first

## NEXT TASKS AFTER v1.3.3 CONFIRMED WORKING
1. UI theme refresh — modernize dashboard/chat to match logo aesthetic
2. Test full Gmail read/send/delete flow via GmailApiClient
3. Test Google Voice forwarding pipeline end to end
4. Consider release keystore setup for production APK
5. Add SEND_SMS to runtime permission request in MainActivity

## DEVELOPER PREFERENCES
- Production-ready code only
- Always ask clarifying questions before writing code
- Explain what you will do and get approval before making changes
- Keep project plan and log updated after every change
- Note areas of concern in code comments
- No Firebase SDK — privacy-first, on-device only
- No cloud backends, no telemetry
- Always give complete copy-paste commands for Termux
- Version bump with every commit
- All Termux paths use /data/data/com.termux/files/home/
