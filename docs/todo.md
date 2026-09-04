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

**Acceptance:** Filter the `dc-pulse` crash-free sessions/users/trend, ANR-free sessions, abnormal-exits-by-kind, DAU-by-device, and Installs-30d panels on `genuine_install="true"` behind a "show all" toggle var. Blocked until a store build carrying the attrs reaches prod: no record has the field yet, so filtering today zeroes row 1. Verify against live data first (`{service_name="cards-client"} | genuine_install="true"` returns rows), and calibrate — if `genuine_install="false"` is a rounding error, close this instead of filtering.

**Hints:** Values are strings, so match `="true"` not a bool. **"Accounts (all-time)" can't be filtered this way** — it's a Postgres count over `profiles`, which has no telemetry attrs; either drop it from scope or join on something server-side. Attribute semantics: `docs/wiki/app-events.md` → "Install and device facts".

## ENG-50 [P1] — Stop the rolling deploy from restart-storming on the single-writer lock

**Problem:** Every prod deploy briefly runs a second machine that cannot take the Postgres advisory lock the old one still holds. It retries, exits, and Fly restarts it, about ten times. Measured on the 2026-09-01 deploy: 10 restarts on the throwaway instance and a CPU spike to 531% from repeated JVM cold starts, ~20 minutes after the deploy had otherwise finished. It resolves itself, and no data is ever at risk — refusing to boot is the correct behaviour. The cost is that a `fatal` uncaught exception is now the normal outcome of a healthy deploy, which means a genuine split-brain would be indistinguishable from deploy noise. That is what makes this P1 rather than cosmetic.

**Acceptance:** A deploy produces at most one restart on the incoming instance. Either have `SingleWriterGuard` wait on the lock rather than exiting (so Fly does not restart it), or have the outgoing machine release the lock before the new one starts.

**Hints:** Sentry https://elijah-dangerfield.sentry.io/issues/CARDS-9Q is this, `level=fatal`, `handled=no`, escalating — previously mis-ledgered as dev-only, it is prod. `SingleWriterGuard.acquire` logs "Single-writer lock held by another instance; retrying (attempt N)" then gives up and exits. `apps/server/fly.prod.toml` explains why the strategy is `rolling` and never blue-green: in-memory room state plus the advisory lock. Rolling avoids the deadlock it was chosen to avoid; it does not avoid this thrash. Evidence: dc-infra → Restarts (24h), broken out per instance.

## ENG-55 [P1] — The emote ticker never stops

**Problem:** `EmojiTray.kt:86` gates a 4Hz ticker on `cooldownEndsAtEpochMs > 0L`, which means "has ever sent an emote", not "is still cooling down". The only writer (`PlayPokerViewModel.kt:1264`) sets `now + EMOJI_COOLDOWN_MS` and nothing ever writes 0 back, so `LaunchedEffect(active)` never re-keys and `while(true){ …; delay(250) }` writes snapshot state for the rest of the session. Eight seconds of it are useful. This is a **fourth** infinite composition-scope read that the ENG-49 sweep missed, and unlike the others it costs the user nothing to trigger.

**Acceptance:** The ticker stops when the cooldown expires. Verify with a composition trace (`scripts/compose-trace.sh`): `SeatEmoteBadge` should stop recomposing a few seconds after an emote, not keep going.

**Hints:** Gate on `cooldownEndsAtEpochMs > now` and exit the loop once it passes. Invalidation is contained to `SeatEmoteBadge` (a Box, a cutout, an emoji Text), not all of `PlayerArea` — `rememberSecondTicker` returns a value so it has no restart scope of its own. Full context: `docs/plans/playpokerscreen-review.md`.

## ENG-56 [P1] — Three one-line animation reads still in composition

**Problem:** The ENG-49 sweep fixed three; these three remain, all the same shape (`by` on an animated value whose only consumer is already a draw-phase lambda). `TurnCountdownRing.kt:82` costs ~1800 recompositions per 30s multiplayer turn, on essentially every turn of every MP hand. `OpponentSeat.kt:123` fires on every turn change for every seat. `PlayerArea.kt:196` (`dragProgress`) is the same anti-pattern as the measured 471→16 fix, in the same file, one screenful below the comment explaining why not to do it.

**Acceptance:** All three read `.value` inside their draw lambda. Re-trace and confirm the counts drop.

**Hints:** `docs/plans/playpokerscreen-review.md` items 2, 3, 5. **Expect `OpponentSeat` not to move the RenderThread number** — its text is still drawn under a per-frame-changing scale, and Skia's glyph reuse does not survive a scale change. Fix it for the recomposition, not for the glyph cache.

## ENG-57 [P2] — Hole cards keyed on Card identity skip their deal-in

