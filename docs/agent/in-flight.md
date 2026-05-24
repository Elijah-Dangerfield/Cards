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

## fix(room): center raise-amount text in RaiseSheet stepper

**Problem:** The raise-amount text in `RaiseSheet`'s stepper sat left-aligned in its pill, not centered like the rest of the stepper geometry implied.
**Approach:** `BasicTextField` in `:libraries:ui` was missing a `textAlign` knob — the todo noted this as a small DS gap. Added an optional `textAlign: TextAlign? = null` parameter that overrides the resolved text style's alignment when set. Callsite in `RaiseSheet` now passes `TextAlign.Center` plus `fillMaxWidth()` so the inner text field fills the pill and its content is centered.
**Reviewer notes:** Added the parameter as nullable so existing callsites get the typography's default alignment unchanged. The `.copy(textAlign = ...)` happens once per recomposition at the text-style layer; no measurable perf cost.
**Deferred:** None.

## fix(room): soften fold-confirm dialog copy

**Problem:** The swipe-fold confirm dialog still read as educational ("Swipe up to fold" / "Toss your hand by swiping up on your cards. Fold this one?") even though the "don't show again" checkbox already defaults to `true` (line 35 of `SwipeFoldConfirmDialog.kt`). On the rare repeat the user sees it, the copy reteaches gesture mechanics they already know.
**Approach:** Heading: "Swipe up to fold" → "Fold this hand?". Body: replaced the gesture-teach line with a one-liner consequence: "You'll forfeit the round and any chips already in the pot." Default-checkbox-on was already in place from a prior change, so this slice is purely the copy half of the bullet.
**Reviewer notes:** No behavior change — just strings. The `swipeFoldGestureAck` system that suppresses the dialog after first acknowledged use is untouched.
**Deferred:** None.
