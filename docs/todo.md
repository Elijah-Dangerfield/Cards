# TODO

**Last reviewed:** 2026-07-18 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing), `ECON` (chip economy integrity), `MOD` (trust & safety / moderation), `SITE` (marketing / support static pages).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## Billing (BILL)

- `[P1]` **A StoreKit purchase made before a fresh-install identity rollover is not recovered.** On a fresh install the anon userId and install_id both rotate, so a replayed receipt's `appAccountToken` (a prior identity) matches neither the caller nor `findInstallLineage` (which keys on install_id) → `apple_account_mismatch` → the paid entitlement is discarded, not credited.
  **Acceptance:** a genuine paid purchase whose `appAccountToken` is a prior, unlinkable anon identity is reconciled to the account that now owns the device and the chips are granted — without reopening the "one user redeems another's receipt" hole. Needs a device-stable purchaser link that survives reinstall (AUTH-19 identity-churn work).
  **Hints:** server `AppStoreReceiptValidator` binding + `PostgresProfileRepository.findInstallLineage`; client `DefaultPurchaseChipPackUseCase.redeemOutstanding`; ties to AUTH-19. Case `docs/agent/feedback-cases/e452cfd17fe94266bd2bd5fcc730a34e.md`; Sentry CARDS-AA.

## Shop (SHOP)

- `[P2]` **SHOP-11 — Shop is empty on debug / sideloaded builds (dev-experience, not a product bug).** Owner confirmed the empty shop was a debug build. Root cause: `billing.realPurchasesEnabled` defaults to `true`, so a sideloaded debug build queries the real Play catalog (unprovisioned for that build) and gets nothing. The `FakeBillingClient` + `DEV_FAKE_CATALOG` exist precisely for this but aren't the default.
  **Acceptance:** a debug / sideloaded build shows the chip packs out of the box (fake catalog, local credit) without hand-toggling a QA flag, so the shop is testable and screenshottable off-store. E.g. default `billing.realPurchasesEnabled` to `false` for debug builds. Do not change release behavior.
  **Hints:** `RealPurchasesEnabled` (`billing.realPurchasesEnabled`, default true) in `libraries/billing`; the `delegate()` gate in `PlayBillingClient`/`StoreKitBillingClient`; `FakeBillingClient(DEV_FAKE_CATALOG)`.

## Engineering (ENG)

- `[P1]` **ENG-31 — Android edge-swipe back does nothing (can't swipe in from the right edge).** Owner-reported: the Android back gesture from the screen edge doesn't navigate back, leaving users stuck without a gesture back.
  **Acceptance:** an edge swipe navigates back consistently across the app on Android gesture-nav devices; predictive-back behaves if enabled. Confirm on a gesture-nav device.
  **Hints:** app-level Android back / edge-to-edge + predictive-back config in the compose app entry, and per-screen `BackHandler` usage that may be swallowing the gesture. Owner-reported.

## Multiplayer (MP)

- `[P1]` **MP-33 — Opening player stats mid-hand and returning resets the turn timer.** Owner-reported: as a player in a live MP game, tapping stats navigates away, and coming back appears to restart the current hand's timer instead of resuming it.
  **Acceptance:** navigating to stats and back during a live MP hand leaves the server-authoritative turn timer running from where it was; the client re-subscribes to the live deadline rather than restarting a local countdown. Reproduce with a scenario test first.
  **Hints:** the play-screen timer is server-held — screen re-entry likely re-inits a local countdown instead of reading the running deadline. Play-screen timer subscription + nav re-composition. Owner-reported.

- `[P1]` **MP-34 — Two players searching for a public table at the same time don't match each other.** Owner-reported: simultaneous "find a table" searches fail to pair, so both sit waiting. Pairing IS built and atomic (`findOrJoinPublic`), so the likely cause is buy-in fragmentation: two searchers whose ranges snap to different canonical tiers each create their own room by design, never sharing one.
  **Acceptance:** under low liquidity, two humans who both hit "find a table" reliably end up at the same table even when their buy-in ranges don't exactly line up. Loosen pairing when few real tables exist (widen tolerance / collapse to fewer canonical tiers / make the lone-searcher consolidation poll tier-tolerant) without letting wildly mismatched stakes merge. Reproduce with a two-client integration test first.
  **Hints:** `InMemoryRoomService.findOrJoinPublic` + `matchmakingCandidates`, `BuyInTier.within` (canonical tiers 1k/5k/25k/100k), and the client's `armWaitingCandidatesPoll`/`migrationTarget` consolidation in `PublicSearchingViewModel`. See `MatchmakingGapsTest.searchersAtDifferentTiers_getSeparateTables` (current intended behavior we'd be relaxing). Owner-reported.

## Trust & safety (MOD)

- `[P2]` **MOD-2 — Reporting needs a bottom sheet with a reason picker plus details.** Owner-requested: when reporting a user, show a bottom sheet where the reporter classifies what happened (multi-select reason list) with an optional free-text field, so reports are easy to triage and classify.
  **Acceptance:** the report flow opens a DS bottom sheet with a multi-select reason list and an additional text input; selected reasons + text submit with the report and are stored so reports can be filtered by category.
  **Hints:** existing report entry point (see MOD-1); add a design-system bottom sheet. Owner-requested feature.
