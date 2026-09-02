# TODO

**Last reviewed:** 2026-08-28 (observability-triage + ENG-45 review) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** **Both platforms are live with real users.** Android on Play, and iOS on the App Store since 2026-07-23 (`cards@0.1.0+3`, tag `v0.1.0`, release channel `store-ios-release`). The goal is scalable, maintainable, production-ready systems: restructure or rebuild rather than stacking minimal patches. But there is no longer a greenfield platform — changes to schema, persisted state, or live behaviour must migrate **and** be safe for the existing population on *both* Android and iOS. Treat any doc or skill that still says "iOS isn't shipped / iOS has no users yet" as stale. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that. Full rule: AGENTS.md → Coding Guidelines and the `work-item` skill (`.claude/skills/work-item/`) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing), `ECON` (chip economy integrity), `MOD` (trust & safety / moderation), `SITE` (marketing / support static pages).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## ENG-36 [P1] — Diagnose the starter-grant "double-miss" and surface reveal health

**Problem:** A new prod user saw neither the onboarding grant number nor the Home welcome-dialog backup, yet got their 10k chips. Why the Home backup didn't fire is unconfirmed (`accountJustCreated` / `welcomeIdentity` race?).

**Acceptance:** Using the `onboarding.grant_revealed` / `grant_reveal_degraded` events, confirm the double-miss cause and close it (widen `GRANT_REVEAL_TIMEOUT`, or make the Home backup fire whenever a fresh account never got a reveal). Add a `dc-funnel` panel: reveals by surface/source + degraded-with-no-backup.

**Hints:** `OnboardingViewModel.kickOffGrantReveal` (`GRANT_REVEAL_TIMEOUT` = 1.5s); `GetHomeScreenNotification.welcome()` gating (`accountJustCreated`, `didSeeInitialGrantInOnboarding`, `welcomeIdentity`). Events in `docs/wiki/app-events.md`; the panel stays empty until a build carrying them ships.

## ENG-37 [P1] — Consolidate the starter-grant reveal onto the Home notification manager (drop the onboarding-step race)

**Problem:** The reveal exists twice — onboarding's `kickOffGrantReveal` (races the balance on a 1.5s `GRANT_REVEAL_TIMEOUT`) and the Home manager's `HomeNotification.Welcome`. **Decided:** make Home `Welcome` the single reveal and delete the onboarding race. That removes the backup, so the Home gating must be bulletproof first — **sequenced after ENG-36**.

**Acceptance:** Onboarding no longer runs the balance race (`GRANT_REVEAL_TIMEOUT` deleted; at most a contentless "you're all set" beat); every new account sees the `Welcome` reveal exactly once, including offline-then-reconnect. The arbiter is a pure function, so unit-test it hard: fresh account online, offline-then-reconnect, slow sync, Home resume re-present, account switch, process death mid-onboarding, welcome-already-seen.

**Hints:** `OnboardingViewModel.kickOffGrantReveal`; arbiter `GetHomeScreenNotification` + `HomeNotificationSnapshot`; `accountJustCreated` latch from `/v1/me` `isNewAccount` (prime suspect — check it survives the onboarding→Home handoff).

## ENG-38 [P1] — Filter emulator/sideload noise out of the `dc-pulse` health panels

**Problem:** The client now stamps `genuine_install` (+ `is_emulator`, `is_sideloaded`, `is_rooted`, `installer_package`, `device_class`, `os_version`) onto every record as a Loki structured-metadata field, but nothing reads it — the crash-free and DAU panels still count emulators as users.

**Acceptance:** Filter the `dc-pulse` crash-free sessions/users/trend, DAU-by-device, and Installs-30d panels on `genuine_install="true"` behind a "show all" toggle var. Blocked until a store build carrying the attrs reaches prod: no record has the field yet, so filtering today zeroes row 1. Verify against live data first (`{service_name="cards-client"} | genuine_install="true"` returns rows), and calibrate — if `genuine_install="false"` is a rounding error, close this instead of filtering.

**Hints:** Values are strings, so match `="true"` not a bool. **"Accounts (all-time)" can't be filtered this way** — it's a Postgres count over `profiles`, which has no telemetry attrs; either drop it from scope or join on something server-side. Attribute semantics: `docs/wiki/app-events.md` → "Install and device facts".

