# 2026-08-19 — expected control-flow throwables reach Loki at ERROR, so the client error panel is mostly noise

**Signal:** Grafana / Loki, no Sentry issue (correctly, that is the whole point of this bug).
Surfaced by the nightly server+client log sweep, not by an alert or a panel.

```
{service_name="cards-client", deployment_environment="prod"} | detected_level=~"warn|error|fatal"
```

Window swept 2026-08-17T06:34Z → 2026-08-19T06:34Z for the run, then widened to 14d to size it.

## What the numbers say

Breakdown of every warn-or-worse client log line in prod over 14 days
(`sum by (exception_type, detected_level) (count_over_time(... [14d]))`):

| level | exception_type | lines |
|---|---|---|
| error | `AuthUnready` | 11 |
| error | (none) | 2 |
| warn | (none) | 21 |
| warn | `AuthUnready` | 3 |
| warn | `SocketException` | 10 |
| warn | `ClientRequestException` | 1 |

**11 of the 13 ERROR-level lines (85%) are `AuthUnready`.** The other 2 are the known CARDS-8V
chip-pack lines already owned by ENG-43 plus the App Store Connect item. Add the 10
`SocketException` warns and half of the entire warn+ stream is signal the codebase has already
decided is not a failure.

## Root cause

`AuthUnready` is declared as `ExpectedControlFlow`
(`libraries/core/src/commonMain/.../AuthGate.kt:91`). That marker's own KDoc in
`ThrowableExtensions.kt` states the contract:

> Telemetry sinks drop these before they turn into error events, so an expected signal that
> reaches an error-level log line never inflates error counts.

The contract is honored in exactly one sink. `SentryLogTree.shouldCaptureEvent`
(`libraries/cards/impl/src/commonMain/.../SentryLogTree.kt:80-84`) filters both exempt classes:

```kotlin
return !throwable.isExpectedControlFlow && !throwable.isOfflineError()
```

`GrafanaLogTree.log` (`libraries/telemetry/impl/src/commonMain/.../GrafanaLogTree.kt`) gates on
level alone:

```kotlin
val forwardAsPlainLog = eventName == null &&
    entry.level.priority >= LogLevel.Warn.priority &&
    klogForwardingEnabled()
```

`isExpectedControlFlow` is referenced in exactly one non-test file in the repo, `SentryLogTree.kt`.
The Grafana tree has never consulted it, nor `isOfflineError` (the ENG-34 exemption, which is why
the 10 `SocketException` warns are here too). So both trees see the same `LogEntry`, and one drops
it while the other ships it at full severity.

## The session that surfaced it is healthy

Install `dec4b4be-f486-4684-992a-764f2c639a87`, session
`bba9d7ba-174a-4673-9df4-016244444638`, store Android build 1026 (`4ea79519ef9c`, = the live
`v0.1.0` release), 2026-08-18T17:26Z. Full reconstruction from Loki:

| t (UTC) | event |
|---|---|
| 17:26:32 | `app.launched` / `app.foregrounded`, cold start |
| 17:26:32 | WARN `accessToken: no session — request will go unauthed` |
| 17:26:36 | `onboarding.step_viewed step=welcome` |
| 17:26:39 | `onboarding.auth_selected method=guest returning=false` |
| 17:26:44 | **ERROR + WARN `auth unready: FinishingSetup`** |
| 17:26:57 | `onboarding.completed`, `account_ready=true`, `duration_sec=20` |
| 17:27:22 | `matchmaking.search_started entry=public` |
| 17:27:40 | `matchmaking.abandoned phase=searching wait_ms=17968` |
| 17:27:42 | `app.backgrounded`, `session_duration_sec=70` |

The gate did its job. `FinishingSetup` means a degraded guest whose account is still being created;
`AuthGateImpl` blocked the profile write, returned the typed failure, kicked the healer, and the
account was ready 13 seconds later. The user finished onboarding, tried public matchmaking, found
nobody (matchmaking is still an unbuilt shell), and left. **No user harm, nothing to fix in auth.**

Two side notes from the same session, neither worth its own item:

- `App recomposed (this should be rare)` is the initial-composition false positive already fixed on
  `develop` by `f7b67e11`. Build 1026 predates that commit, so it is expected on the live build.
- The same `AuthUnready` is logged twice 1ms apart, once at ERROR with no `tag` and once at WARN
  tagged `ProfileRepository`. Worth a glance while fixing this, since one throwable producing two
  log lines at two severities is its own small smell.

## Why this is worth fixing

Not for the user, who never sees it. For us. The client error panel is the surface this very
triage reads to find real bugs, and it is currently 85% a signal the code has already classified as
expected. It also means any future error-rate alert built on this stream starts life miscalibrated.

The volume is small today (11 lines / 14d) only because prod traffic is small (31 foreground
sessions in 14 days). The ratio, not the count, is the problem, and the ratio does not improve with
scale.

## Fix direction

The two trees should not each carry their own opinion about what counts as an error. Lift the
predicate that `SentryLogTree.shouldCaptureEvent` already encodes (expected control flow, offline
errors) into one shared classifier next to `isExpectedControlFlow` in `:libraries:core`, and have
both trees consult it.

Dropping the line entirely is the wrong move. It is genuinely useful at DEBUG/INFO when
reconstructing a session, as this case file demonstrates. Downgrade it rather than delete it, so
the record survives without polluting the error tier.

## Disposition

todo **ENG-44 `[P2]`** (2026-08-19). P2 rather than P1: no user impact and no broken flow, it
degrades an internal signal. Filed anyway because the corrupted signal is the one the nightly
triage depends on. No Sentry issue exists to resolve.
