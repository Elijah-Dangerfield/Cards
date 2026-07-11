# In-flight log

## fix(feedback): surface submit failures instead of faking success (ENG-22)

**Problem:** A failed feedback/bug-report send still showed the thanks snackbar, bumped the counter, and navigated back, silently discarding the user's typed report (the compounding half of the 2026-07-09 lost-feedback incident).
**Approach:** Replaced the `eitherWay {}` in both VMs with success/failure branches: failure keeps the user on the form with text intact and surfaces a typed `SubmitFailed` inline error (same slot as MessageRequired; the Send button is the retry); success is unchanged except the thanks snackbar now resolves on `appScope` after `goBack()` wrapped in `Catching {}` (mirrors EditProfileViewModel's navigate-first pattern, and keeps a string-resolution failure from ever crashing post-submit). Rejected a snackbar-based error in favor of the inline slot the screens already render.
**Reviewer notes:** Failing tests written first (red at `expected SubmitFailed / was null` + goBack asserted); 3 failure-path + 3 success/retry tests added across both VMs with a new RecordingRouter fake. QA entry PROF-3 added.
