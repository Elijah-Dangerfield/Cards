# CARDS-BS — fatal on first launch, Play billing proxy activity

**Signal:** Sentry, no user feedback. 1 event 2026-10-22, **fatal / unhandled**,
`cards@0.6.0+1308`, Android 11, Huawei HRY-LX1T, `store-android-release`. Substatus `regressed`.
Todo: **ENG-84 [P1]**.

## What is proven

`RuntimeException: Unable to start activity ComponentInfo{com.dangerfield.cards/
com.android.billingclient.api.ProxyBillingActivity}` wrapping a `NullPointerException` on an
`Intent`. The visible frames are all platform (`ActivityThread.performLaunchActivity` →
`LaunchActivityItem.execute`), so the failure is in the Play Billing library's activity, not our
code. Breadcrumbs: `Sentry initialized` → `system` → **`Minted fresh install_id on first launch`** →
`navigation`. A first run.

## Inferred, not proven

HRY-LX1T is an Honor 10 Lite. Huawei devices of that generation ship without Google Play Services,
which is the most plausible reason `ProxyBillingActivity` receives a null Intent. Not confirmed from
the event; there is no GMS-presence tag on it. Adding one would settle this class quickly.

## Why it outranks its event count

One event, one user, but it is fatal on first launch: that user never reached the app. Any
GMS-less install hits the same path, and the population is invisible to us precisely because they
crash before emitting anything.

## Fix shape

Find what touches Play Billing during startup ahead of the `BillingAvailability` check, and make an
unavailable store a degraded shop rather than a dead process.
