# TODO

**Last reviewed:** 2026-10-02 (observability-triage; ENG-75/76 filed, ENG-49 reopened) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

## ENG-81 [P0] — The App Store build requires iOS 26.1, shutting out older iPhones

**Problem:** The project-level Release config sets `IPHONEOS_DEPLOYMENT_TARGET = 26.1` while Debug sets 18.2 (both untouched since the initial commit). The live listing says "Requires iOS 26.1 or later", so every iPhone below 26.1 can't install Downcard, and the TikTok campaign can only target iOS 18.7+, so some paid clicks land on phones that can't install.

**Acceptance:** Release matches Debug at 18.2 (or a lower floor if the KMP framework and Swift code allow it). A Release build compiles and launches on an iOS 18 simulator, and the next store release's listing shows the new minimum.

**Hints:** `apps/ios/iosApp.xcodeproj/project.pbxproj`, project-level Release config. App Swift code only gates on `#available(iOS 14.0)`; also check the Kotlin/Native framework's minimum and the Fastfile.

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

**Acceptance:** Filter the `dc-pulse` crash-free sessions/users/trend, ANR-free sessions, abnormal-exits-by-kind, DAU-by-device, and Installs-30d panels on `genuine_install="true"` behind a "show all" toggle var. Blocked until a store build carrying the attrs reaches prod: no record has the field yet, so filtering today zeroes row 1. Verify against live data first (`{service_name="cards-client"} | genuine_install="true"` returns rows), and calibrate — if `genuine_install="false"` is a rounding error, close this instead of filtering.

**Hints:** Values are strings, so match `="true"` not a bool. **"Accounts (all-time)" can't be filtered this way** — it's a Postgres count over `profiles`, which has no telemetry attrs; either drop it from scope or join on something server-side. Attribute semantics: `docs/wiki/app-events.md` → "Install and device facts".

## ENG-50 [P1] — Stop the rolling deploy from restart-storming on the single-writer lock

**Problem:** Every prod deploy briefly runs a second machine that cannot take the Postgres advisory lock the old one still holds. It retries, exits, and Fly restarts it, about ten times. Measured on the 2026-09-01 deploy: 10 restarts on the throwaway instance and a CPU spike to 531% from repeated JVM cold starts, ~20 minutes after the deploy had otherwise finished. It resolves itself, and no data is ever at risk — refusing to boot is the correct behaviour. The cost is that a `fatal` uncaught exception is now the normal outcome of a healthy deploy, which means a genuine split-brain would be indistinguishable from deploy noise. That is what makes this P1 rather than cosmetic.

**Acceptance:** A deploy produces at most one restart on the incoming instance. Either have `SingleWriterGuard` wait on the lock rather than exiting (so Fly does not restart it), or have the outgoing machine release the lock before the new one starts.

**Hints:** Sentry https://elijah-dangerfield.sentry.io/issues/CARDS-9Q is this, `level=fatal`, `handled=no`, escalating — previously mis-ledgered as dev-only, it is prod. `SingleWriterGuard.acquire` logs "Single-writer lock held by another instance; retrying (attempt N)" then gives up and exits. `apps/server/fly.prod.toml` explains why the strategy is `rolling` and never blue-green: in-memory room state plus the advisory lock. Rolling avoids the deadlock it was chosen to avoid; it does not avoid this thrash. Evidence: dc-infra → Restarts (24h), broken out per instance.

## ENG-57 [P2] — Hole cards keyed on Card identity skip their deal-in

**Problem:** `PlayerArea.kt:475` uses `key(card)`, and `Card` is a data class. An identical card dealt into the same slot next hand reuses the composition group, so `arrived`/`revealed`/`settled` stay true and that card renders instantly face-up while its partner flies in. 3.8% — about one hand in 26. The same equality assumption underpins the `LaunchedEffect(human.holeCards)` face-up reset, though at 1/2652 that one self-heals the following hand.

**Acceptance:** Deal-in plays every hand. `BoardArea.kt:91` already does this correctly with `key(table.handNumber)` — match it.

## ENG-58 [P2] — Stale XP shown on every real-chip bust