## ENG-50 [P1] — Stop the rolling deploy from restart-storming on the single-writer lock

**Problem:** Every prod deploy briefly runs a second machine that cannot take the Postgres advisory lock the old one still holds. It retries, exits, and Fly restarts it, about ten times. Measured on the 2026-09-01 deploy: 10 restarts on the throwaway instance and a CPU spike to 531% from repeated JVM cold starts, ~20 minutes after the deploy had otherwise finished. It resolves itself, and no data is ever at risk — refusing to boot is the correct behaviour. The cost is that a `fatal` uncaught exception is now the normal outcome of a healthy deploy, which means a genuine split-brain would be indistinguishable from deploy noise. That is what makes this P1 rather than cosmetic.

**Acceptance:** A deploy produces at most one restart on the incoming instance. Either have `SingleWriterGuard` wait on the lock rather than exiting (so Fly does not restart it), or have the outgoing machine release the lock before the new one starts.

**Hints:** Sentry https://elijah-dangerfield.sentry.io/issues/CARDS-9Q is this, `level=fatal`, `handled=no`, escalating — previously mis-ledgered as dev-only, it is prod. `SingleWriterGuard.acquire` logs "Single-writer lock held by another instance; retrying (attempt N)" then gives up and exits. `apps/server/fly.prod.toml` explains why the strategy is `rolling` and never blue-green: in-memory room state plus the advisory lock. Rolling avoids the deadlock it was chosen to avoid; it does not avoid this thrash. Evidence: dc-infra → Restarts (24h), broken out per instance.

## ENG-49 [P1] — Stop opening a new window every time a bottom sheet shows mid-hand

**Problem:** Both ANRs on record are the main thread blocked on the render thread during a live multiplayer hand. CARDS-C1 (2026-09-02) names the trigger the earlier one couldn't: Compose committed a composition mounting a `ModalBottomSheet`, and `Dialog.show()` → `ViewRootImpl.setView` → `ThreadedRenderer.create` → `nCreateProxy` blocked on `pthread_cond_wait`. Material3's `ModalBottomSheet` on Android is a real `Dialog`, so **every mount allocates a window, a `ViewRootImpl` and its own render proxy.** `PlayerActionSheet` (`PlayPokerScreen.kt:425`) is composed conditionally on the player's turn, so that cost is paid on **every betting decision**. CARDS-C1 was a foreground freeze on a new user's first session, hand 9, against two real humans, on an 8 GB phone — so this is not the low-RAM story this item started as.

**Acceptance:** Opening the betting sheet on a mid-range device does not allocate a new window per turn, verified by profiling `Dialog.show` main-thread time before and after. Plus ANR/OOM charted by `device.class` and platform from `app.launched`'s `previous_exit`, so this is a rate rather than two anecdotes.

**Hints:** Case `docs/agent/feedback-cases/CARDS-C1.md` has the full stack and reasoning; earlier event `docs/agent/feedback-cases/CARDS-BZ.md`. Sentry https://elijah-dangerfield.sentry.io/issues/CARDS-C1 and https://elijah-dangerfield.sentry.io/issues/CARDS-BZ.

Check whether Material3 still needs a separate window at `composeMultiplatform 1.11.0` before hand-rolling anything. Two shapes of fix: keep one sheet mounted and drive visibility through `sheetState`, or render the action sheet as an in-composition overlay (the real fix, bigger change). **Not a pre-release fix** — it is already live in build 1026, it is 2 ANRs in 29 days, and reworking the most-used interactive surface in the app to chase a rare freeze is how you ship a common regression instead.

Two corrections worth keeping. The celebration overlay was the prime suspect here and **is not**: `AchievementCelebrationSheet` is gated on `celebrationActive`, set only when `isBots` (`PlayPokerScreen.kt:664,693`), and both ANRs were multiplayer. And `achievement.celebration_shown` (`PlayPokerViewModel.kt:636`) is logged for every earned achievement *before* the mode check, so it fires when nothing is displayed — rename it or put the mode in the payload before it misleads the next investigation.

