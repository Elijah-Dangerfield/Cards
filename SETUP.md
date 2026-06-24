# Setup checklist — Cards

Action items after running `./scripts/init_project.main.kts`. Work through these in order. Tick off as you go.

- [ ] [Local dev](#local-dev) — hooks + first build
- [ ] [GitHub secrets](#github-secrets-only-if-you-enabled-ci) (only if you enabled CI)
- [ ] [Repo settings](#repo-settings) — Pages, Actions, branch protection
- [ ] [Store listings](#store-listings) — Play Console + App Store Connect
- [ ] [First release](#first-release) — the manual-promotion gotcha
- [ ] [App icons](#app-icons)

---

## Local dev

```sh
./scripts/install_hooks.sh   # commit-msg hook for Conventional Commits
./gradlew build              # first sync + build
```

The Gradle build fails with an install-hooks message if you skip `install_hooks.sh`. That's intentional — release-please derives version bumps from commit history, so every commit must be in Conventional-Commits form (`feat:`, `fix:`, etc.).

To bypass in scripted contexts (not CI — `CI` env var is honored): `-Dcards.skipGitHooksCheck=true`.

---

## GitHub secrets (only if you enabled CI)

Set under **Settings → Secrets and variables → Actions**. All are required for `release.yml` to ship.

### Android signing

| Secret | How to get it |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -i upload-keystore.jks \| pbcopy` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias inside the keystore |
| `ANDROID_KEY_PASSWORD` | Key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Play Console → Setup → API access → create service account with *Release apps to testing tracks* + *Release apps to production*. Download the JSON. |

### Apple signing + App Store Connect

| Secret | How to get it |
| --- | --- |
| `APPLE_TEAM_ID` | Apple Developer → Membership → Team ID |
| `ASC_KEY_ID` | App Store Connect → Users and Access → Keys → Key ID |
| `ASC_ISSUER_ID` | Same page — Issuer ID (top of the Keys tab) |
| `ASC_KEY_P8_BASE64` | `base64 -i AuthKey_XXX.p8 \| pbcopy` |
| `APPLE_DIST_CERT_P12_BASE64` | Export your Apple Distribution cert from Keychain as .p12, then `base64 -i dist.p12 \| pbcopy` |
| `APPLE_DIST_CERT_PASSWORD` | Password you set when exporting the .p12 |
| `FASTLANE_APPLE_ID` *(optional)* | Apple ID email, for `fastlane deliver` |

### Sentry

| Secret | Notes |
| --- | --- |
| `SENTRY_AUTH_TOKEN` | Sentry → User Auth Tokens → scope: `project:releases`, `org:read`. |

And under **Settings → Secrets and variables → Actions → Variables** (not secrets):

| Var | Value |
| --- | --- |
| `SENTRY_ORG` | Your Sentry org slug |
| `SENTRY_PROJECT` | Your Sentry project slug |

---

## Repo settings

- **Actions** → enable workflows.
- **Pages** → Source: `Deploy from a branch`, Branch: `main` / folder: `/pages`. (The `pages.yml` workflow can also publish on push.)
- **Branch protection** on `main`:
  - Require PR.
  - Require status checks: `CI / Build + test`, `commitlint / Validate PR title`.
  - Require linear history (so release-please squash-merges cleanly).

---

## Store listings

Before `release.yml` can ship:

1. **Play Console** → Create app → fill out store listing, data-safety form, content rating, pricing/distribution. Create at least one internal track tester.
2. **App Store Connect** → My Apps → New App → pick the bundle ID that matches `apps/ios/fastlane/Appfile`. Fill out app info, pricing, privacy details.
3. **TestFlight** external group: create a group named `External Testers` (or change `TESTFLIGHT_EXTERNAL_GROUP` in `release.yml`).
4. Privacy policy + terms of service URLs — the `pages/` folder generates these; once Pages is enabled they're at `https://<you>.github.io/<repo>/privacy.html` etc. Paste the URLs into both store listings.

---

## First release

> **!!! READ THIS BEFORE YOUR FIRST RELEASE !!!**
>
> Google Play and Apple both reject automated production uploads until a manually-promoted build exists. `release.yml` detects this and routes the **first** release to:
>
> - **Play internal track** (not production).
> - **TestFlight internal** (not external, not App Store submission).
>
> You must then, **once**:
>
> 1. **Play Console** → Internal testing → Promote release → Production. Fill out the production rollout form manually.
> 2. **App Store Connect** → TestFlight build → Submit for review manually.
>
> From release #2 onward, the pipeline uploads straight to Play production (10% staged rollout) and submits to the App Store with phased release.

The release-please PR shows a `!!! FIRST RELEASE !!!` banner the first time around so this is hard to miss.

---

## App icons

Drop your icons into:

- **iOS** → `apps/ios/iosApp/Assets.xcassets/AppIcon.appiconset/` (replace the placeholder set).
- **Android** → `apps/compose/src/androidMain/res/mipmap-*/` (replace `ic_launcher*.webp`).
- **Shared** (used by Compose splash, about screens, etc.) → `libraries/resources/src/commonMain/composeResources/drawable/`.
- **GitHub Pages** → `pages/app-icon.png`, `pages/favicon.png`, `pages/apple-touch-icon.png`.

---

## Networking

`:libraries:networking` ships a single configured `HttpClient` (plus an
authenticated variant) for every repo and data source to share.

**Set your base URL.** Bind your own `NetworkConfig` (see
`DefaultNetworkConfig`) somewhere in your app — typically a class that reads
the URL from BuildConfig per build variant:

```kotlin
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DefaultNetworkConfig::class])
@Inject
class AppNetworkConfig : NetworkConfig {
    override val baseUrl = AppBuildConfig.API_BASE_URL
}
```

**Authenticated calls.** Inject `NetworkClient` and use
`networkClient.authenticatedClient` for endpoints that need a Bearer token.
The token comes from your `AuthTokenProvider` binding (default is no-op).
401s trigger `refreshAccessToken()`.

**Wrap calls with `Catching { }`** at the call site. Ktor throws on non-2xx
and network errors; the rest of the codebase already uses this pattern.

**JSON config.** `NetworkJson` is strict in debug (unknown keys/missing
fields throw) and lenient in release (so a backend tweak can't crash users).

## Deep links

Compose NavHost handles the routing once URLs reach it. Per-route deep
links go on `screen<Route>(deepLinks = ...)`. Two things to set up per
platform:

- **Android**: uncomment the intent filter in `apps/compose/src/androidMain/AndroidManifest.xml`
  and customize the scheme/host. Compose NavHost reads `Activity.intent.data`
  automatically once a filter matches.
- **iOS**: uncomment `CFBundleURLTypes` in `apps/ios/iosApp/Info.plist` for
  custom-scheme links, or add an `Associated Domains` entitlement + AASA
  file for Universal Links. `iOSApp.swift` already forwards every
  `.onOpenURL` event to the Kotlin `DeepLinkBridge`.

## In-app review

Inject `ReviewPrompter` and call `requestReview()` from a delighted-user
moment (e.g. after the user completes a meaningful task, or after N
sessions). The OS owns the throttling decision — both stores rate-limit how
often the dialog actually shows. Don't show your own UI before/after.

---

See `docs/practices/release.md` for the full pipeline runbook.
