# In-flight — Phase 3 workers

## fix(telemetry): drop expected AuthUnready control-flow from Sentry events (ENG-29)

**Problem:** `AuthUnready` is a deliberate typed short-circuit (auth not ready at cold start), but `SentryLogTree` forwarded any error-level throwable straight to `captureException`, so benign `FinishingSetup` / `NeedAccount` signals became Sentry error events on real beta builds (CARDS-9C / CARDS-9M) and masked real errors.
**Approach:** Central filter over the call-site fix. Added an `ExpectedControlFlow` marker interface in `:libraries:core` (with `Throwable.isExpectedControlFlow`), made `AuthUnready` implement it, and taught `SentryLogTree.shouldCaptureEvent` to skip capturing when the throwable is expected control-flow. It still breadcrumbs + buffers locally, so a feedback report keeps the context. One guard protects every future call site, versus chasing each error-level log that might carry an expected short-circuit.
**Reviewer notes:** `SentryLogTree` can't be exercised against the real static `Sentry` object in a JVM unit test (`Sentry.isEnabled()` is false there), so the regression guard tests the extracted `shouldCaptureEvent` decision instead (AuthUnready → false, real throwable/message → true, below-threshold → false). That is the exact branch the bug lived on.
