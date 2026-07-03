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
| Unauthenticated, stranded (onboarded + online + reason `None`) | `Blocked(FinishingSetup)` **and** kicks `GuestSessionHealer.heal("authGate")` fire-and-forget — honest *now*, the retry succeeds once the heal lands |
| Unauthenticated, offline | `Blocked(Offline)` — couldn't confirm ≠ no account |
| Unauthenticated, server killed the session | `Blocked(SessionExpired)` |
| Otherwise (signed out, pre-onboarding, unresolved) | `Blocked(NeedAccount)`; unresolved fails closed and never heals |

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
- Healer (single-flight): `libraries/identity/impl/…/auth/GuestSessionHealer.kt`
- Nav adapter: `libraries/navigation/impl/…/RealAuthGateChecker.kt`; sheet copy: `apps/compose/…/AuthGateSheet.kt`
