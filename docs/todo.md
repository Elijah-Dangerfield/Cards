# TODO

**Last reviewed:** 2026-06-27 (feedback triage, round 3) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

---

## B. Auth & onboarding

_No open engineering items. (AUTH-9 — the Google browser-OAuth redesign to suspend-until-redirect, with link ≠ sign-in — shipped 2026-06-27, see [decisions.md](./decisions.md). End-to-end device QA + the Supabase dashboard config it depends on remain in [developer-todo.md](./developer-todo.md).)_

_Other follow-ups live in [developer-todo.md](./developer-todo.md); deferred ideas in [backlog.md](./backlog.md)._

---

## G. Billing & IAP

Native IAP (Play Billing + StoreKit 2 + own server validation — no RevenueCat). The `BillingClient` abstraction, fake/dev clients, server wallet ledger, idempotent grant, the server-authoritative redeem endpoint with the real Apple + Google receipt validators, and the client-side validate->grant->reflect flow (behind `billing.realPurchasesEnabled`) already exist; these items fill the remaining gap — the real platform store clients. Live store testing is developer-gated on credentials/listings — those gates live in [developer-todo.md](./developer-todo.md); the code is buildable and unit/locally-testable now.

- `[P1]` **BILL-4 — iOS `StoreKitBillingClient`.** `libraries/billing/impl/src/iosMain` is empty; iOS release builds have no IAP. The shared `BillingClient.consume()` consumable primitive already exists (added with BILL-3) and the chip-pack use case routes through it.
  **Acceptance:** implements `BillingClient` with StoreKit 2 (`Product.purchase()`, `Transaction`, `transaction.finish()` for consumables — finish on `consume()`), forwarding `userId` as `appAccountToken` (a UUID — Supabase user ids already qualify). Verifiable locally via an Xcode `.storekit` test config — **no App Store Connect needed for dev iteration**.
  **Hints:** StoreKit 2's `Product.purchase()` is a Swift-only `async` API, so the impl is Swift, injected via `IosAppComponent` like `AppleSignInCoordinator` (whose Android counterpart is a no-op binding) — NOT a Kotlin/Native binding. That likely means a callback-shaped Swift-friendly protocol (mirroring `AppleSignInCoordinator.requestCredential`) rather than conforming Swift directly to the suspend/StateFlow `BillingClient`; decide whether to wrap or re-shape. Pairs with the BILL-2 Apple validator (same `appAccountToken` pin).

