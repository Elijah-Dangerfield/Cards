# In flight

Worker notes for the reviewer, newest cycle appended. One block per commit.

## feat(telemetry): per-run foreground-termination signal (ENG-42)

**Problem:** ENG-42 / CARDS-3 — a retail iPad took two `WatchdogTermination` fatals on the iOS onboarding welcome screen and abandoned, and we had no way to tell a real OS kill from the user swiping the app out of the switcher. Sentry's watchdog detection is a next-launch heuristic, and ENG-25 deliberately made iOS `previous_exit` a day-granular MetricKit sample that reads `unknown` on exactly these launches.

**Approach:** Added our own per-run marker, which is the option the case file's fix direction leads with. A `RunMarker` (session id, foreground/background, timestamp) is written synchronously on every lifecycle transition; the next cold start reads it and emits `app.previous_run`. The distinction it buys: swiping an app out of the iOS switcher backgrounds it *first*, so a force-quit lands as `background_exit`, while a run that vanished believing it was on screen lands as `foreground_termination`. `previous_session_id` is the join key back to that run's own Loki events, which keeps the marker small enough to write on a lifecycle callback instead of carrying route state.

Two judgement calls worth a second look. **(1)** I shipped the marker on Android too, not just iOS. It costs one `SharedPreferences.commit()` per transition and it is the only way to validate the heuristic: Android has `ApplicationExitInfo` ground truth, so the two disagreeing there is the signal that the iOS reading can't be trusted. The alternative — iOS-only, matching the todo's framing — leaves the new signal permanently uncalibrated. **(2)** Storage is `NSUserDefaults` / `SharedPreferences.commit()`, deliberately not the DataStore everything else in the app persists through. The whole requirement is being on disk before a kill nobody sees coming, and an async store loses precisely the write that matters.

Also emitted `app.exit_metrics` from the existing MetricKit subscriber. It already computed all six `MXAppExitMetric` foreground counts and threw away everything but the most severe classification; keeping the raw counts is the second half of the acceptance ("MetricKit foreground watchdog counts") and is the only source for an actual iOS watchdog rate.

**Reviewer notes:**
- `foreground_termination` is a candidate set, not a verdict — hard crashes, power-off, and OS upgrades all land in it. The rate is only meaningful net of the crashes Sentry reports for the same `previous_session_id`. That caveat is written into the wiki rather than left for whoever builds the panel to rediscover.
- Migration-safe on both live platforms: this only *adds* a key. Existing Android and iOS installs have no prior marker, so their first launch after this ships reports `outcome=unknown` and every launch after that is normal. Nothing existing is read or rewritten.
- `NSUserDefaults.synchronize()` is redundant on modern iOS for a normally-exiting process. It is called anyway because a watchdog kill seconds after a background transition is exactly the case where "the system will get to it" is the assumption under test. Compiles without a deprecation warning on the iOS bindings.
- The `RunOutcomeReporter` rides the lifecycle bus rather than DI init for the ENG-24 reason: a marker written at init carries the pre-boot sentinel session uuid and the join key goes nowhere. Verified the generated iOS and Android components both wire it into the `AppEventListener` multibinding.
- Untested by unit tests: that iOS actually delivers `onBackground` before a force-quit suspension. It's the documented contract and `TelemetryBackgroundFlusher` already depends on it, but the marker's whole premise rests on it and only a device run proves it.

**Deferred:**
- The dashboard panel that charts this. Left on the ENG-42 bullet — a panel over an event no shipped build emits reads as broken, same call the ENG-38 bullet already makes.
- Reproducing and fixing the actual welcome-step hang. That's step 2 of the case file's fix direction and it needs this signal's data first; left on the bullet.

## fix(ui): stop counting the initial composition as a recomposition (ENG-42)

**Problem:** `App recomposed (this should be rare)` fired at WARN on every single cold launch, on both platforms — flagged in the ENG-42 hints as worth a look while in there. `SideEffect` runs after the initial composition too, so `RecompositionTracker` scored a composable that had merely appeared as recomposition #1.

**Approach:** The tracker now skips its first invocation, so the count means recompositions and the existing message is true when it fires. Red first: all three new tests failed against the old tracker (`countStartsAtOneOnTheFirstActualRecomposition` expected `[1, 2]`, got `[1, 2, 3]`), green after. Made `RecompositionTracker` internal rather than private so the logic is testable without driving a composition.

