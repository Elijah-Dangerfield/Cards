# Feedback case cdcfbae0290e471a8bde94cc5b58dc1f

- **Sentry issue:** https://elijah-dangerfield.sentry.io/issues/CARDS-8Q (carrier) · https://elijah-dangerfield.sentry.io/issues/CARDS-8R (feedback twin)
- **Reported:** 2026-07-08T14:23:25Z · FeedbackRoute · dev-ios-debug · cardse@0.1.0+1 (develop @ 0a74cc1f9950)
- **Disposition:** todo: "AUTH-17 — Fix the onboarding header/step chip on the Google sign-in path"

## Bug description
> onboarding without Google looks normal but with it the header is messed up, like the step counter is floating on top of the content instead of a column. I also think the "this is you" text is t there? [sc: isn't there]
>
> — owner (elijahdangerfield111@gmail.com), screenshot attached on the carrier event

## IDs
- user: 51903e85-9b70-4f3f-a50a-c9d7969ff27c (Hidden-Sage-4517 at submit time / elijahdangerfield111@gmail.com)
- session: 4a9af150-df06-4ffb-a794-9300bf642ea7
- install: 61e95306-2d91-4cad-b82b-a7f795d2d9eb
- No MP context.

## Reporter client log
Owner deleted the account, was routed to onboarding, and signed in with Google (twice — repeated the flow to reproduce), then filed feedback from the broken step:

```
14:21:07 DelegatingRouter: Enqueuing navigation: navigate to OnboardingRoute
14:21:07 OnboardingFlow: VM created (instance=cef0bf8)
14:21:07 OnboardingFlow: Onboarded-guard: hasUserOnboarded=false
14:21:09 AuthRepository: signInWithOAuth: launching browser for Google
14:21:18 AuthRepository: completeOAuthRedirect: SignIn Success
14:21:18 AuthRepository: signInWithOAuth(Google): Success
   … (account deleted again, second round)
14:21:56 DelegatingRouter: Enqueuing navigation: navigate to OnboardingRoute
14:21:58 AuthRepository: signInWithOAuth: launching browser for Google
14:22:06 AuthRepository: completeOAuthRedirect: SignIn Success
14:22:06 AuthRepository: signInWithOAuth(Google): Success
14:23:?? device.event: UIApplicationUserDidTakeScreenshotNotification
14:23:?? DelegatingRouter: Enqueuing navigation: navigate to FeedbackRoute
```

## Client state at submit
Absent — non-MP report.

## Server activity
- Tempo/Loki: no warnings or errors for the session in ±20m; only routine sync traffic, all HTTP 200 (wallet/achievements/equipment/progression syncs, `/v1/me`, `/v1/app-config`).
- Pure client-side layout bug — backend not involved.

## Working theory
After returning from the Google OAuth browser flow, the onboarding PickIdentity step renders with a broken header: the "step N of N" `StepProgressChip` (an overlay the host `OnboardingScreen` draws over the step content, `OnboardingScreen.kt` ~L218) ends up floating on top of the content instead of reading as part of a column, and the step's "This is you" title (`onboarding_identity_title`) is missing. The guest/email path renders correctly, so the divergence is specific to the post-OAuth composition path (likely the step content the OAuth path shows omits/offsets the header the chip is positioned against, or insets differ after the ASWebAuthenticationSession returns). Repro looks deterministic: delete account → onboard → continue with Google. A screenshot of the broken state is attached to the carrier event (CARDS-8Q, attachment `screenshot-1.jpg`).
