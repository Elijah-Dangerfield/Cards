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
