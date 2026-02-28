# Gmail Review Implementation Progress

**Source**: `aigentik-gmail-review.md` (Forensic Audit)
**Started**: 2026-02-28
**Base version**: v1.4.6 (versionCode 56)
**Target version**: v1.4.7 (versionCode 57)

---

## Master Task List

### CRITICAL FIXES
| # | Item | Status | File(s) |
|---|------|--------|---------|
| 1 | Handle UserRecoverableAuthException in getFreshToken() | DONE | GoogleAuthManager.kt v1.8 |
| 2 | Fix token deadlock — enable incremental consent flow | DONE | GoogleAuthManager.kt + SettingsActivity.kt v2.2 |

### HIGH PRIORITY FIXES
| # | Item | Status | File(s) |
|---|------|--------|---------|
| 3 | Update Settings UI — "Grant Gmail Permissions" button | DONE | SettingsActivity.kt + activity_settings.xml |
| 4 | Fix silent startup prime failure | DONE | GmailHistoryClient.kt v1.2 |

### RECOMMENDED FIXES
| # | Item | Status | File(s) |
|---|------|--------|---------|
| 5 | Better error logging/propagation in GmailApiClient | DONE | GmailApiClient.kt v1.3 |
| 6 | Better Gmail error messages in MessageEngine | DONE | MessageEngine.kt v1.5 |

### RECOMMENDED-OPTIONAL
| # | Item | Status | File(s) |
|---|------|--------|---------|
| 7 | Scope Health Check in AiDiagnosticActivity | DONE | AiDiagnosticActivity.kt v1.1 + layout |
| 8 | ConnectionWatchdog enhanced OAuth monitoring | DONE | ConnectionWatchdog.kt v2.2 |

### OPTIONAL LONG-TERM
| # | Item | Status | File(s) |
|---|------|--------|---------|
| 9 | Credentials Manager API migration | DOCUMENTED | N/A (future work) |
| 10 | WorkManager periodic deep sync | POLICY CONFLICT | N/A (see notes) |
| 11 | SHA-1 Registry verification | ALREADY FIXED | build.yml |

### VERSION BUMP
| # | Item | Status | File(s) |
|---|------|--------|---------|
| 12 | Version bump to v1.4.7 + CLAUDE.md update | DONE | build.gradle.kts + CLAUDE.md |

---

## Implementation Log

### Item 1: Handle UserRecoverableAuthException (CRITICAL)
- **Status**: DONE
- **File**: `GoogleAuthManager.kt` v1.7 → v1.8
- **Changes**:
  - Import `UserRecoverableAuthException`
  - Catch `UserRecoverableAuthException` specifically before generic `Exception`
  - Store resolution Intent in `pendingScopeIntent`
  - Added `scopeResolutionListener` callback for UI notification
  - Added `gmailScopesGranted` flag, `lastTokenError` diagnostic string
  - Added `hasPendingScopeResolution()`, `onScopeConsentGranted()`, `onScopeConsentDenied()`
  - `signOut()` now clears all scope state

### Item 2: Fix token deadlock — incremental consent flow
- **Status**: DONE
- **File**: `SettingsActivity.kt` v2.1 → v2.2
- **Changes**:
  - After Google sign-in success, calls `attemptGmailTokenFetch()` to trigger consent
  - Registers `scopeResolutionListener` in `onResume()` (auto-launches consent dialog)
  - Handles `RC_GMAIL_CONSENT` activity result (OK → re-fetch token, cancel → show error)
  - Calls `ConnectionWatchdog.checkNow()` after sign-in and scope grant to dismiss alerts

### Item 3: Update Settings UI
- **Status**: DONE
- **Files**: `activity_settings.xml`, `SettingsActivity.kt`
- **Changes**:
  - Added `tvGmailScopeStatus` — shows "Permissions granted" / "Permissions needed"
  - Added `btnGrantGmailPerms` — orange button, appears when scopes not granted
  - Both hidden when not signed in, scope status shown when signed in
  - Button launches stored `pendingScopeIntent` or attempts token fetch

### Item 4: Fix silent startup prime
- **Status**: DONE
- **File**: `GmailHistoryClient.kt` v1.1 → v1.2
- **Changes**:
  - `primeHistoryId()` now returns `PrimeResult` enum (ALREADY_STORED, PRIMED_FROM_API, NO_TOKEN, API_ERROR, NETWORK_ERROR)
  - `lastPrimeResult` exposed for diagnostics
  - Specific error logging for 401/403 HTTP codes
  - Scope resolution check on NO_TOKEN result
  - `AigentikService.kt` updated to log specific prime result

