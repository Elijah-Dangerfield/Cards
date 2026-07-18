# In-flight (this cycle)

## fix(auth): import the session from a cold-launch confirmation link (AUTH-26)

**Problem:** A brand-new email signup's confirmation email redirects to `cards://login-callback`, but `completeOAuthRedirect` only finished an in-memory pending-OAuth handle. After a mid-signup app kill that handle is gone, so the link was discarded ("no pending OAuth handle — ignoring stray redirect") and the confirmed account was stranded unauthenticated.
**Approach:** When no starter is parked, attempt a direct session import from the URL fragment (the same parse+import+hydrate the OAuth sign-in path uses), guarded to the session-less state so a stray redirect can never hijack a live account. A confirmation link carrying tokens establishes the session; a genuine stray/duplicate redirect (no tokens) fails the parse and leaves auth state untouched. Chose this over persisting the pending handle across cold start (more moving parts; the link already carries the session).
**Reviewer notes:** Residual on the *killed-then-relaunch* path only: the app boots session-less to `OnboardingRoute` and `OnboardingViewModel.ResolveEntry` can run before the async import lands, so the user may sit on the Welcome step (now silently authenticated) until the next auth resolve routes them into the identity step. The dominant path (app backgrounded not killed, returning from the browser onto the live verify screen) routes forward immediately via AppResumed. Logged to `docs/backlog.md` (AUTH-26 residual). Not device-verified (deep-link + process-kill timing needs a real device).
**Deferred:** Same-frame cold-launch routing into onboarding → `docs/backlog.md`.

## fix(auth): stop the verify-email screen trapping a new signup on "Welcome back" (AUTH-25)

**Problem:** `VerifyEmailScreen` fires `AppResumed` on mount. For a brand-new signup there's no session yet, so `refreshSession()` returns `SessionExpired`, which the handler routed to `NavigateBackToSignIn` with a cleared back stack — bouncing the user to "Welcome back" with a dead back button the instant the "Check your email" screen appeared.
**Approach:** Treat "no session" as "not confirmed yet" for a brand-new signup (`guestLink = false`): `AppResumed` → `SessionExpired` is now silent (stay and wait), and the explicit "Check verification" tap surfaces the `StillPending` nudge instead of bouncing. A guest linking an email (`guestLink = true`) did have a live session, so a genuine expiry there still routes back to sign in. Reused the existing `guestLink` route flag rather than adding an auth probe.
**Reviewer notes:** Pairs with AUTH-26 — once the confirmation link imports the session, the verify screen's next AppResumed/`Check verification` sees the confirmed session and routes into onboarding. Red-first VM tests updated (the two old tests pinned the buggy bounce). None untested.

## Cycle note

Stopped at 2 items (both P1, both meaty — repository + ViewModel + tests). The remaining confident AUTH items overlap these exact files/paths: the typed-`AuthOutcome` refactor rewrites `VerifyEmailViewModel`/`OnboardingViewModel`, and AUTH-27 (deletion clearing local prefs) touches `SupabaseAuthRepositoryImpl` — picking either this cycle risks stomping these commits. BILL-1 needs the AUTH-19 device-stable purchaser link that doesn't exist yet. Left them for a focused next run.