**Problem:** `PlayerArea.kt:475` uses `key(card)`, and `Card` is a data class. An identical card dealt into the same slot next hand reuses the composition group, so `arrived`/`revealed`/`settled` stay true and that card renders instantly face-up while its partner flies in. 3.8% — about one hand in 26. The same equality assumption underpins the `LaunchedEffect(human.holeCards)` face-up reset, though at 1/2652 that one self-heals the following hand.

**Acceptance:** Deal-in plays every hand. `BoardArea.kt:91` already does this correctly with `key(table.handNumber)` — match it.

## ENG-58 [P2] — Stale XP shown on every real-chip bust

**Problem:** `lastHandXpAwarded` is cleared only by `RequestNextHand` (`PlayPokerViewModel.kt:1008`), which real-chip tables never dispatch — the server auto-advances and the player never taps a dialog CTA. `MultiplayerBustDialog` mounts the instant `handResult` lands, before the award coroutine settles, so it briefly shows the previous hand's XP and then corrects.

**Acceptance:** The bust dialog never shows another hand's number. Clear `lastHandXpAwarded` in `HandEndAchievementsPending`, alongside `recentlyEarned`.

## ENG-59 [P2] — Two places still rebuild a text blob every frame

**Problem:** `BoardArea.kt:124` (pot ship, 800ms) and `AnimatedNumberText.kt:77,113` (chip odometer, 700ms) each feed a **new String every frame** into large text, and they overlap at hand end. This is the exact `GrTextBlobRedrawCoordinator` path the four ANR traces end in, and the last known instance of text *content* changing per frame.

**Acceptance:** Neither produces a new string per frame; quantize to the steps a human can read. Confirm with a trace at hand end.

**Hints:** Do both together or neither — they fire at the same moment on the same screen, so fixing one leaves the stall. The odometer's per-frame string is inherent to an odometer, so this is about step count, not removing the animation.

## ENG-60 — Make TableUiState skippable — CLOSED 2026-09-03, premise was wrong

**What it claimed:** `TableUiState.Active` and `SeatView` are unstable, so every composable taking `table:` is unskippable, and that is why `PlayerInfoTile` could not skip during ENG-49. Billed as the highest-ceiling item on the list.

**What is actually true.** Measured with the Compose compiler's own reports (now wired into the build — see below):

- **Zero** composables in `:features:room:impl` are unskippable. All 222 restartable ones are `skippable`. Strong skipping has been on by default since Kotlin 2.0.20 and this repo is on 2.4.0.
- `TableUiState.Active` is already reported **stable**. `SeatView` is reported unstable, but only because of `lastAction: PlayerAction?` and `personality: BotPersonality?` — types from `:libraries:gameplay` and `:libraries:bots`, which don't apply the Compose compiler, so there is nothing for it to infer.
- The remaining worry was that strong skipping compares *unstable* parameters by reference, so an equal-but-new `SeatView` would still recompose. **It doesn't.** A probe class with a public `var` — unambiguously unstable — skipped on an equal-but-new instance just the same. So did `SeatView` and `TableUiState.Active`.

So a stability config file or a move to `kotlinx.collections.immutable` would have bought nothing measurable, and the "amplifier under ENG-56/57/59" framing was wrong: those three were each independently real, and each was fixed on its own merits.

**What came out of it anyway:**

- `ComposeStabilityTest` (`:features:room:impl`) asserts the behaviour directly — an unchanged seat and an unchanged table skip, a changed one does not. Behavioural, so it survives a change in comparison semantics, unlike an assertion about compiler metadata.
- `-Pcards.composeReports=true` generates the compiler's stability/skippability reports into `build/compose-reports/`. See `build-logic/.../ComposeCompiler.kt` for how to read them, **including the warning not to treat "unstable" in that report as a cost.** Reading it that way is what produced this ticket.

## ENG-61 — Compose tests that cross a hand boundary — DONE 2026-09-03

The play screen went from 14 single-state tests to **106 across five suites**, every one of which drives at least one hand boundary. Suites: table projection and felt rendering, end-of-hand disposition, modal surfaces and the leave flow, multi-hand transitions, and skippability.

