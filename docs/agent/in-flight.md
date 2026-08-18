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
