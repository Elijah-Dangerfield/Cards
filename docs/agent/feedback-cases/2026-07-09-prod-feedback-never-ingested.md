# Feedback case 2026-07-09-prod-feedback-never-ingested

- **Sentry issue:** none — the report never reached Sentry (that is the bug)
- **Reported:** 2026-07-09, owner's first feedback from the TestFlight/store build (release `cards@0.1.0+1`, environment `dev-ios-release`)
- **Disposition:** todo: "ENG-16 [P0] Stop sampling away release error events"

## Bug description
> Owner submitted in-app feedback from the production TestFlight build for the first time. No carrier ("User feedback"/"Bug report") event and no feedback twin ever arrived in Sentry. The content of the feedback itself is unrecoverable — it existed only in the envelope the SDK discarded.

## IDs
- No event id / session id available — nothing was ingested. The build is identifiable from its sibling events: release `cards@0.1.0+1`, environment `dev-ios-release` (channel mislabel already fixed by commit 9d100136; next store build will report prod).

## Evidence
- Sentry, last 24h, project `cards`: only 2 events total, both the IAP warning "Store did not recognize 3/3 chip-pack SKU(s)" (20:01 and 20:27 UTC, 2 users) — proving the store build's Sentry pipeline works end-to-end.
- `message:["User feedback","Bug report"]` over 7d: newest carrier is 2026-07-08 from `dev-ios-debug` (`cardse@0.1.0+1`). Zero feedback events from any release build, ever.
- No production-environment events exist at all in 24h.

## Working theory (confirmed by code read)
`AppTelemetry.initialize` mis-wires the profiling sample rate into the **error-event** sample rate:
`libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AppTelemetry.kt:88` — `config.profilesSampleRate?.let { options.sampleRate = it }`.
`SentryRuntimeConfig.forApp` (same file, ~line 401) sets `profilesSampleRate = 0.05` for release builds, so **store builds randomly drop ~95% of all error/message events client-side — crashes included**. Debug builds use 1.0, which is why every dev playtest feedback arrived and this never surfaced before prod. The IAP warning survived because it fires repeatedly (per shop/launch across 2 users), so a few rolls beat the 5% odds; a one-shot feedback submit almost never will. The feedback UI compounds it: `FeedbackViewModel` ends with `eitherWay {}` and `FeedbackRepository` wraps in `Catching {}`, so the user always sees the success snackbar.

Fix: never sample error events (leave `options.sampleRate` unset / 1.0); wire profiling to a real profiles option if the KMP SDK exposes one, otherwise delete `profilesSampleRate`. The lost report content cannot be recovered — owner should resubmit after the fix ships (or relay it in chat).
