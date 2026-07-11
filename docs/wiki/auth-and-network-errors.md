# Auth readiness & network errors — one authority, honest failures

"Can this user do this, and if not, what do we honestly tell them?" is answered in exactly one place: `AuthGateImpl` (`:libraries:identity:impl`). The navigation gate and the authed call boundary both consult it, so they cannot disagree — an offline guest is told "you're offline," never "you need an account." Errors are data; routing is a consumer decision. Nothing auto-navigates because a failure propagated.

## The vocabulary (`:libraries:core`)

- **`AuthRequirement`** — what an action needs: `None` / `Account` (any server session, guests included) / `ClaimedAccount`. Declared per-route (`Route.authRequirement`) and per-call (`authedCall(requirement = …)`, defaulted to `Account`).
- **`AuthReason`** — why it's blocked: `FinishingSetup`, `NeedAccount`, `NeedClaimedAccount`, `Offline`, `SessionExpired`. Drives the gate sheet's copy and typed call failures alike.
- **`AuthVerdict`** — `Ready | Blocked(reason)`, produced by `AuthGate.verdict()` (sync peek, fail-closed while auth resolves — the router path) or `awaitVerdict()` (suspends for resolve — the call path, so launch-time syncs aren't wrongly blocked).

## The verdict table

| State | Verdict |
|---|---|
| Authenticated, requirement met | `Ready` — **even offline**: we hold a (possibly stale) session; the call fails at transport as an ordinary network error, never through the gate |
| Authenticated guest, needs `ClaimedAccount` | `Blocked(NeedClaimedAccount)` |
| Unauthenticated, guest creation in progress / failed | `Blocked(FinishingSetup)` — it self-heals |
| Unauthenticated, stranded (onboarded + online + reason `None`) | `Blocked(FinishingSetup)` **and** kicks `GuestSessionHealer.heal("authGate")` fire-and-forget — honest *now*, the retry succeeds once the heal lands. The heal is a *decision*, not an unconditional mint — see the ladder below |
| Unauthenticated, offline | `Blocked(Offline)` — couldn't confirm ≠ no account |
| Unauthenticated, session declared dead (server rejection or client-unrecoverable) | `Blocked(SessionExpired)` |
| Otherwise (signed out, pre-onboarding, unresolved) | `Blocked(NeedAccount)`; unresolved fails closed and never heals |

## The healer's decision ladder (`GuestSessionHealer`)

Triggered on cold boot, warm boot, non-cold-boot foreground, connectivity-regained, and the gate's stranded row. Single-flight — overlapping triggers skip (every source re-fires on a later edge). Each run logs one structured `heal source=… action=…` line. The ladder, in order:

1. **Already `Authenticated`** → skip.
2. **`AuthRepository.retry()`** — re-resolve the persisted session. Recovers a dormant/refreshable token without minting anything (`RETRY_RECOVERED`).
3. **Reason `SessionExpired`** → skip. The session is declared dead; the user is on (or headed to) the recovery screen — minting a guest here would paper over a real account.
4. **Reason `SignedOut`** → skip. Deliberate sign-out isn't resurrected as a fresh guest.
5. **A cached server-backed profile exists** (`ProfileRepository.current()` is `Profile.Authenticated`) → **never mint** (AUTH-19). Storage lost the token but this device demonstrably had a real account; a fresh guest would silently strand its chips/XP. Instead: `AuthRepository.markSessionUnrecoverable(wasAnonymous)` — the client-declared twin of a server rejection. Same teardown: clear the supabase session, emit `Unauthenticated(SessionExpired)`, and the app routes to the recovery screen (retry / explicit sign-in / start fresh).
6. **Not onboarded** → skip (onboarding's own start mints). **Offline** → skip (the connectivity-regained edge retries).
7. Otherwise → **mint** via `GuestAccountCreator.ensureSession`. Success flips auth to Authenticated; the `UserChanged` fan-out re-syncs everything.

Two AUTH-19 guarantees back this up in the repository layer:

- **`SessionExpired` is sticky across re-resolves.** A retry (connectivity flip, healer step 2, recovery-screen retry) that still finds no session keeps `Reason.SessionExpired` (and `wasAnonymous`) instead of decaying to `None` — which would have re-armed the silent mint one foreground later.
- **Anonymous sessions keep a file-backed mirror** (`SessionMirrorStore`, consulted by `SecureSessionManager` when the OS-encrypted store comes up empty). Keychain/EncryptedSharedPreferences can lose the session across an app upgrade while ordinary app files survive; for an anonymous account there's no credential to sign back in with, so the mirror *is* the recovery path. Claimed sessions clear the mirror and stay Keychain-only — a real credential exists to recover with, so they don't take the security trade.

## The call boundary (`authedCall`)

- **Pre-flight:** a `Blocked` verdict short-circuits to `Catching.failure(AuthUnready(reason))` without touching the wire. No phantom 401s; short-circuits log at *info*, real failures at *warn*.
- **Mid-flight:** if the bearer refresh is *rejected by the auth server* during a call, `SessionRejectionBus.rejectionEpoch` bumps synchronously (inside the refresh, before the final 401 propagates); `authedCall` sees 401 + epoch bump and remaps to `AuthUnready(SessionExpired)`. A raw 401 without a confirmed rejection is a transient refresh failure — it stays an ordinary `ResponseException`, and repos read it as a connectivity problem, never "sign in."
- **Consuming:** callers declare intent by how they consume the `Catching` —
  - surface: `.mapAuthFailure { reason -> reason.toOutcome() }`
  - ignore (background sync): `.getOrNull()` (or `ifAuthenticated {}` to skip entirely)
  - route: `.onAuthFailure { reason -> router.navigate(AuthGateRoute(reason)) }`

  The operator set is deliberately two; don't grow it without a call site that needs more.
- **Server error envelopes:** `ResponseException.apiProblemOrNull()` / `apiErrorCode()` / `apiErrorMessage()` decode the `{"error":{"code","message"}}` shape once, for every repo.

## Sibling axes (deliberately NOT `AuthReason`)

- **`AccessDeniedBus`** — "we confirmed you and you're blocked" (banned/suspended, 403 envelope) → full-screen `AccessDeniedRoute`. Different cause, recovery, and weight from "we can't confirm you"; kept separate on purpose.
- **`SessionRejectionBus`** — token layer → auth layer teardown signal for a server-rejected session. The epoch above is its only call-boundary surface.

## Key files

- Vocabulary + operators: `libraries/core/src/commonMain/kotlin/com/cards/libraries/core/AuthGate.kt`
- The brain: `libraries/identity/impl/…/auth/AuthGateImpl.kt` (verdict table test: `AuthGateImplTest`)
- Call boundary: `libraries/networking/…/NetworkCall.kt` (`NetworkCallTest`, scenario: `AuthReadinessScenarioTest`)
- Healer (single-flight): `libraries/identity/impl/…/auth/GuestSessionHealer.kt` (`GuestSessionHealerTest`)
- Auth state + sticky SessionExpired + `markSessionUnrecoverable`: `libraries/identity/impl/…/auth/SupabaseAuthRepositoryImpl.kt`
- Anonymous-session mirror: `libraries/identity/impl/…/auth/SessionMirrorStore.kt`, restored by `SecureSessionManager.kt`
- Nav adapter: `libraries/navigation/impl/…/RealAuthGateChecker.kt`; sheet copy: `apps/compose/…/AuthGateSheet.kt`