**Reviewer notes:** This also shifts the rapid-recomposition window by one — the initial composition no longer occupies a slot toward `rapidRecompositionThreshold`. That's the same bug and the right behaviour, but it does mean the rapid threshold is now marginally harder to trip. `App.kt` is untouched: with the count fixed, its wording is accurate as written. If the WARN still fires often in prod after this ships, that's now a real signal about the app root rather than noise.

**Deferred:** None.

## feat(shop): make a store that sells nothing impossible to miss (ENG-43)

**Problem:** On the live App Store build StoreKit recognizes none of the three chip-pack SKUs, so `reconcileAgainst` drops all of them and the Get Chips shelf just disappears. It logged at ERROR from 2026-07-23 and nothing escalated for three weeks, because a prose log line is neither queryable nor alertable and the A5 purchase-success alert structurally cannot see this: zero visible packs means zero purchase attempts, so the success rate stays a healthy 100%.

**Approach:** All three halves of the acceptance. The drop now emits `shop.catalog_skus_dropped` (`dropped`, `total`, `skus`); a new Grafana rule **A8 · Store dropped chip-pack SKUs** (`ffvj3s6ax0h6oe`, folder `downcard-engineering`, group `downcard-hourly`) fires on it in prod; and the repository exposes `observeChipPacksUnavailable()`, which the shop turns into an info banner under the Get Chips heading instead of vanishing the shelf.

