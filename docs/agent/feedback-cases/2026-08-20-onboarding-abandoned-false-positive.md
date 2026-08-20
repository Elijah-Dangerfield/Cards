# 2026-08-20 — `onboarding.abandoned` fires on a back-out, so the funnel calls finishers quitters

**Signal:** Grafana / Loki client event stream, `dc-funnel`. No Sentry issue, no alert — the event
is INFO and looks perfectly healthy until you join it to `onboarding.completed`.

```
sum by (install_id, event_name) (count_over_time({service_name="cards-client", deployment_environment="prod"} | event_name=~"onboarding.abandoned|onboarding.completed" [14d]))
```

Window: 14d to 2026-08-20T06:32Z. Found while checking whether the welcome-step drop-off in the
first busy prod fortnight since launch meant anything (it is the step ENG-42 is about).

## What the numbers say

12 `onboarding.abandoned` events, **100% of them `step="welcome"`**, never any other step. That
alone reads like a welcome-screen problem. It isn't.

| install | abandoned | completed |
|---|---|---|
| `60c6c41a` | 2 | **1** |
| `679dadd5` | 2 | **1** |
| `a0d7a2f5` | 1 | **1** |
| `a7bd3b10` | 2 | **1** |
| `37621f05` | 3 | — |
| `59d5b7db` | 2 | — |

**4 of the 6 installs that "abandoned" onboarding went on to complete it.** 12 events came from 6
installs, so it double- and triple-counts within a single session too. As a funnel metric it is
roughly two-thirds false positive by install, and worse by event.

## Root cause

Full reconstruction, session `57e2f88a-86f9-4f31-8bbe-79808fb2f530`
(install `60c6c41a`, Android store build 1026):

| t (UTC offset from launch) | event |
|---|---|
| +0s | `app.launched` / `app.foregrounded`, cold start |
| +1s | `onboarding.step_viewed step=welcome` |
| +7s | `app.backgrounded` (`session_duration_sec=6`) |
| +19s | **`onboarding.abandoned step=welcome`** |
| +19s | `app.foregrounded` (`cold_start=false`, 27ms later) |
| +19s | `onboarding.step_viewed step=welcome` |
| +23s | `app.backgrounded` (`session_duration_sec=3`) |
| +23s | **`onboarding.abandoned step=welcome`** |
| +23s | `app.foregrounded`, `onboarding.step_viewed step=pick_identity` |
| +40s | `step_viewed step=how_it_works` |
| +64s | `step_viewed step=starter_grant` |
| +71s | `onboarding.completed`, `account_ready=true`, `duration_sec=48` |

The user pressed back on the Welcome step twice, came straight back, and finished onboarding
48 seconds in. The funnel recorded two abandonments.

The emitter is `OnboardingViewModel.onCleared()`
(`features/onboarding/impl/.../OnboardingViewModel.kt:527`):

```kotlin
override fun onCleared() {
    if (!exitedToHome) {
        logger.logEvent("onboarding.abandoned", "step" to state.step.eventName())
    }
    super.onCleared()
}
```

Its own comment names the mechanism without drawing the conclusion: *"system back on Welcome exits
the app and clears the entry"*. On Android that finishes the activity, so the VM clears and the
event fires — but exiting the app is not leaving the funnel. Relaunching builds a fresh VM with
`exitedToHome = false` and a fresh `onboardingStartedAt`, so the cycle repeats and each iteration
emits again. The Welcome step monopolizes the event for the same structural reason: it is the only
step where system back leaves the app rather than stepping backwards inside the flow.

`docs/wiki/app-events.md:116` already hedges (*"best-effort on VM clear"*, *"a process kill won't
emit it"*), but it warns about **under**-counting. The live failure is over-counting, which is the
more dangerous direction because it manufactures a problem instead of hiding one.

## Why this is worth fixing

It corrupts the one metric two open items depend on:

- **ENG-36** is meant to diagnose the starter-grant double-miss from the onboarding event stream.
  A step-scoped abandonment signal that fires on backgrounding poisons that read.
- **ENG-42** is trying to decide whether the iOS `welcome` step really loses people or whether the
  watchdog kills were force-quits. "100% of abandonment happens on welcome" is exactly the kind of
  corroboration that would push that call the wrong way. It is an artifact.

Same class as ENG-44: the bug is in our instruments, not in the product. Unlike ENG-44 it also
misreports a **product** number the owner reads on `dc-funnel`.

## Fix direction

Make the event mean what its name claims. Options, in preference order:

1. **Resolve abandonment at re-entry, not at VM clear.** Record a durable "onboarding in progress,
   step X, started at T" marker in `appCache`; on the next launch that reaches Home without
   completing, or after a staleness window, emit one `onboarding.abandoned`. One event per genuine
   abandonment per install, and it survives process kill — which fixes the under-counting the wiki
   already documents, in the same change.
2. **Minimum viable:** suppress the emit when the clear is a backgrounding rather than a real exit,
   and dedupe per install so a relaunch can't re-fire it. Cheaper, but leaves the process-kill blind
   spot the wiki calls out.

Either way, add `resumed=true/false` (or an explicit `reason`) to the event so the panel can
separate "backed out and came back" from "gone", and update `docs/wiki/app-events.md:116` — its
current caveat describes the opposite failure mode from the real one.

Test-first: a VM test that backgrounds on Welcome, restores, and completes should assert exactly
zero `onboarding.abandoned` events.

## Disposition

todo **AUTH-31 `[P2]`** (2026-08-20). P2 for consistency with ENG-44 — no user-facing breakage, it
degrades an internal/product signal rather than a flow. Filed rather than waved off because the
false-positive rate is ~67% and two open items (ENG-36, ENG-42) would be reasoning off it. No Sentry
issue exists to resolve.
