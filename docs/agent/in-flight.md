# In-flight log

Ephemeral handoff notes from tonight's workers to the reviewer. Deleted when the PR opens.

## fix(home): failed Forfeit surfaces an error snackbar (ROOM-17)

**Problem:** Home's Forfeit action awaited `leaveRoom` and discarded the outcome — after confirming the destructive dialog, a failed leave kept the banner with zero feedback.
**Approach:** New one-shot `HomeEvent.ForfeitFailed` emitted for `NetworkError` AND `Unknown` outcomes (lobby only surfaces NetworkError, but on Home both leave the seat held with no other signal, so both get the snackbar; Unknown also logs its cause). Entry point shows the app-wide error snackbar, same as the lobby's BotActionFailed. Success still clears the banner via the observed rooms flow — no state change needed.
**Reviewer notes:** Test-first — three new HomeViewModelTest cases (network red → green, unknown, success-silent). QA sub-bullet added under MP-7.