**Problem:** `lastHandXpAwarded` is cleared only by `RequestNextHand` (`PlayPokerViewModel.kt:1008`), which real-chip tables never dispatch — the server auto-advances and the player never taps a dialog CTA. `MultiplayerBustDialog` mounts the instant `handResult` lands, before the award coroutine settles, so it briefly shows the previous hand's XP and then corrects.

**Acceptance:** The bust dialog never shows another hand's number. Clear `lastHandXpAwarded` in `HandEndAchievementsPending`, alongside `recentlyEarned`.

## ENG-73 [P2] — An empty request body 500s instead of 400ing, and pages as an error

**Problem:** A client whose upload dies mid-request sends no body; `ContentNegotiation` throws `BadRequestException` wrapping `JsonDecodingException: Expected start of the object '{', but had 'EOF'`, and `StatusPages` logs it as **"Unhandled error"** at ERROR with a full stack, which reaches Sentry (CARDS-CB). Seen twice from one install on `/v1/me/player-stats/sync` and `/v1/me/wallet/sync`, 12s apart — the stats one after the server had waited **39.5 seconds** and then returned 500. A truncated upload is a client-side network condition, not a server fault: it deserves a 400 and a breadcrumb.

**Acceptance:** An empty or truncated body returns 400 and does not create a Sentry error event. The 39.5s wait before EOF also wants a request-body read timeout — that request occupied a connection for 40 seconds to learn nothing.

**Hints:** `ErrorsKt.installStatusPages` maps this; `BadRequestException` should be in the expected-client-error set. Same family as ENG-68 (429 as backpressure, not error) — consider doing them together, since both are "an expected client condition logged as a server failure". This is also the only entry the dc-infra slow-request panel caught this week, so fixing it clears that signal for real findings.

## ENG-74 [P1] — A cold Gradle cache pushes the macOS CI job past its 45-minute timeout

**Problem:** `Build + test` has `timeout-minutes: 45` and the macOS job now runs fully cold: the repo's Actions cache holds konan and Linux entries but no `gradle-home` for macOS, so compile + assemble went from ~11 min (09-05) to ~36-44 min. Two PRs were red purely from being cancelled at the limit with no test failure — #157 at 45m22s and #158 at 45m56s, the latter masking two genuine assertion bugs that only surfaced once the tests could actually run. Today's release PR passed with **35 seconds** to spare. It is a coin flip, and a cancelled job reads as a failing one.

**Acceptance:** A cold PR run finishes with real headroom, and a timeout is distinguishable from a test failure at a glance.

**Hints:** The deadlock is that `setup-gradle` is `cache-read-only` on `pull_request` events, so PR runs can never reseed what they need, and only a `push`-event run to `main` can. No CI ran 09-21 → 09-28, so GitHub evicted everything for inactivity. Options: raise the timeout, let `main` pushes write the macOS gradle-home cache, or split compile and assemble into separate jobs. Found independently by two agents triaging #157 and #158.

## ENG-71 [P1] — Nothing notices when a deploy stalls, so prod ran 17-day-old code unseen

**Problem:** `server-deploy-prod` runs for PR #152 (2026-09-04) and #155 (2026-09-05) are still `waiting` and `pending` on the `production` environment gate, so prod has not deployed since 2026-09-02. No alert covers this: A7 checks whether the server is *silent*, and it is not — it is serving happily, just from old code. The cost is real and was invisible: the OTel trace-root fix (`ac58b1ba`) has been on `main` for over two weeks while poisoned `trace_id=57f45c70...` keeps appearing in prod logs through 2026-09-21.

**Acceptance:** A deploy left unapproved or failed for more than ~24h produces a signal somebody sees. Simplest honest version: a panel or alert comparing the commit prod reports against `origin/main`'s tip. A `/health` endpoint that returns the build SHA would make that a one-line check — it currently returns nothing.

**Hints:** Gate is `environment: production` in `.github/workflows/server-deploy-prod.yml`. Stuck runs: 33922369090 (`waiting`), 33975509833 (`pending`). Related evidence in `docs/agent/feedback-cases/CARDS-C9.md`. Decide deliberately whether the gate earns its keep — it is correct to want one, and a gate nobody is reminded of is the same as no deploy at all.

