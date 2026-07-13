# TODO

**Last reviewed:** 2026-07-12 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing), `ECON` (chip economy integrity).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## GAME — gameplay + table UX

**GAME-30 [P1] — Pre-action toggles (check/fold, check-any)**
- Problem: no pre-select actions exist; a player must wait for their turn to act even when their decision is already made. Standard poker QoL and a named competitor gap.
- Acceptance: on the action UI, a player can arm "Check/Fold" and "Check any" before their turn; the armed action fires automatically on turn arrival and clears if the situation changes (e.g. facing a raise cancels "check").
- Hints: action UI in [PlayerActionSheet.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/ui/PlayerActionSheet.kt) / [TableActionBar.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/ui/TableActionBar.kt).

## BILL — billing

**BILL-10 [P1] — Confirm step on the post-bust quick-buy**
- Problem: the storefront has a two-step `PurchaseConfirmSheet`, but the in-game `QuickBuyChipsSheet` (shown after a MP bust) goes straight to `purchaseChipPack(...)` — one tap closer to a real charge, at the emotionally-loaded just-busted moment. Only the OS store dialog gates it.
- Acceptance: post-bust quick-buy shows the same lightweight confirm (price + "charged via the App Store / Google Play" line) as the storefront before the purchase fires.
- Hints: [PlayPokerViewModel.kt:1019](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayPokerViewModel.kt), storefront pattern in `PurchaseConfirmSheet.kt`.

## ENG — engineering + structural

**ENG-28 [P1] — iOS crash: a TLS-incapable Ktor engine is used on a native HTTPS/WSS path**
- Problem: fatal unhandled `IllegalStateException: TLS sessions are not supported on Native platform` on iOS (Sentry [CARDS-94](https://elijah-dangerfield.sentry.io/issues/CARDS-94), 6 events, HomeRoute, develop) — an engine-less `HttpClient { }` resolves to the CIO/native engine instead of Darwin, so the first HTTPS flush on a background worker aborts the process. Not simulator- or build-type-specific.
- Acceptance: every iOS HTTP + WebSocket client uses the Darwin (NSURLSession) engine explicitly; no code path can resolve to a TLS-incapable native engine (exercise the telemetry OTLP flush + any WSS connect on iOS, assert no such crash).
- Hints: prime suspect `grafanaHttpClient()` at [GrafanaAppEvents.kt:157](../libraries/telemetry/impl/src/commonMain/kotlin/com/cards/libraries/telemetry/impl/GrafanaAppEvents.kt) (background OTLP POST); also [NetworkClientImpl.kt:56](../libraries/networking/impl/src/commonMain/kotlin/com/cards/libraries/networking/impl/NetworkClientImpl.kt); audit the iOS dependency graph for a stray `ktor-client-cio`. Case `docs/agent/feedback-cases/CARDS-94.md`.
