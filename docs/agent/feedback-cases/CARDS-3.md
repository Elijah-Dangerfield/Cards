# CARDS-3 — the OS killed the app twice in a row on the iOS onboarding welcome screen (live App Store build)

**Sentry:** [CARDS-3](https://elijah-dangerfield.sentry.io/issues/CARDS-3) · `WatchdogTermination`,
level `fatal`, `handled=no`, mechanism `watchdog_termination` · 26 events since 2026-06-05, status
`unresolved / regressed`, last seen **2026-08-14T01:40:53Z**.

## Why this is not the old June noise

The issue first fired on `cardse@0.1.0+1` in June, was resolved, and **regressed**. Both events in
the current window are on the shipped App Store build:

| | |
|---|---|
| release | `cards@0.1.0+3`, dist `202607231404`, `commit_sha 36aa3153f4ab` (= tag `v0.1.0`) |
| environment | `store-ios-release` (`releaseChannel=store`, not `beta`) |
| device | `iPad15,3` (iPad Air M2), `simulator=false`, `rooted=false`, locale `en_SG` |
| os | iOS 26.5.2 (23F84) |
| memory | `memory_size` 8.0 GB, `usable_memory` 6.7 GB at launch |
| route | `OnboardingRoute` · `in_foreground=true` |
| install | `743eaa57-a3e0-4780-9e36-e4c258a81207` (one install, one user) |

So this is a real retail iPad, in the foreground, on the public iOS release — not a simulator, not
a side-load, not a dev build.

## The session story

Sentry reports a watchdog termination on the *next* launch, so the two events plus the Loki client
stream (`{service_name="cards-client", deployment_environment="prod"} | platform="ios"`,
`install_id="743eaa57-…"`) reconstruct as:

1. **~01:39:14Z** — an earlier session ends without a clean shutdown → Sentry event #1.
2. **01:39:15.9Z** — cold launch (orphan session `142bb6d6`): `App recomposed (this should be rare)`
   [WARN], then `onboarding.step_viewed step=welcome`.
3. **01:39:16.1Z** — `app.foregrounded` / `app.launched` (`cold_start=true`,
   `previous_exit=unknown`), session `3b656f02`. `GuestSessionHealer … SKIP_NOT_ONBOARDED`.
4. **01:39:16 → 01:39:27Z** — network is fine: three GETs 200 (197 B, 20 458 B ×2) and a POST 204.
   No errors, no `net.backend_unreachable`.
5. **01:39:27 → 01:40:53Z** — ~86 s of nothing. Last breadcrumb is a `SIGNIFICANT_TIME_CHANGE`
   device event. **No `app.backgrounded`.** → Sentry event #2.
6. **01:41:02.2Z** — cold launch again (orphan `cacd8d7a` → session `52bd13bc`), same welcome step.
7. **01:41:21.8Z** — `app.backgrounded`, `session_duration_sec=19`. Nothing from this install since.

The user never emitted `onboarding.auth_selected` — they never got past the **welcome** step, and
they have not come back.

For contrast, the other live iOS install the same week (`ad8cc889`, iPhone 17,2 / iOS 26.6.1,
2026-08-12) walked welcome → pick_identity → how_it_works → starter_grant → `onboarding.completed`
in 34 s with no termination. So it is not universal across devices.

## Working theory (medium confidence — deliberately unresolved)

Two readings fit the same evidence, and **Sentry cannot distinguish them**:

- **Real.** The app is being killed by the OS while foregrounded on the welcome screen. Memory
  exhaustion is a poor fit (6.7 GB usable at launch), so the likelier watchdog trigger is a
  main-thread hang / unresponsive-app timeout. The `App recomposed (this should be rare)` WARN
  fires at every iOS launch, which is at least a hint that the root Compose tree is being rebuilt.
  Two consecutive kills, on the first screen, ending in abandonment.
- **False positive.** `SentryWatchdogTermination` is a next-launch heuristic: no crash report, no
  clean-shutdown flag, app was foregrounded → "watchdog". A user who opens the app, reads the
  welcome copy for ~90 s, swipes it out of the app switcher, and reopens produces exactly this
  trace. `decisions.md` (2026-07-11, ENG-25) already flags the class: a clean-exit-marker heuristic
  "can't distinguish crash/ANR/OOM and misfires on device restarts/upgrades".

**We cannot currently tell which**, and that is itself the finding. `previous_exit` is `unknown` on
every one of these launches — correct behaviour, not a bug: ENG-25 made iOS `previous_exit` a
*consume-once MetricKit sample* that arrives up to 24 h late, so it is `unknown` on most launches
and useless for a same-minute question. The ENG-25 decision explicitly deferred per-run exit
classification with "revisit if iOS exit rates become load-bearing". iOS is now on the App Store
with real users and this is the only fatal on it, so that trigger has fired.

## Fix direction

1. **Decide whether it's real, first.** Get a per-run signal on iOS foreground termination —
   Sentry's `enableWatchdogTerminationTracking` diagnostics plus an explicit
   foreground/background/clean-exit marker of our own, and/or surface MetricKit's
   `MXAppExitMetric` foreground `cumulativeAppWatchdogExitCount` on a dashboard so a real rate is
   visible instead of a single anecdote. Cross-check against `app.backgrounded` (a session with no
   `app.backgrounded` and no crash is the candidate set).
2. **Then chase the hang.** Reproduce on an iPad-class device / iOS 26.5 at the welcome step, and
   look at what runs between `onboarding.step_viewed step=welcome` and the first user tap. The
   ~86 s window with a live network and zero breadcrumbs is where to instrument.
3. **`App recomposed (this should be rare)`** firing on every iOS cold launch deserves its own
   look while in there — either it's mis-worded or the root really is recomposing.

## Disposition

todo **ENG-42 `[P0]`** (2026-08-18). Sentry issue left **unresolved** (fix pending) with a triage
comment. Filed P0 unattended: it is a `fatal` on the live App Store build, on the first screen a
new user sees, and it took out one of the two iOS installs we have. Downgrade to P1 without
argument if step 1 shows the terminations are force-quits.
