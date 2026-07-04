# TODO

**Last reviewed:** 2026-07-01 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

## ROOM

- `[P2]` **Create-game screen: make it scrollable and left-justify its rows to the section header.** Owner feedback on the create-game (private room create) screen — the horizontal rows aren't aligned to their section header the way the profile screen's are, and the screen doesn't scroll so content can be cut off on smaller devices.
  **Acceptance:** the create-game screen scrolls when content exceeds the viewport, and its rows are left-justified flush with the section header, matching the profile screen's treatment.
  **Hints:** the create-game/private-room create screen in `:features:lobby` (`LobbyFeatureEntryPoint` / create-room composables); mirror the profile screen's row/header alignment. Sentry CARDS-8B + CARDS-8D.

## GAME

- `[P2]` **GAME-17 — Make an early bot fold legible: seat fold cue + last move on the player card.** Vs bots, the first-to-act bot can fold instantly at hand start with no visible cue — the reporter only saw two gray cards, and tapping the bot's player-card sheet showed no last move.
  **Acceptance:** a bot fold is visibly announced on its seat (fold cue, not just gray cards) even when it fires at hand start, and the opponent player-card sheet shows the seat's last action including Fold.
  **Hints:** `LocalBotsSession.runUntilHumansTurnOrComplete` applies the UTG fold synchronously before the human's first turn; seat rendering (`PlayerArea`) + tap-an-opponent sheet in the play feature; case docs/agent/feedback-cases/d6022eaee9794557844307fedfdd03ca.md; Sentry CARDS-8H.

## PROG

- `[P2]` **PROG-9 — Mid-game achievements vs bots: horizontally scrolling pager with indicator.** Owner directive — when achievements surface mid-game against bots, present them as a horizontal pager (with page indicator) instead of the current presentation.
  **Acceptance:** multiple mid-game achievements render in a horizontally scrolling pager with an indicator; a single achievement still reads cleanly.
  **Hints:** in-game achievement celebration surface in the play/progression layer (see the progression map in docs/wiki); the recap-notification half of the ask went to backlog.md. Sentry CARDS-8K.

## AUTH

