# CARDS-9C / CARDS-9M — expected `AuthUnready` control-flow leaking into Sentry as errors

- **Sentry:** https://elijah-dangerfield.sentry.io/issues/CARDS-9C (FinishingSetup) ·
  https://elijah-dangerfield.sentry.io/issues/CARDS-9M (NeedAccount)
- **Filed as todo:** ENG-29 (both issues collapse into one — same root cause)
- **Signal:** `com.dangerfield.cards.libraries.core.AuthUnready: auth unready: FinishingSetup`
  and `... auth unready: NeedAccount`
- **Severity:** `handled: yes`, `level: error` (caught, but reported to Sentry as error events — noise, not a crash)
- **Volume:** CARDS-9C 7 events / 1 user; CARDS-9M 6 events / 0 users. First/last seen 2026-07-11 ~20:2xZ.
- **Where:** `environment: beta-ios-release` (real TestFlight build), `commit_branch: main`,
  `commit_sha: 5ce8e0b3f8ad`, `release: cards@1.0+740`, `route: HomeRoute`, iPhone14,2, iOS 26.5.

## Why this is noise, not a bug in the flow

`AuthUnready` is a **deliberate, typed control-flow signal**, not a failure. The auth/network layer
short-circuits an authed call with a typed `AuthUnready(reason)` *without touching the wire* when auth
isn't ready — "no phantom 401s" (`libraries/networking/.../NetworkCall.kt`,
`libraries/core/.../AuthGate.kt:90`). Callers are meant to handle it with the `mapAuthFailure` /
`onAuthFailure` operators (`AuthGate.kt:93-102`). The reasons here are the benign startup states:

- `FinishingSetup` — auth is still bootstrapping (anonymous session healing / token refresh in flight).
- `NeedAccount` — no account yet (fresh/anonymous user).

On `HomeRoute` at cold start something fires an authed call (a wallet / progression / identity sync is
the likely trigger) before auth is ready, gets the expected `AuthUnready`, and — instead of mapping it
away — logs it at **error** level with the throwable attached.

## Root cause: no filter on the Sentry log bridge

`SentryLogTree.captureEvent` (`libraries/cards/impl/.../logging/SentryLogTree.kt:101-113`) forwards
**any** throwable logged at/above `minEventLevel` (Error) straight to `Sentry.captureException(it)`
with no allow/deny filter. So an expected `AuthUnready` that reaches an error-level log line becomes a
Sentry error event. Two fixes are viable (a worker should pick the right altitude):

1. **Central (preferred):** teach the Sentry event path to treat `AuthUnready` (and any typed
   "expected control-flow" throwable) as non-reportable — drop it before `captureException`, or demote
   it below `minEventLevel`. This is a one-place guard that also protects future call sites.
2. **Call site:** find the `HomeRoute` startup sync that logs the failed `Catching`/`Result` at error
   and route `AuthUnready` through `onAuthFailure` (or log it at debug), so it never hits the error tree.
   Grep target: an error-level log of a failed authed call reachable from `HomeViewModel`
   (`features/home/impl/.../HomeViewModel.kt`) / the wallet/progression sync it kicks off on entry.

## Impact

User-facing impact is nil (the call is expected to short-circuit and retry once auth is ready), but it
pollutes Sentry on a **real beta build** with false "errors" that can mask genuine issues and inflate
error counts. Log-spam-worth-silencing, per the observability playbook.

## Suggested fix / acceptance

Cold-starting an iOS session with auth still `FinishingSetup` / `NeedAccount` on `HomeRoute` produces
**zero** `AuthUnready` events in Sentry. Add a regression test around the Sentry log path (or the
call-site auth handling) asserting `AuthUnready` is not captured as an event.
