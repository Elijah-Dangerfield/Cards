# Feedback case CARDS-BS

- **Sentry issue:** https://elijah-dangerfield.sentry.io/issues/CARDS-BS
- **Reported:** 2026-07-25T10:35Z · transaction `ProxyBillingActivity` · store-android-release · cards@0.1.0+1009
- **Disposition:** no-action — upstream Play Billing crash on a superseded build; current code already ships `billing-ktx 9.1.0`; single event on a likely automated-review emulator.

## Signal
Sentry crash CARDS-BS: `RuntimeException: Unable to start activity … ProxyBillingActivity: NullPointerException: … PendingIntent.getIntentSender() on a null object reference`, in `com.android.billingclient.api.ProxyBillingActivity.onCreate` (billing `7.1.1`). 1 event / 1 user, fatal, first=last seen 2026-07-25.

## Environment
- `store-android-release`, `installerStore=com.android.vending`, `isSideLoaded=false`, release **cards@0.1.0+1009** (current shipping build is 1026).
- Device reports "OnePlus 8 Pro" but the metrics don't match a real one: archs `x86_64` first, screen 288×448 @ density 0.66 / 106 dpi, 2 cores, `device.class=low`, `locale en_TT` / `tz America/Los_Angeles`. That fingerprint is an emulator / device-farm / Play automated-review install, not a retail OnePlus 8 Pro.

## Working theory
Well-known upstream Google Play Billing crash: `launchBillingFlow` returns a null `PendingIntent` (Play Store missing/broken on the device, or an emulator without a real billing backend), and Google's `ProxyBillingActivity.onCreate` NPEs on `getIntentSender()`. The crash is entirely Android framework + `billingclient` — **zero first-party frames**, and it's a separate activity's `onCreate`, so there's no app-side try/catch that could stop it. The standard mitigation is to keep the Play Billing Library current: the repo already ships `com.android.billingclient:billing-ktx 9.1.0` (`gradle/libs.versions.toml`), two majors past the `7.1.1` in the crashing build 1009. So on current builds (1026+, carrying 9.1.0) this class is already mitigated. Single occurrence on a likely review emulator. No code action.

**Re-open** if it recurs on a build that ships billing 9.1.0 (≥1026) on a real retail device — that would make it a genuine upstream-resilience gap worth a defensive look (e.g. gating/observing the billing-flow launch), rather than an old-build/emulator artifact.