**Update 2026-10-21: the blocking half is fixed, the noticing half is not.** It happened a third time
(a run from 2026-10-04 still parked on 2026-10-21, blocking the v0.6.0 server deploy). Root cause: a
job in `waiting` **does** hold its concurrency group, so one unapproved run stopped every later run
from reaching the gate at all. Those later runs sat at `pending` with no `pending_deployment`
record, which is why there was never anything to approve. The gate is now its own job with no
concurrency group, and a `supersede` job cancels older still-waiting runs, so a stale run neither
blocks a deploy nor survives to be approved into a stale rollout.

None of that makes a stalled deploy *visible*, which is what this item is actually about. A deploy
can still sit unapproved indefinitely with nobody told. The acceptance above stands: compare the
commit prod reports against `origin/main`, which needs `/_health` to return a build SHA.

## ENG-49 [P1] — The RenderThread text-stall fix did NOT hold on low-end devices

**Problem:** Shipped in `v0.3.0` / build 1209 on 2026-09-21. **Contradicted on 2026-10-01: CARDS-CF (4 events / 2 users) and CARDS-CG (1 event) are both ANRs on 1209 with the same `GrTextBlobRedrawCoordinator::internalRemove` RenderThread stack**, from one vivo V2135 (`device.class: low`, Android 13, 3.8GB) in multiplayer room 434U5F, hands 9 and 23. 7d abnormal exits moved to `{anr: 6, oom: 7}` from `{anr: 1}`. So the fix did not hold on low-end hardware — but 1209 does **not** carry the ENG-55/56/59 perf work, which is sitting unpublished in 0.4.0 (ENG-76), so this is not yet a verdict on the full fix set.

**Acceptance:** Four weeks of a *published* build carrying the perf trio with no new ANR on a `TextBlobRedrawCoordinator` stack, and Play vitals flat or down. Re-read specifically on low-end devices — every confirmed instance so far is one. The clock cannot start until ENG-76 is resolved.

