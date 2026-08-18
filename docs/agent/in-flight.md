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