- `[P1]` **AUTH-16 — Move the Supabase session store to OS-encrypted storage (Keychain / EncryptedSharedPreferences).** The 2026-05-18 decision accepted plaintext token storage explicitly *only until the claim flow shipped* — claim (Apple + Google) is live, so refresh tokens for real, claimed accounts now sit in unencrypted SharedPreferences / NSUserDefaults (supabase-kt's default `multiplatform-settings` store).
  **Acceptance:** the Auth plugin is configured with a custom `sessionManager` backed by EncryptedSharedPreferences (Android) and Keychain (iOS); an existing session migrates on first launch (read old store once, write new, clear old) so nobody gets signed out by the upgrade.
  **Hints:** `install(Auth) { sessionManager = ... }` in [SupabaseClientFactory.kt](libraries/identity/impl/src/commonMain/kotlin/com/cards/libraries/identity/impl/SupabaseClientFactory.kt); Keychain via a Swift Twin per `docs/practices/swift-kotlin.md`. The upgrade sketch in decisions.md 2026-05-18 predates the Supabase re-adoption (`TokenStoreImpl` no longer exists).

- `[P2]` **Remove the first-7-days daily welcome bonus.** Owner directive ("tbh I think we should just get rid of the daily bonus for the first 7 days thing"). This is the shipped "Day N of 7 — welcome bonus" onboarding reward surfaced via in-app messages. Directional/product call — recommend delete vs config-flag-off and ship a slice.
  **Acceptance:** new users no longer see the "Day N of 7 — welcome bonus" daily reward flow; the associated grant/in-app-message logic is removed or disabled behind a config flag, with no dangling references.
  **Hints:** the "Day N of 7 — welcome bonus" in-app message + its grant path (progression/economy + `InAppMessages`); relates to backlog "come back reward" (deferred) — daily-streak mechanics were rejected on principle back in the product spec. Sentry CARDS-89.

## BILL

Native IAP (Play Billing + StoreKit 2 + own server receipt validation — no RevenueCat) is **built and tested** (shipped in #83). What exists: `/v1/billing/redeem` + `billing_transactions` (V81) + `PostgresBillingRepository`; real `AppStoreReceiptValidator` / `GooglePlayReceiptValidator` (dormant until configured, fail-closed); `RealPlayBillingClient` (Android) + `StoreKitBillingClient` (iOS), both DI-bound with `replaces = [DevBillingClient, NoOpBillingClient]`; and the server-authoritative validate→grant→reflect flow behind the `billing.realPurchasesEnabled` flag — all covered by tests (`BillingRoutesTest`, `PostgresBillingRepositoryTest`, `*ReceiptValidatorTest`, `DefaultPurchaseChipPackUseCaseTest`). **What remains to actually sell chips is human**: store listings + credentials + flipping the flag + a beta test pass — all in [developer-todo.md](./developer-todo.md). Only code loose-ends below (BILL-1..5 retired as shipped).

- `[P2]` **BILL-6 — Delete `DevBillingClient` / `NoOpBillingClient` now real platform bindings exist.** Both real clients are bound with `replaces = [DevBillingClient::class, NoOpBillingClient::class]`, so the Dev/NoOp clients are dead in shipping builds — their own `TODO(billing): remove this class once a real platform binding lands` is now due.
  **Acceptance:** Dev/NoOp billing clients removed, `replaces=` lists cleaned up, all billing tests still green. Keep `FakeBillingClient` (it's manually wired for previews/tests, not `@ContributesBinding`).
  **Hints:** [DevBillingClient.kt](libraries/billing/impl/src/commonMain/kotlin/com/cards/libraries/billing/impl/DevBillingClient.kt) + `NoOpBillingClient.kt`.

- `[P2]` **BILL-7 — Verify the Android consumable consume path.** `GooglePlayReceiptValidator` notes that acknowledging/consuming is the client's job (`consumeAsync` for consumable chip packs). Confirm `RealPlayBillingClient` actually consumes after a successful server redeem so the same pack is re-purchasable.
  **Acceptance:** a repeat purchase of the same chip pack succeeds end-to-end (consume fires after redeem) — a test, or a documented manual check on the Play internal track.
  **Hints:** `RealPlayBillingClient` / `AndroidStoreKitCoordinator`; pairs with the BILL device-QA item in developer-todo. If already wired, just add the regression test + close this.

## ENG

- `[P1]` **ENG-9 — Make rewarded chips (level-up + achievement) server-authoritative.** Level-up and achievement chip rewards are granted *client-side* (`LevelUpRewardGranter`, `AchievementRepositoryImpl` → `ChipsRepository.addChips`) and flushed through `POST /v1/me/wallet/sync`, which applies the client-supplied `delta` + `reason` verbatim ([WalletRoutes.kt](apps/server/src/main/kotlin/com/cards/server/routes/WalletRoutes.kt) — only dedupes by idempotency key + floors debits at zero). A modified client can POST `delta=1_000_000, reason="levelup.99"` and mint chips — and minted chips gate real-stakes MP entry (wallet ≥ 4× buy-in), so this has a real-stakes vector despite no cash-out.
  **Acceptance:** credits for reward reasons (`levelup.*`, `achievement.*`) are computed/verified on the server, not trusted from the client — the server owns the reward amounts and credits only when it can witness/verify the trigger, the way starter-grant / welcome-week / bust-protection / MP escrow already are. A client-asserted positive credit for a reward reason is refused; a failing test posts a bogus `levelup` credit and asserts rejection.
  **Hints:** precedent is `ClientGrantableAchievements` + `POST /v1/me/grants/achievement/{id}` — already gates the achievement *inventory* unlock (403 for server-witnessed ids); the *chip amount* rides the unguarded wallet-sync path. Ties to the Phase 4.2 server-authoritative hand-resolution migration noted in that file. Purchased chips are the separate receipt-verified path (BILL-1/2).

- `[P2]` **ENG-10 — Bake commit SHA + branch into build metadata; attach to bug reports + Sentry.** A user's feedback/bug report can't be tied to the exact build's commit, so triage can't tell which code produced it — or whether it's already fixed on a later commit (a real cost given the nightly feedback-triage loop).
  **Acceptance:** a short git SHA (+ branch) is baked into the generated BuildConfig — CI sets it from `github.sha` / `github.ref_name`, local falls back to `git rev-parse` — exposed on `BuildInfo`, attached to the in-app feedback/bug-report payload, and set as a Sentry tag/context.
  **Hints:** extend `writeCommonMetadata` + `BuildInfo` (expect/actual) like `versionName`/`buildNumber` in [Versioning.kt](build-logic/src/main/java/com/cards/util/Versioning.kt); surface in `FeedbackViewModel` / bug-report submit; `release.yml` already runs Sentry `set-commits --auto`. Beta `buildNumber` is already the commit count, a coarse proxy.

- `[P2]` **ENG-11 — Use the new no-background app icon on the privacy/terms website.** The static site header logo on [index.html](pages/index.html), [privacy.html](pages/privacy.html), and [terms.html](pages/terms.html) points at `pages/app-icon.png`; a new transparent-background icon now exists at `libraries/resources/src/commonMain/composeResources/drawable/app_icon_no_background.png` and should replace it.
  **Acceptance:** the three pages render the no-background icon, verified on the deployed Pages site.
  **Hints:** `pages/` is a static site served by [pages.yml](.github/workflows/pages.yml) — it can't read Compose resources, so copy the PNG into `pages/` (overwrite `app-icon.png`, or add it and update the three `<img src="app-icon.png">` refs). Consider whether `favicon.png` / `apple-touch-icon.png` should match.
