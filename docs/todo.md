# TODO

**Last reviewed:** 2026-06-24 · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## A. UX gaps

### Progression & stats

- `[P1]` **PROG-1 — Graduate hand counters + achievement progress to the server (stats-first model).** Today, hand counters (hands played / won / folded / lost-at-showdown / bot hands) and achievement progress counters (no-bust streak, per-bot wins, …) live only on the device. Switch accounts or reinstall and they reset — the stats screen and achievement progress bars look wrong on a different device.

  **Approach:** Stats become the source of truth; achievements become predicates over stats. Server gets a `player_stats` table holding cumulative counters (hands_played / won / folded / lost_at_showdown, bot_hands_played), streak values (current no-bust streak + best), and small per-key maps (per-bot wins). Achievements stop carrying their own progress numbers — they read stats and record a `claimed_at_value` so they don't re-fire. The stats screen reads the same numbers achievements use.

  **Acceptance:** Sign in on a second device → correct stats + achievement progress. Stats screen and achievement bars agree. Adding a new achievement later doesn't need a data migration — it just points at an existing stat.

  **Hints:** Mirror the PlayStyle sync — new `GET/POST /v1/me/stats/sync`, delta-up + snapshot-down, idempotent per batch. Keep client counters as a write-ahead cache for offline play; server is authoritative on reconcile. Templates: `PlayStyleRoutes.kt`, `PlayStyleRepositoryImpl.sync`.

### Auth & onboarding

- `[P2]` **AUTH-1 — Polish the "account-creation pending" UX.** When someone signs up but the server account hasn't created yet (offline / network blip), we show a thin banner and that's it. The state is safe — the user can keep playing locally — but the surface feels under-built. Two upgrades remain: (1) replace the thin `AccountSetupBanner` with a richer dialog when the user first hits it; (2) tighten the device-verify banner copy + placement.

  **Hints:** State lives at `GuestAccountCreator.state`; banner at `apps/compose/AccountSetupBanner.kt`. The in-page Retry mirror on Profile/Settings already shipped via the `AccountSetupRetryBanner` DS primitive + `LocalAccountSetupRetry`.

- `[P1]` **AUTH-2 — Reconcile local bot-play progress when a degraded account is finally created.** While creation is pending (offline), the user plays bots and accrues XP/chips locally against `Profile.Fallback`. Degraded play stays purely local — it doesn't write to the server-bound ledger. When `GuestAccountCreator` succeeds the server is authoritative: replay the pending local deltas on top of the server balance **once**, and never re-grant the provisional starter (`OnboardingStarterGrant`).

  **Hints:** `ChipsRepositoryImpl.sync` already replays pending `wallet_events`; progression/XP sync is the riskier half — same local-until-creation rule.

- `[P2]` **AUTH-3 — Route new OAuth/email sign-ups through onboarding.** Deferred creation sends guests through onboarding, but a brand-new Apple/Google/email sign-up still goes straight to Home (returning sign-ins correctly skip). Routing new sign-ups through PickIdentity/grant needs a reliable new-vs-returning signal — `walletCreated` on first wallet sync, or a server "profile just created" flag.

  **Hints:** OAuth/Apple paths in `OnboardingViewModel` (`handleOAuth` / `finishAppleSignIn`) set `hasUserOnboarded=true` → Home.

- `[P2]` **AUTH-5 — Verify network-required surfaces honor the `Profile.Fallback` gating rule.** Walk Home / Shop / Profile / Edit Profile / Claim / Inventory / Multiplayer / Settings and confirm each matches the rule. Most already do — this is a verification pass, not a redesign.

  **Acceptance:** Reads render cached content; server-mutating surfaces soft-gate (visible, affordances disabled with an offline hint); money + multiplayer hard-gate.

  **Hints:** Network-required surfaces are multiplayer, real-money purchase, and account claim. Edit Profile's avatar picker falls back to a hardcoded starter list when the pack fetch never landed — confirm a `patchMe` from there surfaces errors cleanly.

- `[P1]` **AUTH-6 — Cold-boot-offline load + fallback misbehaves.** A no-internet cold boot shows the "connection issues" banner correctly, but downstream is wrong: creating an MP room pops the "account needed" dialog (it should read as a connection problem off a cached profile, not as account-less), and sign-out → continue-as-guest skips the "new here" banner. Evaluate the load/fallback chain end-to-end: what we load, what we fall back on, how each fallback colors error copy + gating. Offline writes should queue and send on reconnect, not hard-error.

  **Acceptance:** Offline MP entry reads as a connection problem (not "account needed"); a returning user offline uses their cached profile; sign-out → guest shows the "new here" banner.

  **Hints:** Pairs with AUTH-5 and the session-expiry blocking screen. Open product call ("should MP require a real account?") is in [`developer-todo.md`](./developer-todo.md).