### Item 5: Better error propagation in GmailApiClient
- **Status**: DONE
- **File**: `GmailApiClient.kt` v1.2 → v1.3
- **Changes**:
  - Added `lastError` volatile field tracking specific failure reasons
  - `get()`/`post()`/`postRaw()` now set `lastError` with HTTP-code-specific messages
  - Distinguishes: no token, 401 auth expired, 403 access denied, 404, 429 rate limit, network error
  - Added `checkTokenHealth()` method calling `users.getProfile`

### Item 6: Better Gmail error messages in MessageEngine
- **Status**: DONE
- **File**: `MessageEngine.kt` v1.4 → v1.5
- **Changes**:
  - Added `requireGmailReady()` helper checking: context, sign-in, scope grant
  - Returns specific error: "Not signed in", "Gmail permissions needed", or "Gmail not initialized"
  - All 11 Gmail actions now use `requireGmailReady()` instead of bare `appContext` check
  - Keyword fallback Gmail sections also use `requireGmailReady()`
  - `check_email` action shows auth status breakdown

### Item 7: Scope Health Check in AiDiagnosticActivity
- **Status**: DONE
- **Files**: `AiDiagnosticActivity.kt` v1.0 → v1.1, `activity_ai_diagnostic.xml`
- **Changes**:
  - Added Gmail Health section to layout (sign-in, scopes, historyId, check button, result)
  - Shows sign-in email, scope grant status, historyId prime result
  - "Check Gmail Token" button calls `GmailApiClient.checkTokenHealth()` (users.getProfile)
  - Shows token validity, API accessibility, and specific error on failure

### Item 8: ConnectionWatchdog enhanced OAuth monitoring
- **Status**: DONE
- **File**: `ConnectionWatchdog.kt` v2.1 → v2.2
- **Changes**:
  - Now monitors THREE conditions: sign-in, scope grant, scope resolution pending
  - Separate notifications for "session lost" (REAUTH_NOTIFICATION_ID) vs "scope needed" (SCOPE_NOTIFICATION_ID)
  - Added `checkNow()` for immediate check after SettingsActivity sign-in/grant
  - Scope notification is auto-cancelled when scopes are granted
  - Reauth notification dismissed when session restored (existing behavior preserved)

### Items 9–11: Long-term / Documentation only
- See Notes section below

---

## Notes

### SHA-1 Registry (Item 11)
Already resolved in the existing codebase. The build system stores a fixed debug keystore
as a GitHub Actions secret (DEBUG_KEYSTORE_BASE64). SHA-1 is registered in Google Cloud Console:
`E6:76:61:28:5F:6C:27:9D:14:34:C5:66:2C:1E:17:4E:32:67:9D:80`

### WorkManager Deep Sync (Item 10)
**POLICY CONFLICT**: The review recommends WorkManager for periodic "Deep Syncs".
This conflicts with Aigentik's architecture principles:
- CLAUDE.md explicitly states: "No polling loops"
- Privacy policy: "No background data harvesting"
- Current architecture: notification-triggered only (EmailMonitor v4.0)

**Compliant Alternative**: Instead of periodic polling, enhance the existing
notification-triggered flow with a manual "Sync Now" button in Settings or
AiDiagnosticActivity. This gives the user explicit control without background polling.

### Credentials Manager API (Item 9)
The review recommends upgrading from GoogleSignIn to Credentials Manager API.
This is a significant migration that would:
- Replace GoogleSignInClient with CredentialManager
- Change the auth flow from startActivityForResult to credential request
- Require minimum API 28 (current minSdk is 26)

**Decision**: Document as future work. Current GoogleSignIn flow works correctly
once the UserRecoverableAuthException is handled (Item 1). Migration planned
for a future version when the current auth flow needs replacement.

### Manual Verification Checklist (from review)
The following cannot be fixed by code changes and must be verified manually:
1. Google Cloud Console → Gmail API is "Enabled"
2. OAuth consent screen is in "Testing" mode
3. Test user `ismail.t.abdullah@gmail.com` is added
4. SHA-1 matches fixed keystore
5. Android Settings → Notification Access → Aigentik = ON
6. App Info → Battery → Unrestricted
