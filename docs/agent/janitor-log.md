# Janitor log

Ledger of files cleaned by the nightly codebase janitor. One line per file with the date it was
last touched. Don't re-clean anything from the last ~30 days — pick a fresh slice instead.

| Date | File | What |
| --- | --- | --- |
| 2026-07-01 | features/home/impl/.../HomeViewModel.kt | Removed `[recent-achievements-delay]` debug logging (init + per-emission) + its narration |
| 2026-07-01 | features/home/impl/.../HomeScreen.kt | Removed per-recomposition debug log, dead `defaultRecentAchievements()`, inline FQN refs |
| 2026-07-01 | features/home/impl/.../WelcomeDialog.kt | Imported `@Preview` instead of fully-qualified annotation |
| 2026-07-01 | features/home/impl/.../PlayStyleUnlockedDialog.kt | Imported `@Preview` instead of fully-qualified annotation |
| 2026-07-01 | features/home/impl/.../BotTableSetupSheet.kt | Imported `@Preview` + `PreviewContent` instead of FQN |
| 2026-07-01 | features/home/impl/.../PrivateChooseSheet.kt | Imported `@Preview` + `PreviewContent` instead of FQN |
| 2026-07-02 | features/room/impl/.../session/RemotePokerSession.kt | Dropped unused `TableUiState` import; renamed shadowed `previous` → `priorHumans` in the opponent-departure diff |
| 2026-07-02 | features/room/impl/.../session/LocalBotsSession.kt | Imported `GameSpeed` + inlined-FQN `PlayerIntent` fixed; removed per-bot-iteration + duplicate debug logs |
| 2026-07-02 | features/room/impl/.../session/RemotePokerSessionFactory.kt | Dead `ui.label` import removed; imported `RoomConnectionHandle`/`Personality`/`PlayStyle` instead of inline FQN |
| 2026-07-02 | features/room/impl/.../session/SoloBotsPokerSessionFactory.kt | Dead `ui.label` import removed; imported `PlayStyle` instead of inline FQN |
| 2026-07-02 | features/room/impl/.../session/PokerSessionFactory.kt | Dead `ui.label` import removed |
| 2026-07-02 | features/room/impl/.../RemotePokerSessionTest.kt | +7 tests: tableCosmetics pin/non-regress, nextHandCountdown lifecycle, onHandEnded (PROG-4), leave() cash-out balance |
| 2026-07-02 | features/shop/impl/.../ShopScreen.kt | Shelf titles → string resources; stale KDoc (RequestPurchase, hero card) fixed; banners + orphaned comments deleted; FQN → import |
| 2026-07-02 | features/shop/impl/.../ShopViewModel.kt | Dead `val action` removed; stale class KDoc (init refresh, BillingClient) rewritten; narration trimmed |
| 2026-07-02 | features/shop/impl/.../ShopComponents.kt | IconTone enum collapsed (all tones same color, Neutral unused); formatChips passthrough deleted; preview FQNs → imports |
| 2026-07-02 | features/shop/impl/.../PurchaseConfirmSheet.kt | CatalogTimeAnchor / BuildInfo / Platform imports instead of FQN; formatChips → formatThousands |
| 2026-07-02 | features/shop/impl/.../CountdownBadge.kt | Tick threshold reads `1.hours`; preview FQNs → imports; narration trimmed |
| 2026-07-02 | features/shop/impl/.../ShopStateTest.kt, CountdownFormatTest.kt, ShopViewModelTest.kt | +17 tests: classify/sheetModeFor priorities, isExpired anchor math, formatCountdown ladder, expired-offer confirm path |
| 2026-07-02 | features/lobby/impl/.../LobbyViewModel.kt | Removed dead `StartGameComingSoon` error (mapped, never emitted); imported `RemoveBotOutcome` instead of inline FQN |
| 2026-07-02 | features/lobby/impl/.../LobbyScreen.kt | 15 FQN `@Preview` + `PreviewContent`/`Room`/`RoomStatus`/`ButtonDanger`/`TextAlign`/`PaddingValues` → imports; private `formatChips` → shared `formatThousands`; dead error branch dropped |
| 2026-07-02 | features/lobby/impl/.../PrivateCreateScreen.kt | FQN `Preview`/`ColorResource` → imports; private `formatChips` → `formatThousands`; rewrote stale "selectors are presentational" KDoc (they're wired to create) |
| 2026-07-02 | features/lobby/impl/.../PrivateJoinScreen.kt | FQN `@Preview` → import |
| 2026-07-02 | features/lobby/impl/.../LobbyFeatureEntryPoint.kt | Imported `Flow` instead of inline FQN return type |
| 2026-07-02 | libraries/resources/.../strings.xml | Removed dead `lobby_error_start_coming_soon` string |
| 2026-07-02 | features/lobby/impl/.../LobbyViewModelTest.kt | +5 tests: ConnectionUpdated Closed reasons (RoomDeleted/Rejected/IncompatibleVersion/ReconnectFailed) + leave NetworkError |
| 2026-07-02 | features/progression/impl/.../RankDetailSheetEntryPoint.kt | Wired dead `onClaimAccount = {}` stub → `ClaimAccountRoute` (anon rank CTA did nothing) |
| 2026-07-02 | features/progression/impl/.../StatsScreen.kt | All inline copy → string resources; `Achievement` FQNs → import; highlights slot logic extracted to testable `achievementHighlights()`; `percentOf` internal |
| 2026-07-02 | features/progression/impl/.../AchievementsScreen.kt | Copy → string resources; `kotlin.time.Clock` FQNs → import |
| 2026-07-02 | features/progression/impl/.../RankDetailSheet.kt | Copy → string resources; private InfoCard/Bullet → shared internal impl |
| 2026-07-02 | features/progression/impl/.../StatsExplainersSheet.kt | Copy → string resources; duplicate Sheet-prefixed InfoCard/Bullet deleted |
| 2026-07-02 | features/progression/impl/.../InfoCard.kt | NEW — shared internal `InfoCard` + `Bullet` for the progression screens |
| 2026-07-02 | libraries/resources/.../strings.xml | +55 progression strings (stats/achievements/rank/explainer screens) |
| 2026-07-02 | features/progression/impl/.../ProgressionFakes.kt | Absorbed the NeverEmitting* repository fakes (were duplicated, FQN-littered, in two test classes) |
| 2026-07-02 | features/progression/impl/.../StatsViewModelTest.kt, AchievementsViewModelTest.kt | Private NeverEmitting fake dupes removed; use shared fakes |
| 2026-07-02 | features/progression/impl/.../StatsScreenHelpersTest.kt | NEW — +7 tests: highlights ordering/back-fill/cap, percentOf dash + rounding |
| 2026-07-02 | features/profile/impl/.../account/ClaimAccountViewModel.kt | Dropped unreachable `PasswordsDontMatch` Submit branch + sealed variant (canSubmit already requires the match) |
| 2026-07-02 | features/profile/impl/.../account/ClaimAccountScreen.kt | Removed dead `PasswordsDontMatch` `.message()` case + its string import; `PreviewContent` FQN → import |
| 2026-07-02 | features/profile/impl/.../account/DeleteAccountScreen.kt | `PreviewContent` FQN → import |
| 2026-07-02 | libraries/resources/.../strings.xml | Removed dead `auth_claim_error_passwords_dont_match` string |
| 2026-07-02 | features/profile/impl/.../account/AccountViewModelFakes.kt | Extracted `StubAuthRepository` base (error-stubs the interface); `FakeAuthRepository` extends it; `Instant` FQN → import |
| 2026-07-02 | features/profile/impl/.../account/DeleteAccountViewModelTest.kt, AccountActionsViewModelTest.kt | Gated fakes extend `StubAuthRepository` (dropped ~40 lines of duplicated `error("unused")` stubs); pruned now-unused imports |
| 2026-07-02 | features/profile/impl/.../account/ClaimAccountViewModelTest.kt | +1 assertion: Submit-with-mismatch surfaces no error (locks the dead-branch removal) |
| 2026-07-11 | features/onboarding/impl/.../OnboardingViewModel.kt | Removed `onboarding-bounce` repro scaffolding (instance-hash log, guard dump, `step: X → Y` d-logs duplicated by `step_viewed` events); dead `isAuthing` state; dead `DismissError` action; 4 never-emitted guest `OnboardingAuthError` variants; `handleBack` collapsed to the only reachable transition (PickIdentity → Welcome) |
| 2026-07-11 | features/onboarding/impl/.../OnboardingScreen.kt | 10 FQN `@Preview` + `PreviewContent` → imports; dead guest-error mapping + `debugDetails`/DEBUG-suffix machinery; unreachable back buttons on HowItWorks/StarterGrant deleted (`creationStarted` is always true there); guest CTA's never-shown progress label dropped; previews pinned to producible states |
| 2026-07-11 | features/onboarding/impl/.../SignUpViewModel.kt | Dead mismatch re-check on Submit + `PasswordsDontMatch` variant (canSubmit already requires the match); dead `DismissError` action |
| 2026-07-11 | features/onboarding/impl/.../SignInViewModel.kt, ForgotPasswordViewModel.kt, VerifyEmailViewModel.kt | Dead `DismissError`/`DismissBanner` actions removed (no screen dispatches them; errors clear on field edits / next attempt) |
| 2026-07-11 | features/onboarding/impl/.../AuthScreens.kt | Dead `PasswordsDontMatch` mapping + import removed |
| 2026-07-11 | libraries/resources/.../strings.xml | −7 dead strings (guest auth errors ×4, debug suffix, guest-progress label, sign-up passwords-don't-match) |
| 2026-07-11 | features/onboarding/impl/.../AuthViewModelFakes.kt | Dead `MutableSharedFlow` import + unused `sampleAnonymous` removed; `FakeAuthRepository` grew Apple overrides + call counters; shared `FakeAppleSignInCoordinator` absorbed two FQN-littered private Noop copies |
| 2026-07-11 | features/onboarding/impl/.../OnboardingViewModelTest.kt, SignInViewModelTest.kt | +7 tests: Apple link-success / identity-on-another-account / dismissed-sheet / native-failure paths (were 0% covered), post-OAuth back block, Apple sign-in from the SignIn screen |
| 2026-07-11 | features/onboarding/impl/.../VerifyEmailViewModelTest.kt, SignUpViewModelTest.kt | DismissBanner-as-queue-drain → `runCurrent()`; mismatch test renamed + pins no-error; field-edit-clears-error test replaces the dismiss test |