**Hints:** Chronology proving 1135 predates the fix is in `docs/agent/feedback-cases/CARDS-C9.md`. Original diagnosis: `docs/plans/renderthread-text-stall.md`, case `CARDS-C1.md`. Sentry: [CARDS-C9](https://elijah-dangerfield.sentry.io/issues/7744693593/), CARDS-C1, CARDS-BZ. 72 commits sit on `main` unshipped, including R8, baseline profiles and the Sentry mapping upload.

If it recurs **on a post-fix build**, the shape to look for is an animation whose value is read during composition (`val x by animateFloatAsState(...)`), which recomposes its whole subtree every frame. Three instances caused this. `AnimatedStateReadInComposition` in `:detekt-rules` now runs (ENG-54) and cleared 19 instances, but it only matches the `by animateFloatAsState(...)` shape — a clean lint is not proof that nothing recomposes per frame. Capture a trace with `scripts/compose-trace.sh`.

## ENG-68 [P2] — Stop treating 429 as an error on background syncers

**Problem:** `isExpectedClientError` allowlists only 401/403 (`ExpectedClientErrors.kt:30`), so a 429 from a background syncer becomes `KLog.e` and a Sentry event. Rate limiting is normal backpressure, not a failure. Worse, `UserScopedSyncCoordinator`'s `RunWhenRetry.exponential()` ladder (`RunWhen.kt:101-108`) does not skip 4xx the way the HTTP retry does (`RetryPolicy.kt:168-173`), so one exhausted bucket costs six more requests and six more Sentry events per foreground — it retries against a bucket it already knows is empty.

**Acceptance:** A 429 on a background sync is a breadcrumb, not an error event, and the coordinator does not retry into a bucket it just exhausted.

**Hints:** Same argument ENG-34/ENG-35 already made for offline and 403. User-facing routes already map 429 sensibly (`MatchmakingRepositoryImpl.kt:49`, `FriendRepositoryImpl.kt:124`); it is only the background syncers that do not. The bucket mis-assignment that triggered this is fixed (equipment sync moved from the 30/hr profile bucket to the 480/hr progression one), so the symptom is gone — but the handling is still wrong and the next mis-sized bucket will look identical.

**Not user-affecting today:** over Loki's full 30-day retention the server returned 429 twelve times, all `deployment_environment=dev`, zero in prod. Prod served 583 equipment syncs in 14 days cleanly.

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

## AUTH-32 [P2] — StrandedIdentity canary false-fires on the cold-boot foreground echo

**Problem:** `StrandedIdentityDetector` warns `stranded_fallback_online_onboarded` and is documented to read zero post-heal. It has fired on 6 real production/store installs across three separate releases (968/1026/1135) over the last month. `AppEventDispatcher` fires both `ColdBoot` and `OnForeground(isColdBoot=true)` on the same launch, and the detector's two siblings (`GuestSessionHealer`, `AuthReResolver`) both skip that duplicate foreground — the detector doesn't, so it can sample the profile while the `ColdBoot`-triggered heal is still in flight. Traced one hit end to end: fired 1s after cold launch, user reached a playable game 5.6s later.

**Acceptance:** The canary reads zero on a healthy population again, or — if a real stranding case turns up — it's distinguishable from this race. Confirm whether bots mode needs a resolved server profile at all; if not, that's a separate reason today's traced session isn't proof of recovery.

**Hints:** `libraries/identity/impl/src/commonMain/kotlin/com/cards/libraries/identity/impl/auth/StrandedIdentityDetector.kt` — add the same `if (event.isColdBoot) return` guard as `GuestSessionHealer.kt:79` / `AuthReResolver.kt:46`. Case `docs/agent/feedback-cases/2026-09-09-stranded-identity-false-positive.md`.

## GAME-35 [P2] — Stale Call button silently no-ops in a local-bots hand

**Problem:** A retail Android install tapped "Call" in a local-bots game and it silently failed — `GameEngine.resolveAction` threw `IllegalArgumentException("Nothing to call")` (toCall was already 0), caught in `PlayPokerViewModel`'s Submit handler, but `IllegalArgumentException` isn't one of the branches that surfaces player feedback (only `IntentTimeoutException`/`IntentRejectedException` are), so the tap's haptic/chip-click fired but nothing else happened. One occurrence, immediately after two "Bot decision is stale, skipping apply" log lines in the same session.

**Acceptance:** A stale/no-longer-legal Call either can't be tapped (button disabled before the state changes) or fails with the same user-visible feedback as a rejected intent. Repro ideally reduces the race rather than just widening the catch.

**Hints:** `GameEngine.kt:331` (the throw); `PlayPokerViewModel.kt:982-999` (the catch with the `else -> Unit` gap); `LocalBotsSession.kt:409-425` (`isHumanIntentLegal`, the legality check the race slips past). Case `docs/agent/feedback-cases/2026-09-16-stale-call-button-local-bots.md`.

## ENG-76 [P1] — Nothing tells us a shipped release is still sitting unpublished

**Problem:** Play has **managed publishing on**, so the release workflow's `Successfully committed` only queues the release — it does not publish it. `v0.4.0` has been approved by Google and parked under "Changes ready to publish → Production → 0.4.0 → Start full rollout" since 2026-09-28. Prod telemetry over 3 days: `0.1.0 (1135)` 32 launches, `0.3.0 (1209)` 35, **`0.4.0 (1262)` 1**. Every fix in the release — the bot-freeze nonce collision, the SQLite fallback, the perf trio — has reached essentially nobody, and no alert, dashboard or workflow step says so.

**Acceptance:** A release that is committed but unpublished is visible without opening Play Console — either the workflow polls the edit's publishing status and fails/warns, or a dashboard panel compares the latest released version against the top `service_version` in prod launches. Decide separately whether managed publishing should stay on at all.

**Hints:** Play Console → Publishing overview shows the pending change and the "Managed publishing on" toggle. The `service_version` breakdown is `sum by (service_version) (count_over_time({service_name="cards-client", deployment_environment="prod"} | event_name="app.launched" [3d]))`. Related: ENG-52 shipped the update prompt in the same unpublished build.

## ENG-80 [P1] — Billing alerts: the client half is fixed, the Grafana rules are still yours

**What the earlier filing got wrong.** It said `shop.catalog_skus_dropped` "is not reaching Loki at
all, so A8 currently watches nothing", and put 22 Sentry events against it. Both halves were off,
and the correction matters because it changes the fix:

- CARDS-8V holds **2 events, 1 user**, both on 2026-10-19, iOS 0.5.0 (`substatus: regressed`). Not 22.
- The structured event is absent from Loki, but so is the `logger.e` beside it — and so is **every
  other record from that session** (`f79c2178-fdcc-43a3-b53e-0f6208f65128`: nothing in Loki,
  everything in Sentry). Four other sessions from the same `install_id` exported 554 records fine,
  and `telemetry.appEventsSampleRate` has no prod override, so it is neither credentials nor
  sampling. One session's entire export vanished.

**The mechanism, and what changed.** The batch stage holds a tick's worth of records in RAM and only
a *batch* reaches the disk buffer, so a session that ends inside that window exports nothing —
"durable" starts at the first batch, not the first record. `shop.catalog_skus_dropped` fires on the
catalog refresh a cold boot kicks off, a second or two in, squarely inside it. Sentry got the error
because its transport is immediate.

Two client fixes landed:

1. `GrafanaLogTree` flushes immediately after a small allowlist of money-path events
   (`shop.catalog_skus_dropped`, `purchase.{initiated,completed,failed}`) — all single-digit per
   month, so a flush per occurrence is affordable. Per-hand and per-action events must never join
   that list; a flush is a network attempt.
2. `OtlpJsonLogRecordExporter` logged **nothing** on failure and collapsed every status into
   `Failure`, which means "keep the batch and retry". A 4xx the gateway will never accept therefore
   sat at the head of an ordered disk buffer forever and took every later session with it — a
   candidate for the vanished session that was impossible to confirm precisely because nothing was
   logged. Failures now log with the status code, and a permanent rejection settles the batch
   instead of blocking the queue. Those logs are tagged `GrafanaExport`, which
   `GrafanaLogTree.isLoggable` excludes, so a rejected export cannot produce the record that
   produces the next one.

**Still yours, and still the part that makes the alerts fire.** Grafana is read-only for triage, so
the rule edits are a deliberate human change:

- **A5** (`bfrtc7fm94qgwb`) gates on `$B >= 5` purchase attempts per hour. Prod had 3 in 30 days.
  The guard that stops noise also stops the alert from ever evaluating at our volume.
- **A8** (`ffvj3s6ax0h6oe`) counts `shop.catalog_skus_dropped` over a **6-hour** window with
  `no_data_state: OK`, against an event that only emits when an affected user opens the shop. Widen
  the window well past the gap between observations, or compare shop views against resolvable packs,
  so the signal is a *state* rather than an event burst.

**The orphaned purchase was not a bug, and the funnel is now closed.** The `purchase.initiated` on
2026-10-17 (0.5.0, `chip_pack_small`, iOS 27.0.1) with no terminal event after it is the
anonymous-buyer shape: `DefaultPurchaseChipPackUseCase` logged `initiated` first, and the
`NotSignedIn` / `ClaimAccountRequired` gates both returned before `logPurchaseEvent` ran. Expected
behaviour (purchases require a real account) that was indistinguishable in Loki from a purchase
that started and died.

Fixed structurally rather than at the two gates: `invoke` logs `initiated`, delegates to a private
`resolve`, and logs the terminal event on the way out, so a future early return cannot escape it.
The two gates now emit `purchase.blocked` with a `reason` of `not_signed_in` or
`claim_account_required` — its own event name, not a `purchase.failed` reason, because nothing is
broken on those branches and folding them in would make A5 page on expected behaviour. Every
terminal outcome is in the telemetry flush allowlist now (`completed`, `failed`, `cancelled`,
`blocked`), since a terminal event that can be lost while its `initiated` survives invents a stuck
purchase that never happened.

**Follow-up, once 0.6.1 is in users' hands:** `initiated` minus the four terminals becomes a real
stuck-purchase signal and is worth its own rule. Do not build it sooner. Older builds cannot emit
the terminal events it would count, which is precisely the mistake that made A8 useless.

**A5 and A8 were rewritten on 2026-10-21** (both could never fire; see `docs/decisions.md`). A5 is
now "any `purchase.failed` in 7 days" rather than an hourly success rate gated to 5 attempts; A8
counts over 7 days rather than 6 hours and is deliberately sticky. Remaining known gap: both use
`no_data_state: OK`, so a total Loki outage reads healthy on every event-based rule. A "client
telemetry has gone quiet" watchdog would cover them all at once, and does not exist.

## ENG-82 [P1] — Grafana rejects telemetry with 400 and we cannot tell why

**Problem:** CARDS-CZ, five rejections in two seconds on 2026-10-23, iOS 27.0.1, `cards@0.6.0+1308`,
one install (`f6d7a420`), ~10 records dropped across batches of 1 to 4. The ENG-80 logging added in
0.6.0 is doing its job: a permanent 4xx now gets logged and the batch settled instead of blocking
the queue. But it logs **only the status code**, so the reason Grafana refused is unknowable and the
underlying defect cannot be fixed from the signal. Other records from the same session (`app.launched`,
`shop.viewed`, `app.backgrounded`) reached Loki fine, so this is a subset of records, not a dead pipe.

**Not yet known, and worth not guessing:** two candidates fit and neither is confirmed. Oversized
attribute values (iOS `DarwinHttpRequestException` messages are multi-hundred-character NSError
dumps, and `klogForwardingEnabled` forwards Warn+ logs with `exception_message` attached) or
out-of-window timestamps on buffer-replayed records. A dropped-SKU correlation was checked and
**ruled out**: CARDS-8V is a different install on a different OS and build.

**Acceptance:** the log line names what Grafana objected to (status plus a truncated response body),
and the class of record that caused it stops being rejected. Watch the rate as iOS 0.6.0 phases out:
it is currently one of only ~5 iOS installs on 0.6.0, so the share is not small.

**Hints:** `OtlpJsonLogRecordExporter.export` in `:libraries:telemetry:impl` logs the status and
discards `response.bodyAsText()`. Cap attribute values there too while in the file; an unbounded
user-content-derived string going to a telemetry gateway is a bad idea independent of this bug.
Reconsider the ERROR level for a routine permanent-rejection drop, which is what put this in Sentry
and in the owner's inbox. Case `docs/agent/feedback-cases/CARDS-CZ.md`;
https://elijah-dangerfield.sentry.io/issues/CARDS-CZ/

## ENG-83 [P1] — iOS refuses to open the problem-gambling resource link

**Problem:** CARDS-CY, 2026-10-22, `cards@0.6.0+1308`, iOS 27.0.1, route `ShopProductSheetRoute`:
`iOS refused to open https://www.ncpgambling.org/`. The responsible-gambling resource is a dead tap
on iOS. One event so far, but it is deterministic rather than incidental: `canOpenURL` fails the
same way every time for a scheme the app has not declared, which is a known landmine in this
codebase's lineage. Compliance-adjacent as well as broken, since problem-gambling resources are the
kind of thing a store reviewer checks for a poker app.

**Acceptance:** tapping the resource opens the page on a real iOS device, and the failure path does
not log at ERROR when it is the OS declining rather than a bug.

**Hints:** `Router.openWebLink` and the iOS `WebLinkLauncher` in
`libraries/navigation/impl/.../IosWebLinkLauncher.kt`. Check `LSApplicationQueriesSchemes` in the
iOS `Info.plist` and whether `canOpenURL` is being consulted at all for an `https` URL, which does
not need it. Case `docs/agent/feedback-cases/CARDS-CY.md`;
https://elijah-dangerfield.sentry.io/issues/CARDS-CY/

## ENG-84 [P1] — Fatal crash launching the Play billing activity on a device without Play Services

**Problem:** CARDS-BS, fatal and unhandled, 2026-10-22, `cards@0.6.0+1308`, Android 11, Huawei
HRY-LX1T. `RuntimeException: Unable to start activity ComponentInfo{.../ProxyBillingActivity}` with
a nested `NullPointerException` on an `Intent`. Breadcrumbs show `Minted fresh install_id on first
launch` immediately before, so this is a **first run**. Huawei devices of this generation ship
without Google Play Services, which is the most likely reason the billing proxy gets a null Intent.
Marked `regressed`, so it has been seen before. One event, one user, but a fatal on first launch
means that user never got into the app at all.

**Acceptance:** a device with no Play Services reaches Home without crashing, and the shop degrades
to "chip packs unavailable" rather than taking the process down.

**Hints:** `:libraries:billing:impl` and wherever the billing client connects during startup.
`BillingAvailability` already models an unavailable store; the question is whether anything calls
into Play Billing before that check. The Huawei build has no emulator equivalent, so reason it
through rather than trying to reproduce. Case `docs/agent/feedback-cases/CARDS-BS.md`;
https://elijah-dangerfield.sentry.io/issues/CARDS-BS/

## ENG-85 [P2] — The in-app review prompt throws when no Activity is foregrounded

**Problem:** CARDS-D0, 2026-10-23, `cards@0.6.0+1308`, Android 16, route `PlayMultiplayerRoute`:
`IllegalStateException: No foreground Activity available for in-app review.` from
`AndroidReviewLauncher.kt:39`, fired twice in a row right after two achievement celebrations. The
prompt is requested at a moment when `ActivityProvider.currentActivity()` is null, so the launcher
`error()`s. Contained by `Catching`, so no crash, but it logs at ERROR and the review ask is
silently lost at exactly the moment it was best timed.

**Acceptance:** no foreground Activity means the prompt is deferred or skipped quietly, not thrown
and logged as an error.

**Hints:** `AndroidReviewLauncher.requestReview` and `RealReviewPromptCoordinator.requestPrompt:82`.
A missing Activity is an expected condition for a prompt fired off a coroutine, so it wants a
null-return path rather than `error()`. Worth checking whether the coordinator should re-arm so the
ask survives to the next good moment. Case `docs/agent/feedback-cases/CARDS-D0.md`;
https://elijah-dangerfield.sentry.io/issues/CARDS-D0/

## ENG-86 [P1] — Store release notes are raw changelog markdown, commit links and all

**Problem:** The App Store "What's New" for 0.6.0 literally reads
`## [0.6.0](https://github.com/Elijah-Dangerfield/Cards/compare/v0.5.0...v0.6.0) (2026-10-21)`
followed by `* **auth:** offer the stranded account back after a session loss ([7998719](https://github.com/...))`.
Both store lanes take `gh release view --json body` verbatim: `release.yml` → "Render release notes
for Play" (`head -c 500`) and "Fetch release notes for TestFlight changelog" (`head -c 3000`). So
players get release-please's internal changelog, including markdown syntax, conventional-commit
prefixes, bare commit shas, full GitHub URLs and internal ticket ids, mid-word truncated at a byte
count. Every release since the automation landed looks like this.

**Acceptance:** the store listing shows a few plain sentences a player can read, with no markdown,
no URLs, no shas, no `feat:`/`fix:` prefixes and no truncation mid-word. Both lanes use the same
copy, and a release with nothing user-facing says something honest rather than dumping chores.

**Hints:** `release.yml` lines ~251 (Play) and ~613 (iOS). Options, cheapest first: strip and
reformat the body in the workflow; or keep a hand-written `docs/release-notes/<version>.md` that the
lane prefers and falls back from; or have release-please emit a separate user-facing section. The
house voice is in `docs/marketing-copy.md` — warm, plain, no marketing filler — and the
`unslop-text` skill applies, since this is user-facing copy. Worth fixing before 0.6.1: the listing
is the first thing a new player reads.

## ENG-87 [P2] — Nothing notices if client telemetry stops arriving at all

**Problem:** Every event-based alert (A4, A5, A8) sets `no_data_state: OK`, which is right on its
own — no dropped-SKU events means the shop is fine — but it means a total ingest failure reads as
healthy across all of them simultaneously. If the client stopped reaching Grafana tomorrow, the
alert page would stay green and the dashboards would empty out with nobody told. ENG-82 is a live
reminder that records do get refused. Sibling of ENG-71 (nothing notices a stalled deploy) and
ENG-76 (nothing notices an unpublished release): the same blind spot, a third time.

**Acceptance:** a sustained drop in client event volume against its own recent baseline produces a
signal. Not an absolute threshold, which would need re-tuning every time the population moves.

**Hints:** `app.foregrounded` is the honest heartbeat (`app.launched` undercounts, see the CARDS-CK
entry in the observability log). Grafana rule edits are a deliberate human change. Keep it a
warning, not a page: a quiet hour at this population is normal, a quiet day is not.
