# Platform Game Services (Game Center + Google Play Games v2)

## Context

Mirror the app's achievements and progression to the platform game services — Apple Game Center on iOS, Google Play Games Services v2 on Android — behind one common Kotlin interface. The app/server stays the source of truth; mirroring is one-way, fire-and-forget, and idempotent. This gives players native achievement/leaderboard visibility (and store-listing perks like Play Games discoverability) without touching core game logic.

The codebase already has the exact pattern twice ([StoreKitCoordinator.kt](libraries/billing/src/commonMain/kotlin/com/cards/libraries/billing/StoreKitCoordinator.kt) + `IOSStoreKitCoordinator.swift`, and `AppleSignInCoordinator`): callback-based common interface → Swift class conforms via SKIE, injected via `IosAppComponent` → real Android impl bound with kotlin-inject-anvil.

## Decisions made (documented per convention)

- **Leaderboards: lifetime Total XP + lifetime Hands Won.** Not chips — freemium economy means chip leaderboards would be pay-to-rank. Both metrics are monotonic and engagement-shaped, matching the "XP decoupled from win/lose" decision.
- **Unlock-only mirroring in V1** — no partial-progress steps/percent (avoids the GC-percent vs Play-steps impedance mismatch). Interface can grow `setAchievementProgress` later.
- **Mystery achievements → "hidden" in both stores** (store-side config; no runtime handling needed).
- **iOS auth sheet: present once, deferred.** `authenticateHandler` is set at launch but the returned view controller is held and only presented when the reporter kicks auth (flag on). GC-signed-out users see one dismissible sheet; already-signed-in users (majority) auth silently. Never blocks launch.
- **Gate everything behind `gameServices.enabled` config flag, default false.** All code ships dark; flip server-side after store setup is live.
- **Skip `GKAccessPoint` in V1** (floating overlay fights the poker table layout). Native UI reached via buttons on the existing achievements screen instead.

## Architecture

Three layers, cloning billing's design:

1. **`GameServicesCoordinator`** (common interface, callback-based, flat types only — String/Long/Boolean/closures so a plain Swift class conforms via SKIE):
   - `startAuthentication()`, `setAuthenticationListener(onChanged: (Boolean) -> Unit)`
   - `unlockAchievement(achievementKey, onComplete: (success, errorMessage) -> Unit)`
   - `submitScore(leaderboardKey, value: Long, onComplete)`
   - `showAchievementsUi()`, `showLeaderboardUi(leaderboardKey: String?)`
