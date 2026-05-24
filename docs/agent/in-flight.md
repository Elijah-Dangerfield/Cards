## fix(profile): route ClaimAccount email to SignUp, not SignIn

**Problem:** Claim Account → "Continue with email" sent anonymous guests to the sign-in form. Anonymous claim is by definition a "create a real account, preserve progress" flow — the sign-up path already routes through `linkEmailIdentity` to keep chips/XP/history.
**Approach:** `ProfileFeatureEntryPoint.ClaimAccountRoute` now navigates to `SignUpRoute()`. SignUp's "Already have an account? Sign in" stops being a `goBack()` (which would land back on ClaimAccount in the new flow) and instead pops SignUp + navigates to `SignInRoute()` with `launchSingleTop` — works for both the onboarding flow (Onboarding→SignIn→SignUp pops back to SignIn) and the new claim flow (Profile→ClaimAccount→SignUp lands on Profile→ClaimAccount→SignIn).
**Reviewer notes:** Two enqueued `router` calls in the SignUp `onSignIn` callback (popBackTo + navigate). Order matters and relies on `DelegatingRouter`'s FIFO queue, which is the existing contract — the same pattern would be needed anywhere we want "replace this screen with that one" semantics. Worth a peek at whether to add a `popUpToRoute` field on `NavigationOptions` later, but not for this slice.
**Deferred:** None.

## feat(auth): confirm-password field on sign-up

**Problem:** Sign-up had a single password field, so a typo could lock a user out of their freshly-created account.
**Approach:** Added `confirmPassword: String` + `passwordMismatch: Boolean` derived field to `SignUpState`, plus a `ConfirmPasswordChanged` action. `canSubmit` now requires the confirmation to match, and `Submit` short-circuits with a "Passwords don't match." error if state ever desyncs. `SignUpScreen` renders a second `PasswordField` underneath the first; the helper switches to a danger-colored "Passwords don't match" copy when the confirm value diverges. Existing `PasswordField` helper picked up `label`, `isError`, and an `onNext` IME handler so we don't need to fork it. Added two coverage tests (mismatch blocks submit, matched fields pass canSubmit) and a `_PasswordMismatch` preview.
**Reviewer notes:** None — straightforward state-machine extension. The existing 10-test suite still passes.
**Deferred:** None.
