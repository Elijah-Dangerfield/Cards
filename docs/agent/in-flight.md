# In-flight (worker handoff log)

## refactor(billing): delete Dev/NoOp billing clients (BILL-6)

**Problem:** `DevBillingClient` and `NoOpBillingClient` were dead code — both platform bindings replaced them, and their own TODO said to remove them once real bindings landed.
**Approach:** Deleted both classes, moved `DEV_FAKE_CATALOG` into `FakeBillingClient.kt` (both platform clients still construct the fake from it in debug), dropped the now-empty `replaces=` lists, and rewrote the kdoc in `BillingClient`, `ProductsRepositoryImpl`, and `BillingAvailabilityImpl` that described the old default-binding story.
**Reviewer notes:** `docs/agent/compose-ui-testing-spike.md` still cites the Dev→NoOp replacement as a DI-swap example; left it as-is since it's a historical spike record. `developer-todo.md` also mentions `DevBillingClient` but workers can't touch that file.

## chore(pages): use the no-background app icon as the site logo (ENG-11)

**Problem:** The static site header logo used the old solid-background `app-icon.png`; a transparent-background icon now exists in Compose resources.
**Approach:** Downscaled `app_icon_no_background.png` (1024px) to 256px with `sips` — matching the old asset's dimensions since the CSS renders it at 96px — and overwrote `pages/app-icon.png`, so all three pages pick it up with no HTML changes. Kept `favicon.png` / `apple-touch-icon.png` on the opaque version deliberately: apple-touch icons must be opaque (iOS fills transparency with black) and a 64px favicon reads better with a solid ground.
**Reviewer notes:** Acceptance says "verified on the deployed Pages site" — deploy happens on merge via pages.yml, so verify post-merge. Rendered PNG checked locally (transparent rounded corners, RGBA).

## feat(server): mint reward chips server-side, refuse client credits (ENG-9)

**Problem:** Level-up and achievement chip rewards were client-asserted through `POST /v1/me/wallet/sync`, which applied any `delta` + `reason` verbatim — a modified client could mint unlimited chips, and minted chips gate real-stakes MP entry.
**Approach:** Wallet sync now refuses positive deltas with `levelup.*` / `achievement.*` reasons (new `RefusedServerOwned` outcome, red test first); the server grants those itself at the triggers it witnesses — level chips on progression sync (XP total crossing a rewarded level), achievement chips on earned-achievement sync — from server-owned tables in `RewardChips`, using the same `achievement:<id>` ledger-key convention as the existing MP witness so the two paths can never double-credit. Client treats the refusal as terminal (drops the outbox row, adopts the authoritative balance) and keeps its optimistic local credit for instant UI. Chose grant-at-witness-point over verify-in-place (one mint path, not two); chose mirrored server tables over a shared KMP module, matching the `ClientGrantableAchievements` / `CHIP_REWARDS` precedent — see decisions.md 2026-07-04.
**Reviewer notes:** Also aligned the client registry's MP achievement `chipReward` display amounts with what the server actually grants (they had drifted: e.g. `BUST_DEALT_5_MP` showed 500 but the server grants 2,000). `RewardChips.levelForXp` duplicates the bundled default curve — if `progression.levelCurve` / `progression.levelRewards` are ever retuned via app-config, the server tables must move too (flagged in the decisions entry).
**Deferred:** `iap.*` credits still ride wallet-sync client-asserted alongside the BILL-5 server redeem path — same refusal treatment probably applies but it's BILL-1/2 territory; reviewer please triage. Achievement *facts* (the earned set) are still client-asserted for SP ids — total mintable is now bounded by the fixed reward tables, full trust needs Phase 4.2 server-side hand resolution. Server reading its progression tables from its own app-config (instead of bundled defaults) noted in decisions.md as a revisit trigger, no backlog entry.

## feat(build): bake commit SHA + branch into builds, surface in Sentry + feedback (ENG-10)

**Problem:** A feedback/bug report couldn't be tied to the exact commit the installed build came from, so triage can't tell which code produced it or whether it's already fixed.
**Approach:** `loadVersionMetadata` now resolves a 12-char SHA + branch — `GITHUB_SHA` / `GITHUB_REF_NAME` in CI (exported automatically, no workflow changes), `git rev-parse` locally via `providers.exec` (configuration-cache safe; a plain `ProcessBuilder` fails the build), `"unknown"` in the gitless Docker server build. Emitted as `COMMIT_SHA` / `COMMIT_BRANCH` on `CardsBuildConfig`, exposed through `BuildInfo` (all three actuals), set as `commit_sha` / `commit_branch` Sentry tags at init, and prepended as a "Build: 0.0.1 (1) @ <sha> (<branch>)" line on the feedback/bug-report comments so it's readable in the report itself.
**Reviewer notes:** Verified the generated `CardsBuildConfig` carries the real SHA/branch locally. `libraries/flowroutines/src/jvmMain/.../BuildConfig.jvm.kt` is a stray `actual object BuildInfo` in a module with no jvm target (dead file, never compiled) — left alone.

## feat(wallet): remove the first-7-days daily welcome bonus

**Problem:** Owner directive — kill the "Day N of 7 — welcome bonus" daily reward. The item asked for a delete-vs-config-flag recommendation.
**Approach:** Deleted outright rather than flagging off: the owner's wording was "get rid of it", daily-streak mechanics were rejected on principle in the product spec, we're pre-launch with nobody mid-week, and a flag would leave dead grant code + copy to maintain. Removed the server grant path (`maybeApplyWelcomeWeek` + copy + `Wallet.WELCOME_WEEK_*` constants + the now-unused `clock` param on `walletRoutes`), its 11 route tests + test-harness machinery, the welcome-dialog "open the app every day this week" expectation line, and its string resource. Starter grant and bust protection untouched.
**Reviewer notes:** Existing `welcome_week_day_*` ledger rows (dev data only) are inert — nothing reads or writes those keys anymore. The backlog "come back reward" idea is unaffected.

## fix(lobby): align create-screen cosmetic shelves with their labels (ROOM-15)

**Problem:** On the create-game screen the felt / card-back shelves started one D600 left of their labels — `EdgeToEdgeRow` escapes *screen* padding, but these shelves sit inside the clipped Rules card, so the escape just got clipped at the card border and item 0 landed flush against it.
**Approach:** Swapped `EdgeToEdgeRow` for a plain `LazyRow` with `contentPadding = D600` inside `CosmeticPickerRow` — item 0 now sits under the label, and scrolled content still bleeds to the card's edges (clipped by the card's own rounded shape, which reads intentional). Kept `EdgeToEdgeRow` untouched for its screen-level users (profile/home shelves).
**Reviewer notes:** Verified via preview geometry reasoning + compile; the misalignment only reproduces inside a padded/clipped container, which no other `EdgeToEdgeRow` caller has.
