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

## ENG-55 — DONE 2026-09-28

The gate fix itself had already landed in `d5bc323c` and the todo was stale. What was genuinely missing is now in: the ticker reads `LocalClock` so it can be driven, returns whole seconds so the badge invalidates once a second rather than per 250ms poll, and snaps under `@Preview`. Three tests prove it stops — zero clock reads across 60 virtual seconds after the deadline — which is what the acceptance actually asked for.

## ENG-56 — CLOSED 2026-09-28, already fixed; the entry was stale

All three sites already read `.value` inside their draw lambda (`TurnCountdownRing.kt:100`, `OpponentSeat.kt:151`, `PlayerArea.kt:380`), landed in `d5bc323c` / `0dcbe48d`. No code change was needed. `AnimatedStateReadInComposition` passing on every build is the standing guard.

## ENG-57 [P2] — Hole cards keyed on Card identity skip their deal-in

**Problem:** `PlayerArea.kt:475` uses `key(card)`, and `Card` is a data class. An identical card dealt into the same slot next hand reuses the composition group, so `arrived`/`revealed`/`settled` stay true and that card renders instantly face-up while its partner flies in. 3.8% — about one hand in 26. The same equality assumption underpins the `LaunchedEffect(human.holeCards)` face-up reset, though at 1/2652 that one self-heals the following hand.

**Acceptance:** Deal-in plays every hand. `BoardArea.kt:91` already does this correctly with `key(table.handNumber)` — match it.

## ENG-58 [P2] — Stale XP shown on every real-chip bust

**Problem:** `lastHandXpAwarded` is cleared only by `RequestNextHand` (`PlayPokerViewModel.kt:1008`), which real-chip tables never dispatch — the server auto-advances and the player never taps a dialog CTA. `MultiplayerBustDialog` mounts the instant `handResult` lands, before the award coroutine settles, so it briefly shows the previous hand's XP and then corrects.

**Acceptance:** The bust dialog never shows another hand's number. Clear `lastHandXpAwarded` in `HandEndAchievementsPending`, alongside `recentlyEarned`.

## ENG-59 — DONE 2026-09-28

`AnimatedNumberText` was only half fixed in `2629c5232`: the live roll was quantized but the `revealKey` replay path — the wallet's "balance changed while you were away" roll on Home and Shop — still wrote the raw animated value every frame. Both paths now share one quantizing helper. Measured by test at 13 text changes per roll against ~44 per-frame.

## ENG-60 — Make TableUiState skippable — CLOSED 2026-09-03, premise was wrong

**What it claimed:** `TableUiState.Active` and `SeatView` are unstable, so every composable taking `table:` is unskippable, and that is why `PlayerInfoTile` could not skip during ENG-49. Billed as the highest-ceiling item on the list.

**What is actually true.** Measured with the Compose compiler's own reports (now wired into the build — see below):

- **Zero** composables in `:features:room:impl` are unskippable. All 222 restartable ones are `skippable`. Strong skipping has been on by default since Kotlin 2.0.20 and this repo is on 2.4.0.
- `TableUiState.Active` is already reported **stable**. `SeatView` is reported unstable, but only because of `lastAction: PlayerAction?` and `personality: BotPersonality?` — types from `:libraries:gameplay` and `:libraries:bots`, which don't apply the Compose compiler, so there is nothing for it to infer.
- The remaining worry was that strong skipping compares *unstable* parameters by reference, so an equal-but-new `SeatView` would still recompose. **It doesn't.** A probe class with a public `var` — unambiguously unstable — skipped on an equal-but-new instance just the same. So did `SeatView` and `TableUiState.Active`.

So a stability config file or a move to `kotlinx.collections.immutable` would have bought nothing measurable, and the "amplifier under ENG-56/57/59" framing was wrong: those three were each independently real, and each was fixed on its own merits.

**What came out of it anyway:**

- A `ComposeStabilityTest` was written to assert the skipping behaviour directly, then deleted: it exercised Compose's own comparison semantics rather than any of our code, so it could not fail from a change we made. Guarding a framework guarantee is maintenance, not coverage.
- `-Pcards.composeReports=true` generates the compiler's stability/skippability reports into `build/compose-reports/`. See `build-logic/.../ComposeCompiler.kt` for how to read them, **including the warning not to treat "unstable" in that report as a cost.** Reading it that way is what produced this ticket.