Judgement calls: **(1)** A8 is `severity: warning`, not critical, so it emails rather than paging the phone. The fix for this condition lives in App Store Connect during business hours, so a 3am page buys nothing but a woken owner. The counter-argument is real (iOS revenue is zero while it's true), and if the owner disagrees it's a one-field change. **(2)** I kept the existing ERROR log alongside the new event rather than replacing it as the acceptance's "instead of a bare log string" wording suggests. The ERROR is what puts this in front of a human in Sentry; the event is what a rule can fire on. Deleting the ERROR would have traded one audience for the other. **(3)** Only an *authoritative* store answer moves the flag. An unreachable store leaves it untouched in both directions, so a connectivity blip can never accuse the store of being misconfigured, and can't clear the accusation while the packs are still hidden either.

**Reviewer notes:**
- **I did not resolve Sentry CARDS-8V, deliberately, even though ENG-43 is fully shipped.** The house rule says a fully-shipped triage item closes its issue, but that assumes shipping the fix ends the condition. It doesn't here: the SKUs are still missing in App Store Connect, iOS revenue is still zero, and the issue will re-fire the next time a user opens the shop. Resolving it would hide a live revenue-zero condition behind a visibility change. It closes when the human ASC item in `developer-todo.md` lands.
- `platform` is already an OTel resource attribute on every record, so the event doesn't repeat it. The acceptance named it as an attribute; the query works either way, and duplicating an existing dimension seemed worse than relying on it.
- A8 has never evaluated against real data, because no shipped build emits the event yet. The LogQL mirrors A5's verified stream selector and `no_data`/`exec_err` are both `OK`, so the failure mode is silence rather than a false page. Worth a look once a build carrying the event ships.
- The banner only appears when the store gave an authoritative "none". A fresh install that has never reached the store still hides the shelf silently, which is intended: we genuinely don't know, and apologizing for a maybe is worse than saying nothing.
- Added `observeChipPacksUnavailable()` to the `ProductsRepository` interface, which meant touching five test fakes across room / profile / billing / products. All mechanical.

**Deferred:**
- The App Store Connect half (create/approve the three IAPs, attach to the live version, confirm Paid Apps + tax/banking). Human-only, already on `developer-todo.md` — nothing in the repo can fix it.
- A `dc-revenue` panel breaking the event out by platform and SKU. The alert covers "tell me when", a panel would cover "show me the history"; left unbuilt because it reads as broken until a build ships the event. Not filed anywhere.

## fix(server): log and throttle failed admin-token attempts (ENG-41)

**Problem:** An unauthenticated scanner probed `POST /v1/admin/grant-chips` and `/v1/admin/messages` on prod in 2026-08. Nothing moved, but the 401 branch logged nothing and `/v1/admin` opted into no bucket, so it sat behind only the global 600/IP/min. A brute force against the chip-minting route would have been invisible on every dashboard and cheap to run: roughly 864k guesses a day.

**Approach:** The WARN went into `authenticatedAsAdmin` itself rather than the eight 401 branches the todo pointed at. One gate means no future admin route can forget to log, and the branches stay readable. It carries method, path, client IP, and which of the three failure modes it was; the presented token is never logged, because a near-miss guess sitting in the log store puts the secret somewhere much softer than the secret store.

**The judgement call worth reviewing: the bucket counts only *failed* attempts.** The todo suggested a flat 20/hour on `/v1/admin`. I built that first and then realized it would throttle the owner out of the hosted config console, which fires several authenticated reads per page load — and throttling a caller who already holds the secret protects nothing anyway. So `requestWeight` is 0 for a valid token and 1 otherwise: a guessing budget rather than a request budget, 20 wrong tokens per IP per hour, correct ones free. It's a stronger guarantee than the todo asked for (an attacker gets 20/hour instead of 864k/day) and it can't lock out a legitimate operator. If you'd rather have the blunt version, it's a one-line revert of the `requestWeight` block.

**Reviewer notes:**
- The constant-time compare moved to `AdminConfig.matchesApiToken` because the gate and the limiter both need it, and two copies of a constant-time compare is how one of them quietly stops being constant-time. Same algorithm, same length short-circuit, just one home.
- **Anyone registering `adminRoutes` / `configAdminRoutes` must now install the RateLimit plugin**, or Ktor throws at route setup. That's what broke every admin test on the first run. `installRateLimits` therefore takes `AdminConfig`, which touched ten test call sites — all mechanical, all passing `apiToken = null`.
- `withApp` in `AdminRoutesTest` now installs the limiter, so the pre-existing auth tests run through the same path prod does. They still pass, which is the useful part: the limiter doesn't change any 401 behaviour.
- Log assertions use a logback `ListAppender` on the `AdminAuth` logger. First use of that pattern in the server tests. The alternative was asserting nothing about the log, which would have left the "never log the token" property untested — and that's the property most worth a regression guard.
- Full `:apps:server:test` suite re-run clean, not just the admin files.

**Deferred:**
- Per-IP is still the keying strategy, with the shared-NAT caveat the `RateLimits.kt` header already documents. Not worth solving for a route whose only honest callers are machines.
- No alert on the new WARN. A rate rule over "someone guessed wrong" would be noise at current volume; the line being queryable is the ask, and A4's shape is there if it ever needs one. Not filed anywhere.

## Cycle note: ENG-40 is blocked on something the repo can't reach

Not a commit — a dead end worth recording so the next worker doesn't spend the same time on it.

ENG-40 wants the real App Store id to replace `APP_STORE_ID_PLACEHOLDER`, and its Hints say the id is resolvable without App Store Connect access via `itunes.apple.com/lookup?bundleId=com.dangerfield.cards.Cards`. **That method does not work.** The lookup returns `resultCount: 0` for that bundle id in every storefront tried (us, sg, gb, ca, au, de), a search for the product name ("Downcard", per `apps/ios/Configuration/Config.xcconfig`) returns ten unrelated apps, and the Sentry event for the live release carries no `app_identifier` to cross-check against. The checked-in bundle id is also `com.dangerfield.cards.Cards$(TEAM_ID)` with `TEAM_ID` empty in the repo, so the shipped identifier may carry a team suffix that isn't in source at all.

Either the human reads the id off App Store Connect, or someone with the signed build reads it from there. Left the item untouched; the stale Hints line is the thing to fix when that lands.

---

# 2026-08-20 cycle — worker 1

## fix(test): pass AdminConfig to installRateLimits in the MP harness

**Problem:** Not from the todo list. `:apps:integration:testDebugUnitTest` has not compiled on develop since ENG-41 (ec0755f8) gave `installRateLimits` a required `AdminConfig` parameter and updated the in-module server tests but not `apps/integration`'s `InProcessServer`.

**Approach:** Passed the same null-token config the server route tests use. Split into its own commit because it isn't AUTH-29's work — but I couldn't verify my own change to that module without it, and leaving develop red there is worse than a one-line drive-by.

**Reviewer notes:** Whatever runs `:apps:integration` in CI evidently isn't gating develop, or ENG-41 wouldn't have landed. Worth a look — the MP scenario harness is the only thing testing the room socket end to end. Full `:apps:integration:testDebugUnitTest` passes now.

**Deferred:** None.

## fix(auth): answer a stranded session with a typed 401, not a raw 500 (AUTH-29)

**Problem:** A verified JWT naming a user id with no `auth.users` row failed V11's `*_user_id_fk` on every per-user write. That reached the generic StatusPages handler, so the client got a 500 quoting the constraint, each attempt filed a Sentry issue, and nothing told the client to stop — so it kept syncing.

**Approach:** One wire answer (`401 account_not_found`), two detectors: an explicit `SELECT 1 FROM auth.users` pre-flight on the profile-create branch, plus a StatusPages net that recognises a 23503 violation of any `*_user_id_fk`. **The judgement call worth reviewing: the todo asked only for the pre-flight ("detect before the child write"), and I built the net as well.** The pre-flight alone is incomplete — every repo that writes a per-user row would have to remember it — and a net alone would put driver-error-message parsing on the hot path. Together, the net covers every table for free and the request that defines the whole session (`GET /v1/me`) gets a positive assertion. The pre-flight is nearly free because a `profiles` row is itself proof of an `auth.users` row: V11's FK plus `ON DELETE CASCADE` means they exist or vanish together, so only the create branch can be about to write against a ghost.

**Second call: 401 over 409.** 409 is the more literal status — the token verifies, so nothing is unauthenticated. But a client that doesn't recognise the code has to do *something*, and re-authenticating is the only useful something; 409 would read as an unhandled error and keep the retry loop alive, which is the bug being fixed. Ktor's bearer plugin doesn't spend a refresh round-trip on it because the response carries no `WWW-Authenticate`. One-field change if you disagree.

Client side adds no new machinery: the typed code feeds the existing `SessionRejectionBus`, the same seam a server-rejected refresh uses, so the teardown, the `SessionExpired` state, and the recovery screen are all already built and tested. Full rationale in `docs/decisions.md`.

**Reviewer notes:**
- **Red-then-green held on the server, not on the client.** Three of the seven cases in `UnknownAuthUserResponseTest` fail against the pre-fix `Errors.kt` (verified by stashing it). The client test exercises a function that didn't exist before, so its "red" is only a compile failure — weaker, and worth knowing.
- **The two new `PostgresProfileRepositoryTest` cases did not run locally.** Docker isn't available on this machine, so `DatabaseTest` skipped via its `Assume`. They're the only coverage of the pre-flight against a real `auth.users`, and CI is the first place they'll actually execute. If they fail there, the suspect is the raw `SELECT 1 FROM auth.users` in `PostgresProfileRepository.authUserExists` — everything else in this change is Docker-free and green.
- `AuthTokenProvider` gained `isAnonymousSession()`. Two implementations, both updated; no default value on purpose, since a silently-wrong `false` would offer a guest the wrong recovery screen.
- A knock-on I like but didn't set out to build: the rejection signal bumps `SessionRejectionBus.rejectionEpoch` synchronously, before the exception propagates, so `authedCall` remaps the failing call to `AuthUnready(SessionExpired)`. That's logged at info, which means the storm stops *and* stops filing error telemetry on its way out.
- `theResponseLeaksNoSchemaDetail` passes against the old code too. It's guarding the new response shape rather than reproducing the old leak — the old leak came from Exposed's own message, which the synthetic exception doesn't carry.
- No Sentry issue to resolve; AUTH-29 was filed from a log pattern, not a captured event.

**Deferred:**
- The other `findOrCreate` repositories (wallet, player-stats, progression, play-style) still have no pre-flight and rely on the FK net. That's the intended split, not an oversight — but if one of them ever needs the positive assertion, `authUserExists` should move out of `PostgresProfileRepository` and become something shared. Noted here only; nothing filed.
- Nothing charts `account_not_found` yet. It's a warn-level log line, queryable in Loki, and at current (essentially zero) volume a panel or alert over it would be noise. Not filed.
