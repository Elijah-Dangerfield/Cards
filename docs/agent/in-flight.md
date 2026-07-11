# In-flight log

## fix(feedback): surface submit failures instead of faking success (ENG-22)

**Problem:** A failed feedback/bug-report send still showed the thanks snackbar, bumped the counter, and navigated back, silently discarding the user's typed report (the compounding half of the 2026-07-09 lost-feedback incident).
**Approach:** Replaced the `eitherWay {}` in both VMs with success/failure branches: failure keeps the user on the form with text intact and surfaces a typed `SubmitFailed` inline error (same slot as MessageRequired; the Send button is the retry); success is unchanged except the thanks snackbar now resolves on `appScope` after `goBack()` wrapped in `Catching {}` (mirrors EditProfileViewModel's navigate-first pattern, and keeps a string-resolution failure from ever crashing post-submit). Rejected a snackbar-based error in favor of the inline slot the screens already render.
**Reviewer notes:** Failing tests written first (red at `expected SubmitFailed / was null` + goBack asserted); 3 failure-path + 3 success/retry tests added across both VMs with a new RecordingRouter fake. QA entry PROF-3 added.

## feat(telemetry): logEvent + GrafanaLogTree client app events pipe (ENG-18)

**Problem:** No product analytics — anything only the client sees (matchmaking back-outs, backend-unreachable errors) never reaches Grafana.
**Approach:** Shipped PR 1 of the approved plan (`docs/plans/client-app-events-otel.md`): `Logger.logEvent(name, attrs)` in `:libraries:core`, new single-module `:libraries:telemetry:impl` hosting `GrafanaLogTree` (opentelemetry-kotlin 0.5.0, batch OTLP export direct to Grafana Cloud with basic-auth Ktor client), remote-config kill switch + per-session sampling, AutoInit planting + `app.launched`, and starter events in RoomRepositoryImpl / DefaultPurchaseChipPackUseCase / PlayPokerViewModel. All library APIs verified against the 0.5.0 source tarball before writing (exporter/processor factory signatures, `Logger.emit(eventName)`, custom-client plugin requirements). Rejected a new injectable event tracker in favor of riding the existing KLog trees (plan's call; call sites already hold loggers).
**Reviewer notes:** Credentials are blank placeholder constants in `GrafanaCloud` — the tree is never planted until the owner pastes the logs:write token (developer-todo), so nothing ships bytes yet; the disabled path logs once at boot. 8 tests cover forward/filter/kill-switch/sampling/rollover/stringification via a synchronous recording processor (avoids racing the library's export coroutines — deliberately not the in-memory exporter, whose simple processor exports on Dispatchers.Default). Ktor skew: 0.5.0 built against 3.5.1, repo forces 3.5.0 — compiles green both platforms; real send verified only once the token lands. ENG-18 rewritten in todo to the remaining slice (verify + PR 2 sweep).
**Deferred:** PR 3 (Warn+ forwarding flag) and PR 4 (dashboards) — stay under ENG-18 in the plan. `docs/wiki/app-events.md` event registry — lands with the PR 2 sweep.

## docs(wiki): refresh wallet page to the PROG-11 derived-balance model (ENG-23)

**Problem:** `docs/wiki/wallet.md` still described the pre-PROG-11 client (single mutable balance trusted from every sync; InsufficientChips "silently resets… no user-facing surface yet").
**Approach:** Rewrote the client-side claims against the shipped `ChipsRepositoryImpl`: derived balance (snapshot + pending outbox), enqueue-only writes, `ChipSyncRejection` snackbar on InsufficientChips, null-until-hydrated first-contact behavior, and replaced the stale line-number key-file pointer with function names.
**Reviewer notes:** None.