## ENG-66 — Macrobenchmark frame timing — NOT DOING, 2026-09-04

Recorded so it does not get re-proposed. `FrameTimingMetric` is the standard tool for "run a UI journey, diff P95 frame times," and it is the right shape for catching jank regressions. It needs a real device to produce a usable signal: emulator frame timing on a shared CI runner has run-to-run variance larger than the regressions worth catching, so a per-PR threshold produces flaky red and gets disabled. CI here is macOS + ubuntu runners with no device, and the owner is not standing up a device farm or Firebase Test Lab for this.

What covers the gap instead: `AnimatedStateReadInComposition` catches the *cause* class statically on every PR with zero noise (it found 19 instances on its first run), ENG-63 gives real-user frame data with no device at all, and Play vitals plus the existing `PreviousExit.Anr` telemetry remain the ground truth. Revisit only if a physical device farm appears for another reason.

## ENG-61 — Compose tests that cross a hand boundary — DONE 2026-09-03

The play screen went from 14 single-state tests to **106 across five suites**, every one of which drives at least one hand boundary. Suites: table projection and felt rendering, end-of-hand disposition, modal surfaces and the leave flow, multi-hand transitions, and skippability.

They paid for themselves immediately, finding a latent crash (`BoardArea`'s `card!!`), the stacked practice-tier explainer, the action sheet riding a hand boundary, and the frozen player-profile snapshot. They also caught an ordering flaw in the harness itself: with `autoAdvance` off, a hoisted state write must be pumped before the clock advances.

Note for anyone extending them: Robolectric's default viewport is 320x470px, shorter than any shipping phone, which measures some felt elements to zero height. `PlayPokerScreenTableTest` sets `qualifiers = "w411dp-h891dp-xhdpi"`; the others should be brought in line.

## ENG-72 — DONE 2026-09-28, shipping in the next release

Android now hands Room a `FallbackSQLiteDriver`: bundled when its native lib loads, `AndroidSQLiteDriver` (platform SQLite, no `.so`) when it does not. iOS keeps the bundled driver.

The non-obvious part, worth keeping: the decision latches on a `:memory:` probe at first touch of **either** `open()` or `hasConnectionPool`, not inside the first real `open()`. Room reads `hasConnectionPool` once at connection-manager construction and the two drivers disagree (`Bundled=false`, `Android=true`), so a fallback that fired later would leave Room running its own pool over a driver that already has one. The probe is also what forces `NativeLibraryObject.<clinit>`, which is the only thing that makes the fallback fire at all — the native library does not load at driver construction.

Emits `db.driver_fallback` so the rate is visible; a silent fallback would have replaced a loud crash with nothing. Verification gap: tests use fake drivers, not a real Room database on a device.

## ENG-73 [P2] — An empty request body 500s instead of 400ing, and pages as an error

**Problem:** A client whose upload dies mid-request sends no body; `ContentNegotiation` throws `BadRequestException` wrapping `JsonDecodingException: Expected start of the object '{', but had 'EOF'`, and `StatusPages` logs it as **"Unhandled error"** at ERROR with a full stack, which reaches Sentry (CARDS-CB). Seen twice from one install on `/v1/me/player-stats/sync` and `/v1/me/wallet/sync`, 12s apart — the stats one after the server had waited **39.5 seconds** and then returned 500. A truncated upload is a client-side network condition, not a server fault: it deserves a 400 and a breadcrumb.

**Acceptance:** An empty or truncated body returns 400 and does not create a Sentry error event. The 39.5s wait before EOF also wants a request-body read timeout — that request occupied a connection for 40 seconds to learn nothing.

**Hints:** `ErrorsKt.installStatusPages` maps this; `BadRequestException` should be in the expected-client-error set. Same family as ENG-68 (429 as backpressure, not error) — consider doing them together, since both are "an expected client condition logged as a server failure". This is also the only entry the dc-infra slow-request panel caught this week, so fixing it clears that signal for real findings.

## MP-39 — DONE 2026-09-28, needs a server deploy to reach anyone

Bot nonce is now `bot:<session>:<hand>:<seat>:<street>:<lastSequence>`, so a bot acting twice on one street after a raise no longer collides with itself. `lastSequence` already existed and `TurnTimerDriver` already used it for this same collision.

Two things beyond the nonce. `IntentResult.Duplicate` distinguishes a swallowed replay from real work, which is what made this invisible — the ack still reports `accepted` for a retrying client, so the wire contract is unchanged. And a stalled-turn watchdog re-reads the table 5s after every submit, logging at ERROR and re-driving if nothing moved, so any *other* freeze path is loud instead of silent.

**Server-only**, so it reaches players on deploy with no app update.

## ENG-74 [P1] — A cold Gradle cache pushes the macOS CI job past its 45-minute timeout

**Problem:** `Build + test` has `timeout-minutes: 45` and the macOS job now runs fully cold: the repo's Actions cache holds konan and Linux entries but no `gradle-home` for macOS, so compile + assemble went from ~11 min (09-05) to ~36-44 min. Two PRs were red purely from being cancelled at the limit with no test failure — #157 at 45m22s and #158 at 45m56s, the latter masking two genuine assertion bugs that only surfaced once the tests could actually run. Today's release PR passed with **35 seconds** to spare. It is a coin flip, and a cancelled job reads as a failing one.

**Acceptance:** A cold PR run finishes with real headroom, and a timeout is distinguishable from a test failure at a glance.

**Hints:** The deadlock is that `setup-gradle` is `cache-read-only` on `pull_request` events, so PR runs can never reseed what they need, and only a `push`-event run to `main` can. No CI ran 09-21 → 09-28, so GitHub evicted everything for inactivity. Options: raise the timeout, let `main` pushes write the macOS gradle-home cache, or split compile and assemble into separate jobs. Found independently by two agents triaging #157 and #158.

## ENG-71 [P1] — Nothing notices when a deploy stalls, so prod ran 17-day-old code unseen

**Problem:** `server-deploy-prod` runs for PR #152 (2026-09-04) and #155 (2026-09-05) are still `waiting` and `pending` on the `production` environment gate, so prod has not deployed since 2026-09-02. No alert covers this: A7 checks whether the server is *silent*, and it is not — it is serving happily, just from old code. The cost is real and was invisible: the OTel trace-root fix (`ac58b1ba`) has been on `main` for over two weeks while poisoned `trace_id=57f45c70...` keeps appearing in prod logs through 2026-09-21.

**Acceptance:** A deploy left unapproved or failed for more than ~24h produces a signal somebody sees. Simplest honest version: a panel or alert comparing the commit prod reports against `origin/main`'s tip. A `/health` endpoint that returns the build SHA would make that a one-line check — it currently returns nothing.

**Hints:** Gate is `environment: production` in `.github/workflows/server-deploy-prod.yml:53`. Stuck runs: 33922369090 (`waiting`), 33975509833 (`pending`). Related evidence in `docs/agent/feedback-cases/CARDS-C9.md`. Decide deliberately whether the gate earns its keep — it is correct to want one, and a gate nobody is reminded of is the same as no deploy at all.

## ENG-49 [P1] — The RenderThread text-stall fix did NOT hold on low-end devices

**Problem:** Shipped in `v0.3.0` / build 1209 on 2026-09-21. **Contradicted on 2026-10-01: CARDS-CF (4 events / 2 users) and CARDS-CG (1 event) are both ANRs on 1209 with the same `GrTextBlobRedrawCoordinator::internalRemove` RenderThread stack**, from one vivo V2135 (`device.class: low`, Android 13, 3.8GB) in multiplayer room 434U5F, hands 9 and 23. 7d abnormal exits moved to `{anr: 6, oom: 7}` from `{anr: 1}`. So the fix did not hold on low-end hardware — but 1209 does **not** carry the ENG-55/56/59 perf work, which is sitting unpublished in 0.4.0 (ENG-76), so this is not yet a verdict on the full fix set.

**Acceptance:** Four weeks of a *published* build carrying the perf trio with no new ANR on a `TextBlobRedrawCoordinator` stack, and Play vitals flat or down. Re-read specifically on low-end devices — every confirmed instance so far is one. The clock cannot start until ENG-76 is resolved.

**Hints:** Chronology proving 1135 predates the fix is in `docs/agent/feedback-cases/CARDS-C9.md`. Original diagnosis: `docs/plans/renderthread-text-stall.md`, case `CARDS-C1.md`. Sentry: [CARDS-C9](https://elijah-dangerfield.sentry.io/issues/7744693593/), CARDS-C1, CARDS-BZ. 72 commits sit on `main` unshipped, including R8, baseline profiles and the Sentry mapping upload.

If it recurs **on a post-fix build**, the shape to look for is an animation whose value is read during composition (`val x by animateFloatAsState(...)`), which recomposes its whole subtree every frame. Three instances caused this. `AnimatedStateReadInComposition` in `:detekt-rules` now runs (ENG-54) and cleared 19 instances, but it only matches the `by animateFloatAsState(...)` shape — a clean lint is not proof that nothing recomposes per frame. Capture a trace with `scripts/compose-trace.sh`.

## ENG-54 — Make the AnimatedStateReadInComposition detekt rule run — DONE 2026-09-03

The prime suspect was right: it was the alpha. Bumping detekt `2.0.0-alpha.5` -> `2.0.0-alpha.6` made the rule dispatch, with no change to the rule itself. Everything else that had been ruled out (provider ordering, config cache, jar freshness, YAML, baseline) was a red herring.

It found **19 instances** on its first run, seven of them in files that had just been swept by hand for exactly this pattern. All 19 are cleared — see `3ff898ba`. One deliberate suppression remains, in `Header.elevateOnScroll`, with its reason in the code: `Modifier.shadow` has no lambda form.

## ENG-68 [P2] — Stop treating 429 as an error on background syncers

**Problem:** `isExpectedClientError` allowlists only 401/403 (`ExpectedClientErrors.kt:30`), so a 429 from a background syncer becomes `KLog.e` and a Sentry event. Rate limiting is normal backpressure, not a failure. Worse, `UserScopedSyncCoordinator`'s `RunWhenRetry.exponential()` ladder (`RunWhen.kt:101-108`) does not skip 4xx the way the HTTP retry does (`RetryPolicy.kt:168-173`), so one exhausted bucket costs six more requests and six more Sentry events per foreground — it retries against a bucket it already knows is empty.

**Acceptance:** A 429 on a background sync is a breadcrumb, not an error event, and the coordinator does not retry into a bucket it just exhausted.

**Hints:** Same argument ENG-34/ENG-35 already made for offline and 403. User-facing routes already map 429 sensibly (`MatchmakingRepositoryImpl.kt:49`, `FriendRepositoryImpl.kt:124`); it is only the background syncers that do not. The bucket mis-assignment that triggered this is fixed (equipment sync moved from the 30/hr profile bucket to the 480/hr progression one), so the symptom is gone — but the handling is still wrong and the next mis-sized bucket will look identical.

**Not user-affecting today:** over Loki's full 30-day retention the server returned 429 twelve times, all `deployment_environment=dev`, zero in prod. Prod served 583 equipment syncs in 14 days cleanly.

## ENG-67 — Sentry mapping upload — DONE 2026-09-04, verify on the first minified release

The Sentry Android Gradle plugin now injects the ProGuard UUID and uploads `mapping.txt`. `autoInstallation` is off — the app already uses the KMP Sentry SDK, and the plugin's default would have added `sentry-android` on top of it.

Verified locally: a release build packages `assets/sentry-debug-meta.properties` carrying `io.sentry.ProguardUuids`, which is what makes a mapping associable. Uploading only happens when `SENTRY_AUTH_TOKEN` is present, so a contributor without one can still build a release.

The hand-rolled `upload-proguard` step in `release.yml` is deleted. It could never have worked: it named a manifest path AGP no longer writes, and the app had no UUID to match, so it associated with nothing and reported success. Dormant behind a minification guard until R8 landed, which is why nobody noticed.

**Still to confirm:** read the frames on a real Sentry issue from the first minified release. An upload that "succeeded" proves nothing on its own — that is exactly how the old step looked healthy for months.

## ENG-69 — Cold-start timing on dc-perf — DONE 2026-09-04, empty until a build ships

`app.startup`, one event per process, carrying `startup_ms` from OS process creation to the first frame a player can act on. Four stats and two timeseries on `dc-perf` under "How fast does it start?".

Measured from process creation rather than from our first line of Kotlin, because a large share of a cold start (process fork, DEX loading, Application init) happens before any of our code runs — and that is exactly the part a Baseline Profile is meant to improve. A timer started later would have reported "no change" after the change that mattered most.

Two things are deliberately dropped at the source rather than charted. Launches over 30s are the system having started our process in the background hours before anyone opened the app; they are real elapsed time and would drag every percentile somewhere meaningless. Repeat calls within a process are Activity recreations (rotation, theme change), which draw a fresh first frame that is not a startup.

`MainActivity` also calls `reportFullyDrawn()` at the same instant, which is a separate win: Play Console grades "fully drawn" startup on that call, and without it Play measures to the splash frame — a number no player experiences.

**Android only.** iOS has no readable process-start clock: `sysctl(KERN_PROC)` is the only source, Kotlin/Native does not expose `kinfo_proc` for Apple targets, and it is a required-reason API besides. The correct iOS source is MetricKit's `MXAppLaunchMetric.histogrammedTimeToFirstDraw`, which we can reach through the existing `MetricKitExitReport` seam. It should land under its own event name — it is a daily histogram, not a single launch. Reasoning is in `IosProcessStartTimeProvider`'s KDoc.

**Still to confirm:** the panels have never rendered against real data. The query shapes were validated against `app.backgrounded`, which proved the Loki `by (...)` gotcha now written up in `docs/wiki/observability.md`, but "the query is valid" is not "the number is right". Check the median against a stopwatch on a real cold start once a build lands.

## ENG-53 — Turn on R8 obfuscation — DONE 2026-09-04, needs a device check before release

`isMinifyEnabled` and `isShrinkResources` are on for release, with rules in `apps/compose/proguard-rules.pro`. Play's "Obfuscation (1%)" warning and its Feb 2027 deadline were the trigger; shrinking and optimisation come along with it.

**Not yet verified on a device.** The minified APK builds (31.9MB), but R8 breaks things that are found *by name at runtime*, and that only shows up when the app runs. The three at risk here are `@Serializable` models, `@Serializable` nav routes, and the DI entry point — all have keep rules, none have been exercised minified. Run `./gradlew :apps:baselineprofile:pixel6Api34BenchmarkReleaseAndroidTest -Pcards.targetEnv=dev` (CARDS_PROFILE_GEN=true if in CI): `benchmarkRelease` *is* minified, so the profile journey doubles as an R8 smoke test through onboarding, Home and a hand.

**Known noise:** R8 logs "An error occurred when parsing kotlin metadata" repeatedly. Its metadata parser is older than Kotlin 2.4.0 — an AGP/Kotlin version skew, not a correctness problem. It can reduce obfuscation quality, so re-check Play's percentage after the first minified release.

## ENG-52 — DONE 2026-09-28

`NoUpdateSource` is gone. Android asks Play In-App Updates whether an update is actually installable, and gets the `major.minor.patch` label from two remote-config values `release.yml` now publishes after a successful production upload. The name is used only when Play's `availableVersionCode` matches the published one, so a stale or racing config resolves to null rather than a wrong label. iOS uses the public iTunes lookup.

The 10%-rollout argument in `AppUpdateSource`'s KDoc is dead and was removed. What still justifies asking the store rather than trusting config alone is the window between "Play accepted the upload" and "installable to this user" — config alone would prompt into it.

Worth recording: a purely **local** cache cannot work here, because the app only ever writes its own version, so "is current older than cached?" is never true. Any working design needs an external source.

Silent until the next production release writes the config pair; set the two flags by hand in the admin console to light it up sooner.

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

## ENG-70 — CLOSED 2026-09-28, misdiagnosed; the fix already shipped

Not a Compose Multiplatform `UriHandler` problem. `No handler available for <url>` was **our own string**: `IosWebLinkLauncher` gated every open on `check(application.canOpenURL(targetUrl))`, and since iOS 9 that call returns false for any scheme not declared in `LSApplicationQueriesSchemes`, so it blocked every outbound link. `ca77c1af` removed the gate on 2026-09-03, about 2.5 hours after the last event, and added `IosWebLinkLauncherTest` to guard it.

The ticket was filed on 09-05 by grepping a tree the string had already been deleted from, and the Compose theory was invented to explain that absence. Sentry has CARDS-C2 resolved, last seen 2026-09-03 16:42Z.

**Nothing to build.** `ca77c1af` is in `v0.3.0`. iOS store users are still on `0.1.0+1135` because the 09-21 release ran with the iOS job skipped, so the remaining work is shipping iOS, tracked in `developer-todo.md`.

## GAME-35 [P2] — Stale Call button silently no-ops in a local-bots hand

**Problem:** A retail Android install tapped "Call" in a local-bots game and it silently failed — `GameEngine.resolveAction` threw `IllegalArgumentException("Nothing to call")` (toCall was already 0), caught in `PlayPokerViewModel`'s Submit handler, but `IllegalArgumentException` isn't one of the branches that surfaces player feedback (only `IntentTimeoutException`/`IntentRejectedException` are), so the tap's haptic/chip-click fired but nothing else happened. One occurrence, immediately after two "Bot decision is stale, skipping apply" log lines in the same session.

**Acceptance:** A stale/no-longer-legal Call either can't be tapped (button disabled before the state changes) or fails with the same user-visible feedback as a rejected intent. Repro ideally reduces the race rather than just widening the catch.

**Hints:** `GameEngine.kt:331` (the throw); `PlayPokerViewModel.kt:982-999` (the catch with the `else -> Unit` gap); `LocalBotsSession.kt:409-425` (`isHumanIntentLegal`, the legality check the race slips past). Case `docs/agent/feedback-cases/2026-09-16-stale-call-button-local-bots.md`.

## ENG-75 — DONE 2026-10-02, needs the next release to prove it

`curl` treats `[` and `]` as URL-glob range syntax, so `filter[bundleId]=...` aborted with
`bad range in URL position 54` before the request was ever sent. That error fell into
`|| echo '{"data":[]}'`, which read as "no such app", which resolved to `is_first_release=true`
and the TestFlight-only lane — on every release since v0.1.0, each one reporting success.
Nothing to do with credentials or the bundle ID; both were correct all along.

Fixed with `-g` (`--globoff`) on both ASC calls, and a failed lookup now fails the step instead
of silently downgrading the lane. Verified locally: without `-g` curl exits 3 without contacting
Apple; with it the request reaches ASC.

**Still open:** the first release after this lands must be checked for
`Existing builds found on ASC (app …) — full release lane` in the iOS job log.

## ENG-76 [P1] — Nothing tells us a shipped release is still sitting unpublished

**Problem:** Play has **managed publishing on**, so the release workflow's `Successfully committed` only queues the release — it does not publish it. `v0.4.0` has been approved by Google and parked under "Changes ready to publish → Production → 0.4.0 → Start full rollout" since 2026-09-28. Prod telemetry over 3 days: `0.1.0 (1135)` 32 launches, `0.3.0 (1209)` 35, **`0.4.0 (1262)` 1**. Every fix in the release — the bot-freeze nonce collision, the SQLite fallback, the perf trio — has reached essentially nobody, and no alert, dashboard or workflow step says so.

**Acceptance:** A release that is committed but unpublished is visible without opening Play Console — either the workflow polls the edit's publishing status and fails/warns, or a dashboard panel compares the latest released version against the top `service_version` in prod launches. Decide separately whether managed publishing should stay on at all.

**Hints:** Play Console → Publishing overview shows the pending change and the "Managed publishing on" toggle. The `service_version` breakdown is `sum by (service_version) (count_over_time({service_name="cards-client", deployment_environment="prod"} | event_name="app.launched" [3d]))`. Related: ENG-52 shipped the update prompt in the same unpublished build.

## ENG-77 — DONE 2026-10-04; the premise was wrong and the gate was still worth it

Filed claiming lint "would have caught CARDS-CK". **It would not.** Verified by running
`lintRelease` against that exact commit: clean. Lint sees the `SDK_INT` guard around the API
*call* and is satisfied; it does not model the signature resolution that actually broke. A
deliberately unguarded `getHistoricalProcessExitReasons` call in the same file *does* trip
`NewApi`, so the check is active — it simply cannot see this variant. The reflection test in
`AndroidPreviousExitProviderApiLevelTest` remains the only guard for it.

Turned lint on anyway, because the first full run found four real errors for roughly nine
seconds of CI:

- `AndroidStrictModeLog.record(violation: Violation)` — API 28 type in a method signature, the
  same shape as CARDS-CK, fixed the same way.
- `windowSplashScreenBackground` (API 31) set in `values/` — moved to `values-v31/` behind a
  `Theme.Cards.Base` split.
- Three `MissingPermission` on `ACCESS_NETWORK_STATE` in `libraries/networking/impl`. The app
  only had that permission because `androidx.work` declares it; nothing in this repo did.
  Declared it in the module whose code needs it.

Fixed rather than baselined, so there is no `lint-baseline.xml` and the report starts empty.
Config lives in `build-logic/.../AndroidConfiguration.kt`; the `lint` job is in `ci.yml`.
`NewerVersionAvailable` is disabled — it hits the network on every run.

## AUTH-33 [P1] — The welcome screen reveals the previous account's balance as the starter grant

**Problem:** `GetHomeScreenNotification` falls back to `chipBalance > 0 -> Exact(chipBalance)` with the comment "A fresh account's balance equals its grant before they've played." That holds for a fresh install and breaks on a device that still has a prior account's balance cached. The owner's new guest was welcomed with **14,020** — the old account's balance — while its wallet correctly held 10,000. Reads as chip inflation; it is a wrong number on a dialog. Ledger verified clean: one `starter_grant` of exactly 10,000 per user, every balance equal to its event sum.

**Acceptance:** The reveal shows the grant or nothing. Prefer the explicit server grant; if that is absent, do not substitute a balance that could belong to a different account — hold for hydration or show `Pending`. A balance is only usable as a proxy when it provably belongs to the account being welcomed (compare the user id the balance was cached under).

**Hints:** `features/home/impl/.../notification/GetHomeScreenNotification.kt:197-212`. Test the case directly: cached balance from account A, `accountJustCreated` for account B. Case: `docs/agent/feedback-cases/2026-10-08-ios-upgrade-session-loss-and-14020.md`.

## AUTH-34 [P0] — A recoverable account can be abandoned silently after a lost session

**Problem:** When the iOS Keychain loses a claimed session across an app update, `GuestSessionHealer` correctly refuses to mint over the real account (AUTH-19) and routes to recovery. Twelve seconds later the owner was in ordinary onboarding picking "continue as guest", tagged `returning=false`, and a second account was created on the same `install_id`. The old account, `e8226cac…` with 14,020 chips, is still there and still signable-into — nothing told him that. The mint was refused and the account got stranded anyway.

**Acceptance:** After a `Session unrecoverable` decision with a cached `Profile.Authenticated`, the user cannot reach new-account onboarding without being shown what they are leaving: the display name, and that signing in with the original provider restores it. Continuing as guest stays possible, but as a deliberate choice. Emit an event for the choice so the rate is visible.

**Hints:** `GuestSessionHealer.stopForRecovery` / `markSessionUnrecoverable`; the onboarding entry that logged `onboarding.auth_selected method=guest returning=false`. The keychain loss itself is **not** the bug to fix — `SessionMirrorStore` is anonymous-only on purpose (decisions.md 2026-07-11), and sign-in is the designed recovery for a claimed account. **Urgency:** 88% of iOS users are still on 0.1.0 (57 launches vs 8 over 7 days), so the exposed population has not taken the update yet. Case: `docs/agent/feedback-cases/2026-10-08-ios-upgrade-session-loss-and-14020.md`.
