# Feedback case CARDS-BS

- **Sentry issue:** https://elijah-dangerfield.sentry.io/issues/CARDS-BS
- **Reported:** 2026-07-25T10:35Z · transaction `ProxyBillingActivity` · store-android-release · cards@0.1.0+1009
- **Disposition:** no-action — uncatchable upstream Play Billing crash (null `PendingIntent` in Google's `ProxyBillingActivity.onCreate`); single event on a Play review/robo-test emulator, true count 1. NOT because of a version bump — see the correction below.

## Signal
Sentry crash CARDS-BS: `RuntimeException: Unable to start activity … ProxyBillingActivity: NullPointerException: … PendingIntent.getIntentSender() on a null object reference`, in `com.android.billingclient.api.ProxyBillingActivity.onCreate` (billing `7.1.1`). 1 event / 1 user, fatal, first=last seen 2026-07-25.

## Environment
- `store-android-release`, `installerStore=com.android.vending`, `isSideLoaded=false`, release **cards@0.1.0+1009** — which is the **`v0.1.0` tag, the only release tag and the CURRENT production build** (commit `c9acade5`), NOT a superseded one.
- Device reports "OnePlus 8 Pro" but the metrics don't match a real one: archs `x86_64` first, screen 288×448 @ density 0.66 / 106 dpi, 2 cores, `device.class=low`, `locale en_TT` / `tz America/Los_Angeles`. That fingerprint is an emulator / device-farm / Play automated-review install, not a retail OnePlus 8 Pro.

## Working theory
Well-known upstream Google Play Billing crash: `launchBillingFlow` returns a null `PendingIntent` (Play Store missing/broken on the device, tampered/emulated device, or fraud), and Google's `ProxyBillingActivity.onCreate` NPEs on `getIntentSender()`. The crash is entirely Android framework + `billingclient` — **zero first-party frames**, and it's a separate activity's `onCreate`, so there is **no app-side try/catch that could stop it**. It's reported across billing `3.x` / `5.x` / `7.x` and there's no authoritative evidence `8.x`/`9.x` fixes it — the origin is device/Play-side, outside app control. Single occurrence on a Play review/robo-test emulator. **No first-party code action possible.**

**CORRECTION (2026-07-27, adversarial re-check):** the first pass wrongly claimed build 1009 was superseded by a 9.1.0 build "1026". In fact **1009 (billing 7.1.1) IS the current production build** (`v0.1.0`, the only release tag). The billing `7.1.1 → 9.1.0` bump (commit `dc14c4c5`) is on **`develop` only, unreleased** — `origin/main` still reads `billing-ktx 7.1.1` — and is not a confirmed fix for this NPE anyway. "1026" is merely the commit-count of unreleased `main` HEAD (a number-collision with dc14c4c5's own commit-count on develop's line, which misled the first pass). So the version-bump reasoning was moot: the disposition holds only because the crash is uncatchable upstream code on a review-bot event.

**Re-open** if it recurs on a **real, non-emulator retail device** (any build).
