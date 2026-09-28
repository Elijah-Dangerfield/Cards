# CARDS-C2 — iOS Terms/Privacy links dead on the onboarding welcome screen

- **Sentry:** https://elijah-dangerfield.sentry.io/issues/CARDS-C2
- **Filed as todo:** ENG-70
- **Signal:** `kotlin.IllegalStateException: No handler available for https://downcard.app/terms`
- **Severity:** error / handled (`mechanism: generic`, `handled: yes`, `level: error`, escalating)
- **Volume:** 12 events, 2 users, first/last seen 2026-09-03 16:29Z → 16:42Z (single tap-loop cluster)
- **Where:** platform `cocoa` (iOS), `environment: store-ios-release`, `release: cards@0.1.0+1135`
  (App Store build), `route: OnboardingRoute`, step `welcome`, iPhone12,3 / iOS 26.6.1.
  `session_id: 9379c554-3e4a-4c3c-a751-b857debc2f25`, `install_id: 5574f7f6-1eb5-47e8-8586-4b7a1fb60c16`.

## What the signal tells us

A retail iOS user on the onboarding welcome screen tapped the Terms link (rendered inside the
"By continuing, you agree…" consent line) and the app threw a caught `IllegalStateException` back
into the log stream. Six repeated fires in ~13 minutes, all with the same URL — that is the user
retrying the tap, not a background worker. The exception is handled, so the app does not crash,
but nothing opens either. From the user's seat: the link does nothing.

Breadcrumbs show only `OnboardingFlow onboarding.step_viewed step=welcome` + repeated
`klog error No handler available for https://downcard.app/terms` followed by an OTLP flush each
time — one caught error per tap.

The string is not raised anywhere in first-party code (grep returns no hits). It is the exact
message shape Compose Multiplatform's default iOS `UriHandler` uses when it is asked to open a URL
and no `LocalUriHandler` override is installed at the composition root — see the JetBrains
Compose iOS `DefaultUriHandler` (`error("No handler available for $uri")`).

## Working theory (confidence: medium)

The onboarding consent line is built with `buildClickableText(...) { link(...) { onOpenUrl(...) } }`
(`features/onboarding/impl/.../OnboardingScreen.kt:426`). The `link { onClick }` path uses a
custom string annotation (`ANNOTATED_STRING_ON_CLICK_KEY`) and routes the tap through
`handler?.onClick?.invoke()` (`libraries/ui/.../ClickableText.kt:185`), which calls
`onOpenUrl = router::openWebLink` → `IosWebLinkLauncher.open` → `UIApplication.openURL`. That path
should work — the launcher is deliberate about not gating on `canOpenURL` and returns success
even if the completion handler later reports refusal (`IosWebLinkLauncher.kt:19-24`).

So the tap is reaching a different link path on iOS — the framework's own `LinkAnnotation.Url` /
accessibility open-URL surface — which then calls `LocalUriHandler.openUri(url)` on the default
Compose Multiplatform iOS handler, and that throws the "No handler available" string this event
records. The most plausible seams (unverified, worth checking in order):

1. `LocalUriHandler` is not overridden at the app's composition root, so iOS keeps its default
   `DefaultUriHandler` which throws. Providing an override that delegates to
   `WebLinkLauncher` would close both the direct-tap and the accessibility path.
2. Something else in the welcome layout — an auto-linked URL span (`autoLinkUrls = true`), a
   fallback `LinkAnnotation`, or a system link-preview handler — bypasses the `onClick` path in
   favour of the framework's URL open.

Confidence is medium because (a) the native stack is stripped (all `<redacted>` frames), (b) the
crashed thread is the iOS main dispatch queue post-tap and does not name a Kotlin call site, and
(c) Android does not show this class at all in the last 30d, which fits an iOS-only
`LocalUriHandler` gap.

## Backend correlation

None expected: this is a client-side URL-open failure. No cards-server prod warn/error/fatal lines
in the surrounding 24h window; nothing in Tempo for this `session_id`.

## Suggested fix / acceptance

Tapping any legal link (`TERMS_OF_SERVICE`, `PRIVACY_POLICY`) on the iOS onboarding welcome screen
opens the URL in the system browser and emits no `IllegalStateException: No handler available`
in Sentry or Loki for two consecutive store releases. Direction of least surprise is to install a
`LocalUriHandler` at the composition root that delegates to the injected `WebLinkLauncher`, so any
Compose surface (main tap, accessibility action, future `LinkAnnotation.Url` usage) reaches the
same code path. `libraries/navigation/impl/src/iosMain/.../IosWebLinkLauncher.kt` already knows how
to hand a `NSURL` to `UIApplication.openURL` correctly — this is about routing all iOS URL opens
through it, not rewriting it.

Verify on a real store build (release variant, not debug): iOS release ships this behaviour, and
the Sentry log-forwarding path in the breadcrumbs shows the exact user session that would exercise
it. A `commonTest` covering the `LocalUriHandler` override wired at composition root would guard
the regression class.
