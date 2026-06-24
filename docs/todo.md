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

- `[P2]` **AUTH-1 — Polish the "account-creation pending" UX.** When someone signs up but the server account hasn't created yet (offline / network blip), we show a thin banner and that's it. The state is safe — the user can keep playing locally — but the surface feels under-built. Three small upgrades: (1) replace the thin `AccountSetupBanner` with a richer dialog when the user first hits it; (2) tighten the device-verify banner copy + placement; (3) optionally mirror the Retry button on Profile/Settings near `SaveProgressBanner`.

  **Hints:** State lives at `GuestAccountCreator.state`; banner at `apps/compose/AccountSetupBanner.kt`.

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

- `[P1]` **MP-3 — Author the multiplayer section of [`docs/QA.md`](./QA.md).** Onboarding is seeded (16 tests); MP needs the same treatment. Cover the major MP surfaces as device-runnable scenarios: create-room + join-by-code, find a public game via matchmaking, play a hand to showdown, multi-hand sequence (button rotation), host disconnect + promotion, reconnect mid-hand, graceful vs force-quit leave, bust + re-buy.

  Match the existing test shape (ID + priority + platform + **State** / steps / **Expected**). Finish lines vary by path — joining ends at the lobby, finding ends at sit-down, playing ends at post-hand or the next-hand prompt.

  **Hints:** Walk `features/room/impl/` + `apps/server/src/main/kotlin/com/cards/server/routes/RoomSocketRoutes.kt` + the matchmaking routes; [`wiki/multiplayer.md`](./wiki/multiplayer.md) is the architectural primer. Cross-reference `MP-1` / `MP-2` where a known gap applies.

- `[P2]` **MP-1 — Orphaned-room policy — read-only spectator downgrade.** Seat-forfeit on grace expiry already lands (`forfeitSeat`); the remaining half is downgrading the forfeited member's WS subscription to **read-only spectator** instead of closing the socket, with `GET /v1/me/active-rooms` driving a Rejoin / Forfeit banner. The seatless-subscriber socket auth this needs already exists.

- `[P0]` **MP-2 — Close the remaining multiplayer test gaps.** MP is the load-bearing feature of the app. The integration module + engine property tests + chaos suite are largely landed (see [`practices/testing.md`](./practices/testing.md)). Still open:

  - **Compose UI tests for `PlayPokerScreen`** — ~15 tests across the screen's 6+ states (your turn, bot thinking, raise unavailable, showdown, fold-around, loading, connection lost). Wire `androidx.compose.ui.test` into `:features:room:impl`'s `androidUnitTest`.
  - **Server restart mid-hand → full client reconnect.** Server-side hydration is pinned by `SessionHydrationTest`; the open part is the client-reconnect-after-restart end-to-end in `:apps:integration`.
  - **`FakeRoomServer` for the integration tier.** A fake that responds to `StartHand` / `SubmitIntent` / `RequestNextHand` using a real `GameSession`, so client-side tests can cover full turn cycles without booting Ktor.
  - **Latency-simulating transport** (200ms+ RTT) at `:apps:integration`. The double-submit dedupe guard is pinned at the VM layer; the latency transport itself is missing.

  **Acceptance:** Each bullet lands as its own commit. The "test the seams in production order" rules in [`practices/testing.md`](./practices/testing.md) apply to every new test.

  **Out of scope:** Emulator-based UI tests (device-smoke checklist is the substitute) and hand-history regression fixtures (gated on a real production playtest).

- `[P1]` **MP-6 — Bots-for-chips cashout doesn't match the stack the player saw; make the settlement honest.** On leaving a bot table the player won, the server pays a capped `bot_subsidy_payout` (room MZJMA5: granted 4475, cap 25000) instead of the ~9k stack shown at the table, so "the chips didn't go with me." A second user questioned an odd 10,018 balance from the same subsidy arithmetic. No accounting corruption found — the subsidy model is just opaque to the player.

  **Acceptance:** Either the cashout credits the chips the player watched themselves win, or the table/cashout UX makes the subsidy + cap explicit before and after the hand so the resulting balance is never surprising. Make a directional call and ship a slice.

  **Hints:** `DefaultTableSessionService.cashOut` → `bot_subsidy_payout` (cap 25000); wallet ledger is authoritative. Cases `docs/agent/feedback-cases/6dd1f1ffddb347fd9cf6c5909caa98d0.md` + `docs/agent/feedback-cases/a0e30df3e1f845e085a7b360e3e5a4c5.md`; Sentry CARDS-2N / CARDS-2Y. (The "next-hand button did nothing" half of CARDS-2N is the separate P0 hand-end stall, not this item.)

---

## C. Engineering

### Lint / static analysis

- `[P1]` **ENG-2 — Stand up detekt as the project's custom-rule framework, gated in CI + pre-push.** The point is a growable set of AGENTS.md conventions the build mechanically enforces — both in CI and on `.githooks/pre-push` — so neither humans nor the nightly agents can violate them. Land the framework + the first rule now; the rest are cheap follow-ons.

  - **Framework:** add detekt to `gradle/libs.versions.toml` + a `build-logic/` convention plugin, wire `detekt` into `check` (so CI's existing gradle run catches it) and into a new `.githooks/pre-push`. Land behind a baseline file so the gate is green on day one.
  - **Rule #1 — `verifyStrings`:** fail on inline user-facing string literals (`Text("…")`, `placeholder = "…"`, VM-emitted copy) outside `:libraries:resources`, with an allowlist for glyph-only / preview / server-supplied strings.
  - **Follow-on rules (each a separate item, not part of this one):** `Catching {}` over `try/catch` / `runCatching`; `DispatcherProvider` over direct `Dispatchers.{Main,IO,Default,Unconfined}`; raw `Color(0xFF…)` / `Color.White.copy(alpha=)` / one-off `RoundedCornerShape(N.dp)` for semantic surfaces.

  **Acceptance:** Adding `Text("Hello")` to a feature `:impl` fails both `./gradlew check` and the pre-push hook; `stringResource(...)` passes; a documented suppress annotation clears a flagged line; adding a second rule is a localized change (new rule class + config entry), no framework rework.

  **Hints:** Convention plugins live in `build-logic/`; existing `.githooks/` has `commit-msg`. **Out of scope:** migrating the existing string violations — separate cleanup once the gate exists.

### Billing

- `[P0]` **BILL-1 — Server-side IAP receipt validation + server-authoritative purchase ledger.** Today `ShopViewModel.ConfirmPendingPurchase` drives `billingClient.purchase(...)` and credits chips locally on success — the server never validates the receipt, so a forged receipt mints chips. Before any real-money sale: `POST /v1/billing/redeem` validates against the Apple App Store Server API / Google Play Developer API, then grants chips through the server wallet ledger, idempotent per store transaction id. **Gated on:** store IAP products + store API credentials existing (developer-todo).

  **Hints:** Grant precedent is `ChipsRepository.addChips(idempotencyKey=…)` / the wallet ledger. Verify-before-credit — never trust the client for paid chips.
