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
