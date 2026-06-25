# TODO

**Last reviewed:** 2026-06-25 · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

- `[P1]` **PROG-1 — Convert the achievement engine to predicates over the server-authoritative player stats.** `StatsViewModel` now reads the no-bust streak off `PlayerStatsRepository.observeStats()`, but the achievement engine still carries its own per-id progress in `AchievementProgress.counters` / `customCounters`, accumulated locally in `AchievementRepositoryImpl.recordHand`. What's left: make the predicates read the cumulative counters / no-bust streak / per-bot wins off the `PlayerStats` snapshot, recording a `claimed_at_value` per achievement so they unlock once and don't re-fire.

  **Approach:** Stats are the source of truth; achievements become predicates over stats. This is the riskier half — it touches the live unlock path in `AchievementRepositoryImpl.recordHand`, so land it as its own commit with the existing achievement unlock tests green.

  **Acceptance:** Sign in on a second device → correct achievement progress after one sync. Stats screen and achievement bars agree. Adding a new achievement later points at an existing stat, no data migration.

  **Hints:** Repo + DTOs: `PlayerStatsRepositoryImpl` / `dto/PlayerStatsDto.kt`. Endpoints are `/v1/me/player-stats` + `/v1/me/player-stats/sync`. `observeStats()` is already an offline-first `Flow<PlayerStats?>` and `StatsViewModel` already injects it (the no-bust streak tiles read from it). The streak the client sends is computed in `PlayerStatHandSummaryBuilder` (seeded from the cached snapshot).

### Auth & onboarding

- `[P2]` **AUTH-1 — Tune the device-verify banner placement.** Copy is refined; what's left is where the banner sits relative to the verify CTA on `VerifyEmailScreen` — eyeball the spacing/position against the screen and adjust.

  **Hints:** Verify surface is `features/onboarding/impl/AuthScreens.kt` (`VerifyEmailScreen`, banner sits between body + the Check-verification button). Needs Studio to eyeball.

- `[P2]` **AUTH-7 — Move onboarding legal-link labels into `:libraries:resources` (proposed 2026-06-25).** The consent line's two tappable link labels are inline literals — `link("Terms of Service")` / `link("Privacy Policy")` in `OnboardingScreen.kt:433-434` — while the surrounding sentence is already resourced (`onboarding_welcome_consent`). Per the string-resource convention these user-facing labels belong in `strings.xml`.

  **Acceptance:** Both labels read via `stringResource`; the link text still matches the substrings inside `onboarding_welcome_consent` so the clickable spans line up.

  **Hints:** Add two entries next to `onboarding_welcome_consent`; the `link(...)` calls take the resolved strings.

### Gameplay & table UX

- `[P2]` **GAME-3 — Emote button glyph isn't optically centered.** The play-poker emote trigger ([`TopBarEmojiButton`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/EmojiTray.kt) → DS [`EmojiButton`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/icon/EmojiButton.kt)) centers its circular bounding box correctly, but the emoji glyph sits slightly up-and-left inside it — the text line-box midpoint ≠ the glyph's visual midpoint.

  **Acceptance:** The glyph reads optically centered in the circle at every `Size`.

  **Hints:** The `Box`/`Text` in `EmojiButton.kt`; likely needs a glyph-vs-line-box offset, not just `Alignment.Center`. **Worker note:** needs Studio to eyeball against the size-scale `@Preview`.

---

## B. Engineering

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
