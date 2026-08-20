# TODO

**Last reviewed:** 2026-08-20 (curate-todos) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

## AUTH-31 [P2] — `onboarding.abandoned` fires on a back-out, so the funnel counts finishers as quitters

**Problem:** The event is emitted from `OnboardingViewModel.onCleared()`, and system-back on the Welcome step exits the app, so backgrounding-then-returning logs an abandonment. 4 of the 6 prod installs that "abandoned" in 14d went on to complete onboarding, and 12 events came from those 6 installs. All 12 read `step=welcome`, which is an artifact of where back exits the app, not a welcome-screen problem.

**Acceptance:** One `onboarding.abandoned` per genuine abandonment per install — resolve it at re-entry (durable in-progress marker in `appCache`, settled on the next launch that reaches Home or after a staleness window) rather than at VM clear, which also fixes the process-kill under-count the wiki already documents. Event carries a `resumed`/`reason` attribute; a VM test that backgrounds on Welcome, restores and completes asserts zero abandonment events.

**Hints:** `OnboardingViewModel.kt:527` (`onCleared`) and the `exitedToHome` latch; `docs/wiki/app-events.md:116` warns about the opposite failure mode and needs correcting. **Fix before trusting welcome-step drop-off in ENG-36 or ENG-42.** Case `docs/agent/feedback-cases/2026-08-20-onboarding-abandoned-false-positive.md`. No Sentry issue (that's the bug).

## ENG-36 [P1] — Diagnose the starter-grant "double-miss" and surface reveal health

**Problem:** A new prod user saw neither the onboarding grant number nor the Home welcome-dialog backup, yet got their 10k chips. Why the Home backup didn't fire is unconfirmed (`accountJustCreated` / `welcomeIdentity` race?).

**Acceptance:** Using the `onboarding.grant_revealed` / `grant_reveal_degraded` events, confirm the double-miss cause and close it (widen `GRANT_REVEAL_TIMEOUT`, or make the Home backup fire whenever a fresh account never got a reveal). Add a `dc-funnel` panel: reveals by surface/source + degraded-with-no-backup.

**Hints:** `OnboardingViewModel.kickOffGrantReveal` (`GRANT_REVEAL_TIMEOUT` = 1.5s); `GetHomeScreenNotification.welcome()` gating (`accountJustCreated`, `didSeeInitialGrantInOnboarding`, `welcomeIdentity`). Events in `docs/wiki/app-events.md`; the panel stays empty until a build carrying them ships.

## ENG-37 [P1] — Consolidate the starter-grant reveal onto the Home notification manager (drop the onboarding-step race)

**Problem:** The reveal exists twice — onboarding's `kickOffGrantReveal` (races the balance on a 1.5s `GRANT_REVEAL_TIMEOUT`) and the Home manager's `HomeNotification.Welcome`. **Decided:** make Home `Welcome` the single reveal and delete the onboarding race. That removes the backup, so the Home gating must be bulletproof first — **sequenced after ENG-36**.

**Acceptance:** Onboarding no longer runs the balance race (`GRANT_REVEAL_TIMEOUT` deleted; at most a contentless "you're all set" beat); every new account sees the `Welcome` reveal exactly once, including offline-then-reconnect. The arbiter is a pure function, so unit-test it hard: fresh account online, offline-then-reconnect, slow sync, Home resume re-present, account switch, process death mid-onboarding, welcome-already-seen.

**Hints:** `OnboardingViewModel.kickOffGrantReveal`; arbiter `GetHomeScreenNotification` + `HomeNotificationSnapshot`; `accountJustCreated` latch from `/v1/me` `isNewAccount` (prime suspect — check it survives the onboarding→Home handoff).

## ENG-38 [P1] — Emit stable install/device facts as OTel Resource attributes, then filter noise out of the dashboards

**Problem:** Prod client telemetry can't tell a genuine retail install from an emulator or a side-loaded store build, so that noise pollutes crash-free, DAU, and the all-time user count (one emulator ANR dragged crash-free users to 94%). Launch events only carry `install_id` / `platform` / `previous_exit` / `release_channel`.

**Acceptance:** Emit `is_emulator`, `is_sideloaded`, `installer_package`, `is_rooted` (+ `device_class`, `os_version`) as OTel **Resource** attributes set once at SDK init, and confirm the Loki OTLP mapping lands them as filterable structured metadata. Then filter the `dc-pulse` crash-free / DAU / all-time-users panels on `genuine_install` behind a "show all" toggle var (Grafana MCP, once a build carrying the attrs ships). **Tag, don't drop** — the noise stays queryable.

**Hints:** Sentry already computes these (`isSideLoaded`, `device.class`, `os`) on the event — mirror that into the Resource, near the telemetry bootstrap (cf. `GrafanaAppEvents`). Debug builds already route to dev, so the prod gap is specifically store-build-on-emulator. Keep new attrs low-cardinality.

## ENG-42 [P0] — Chart the iOS foreground-termination rate, then rule the welcome-screen kills real or not

**Problem:** A retail iPad on the App Store build `cards@0.1.0+3` hit two `WatchdogTermination` fatals while foregrounded on the onboarding `welcome` step, then abandoned. The per-run signal to judge it now exists (`app.previous_run` splits `foreground_termination` from `background_exit`; `app.exit_metrics` carries the raw MetricKit watchdog counts), but nothing charts it, so it's still an anecdote instead of a rate.

**Acceptance:** A panel charts `foreground_termination` net of Sentry-reported crashes, split by platform, once a build carrying the events ships. Then either reproduce and fix the welcome-step hang, or show these were force-quits and drop this to P1.

**Hints:** Read `docs/wiki/app-events.md` → "Reading `app.previous_run` honestly" first — `foreground_termination` is a candidate set, not a verdict, and Android is the calibration (it carries the same marker plus `ApplicationExitInfo` ground truth in `previous_exit`). Instrumentation is `RunOutcome*` in `:libraries:telemetry:impl`. Case `docs/agent/feedback-cases/CARDS-3.md`; Sentry https://elijah-dangerfield.sentry.io/issues/CARDS-3.
