# TODO

**Last reviewed:** 2026-07-13 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

- `[P1]` **BILL-11 — Redeem rejects stale receipts as `apple_account_mismatch` after an account upgrade.** StoreKit transactions minted under a pre-AUTH-19 guest id (`52f3f9c1`) carry that id as `appAccountToken`; after upgrade the caller is `6f0a900c`, so `AppStoreReceiptValidator.validate` rejects them → 400 `receipt_rejected`, purchase stranded uncredited. Fresh purchases under the current id redeem fine, so the user sees "only the medium pack works" (small `…803` / large `…555` are stale). Downstream of AUTH-19.
  **Acceptance:** a receipt whose `appAccountToken` belongs to any identity in the install's account-upgrade lineage redeems successfully (or the token is migrated/re-stamped on upgrade); the previously-stranded small/large orders credit on retry. Reproduce test-first.
  **Hints:** `apps/server/src/main/kotlin/com/cards/server/data/AppStoreReceiptValidator.kt:122`; `apps/server/.../routes/BillingRoutes.kt:120`; case `docs/agent/feedback-cases/62fc0f3d25054a34a14bb00a93c06f09.md`; https://elijah-dangerfield.sentry.io/issues/CARDS-9Y . Pairs with BILL-12.

- `[P1]` **BILL-12 — Client misclassifies a redeem 400 as transient, shows false "payment went through" + retries forever.** `BillingRepositoryImpl.redeem` posts via `authedCall` (Ktor `expectSuccess`), so a 400 throws `ClientRequestException` before the `BadRequest → RedeemOutcome.Rejected` branch runs; `Catching` swallows it and defaults to `RedeemOutcome.Unavailable`. A hard `receipt_rejected` is then surfaced as "chips on the way / retrying next launch" and the unfinished transaction is re-redeemed every launch. The `BadRequest → Rejected` branch is dead code.
  **Acceptance:** a 400 `receipt_rejected` from `/v1/billing/redeem` yields `RedeemOutcome.Rejected` (honest failure dialog, no retry loop), not `Unavailable`. Reproduce test-first with a fake 400.
  **Hints:** `libraries/billing/impl/src/commonMain/kotlin/com/cards/libraries/billing/impl/BillingRepositoryImpl.kt:59,78,88`; `DefaultPurchaseChipPackUseCase.kt:122,130`; case `docs/agent/feedback-cases/2f2ed445e0e84779b33d66bb467b4e44.md`; https://elijah-dangerfield.sentry.io/issues/CARDS-A0 . Pairs with BILL-11.

## Rooms UI (ROOM)

- `[P2]` **ROOM-18 — Add loading / failed / anti-spam states to the "add a bot" button on the add-game screen.** Owner request: tapping "add a bot" gives no pending or error feedback and can be spam-tapped, firing duplicate add-bot requests.
  **Acceptance:** the button shows a loading state while the add-bot request is in flight, disables to prevent repeat taps, and surfaces a failure state if it errors.
  **Hints:** add-bot control on the add-game / create-game screen (see `features/lobby/impl/.../PrivateCreateScreen.kt` and the room add-bots path, cf. ROOM-16); https://elijah-dangerfield.sentry.io/issues/CARDS-9W .
