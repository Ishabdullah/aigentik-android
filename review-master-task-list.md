# Aigentik Android — Review Master Task List
**Source**: aigentik-android-review.md (Gemini CLI, Feb 27 2026)
**Generated**: Feb 28 2026
**Status legend**: ⬜ Pending | 🔄 In Progress | ✅ Done | ⏸ Blocked (needs decision) | 🚫 Policy Violation

---

## CATEGORY A — High Priority Fixes / Recommended (from review)

| # | Task | File(s) | Status |
|---|------|---------|--------|
| A1 | Update `llama_jni.cpp` to use temperature + top-p sampling instead of greedy | `cpp/llama_jni.cpp`, `ai/LlamaJNI.kt`, `ai/AiEngine.kt` | ✅ |
| A2 | Add temperature param to `LlamaJNI.generate()` JNI bridge | `ai/LlamaJNI.kt` | ✅ |
| A3 | Expose configurable temperature in `AiEngine` (lower for commands, higher for chat) | `ai/AiEngine.kt` | ✅ |
| A4 | Enhance `ConnectionWatchdog` to post high-priority notification when OAuth token lost | `system/ConnectionWatchdog.kt` | ✅ |
| A5 | Room migration for `ContactEngine` (JSON → Room SQLite) | `core/ContactEngine.kt` + new Room files | ✅ |
| A6 | Room migration for `RuleEngine` (JSON → Room SQLite) | `core/RuleEngine.kt` + new Room files | ✅ |

---

## CATEGORY B — Medium Priority Improvements

| # | Task | File(s) | Status |
|---|------|---------|--------|
| B1 | Filter common English words from `DestructiveActionGuard.confirmWithPassword()` to reduce accidental confirmation risk | `auth/DestructiveActionGuard.kt` | ✅ |
| B2 | Increase `MessageDeduplicator` fingerprint from 50 to 100 characters to reduce false dedup on long similar messages | `core/MessageDeduplicator.kt` | ✅ |

---

## CATEGORY C — Low Priority Improvements

| # | Task | File(s) | Status |
|---|------|---------|--------|
| C1 | Expose `nativeLibLoaded` flag in `LlamaJNI` so `AiEngine.getStateLabel()` can show "Native lib error" vs "Not loaded" | `ai/LlamaJNI.kt`, `ai/AiEngine.kt` | ✅ |
| C2 | Improve battery optimization discoverability — already done via addActivity() hint in MainActivity | `ui/MainActivity.kt` | ✅ Already done in v1.3.3 |

---

## CATEGORY D — Security Improvements

| # | Task | File(s) | Status |
|---|------|---------|--------|
| D1 | Add backup exclusion rules to protect `contacts.json`, `rules/`, and `chat_database` from cloud backup | `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | ✅ |
| D2 | Add code comment in `ContactEngine` noting plaintext storage risk | `core/ContactEngine.kt` | ✅ |

---

## CATEGORY E — Performance Improvements

| # | Task | File(s) | Status |
|---|------|---------|--------|
| E1 | Add comment in `llama_jni.cpp` documenting KV cache reset tradeoff (clean state vs prefill speed) — no code change needed, already documented | `cpp/llama_jni.cpp` | ✅ Already documented; expanded in v1.2 |
| E2 | Update `AndroidManifest.xml` service foreground type comment — note `specialUse` consideration for future Android versions | `AndroidManifest.xml` | ✅ |

---

## CATEGORY F — Partially Implemented — Complete These

| # | Task | File(s) | Status |
|---|------|---------|--------|
| F1 | `ConnectionWatchdog` error recovery: automated user-facing notification (supersedes A4) | `system/ConnectionWatchdog.kt` | ✅ Done as A4 |
| F2 | Contact Management: NL command to set per-contact instructions | `core/MessageEngine.kt`, `ai/AiEngine.kt` | ✅ |
| F3 | Rule Management: GUI to view/edit rules | new `ui/RuleManagerActivity.kt`, new layout, `ui/SettingsActivity.kt` | ✅ |

---

## CATEGORY G — Optional Improvements (from review "Optional Improvements" section)

| # | Task | File(s) | Status |
|---|------|---------|--------|
| G1 | Add AI Diagnostic screen: test native library loading and inference speed | new `ui/AiDiagnosticActivity.kt`, new `res/layout/activity_ai_diagnostic.xml` | ✅ |
| G2 | Add list of downloaded GGUF models in `ModelManagerActivity` with switch button | `ui/ModelManagerActivity.kt`, `res/layout/activity_model_manager.xml` | ✅ |

---

## CATEGORY H — Optional Long-Term (from review "Long-Term Structural Recommendations")

| # | Task | File(s) | Status |
|---|------|---------|--------|
| H1 | Contextual Memory: per-contact conversation history window for remote channels (SMS/Email) | `core/MessageEngine.kt` + new Room files | ✅ Room-backed, 2hr gap reset, 20 turn trim |
| H2 | Multi-Model Support: UI to switch between multiple downloaded GGUF models | `ui/ModelManagerActivity.kt` | ✅ Covered by G2 |

---

## OPEN ARCHITECTURAL DECISIONS

### Decision D1 — Room Migration (ContactEngine + RuleEngine)
The review recommends migrating from JSON files to Room/SQLite for better data integrity and performance at scale.
**Options presented separately in clarifying question — awaiting user decision.**

### Decision D2 — Contextual Memory Storage
The review recommends per-contact conversation history for remote channels.
**Options presented separately in clarifying question — awaiting user decision.**

---

## MANIFEST / WIRING TASKS

| # | Task | Status |
|---|------|--------|
| M1 | Register `RuleManagerActivity` in `AndroidManifest.xml` | ✅ |
| M2 | Register `AiDiagnosticActivity` in `AndroidManifest.xml` | ✅ |
| M3 | Add "Manage Rules" button to `SettingsActivity` + layout | ✅ |
| M4 | Add "AI Diagnostic" button to `SettingsActivity` + layout | ✅ |

---

## EXCLUDED ITEMS (Policy Violations — Not Implementing)

*None identified. All review suggestions are compatible with the privacy policy.*

---

## PROPOSED ALTERNATIVES FOR DEFERRED ITEMS

*None required — all items are implementable within policy constraints.*

---

**Total tasks**: 24 (including D1+D2 decisions)
**Status**: ALL COMPLETE ✅
**Files modified**: 15 existing files
**Files created**: 13 new files (10 Kotlin, 2 XML layouts, 1 backup_rules.xml, 1 data_extraction_rules.xml)
**Version**: v1.4.6 (versionCode 56)