### Gameplay & table UX

- `[P2]` **GAME-3 — Emote button glyph isn't optically centered.** The play-poker emote trigger ([`TopBarEmojiButton`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/EmojiTray.kt) → DS [`EmojiButton`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/icon/EmojiButton.kt)) centers its circular bounding box correctly, but the emoji glyph sits slightly up-and-left inside it — the text line-box midpoint ≠ the glyph's visual midpoint.

  **Acceptance:** The glyph reads optically centered in the circle at every `Size`.

  **Hints:** The `Box`/`Text` in `EmojiButton.kt`; likely needs a glyph-vs-line-box offset, not just `Alignment.Center`. **Worker note:** needs Studio to eyeball against the size-scale `@Preview`.

---

## B. Multiplayer hardening

- `[P0]` **MP-2 — Close the remaining multiplayer test gaps.** MP is the load-bearing feature of the app. The integration module + engine property tests + chaos suite are largely landed (see [`practices/testing.md`](./practices/testing.md)). Still open:

  - **Compose UI tests for `PlayPokerScreen`** — ~15 tests across the screen's 6+ states (your turn, bot thinking, raise unavailable, showdown, fold-around, loading, connection lost). Wire `androidx.compose.ui.test` into `:features:room:impl`'s `androidUnitTest`.
  - **Server restart mid-hand → full client reconnect.** Server-side hydration is pinned by `SessionHydrationTest`; the open part is the client-reconnect-after-restart end-to-end in `:apps:integration`.
  - **`FakeRoomServer` for the integration tier.** A fake that responds to `StartHand` / `SubmitIntent` / `RequestNextHand` using a real `GameSession`, so client-side tests can cover full turn cycles without booting Ktor.

  **Acceptance:** Each bullet lands as its own commit. The "test the seams in production order" rules in [`practices/testing.md`](./practices/testing.md) apply to every new test.

  **Out of scope:** Emulator-based UI tests (device-smoke checklist is the substitute) and hand-history regression fixtures (gated on a real production playtest).

- `[P1]` **MP-6 — Surface bot-table chip settlement at the surprising moments.** Two disclosure surfaces remain on subsidized bot tables: (1) a post-leave confirmation (toast / Home summary) naming the credited amount + new wallet balance, so the balance change is never a silent surprise; (2) a sit-down disclosure when the player is near their daily subsidy cap, so they learn it before playing rather than from an "odd balance" afterward.

  **Acceptance:** A player who wins on a subsidized table and leaves sees the credited amount confirmed; a player near their daily cap is told before they sit.

  **Hints:** `DefaultTableSessionService.cashOut` credits `finalStack`; `SubsidyCapReached` (`SitDownResult`) is the cap gate. Sentry CARDS-2N / CARDS-2Y.

- `[P0]` **MP-7 — Private (human-vs-human) table winnings don't settle to the wallet on leave.** A player won a 500-chip pot in a private 2-player room (A5MEME), left, and their wallet showed nothing; a background/foreground later bumped the balance by +100, so the reconcile is not just missing but inconsistent. The server logs `Hand N finished` / seat-forfeit but emits no wallet-credit on leave for a private fake-chip room. Needs a product call on whether private fake-chip rooms move the wallet at all: if yes, this is a settlement bug (mirror the bots path's `cashOut` final-stack credit); if no, the table stack must stop being framed as the wallet balance. Either way "win 500 → wallet unchanged → +100 on resume" is broken and surprising.

  **Acceptance:** Leaving a private MP room settles the final table stack to the wallet exactly once (or, if private rooms are decided not to touch the wallet, the table stack is never presented as a wallet change). No phantom delta appears on the next background/foreground resync.

  **Hints:** Bots path precedent is `DefaultTableSessionService.cashOut` (credits `finalStack`); the private-room leave path has no equivalent. `ChipsRepository.addChips(idempotencyKey=…)` is the idempotent credit primitive; resume reconcile is `ChipsSync`. Case `docs/agent/feedback-cases/b12633cf4d4441a992f5de348a5900a8.md` (full A5MEME story, both seats) + `docs/agent/feedback-cases/624b47e2cfff46fc8d01f66f810d60dd.md` (the +100-on-resume detail). Sentry CARDS-3C/3E + CARDS-3F/3G.