2. **`GameServicesClient`** (suspend/Flow surface for app code): `authState: StateFlow<GameServicesAuthState>`, suspend `unlockAchievement`/`submitScore` returning `Result`, UI methods. `DefaultGameServicesClient` bridges callbacks via `suspendCancellableCoroutine` (mirror billing's `awaitPurchase` helpers). Use `Catching {}` per repo convention.
3. **`GameServicesReporter`** (impl commonMain, `AutoInit` multibound singleton) — the only piece with product knowledge. No ViewModel or repository changes needed:
   - Flag gate first; if off, never even kicks auth.
   - **Achievements:** `combine(client.authState, achievementRepository.observeProgress())`, filter authenticated, diff `AchievementProgress.earned.keys` against an in-memory session-reported set, unlock each new key. Over-reporting is harmless (both stores no-op re-unlocks) — that's the idempotency guarantee.
   - **Scores:** collect `progressionRepository.observeProgression()`, debounce ~5s, `distinctUntilChanged` on `(totalXp, handsWon)`, submit both boards. Monotonic + stores keep best score → stale submits can never regress.
   - **Re-sync for free:** `UserScopedSyncCoordinator` already re-syncs the cards repos on UserChanged/AccountClaimed/OnForeground; flows re-emit and the reporter re-reports. Deliberately NOT implementing `UserScopedSyncer` — it lives in `libraries/cards/impl`, which would be a new ugly module edge for zero benefit.

### ID mapping (the cross-store wrinkle)

- Neutral key = `AchievementId.name.lowercase()` (e.g. `first_hand`); leaderboard keys `total_xp`, `hands_won`.
- **iOS**: Game Center IDs are chooseable → derive literally: `ach_first_hand`, `lb_total_xp`. Configure those exact strings in App Store Connect. No mapping table.
- **Android**: Play Games IDs are console-generated opaque strings (`CgkI…`) → resolve at runtime from `games-ids.xml` (the Play Console "Get resources" export) via `resources.getIdentifier("achievement_$key", "string", packageName)`. Missing resource → logged no-op. Never hardcode a `CgkI…` id in Kotlin.

## Files

**New module `libraries/gameservices`** (+ `:impl`) via `scripts/create_module.main.kts` (library type). Note package convention: dirs `com/cards/...` but package `com.dangerfield.cards.libraries.gameservices` (matches billing).

- `libraries/gameservices/src/commonMain/.../GameServicesCoordinator.kt` — interface + `awaitUnlock`/`awaitSubmitScore` bridges + `GameServicesException`
- `.../GameServicesClient.kt` — client interface, `GameLeaderboard(key)` enum (TotalXp, HandsWon), `GameServicesAuthState` enum
- `.../GameServicesConfigValues.kt` — `GameServicesEnabled : FlagConfigValue`, path `gameServices.enabled`, default false, `@ContributesBinding(..., boundType = QaConfigValue::class, multibinding = true)` + `forTest` companion (clone [SocialConfigValues.kt](libraries/social/src/commonMain/kotlin/com/cards/libraries/social/SocialConfigValues.kt))
- `libraries/gameservices/impl/src/commonMain/.../DefaultGameServicesClient.kt`, `GameServicesReporter.kt`, `FakeGameServicesCoordinator.kt` (+ tests in commonTest)
- `libraries/gameservices/impl/src/androidMain/.../AndroidGameServicesCoordinator.kt`

**Modified:**
- `gradle/libs.versions.toml` — add `com.google.android.gms:play-services-games-v2:20.1.2`
- [CardsApplication.kt](apps/compose/src/androidMain/kotlin/com/cards/CardsApplication.kt) — `PlayGamesSdk.initialize(this)` in `onCreate` (safe pre-flag: silent if unconfigured)
- `apps/compose/src/androidMain/AndroidManifest.xml` — `<meta-data android:name="com.google.android.gms.games.APP_ID" android:value="@string/game_services_project_id"/>` — **must be a @string reference**; a numeric literal crashes GmsCore at runtime
- `apps/compose/src/androidMain/res/values/games-ids.xml` — placeholder (`app_id = "0"`) until console export exists
- [IosAppComponent.kt](apps/compose/src/iosMain/kotlin/com/cards/IosAppComponent.kt) — constructor param + `@Provides` + `create(...)` param (exact StoreKitCoordinator pattern)
- `apps/ios/iosApp/Platform/IOSGameServicesCoordinator.swift` — new
- `apps/ios/iosApp/iOSApp.swift` — instantiate + pass into `create(...)`
- `apps/ios/iosApp/iosApp.entitlements` — add `com.apple.developer.game-center = true` (+ enable capability in Xcode)
- [ConfigManifestDriftTest.kt](apps/integration/src/androidUnitTest/kotlin/com/cards/integration/ConfigManifestDriftTest.kt) — register the new flag path (repo rule)
- Optional Phase 5: `features/progression/impl` AchievementsScreen/ViewModel — "View on Game Center / Play Games" buttons, shown when `flag && authState == Authenticated`, calling `client.showAchievementsUi()` / `showLeaderboardUi()`

### Android coordinator details

`@Inject @SingleIn(AppScope::class) @ContributesBinding(AppScope::class)`, takes `Context` + `ActivityProvider`. All v2 clients need a foreground Activity (`PlayGames.getAchievementsClient(activity)` etc.); null activity → complete `success=false`, reporter retries on next emission. Auth: `GamesSignInClient.isAuthenticated()` on listener registration + after `signIn()`; v2 has no change callback, so cache last value and notify on change. Use `unlockImmediate`/`submitScoreImmediate` (Task-returning, honest callbacks). Native UI via `achievementsIntent`/`getLeaderboardIntent` + `startActivityForResult`.

### iOS coordinator details

Set `GKLocalPlayer.local.authenticateHandler` in `init` (Apple requires early; `iOSApp.swift` constructs coordinators before `create(...)`). Hold any returned VC in `pendingAuthViewController`; present it only in `startAuthentication()` from the root VC. Unlock: `GKAchievement(identifier: "ach_" + key)`, `percentComplete = 100`, **`showsCompletionBanner = false`** (app already toasts unlocks). Score: `GKLeaderboard.submitScore(Int(value), context: 0, player: .local, leaderboardIDs: ["lb_" + key])`. UI: `GKGameCenterViewController(state: .achievements)` / leaderboard variant, coordinator as `GKGameCenterControllerDelegate` for dismissal. Guard all calls on `isAuthenticated`.

DI symmetry note: unlike billing, **no no-op coordinator needed on either platform** — both have real implementations (Android via anvil binding, iOS via `IosAppComponent` provide).

## Phasing (commit-sized, all dark behind the flag)

1. **API module** (2 commits): interfaces + flag + drift-test update + key-charset test (every `AchievementId` lowercased matches `[a-z0-9_]+`)
2. **impl commonMain** (2 commits): `DefaultGameServicesClient` + fake + tests; `GameServicesReporter` + tests
3. **Android** (2 commits): catalog dep + coordinator + `PlayGamesSdk.initialize`; manifest meta-data + placeholder games-ids.xml
4. **iOS** (2 commits): Swift coordinator + entitlement/capability; `IosAppComponent` + `iOSApp.swift` wiring
5. **Optional — native UI entry points** (1–2 commits): achievements-screen buttons
6. **Go live** (mostly not code): run both store runbooks below, commit real `games-ids.xml`, device-test with flag flipped via QA menu, then flip `gameServices.enabled` server-side

## Testing

**Unit (commonTest, CI):** `GameServicesReporterTest` with `FakeGameServicesCoordinator` + in-memory repos: (a) flag off → nothing, not even auth kick; (b) unauthenticated → nothing, then full backfill of earned set when auth flips true; (c) new unlock while authed → one call; (d) no session re-reports; (e) score debounce + distinctUntilChanged; (f) failed unlock retried on next emission. Plus client callback→suspend bridging tests and an `apps/integration` DI wiring test (reporter constructs in merged graph).

**Device (after store setup):**
- Android: needs debug-keystore SHA-1 credential + tester account. Verify auto sign-in, unlock, leaderboard, relaunch idempotency.
- iOS: sandbox Apple ID in Settings > Game Center on a real device (simulator auth is unreliable). Dev builds hit sandbox automatically.

## Runbook A — App Store Connect (Game Center)

1. Xcode: target > Signing & Capabilities > + Capability > **Game Center** (entitlement is committed in Phase 4; this updates the App ID).
2. ASC: App > **Game Center** section > enable for the app version.
3. Leaderboards (+): two **Classic** boards — ID `lb_total_xp` (Integer, High-to-Low) and `lb_hands_won`. ≥1 localization each (name + score suffix).
4. Achievements (+), one per `AchievementId`: ID exactly `ach_<enum_lowercased>`. **Do the points math first**: hard caps are 100 pts/achievement, 1000 pts total; with ~53 achievements allocate by rarity (e.g. Common 5 / Rare 25 / Epic-Legendary 50), keep total ≤ ~900 for future headroom — editing points later means touching every entry. Hidden = our `isMystery`. Each localization **requires a square image** (512×512 min, 1024 recommended) — 53 images is the real cost; batch-generate from rarity color + registry icon glyph. Create dormant `*_MP` ids now (hidden) to do the points budget once.
5. Sandbox testing: create Sandbox Apple IDs (Users & Access > Sandbox Testers); sign in on device under Settings > Game Center. Achievements/leaderboards are live in sandbox immediately on save — no review needed to dev-test. They ship publicly with the next app version.

## Runbook B — Google Play Console (Play Games Services)

1. Play Console > app > **Grow users > Play Games Services > Setup and management > Configuration** > create PGS project; link a Google Cloud project (create fresh — repo has no Firebase).
2. Cloud console: configure + publish the OAuth consent screen (External; app name + support email). PGS API is enabled by the linking flow.
3. **Credentials** (PGS Configuration): add Android credentials for **every signing key** — Play App Signing key SHA-1, upload key SHA-1, and each dev's debug keystore SHA-1 (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`). Missing debug SHA-1 = silent sign-in failure, the #1 "it doesn't work" cause.
4. Achievements: create one per id — name (choose so the console-slugified resource name comes out as `achievement_first_hand` etc.), description, **512×512 icon required**, Hidden for mystery, incremental = No. Minimum 5 required to publish; we have ~53.
5. Leaderboards: "Total XP" + "Hands Won", Numeric/Integer, larger-is-better, 512×512 icons.
6. **Get resources** > Android XML → this is `games-ids.xml` (app_id + all generated ids). Verify resource names match our `achievement_<key>` / `leaderboard_<key>` convention; commit to `apps/compose/src/androidMain/res/values/`, wire manifest meta-data to the app_id string.
7. Testers tab: add every device-tester's Google account. **Until the PGS project is published, only testers can sign in.**
8. **Publish the games project** (separate "Review and publish" flow from app releases) before flipping the flag for production users — unpublished = all non-testers silently fail auth (tolerated by design, but mirrors nothing).

## Risks / gotchas

- games-ids.xml drift: new `AchievementId` without console entry → logged no-op on Android. Add a debug log of unresolved keys so QA notices.
- Manifest APP_ID must be `@string`, never a literal number.
- GC sandbox is flaky (stale auth caches, delayed unlock visibility) — try a second sandbox account before chasing "bugs".
- Android Play Games unlock overlay banner can't be suppressed per-unlock → brief double celebration with our toast (iOS suppressed via `showsCompletionBanner = false`). Accept for V1.
- PGS config changes after publish go through re-review; get achievements/points right before publishing.

## Verification

1. `./gradlew :libraries:gameservices:impl:allTests` (reporter + client tests) and integration wiring test pass.
2. Full build both platforms: `./gradlew assembleDebug` + iOS build via Xcode (SKIE conformance compiles).
3. Flag off (default): app boots, zero game-services calls (assert via reporter test + no log lines).
4. After store setup: QA-menu flag flip on device → Android auto-sign-in + unlock + leaderboard visible in Play Games UI; iOS deferred auth sheet → unlock + score visible in GKGameCenterViewController. Kill/relaunch → no duplicate banners, scores unchanged.
