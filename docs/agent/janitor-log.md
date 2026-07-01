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