**Also worth noting:** both ANRs needed a second stressor. CARDS-BZ followed an OOM kill 33 minutes earlier; CARDS-C1 came 8 seconds after `Unable to resolve host "cards-server-prod.fly.dev"` and two `conn.reconnecting` events. Reconnect churn on top of window allocation may be the combination that actually crosses 5 seconds.

## ENG-48 [P2] — Sustained concurrent flushes for one user 500 instead of queueing

**Problem:** The pool runs REPEATABLE READ, so concurrent updates to one `user_progression` row abort with `40001`; Exposed retries a bounded number of times, then the request fails. Measured: 4 concurrent flushes of one user pass, 6 and 8 fail. Pre-existing (the per-event write had the same shape), and the batch fix makes it rarer by shortening requests, but `RetryPolicy.idempotent()` still overlaps retries.

**Acceptance:** Concurrent flushes for one user converge instead of erroring — widen the retry budget for serialization failures, or serialise per user. A test at 8+ concurrent flushes passes without a 500 and without losing credit.

**Hints:** `Database.transaction` wraps `newSuspendedTransaction`; Exposed 0.56 retries via `transaction.maxAttempts`. The writes themselves are already safe — `PostgresProgressionRepository.addToTotal` is a relative `total_xp = total_xp + ?`, so this is about availability, not correctness. Don't "fix" it by reverting to a read-then-write absolute update: that trades 500s for silent lost credit.

`PostgresPlayerStatsRepository.applyHandBatch` has the same shape by design (ENG-47): the counter fold is order-dependent, so it can't be expressed as relative arithmetic and instead takes `SELECT … FOR UPDATE` on the aggregate. Under REPEATABLE READ that raises `40001` rather than queueing, so whatever fixes progression should cover it too. Play style and progression are both relative and need no lock.

## ENG-46 [P1] — Alert on slow-but-successful server requests (A1–A8 are blind to them)

**Problem:** The ENG-45 requests each logged `200 OK` at INFO for up to 501 seconds and tripped nothing. A1–A8 cover ledger drift, Fly/Supabase down, backend-unreachable, purchase failures, OOM, silence and dropped SKUs — none covers a request that succeeds slowly, and no `dc-infra` panel charts per-route server latency.

**Acceptance:** A route-level latency signal exists (panel + alert) that would have fired within a day of 2026-08-21, without paging on the legitimately-slow long-poll paths. Verify by replaying the window: the alert must trip on the real `progression/sync` data.

**Hints:** Server durations are already in Loki (`{service_name="cards-server"}`, the `CallLogging.kt` `... in NNNms` line) — parse there rather than waiting on spanmetrics, since gameplay spans are INTERNAL-kind and never reach `traces_spanmetrics_*` (`docs/wiki/observability.md` → Known gaps). Alerts live in the `downcard-engineering` folder; `severity=critical` pages a phone, so this is a warning. Case `docs/agent/feedback-cases/CARDS-BW.md` → "Why nothing caught it".

## ENG-42 [P0] — Chart the iOS foreground-termination rate, then rule the welcome-screen kills real or not

**Problem:** A retail iPad on the App Store build `cards@0.1.0+3` hit two `WatchdogTermination` fatals while foregrounded on the onboarding `welcome` step, then abandoned. The per-run signal to judge it now exists (`app.previous_run` splits `foreground_termination` from `background_exit`; `app.exit_metrics` carries the raw MetricKit watchdog counts), but nothing charts it, so it's still an anecdote instead of a rate.

**Acceptance:** A panel charts `foreground_termination` net of Sentry-reported crashes, split by platform, once a build carrying the events ships. Then either reproduce and fix the welcome-step hang, or show these were force-quits and drop this to P1.

**Hints:** Read `docs/wiki/app-events.md` → "Reading `app.previous_run` honestly" first — `foreground_termination` is a candidate set, not a verdict, and Android is the calibration (it carries the same marker plus `ApplicationExitInfo` ground truth in `previous_exit`). Instrumentation is `RunOutcome*` in `:libraries:telemetry:impl`. Case `docs/agent/feedback-cases/CARDS-3.md`; Sentry https://elijah-dangerfield.sentry.io/issues/CARDS-3.