They paid for themselves immediately, finding a latent crash (`BoardArea`'s `card!!`), the stacked practice-tier explainer, the action sheet riding a hand boundary, and the frozen player-profile snapshot. They also caught an ordering flaw in the harness itself: with `autoAdvance` off, a hoisted state write must be pumped before the clock advances.

Note for anyone extending them: Robolectric's default viewport is 320x470px, shorter than any shipping phone, which measures some felt elements to zero height. `PlayPokerScreenTableTest` sets `qualifiers = "w411dp-h891dp-xhdpi"`; the others should be brought in line.

## ENG-49 [P2] — Confirm the RenderThread text stall is actually gone in production

**Problem:** Fixed 2026-09-03, unverified in production. Three infinite animations read their value during composition, recomposing their whole subtree every frame: `PlayerArea`'s turn pulse (471 -> 16), and `GoldSeatRing` on every opponent seat (57 -> 3). All fed text, which thrashed Skia's glyph cache and wedged the RenderThread — worst draw 127.1ms -> 49.6ms. Whether that is enough to stop the ANRs only production can say.

**Acceptance:** No new ANR with a `GrTextBlobRedrawCoordinator` RenderThread stack for four weeks, and Play vitals ANR rate flat or down. If one appears, capture a trace with `scripts/compose-trace.sh` and look for the next composable recomposing per frame.

**Hints:** Full write-up and step-by-step plan in `docs/plans/renderthread-text-stall.md`. Case: `docs/agent/feedback-cases/CARDS-C1.md`. Sentry https://elijah-dangerfield.sentry.io/issues/CARDS-C1 and https://elijah-dangerfield.sentry.io/issues/CARDS-BZ.

If it recurs, the shape to look for is an animation whose value is read during composition (`val x by animateFloatAsState(...)`), which recomposes its whole subtree every frame. Three instances of that caused this. `AnimatedStateReadInComposition` in `:detekt-rules` is meant to catch them and does not run yet — see ENG-54.

## ENG-54 — Make the AnimatedStateReadInComposition detekt rule run — DONE 2026-09-03

The prime suspect was right: it was the alpha. Bumping detekt `2.0.0-alpha.5` -> `2.0.0-alpha.6` made the rule dispatch, with no change to the rule itself. Everything else that had been ruled out (provider ordering, config cache, jar freshness, YAML, baseline) was a red herring.

It found **19 instances** on its first run, seven of them in files that had just been swept by hand for exactly this pattern. All 19 are cleared — see `3ff898ba`. One deliberate suppression remains, in `Header.elevateOnScroll`, with its reason in the code: `Modifier.shadow` has no lambda form.

## ENG-53 [P2] — Turn on R8 obfuscation before Play's Feb 2027 deadline

**Problem:** Android vitals reports obfuscation at **1%** (the 1% is pre-obfuscated dependencies; none of our code is), against Play's 25% threshold, with a stated deadline of Feb 2027 and "may impact your visibility and publishing capabilities". Release builds set `isMinifyEnabled = false` and there is no `proguard-rules.pro`, so they are neither shrunk nor obfuscated and emit no `mapping.txt`.

**Acceptance:** Release builds obfuscate, `mapping.txt` reaches Sentry, and the smoke checklist passes against the installed release artifact. Staged per `docs/plans/r8-obfuscation.md` so a failure names its own cause: shrink-only first, then renaming, then optimization.

**Hints:** Full plan and risk inventory in `docs/plans/r8-obfuscation.md`. **No test we have can catch R8 breakage** — every test we have runs on the JVM against unminified classes, including the Robolectric `compose-uiTest` suites — so verification is the device smoke run, not a test suite. Top risk is type-safe nav routes: `androidx.navigation` keys destinations on the qualified class name and ours are `@Serializable` classes. Fix `KLog.kt:256-258` first — it filters stack frames by `::class.qualifiedName`, so obfuscation would silently misattribute every log line to the logger instead of the call site. Rollback is one line.

## ENG-52 [P1] — Give the update prompt a real version source

**Problem:** The "there's a newer Downcard" prompt is wired end to end (rule, arbiter slot, sheet, persistence) but bound to `NoUpdateSource`, which never reports an update, so it can never fire. The two stores answer different questions and neither alone is enough. **Play's In-App Updates API** correctly answers "is an update available to *this* install", staged rollouts included, but returns an `availableVersionCode` integer and never a version *name* — and the prompt rule needs `major.minor.patch` to tell a feature release from a patch. **Apple ships no equivalent API at all**, though its public iTunes lookup does return a real version string.

**Acceptance:** A device one feature release behind sees the prompt once; a device one patch behind never does; a device on a staged rollout it hasn't reached yet never does. Swap the `@ContributesBinding` off `NoUpdateSource` — no call-site changes.

**Hints:** The shape that satisfies both stores is Play for *availability* plus our own version-name source for the *label*. `release.yml` already publishes a config manifest at release time (`PUT /v1/admin/config/manifest`), so the server already knows the shipped version name; exposing it is the smaller half. iOS can use the iTunes lookup alone. Don't drive this from remote config on its own: it answers "what we shipped", not "what this user can install", so it would prompt everyone during a 10% rollout. Reasoning is in the `NoUpdateSource` KDoc; rule and its tests are `AppVersion.isWorthPromptingFrom`.

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