- `[P1]` **MP-8 — Room socket reconnect storm after the sole other human leaves.** Once the only opponent leaves a 2-player room (room NP2DDJ), the client socket wedges into an unbounded connect→drop→reconnect loop: hundreds of `Room socket connected` immediately followed by `Room socket reconnecting (attempt=1, backoff=…)` for ~30s after an NSPOSIXError 57 "Socket is not connected", with `attempt=1` never incrementing and no give-up. The user's only escape was mashing Back. Distinct from the existing backlog "$0 buy-in + 409 on POST /bots" residual (same room) — this is the reconnect-reliability half.

  **Acceptance:** After the peer leaves and the socket half-opens, reconnect attempts back off and increment, and after a bounded number of failures the client lands on a terminal "reconnect failed / leave" state instead of looping. No tight connect/reconnect storm in the session log.

  **Hints:** `RoomSocket` reconnect logic — the `attempt` counter isn't advancing and there's no backoff ceiling / terminal state; the server-side socket is half-open (status never dropped back to Lobby after the sole-human-left rebound, which is the shared root with the backlog item). Case `docs/agent/feedback-cases/74169a5f37b34263a6250e1081e30368.md`; Sentry CARDS-37. Related: backlog "MP lobby shows $0 buy-in + 409 … after sole-human-left rebound".

---

## C. Engineering

### Lint / static analysis

- `[P1]` **ENG-2 — Stand up detekt as the project's custom-rule framework, gated in CI + pre-push.** The point is a growable set of AGENTS.md conventions the build mechanically enforces — both in CI and on `.githooks/pre-push` — so neither humans nor the nightly agents can violate them. Land the framework + the first rule now; the rest are cheap follow-ons.

  - **Framework:** add detekt to `gradle/libs.versions.toml` + a `build-logic/` convention plugin, wire `detekt` into `check` (so CI's existing gradle run catches it) and into a new `.githooks/pre-push`. Land behind a baseline file so the gate is green on day one.
  - **Rule #1 — `verifyStrings`:** fail on inline user-facing string literals (`Text("…")`, `placeholder = "…"`, VM-emitted copy) outside `:libraries:resources`, with an allowlist for glyph-only / preview / server-supplied strings.
  - **Follow-on rules (each a separate item, not part of this one):** `Catching {}` over `try/catch` / `runCatching`; `DispatcherProvider` over direct `Dispatchers.{Main,IO,Default,Unconfined}`; raw `Color(0xFF…)` / `Color.White.copy(alpha=)` / one-off `RoundedCornerShape(N.dp)` for semantic surfaces.

  **Acceptance:** Adding `Text("Hello")` to a feature `:impl` fails both `./gradlew check` and the pre-push hook; `stringResource(...)` passes; a documented suppress annotation clears a flagged line; adding a second rule is a localized change (new rule class + config entry), no framework rework.

  **Hints:** Convention plugins live in `build-logic/`; existing `.githooks/` has `commit-msg`. **Out of scope:** migrating the existing string violations — separate cleanup once the gate exists. **Version blocker (needs a human call before this is worker-pickable):** the repo is on Kotlin 2.3.21, but detekt 1.23.x bundles the 2.0.0 compiler and chokes on 2.3 metadata (detekt#8865) — only `dev.detekt` 2.0.0-alpha supports Kotlin 2.3, and gating CI + pre-push on an alpha risks reddening every build. Either pin a deliberate detekt-2-alpha version (then the framework + `verifyStrings` rule can land behind a baseline) or hold until detekt 2 stabilises.

### Billing

- `[P0]` **BILL-1 — Server-side IAP receipt validation + server-authoritative purchase ledger.** Today `ShopViewModel.ConfirmPendingPurchase` drives `billingClient.purchase(...)` and credits chips locally on success — the server never validates the receipt, so a forged receipt mints chips. Before any real-money sale: `POST /v1/billing/redeem` validates against the Apple App Store Server API / Google Play Developer API, then grants chips through the server wallet ledger, idempotent per store transaction id. **Gated on:** store IAP products + store API credentials existing (developer-todo).

  **Hints:** Grant precedent is `ChipsRepository.addChips(idempotencyKey=…)` / the wallet ledger. Verify-before-credit — never trust the client for paid chips.
