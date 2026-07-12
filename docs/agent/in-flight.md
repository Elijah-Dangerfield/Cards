# In-flight log

Ephemeral handoff notes from tonight's workers to the reviewer. Deleted when the PR opens.

## fix(home): failed Forfeit surfaces an error snackbar (ROOM-17)

**Problem:** Home's Forfeit action awaited `leaveRoom` and discarded the outcome — after confirming the destructive dialog, a failed leave kept the banner with zero feedback.
**Approach:** New one-shot `HomeEvent.ForfeitFailed` emitted for `NetworkError` AND `Unknown` outcomes (lobby only surfaces NetworkError, but on Home both leave the seat held with no other signal, so both get the snackbar; Unknown also logs its cause). Entry point shows the app-wide error snackbar, same as the lobby's BotActionFailed. Success still clears the banner via the observed rooms flow — no state change needed.
**Reviewer notes:** Test-first — three new HomeViewModelTest cases (network red → green, unknown, success-silent). QA sub-bullet added under MP-7.

## fix(shop): failed first catalog load shows retry, not "empty shop" (SHOP-10)

**Problem:** the cold-boot catalog fetch is repository-self-triggered, but `hasRefreshError` was only raised from the VM's pull-to-refresh path — a fresh install whose first fetch failed landed on the misleading "Shop is empty for now" screen with no retry.
**Approach:** pushed the failure signal into the repository: `ProductsRepository.observeRefreshFailed()` (raised on a failed attempt, cleared when the next attempt starts / succeeds), mirrored into `ShopState.hasRefreshError` the same way `observeIsRefreshing` already is. The VM's own `RefreshFailed` round-trip is deleted (repo flag covers VM-invoked refreshes too; failure logging moved into the repo). Alternative rejected: a VM-side "refresh finished but catalog still empty → assume error" heuristic — it can't tell a genuinely empty catalog from a failed fetch. Screen gets a first-class `LoadFailedState` (title + subtitle + Retry) when loaded + empty + error; the bottom ErrorBanner now only shows over a non-empty catalog (stale-while-revalidate), so there's never a doubled error surface.
**Reviewer notes:** Test-first — two ShopViewModelTest cases simulating a repo-driven refresh failing/succeeding were red before the VM wiring, plus a repo-level flag-lifecycle test. New preview `ShopScreenPreview_FirstLoadFailed`; QA covered as a sub-bullet on AUTH-5's Shop row.
