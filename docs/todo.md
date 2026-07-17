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

## Auth & onboarding (AUTH)

- `[P2]` **Formalize a single typed `AuthOutcome` from the auth layer.** Every auth path now classifies new-vs-returning off the authoritative `ProfileRepository.resolveIsNewAccount()` signal (onboarding, Home welcome, and VerifyEmail — the `walletJustCreated` proxy is fully retired). Remaining polish: return one typed `AuthOutcome` (`SignedUp`/`SignedIn`/`Linked`) from the auth layer so the three cases are a type at each call site instead of a boolean reconstructed per-call-site (`isBrandNewAccount()` + an `isAnonymous`/link check).
  **Acceptance:** `AuthRepository` sign-in/link entry points return a typed `AuthOutcome`; onboarding/verify/claim branch on it rather than recomputing new-vs-returning locally.
  **Hints:** call sites: `OnboardingViewModel.isBrandNewAccount()`, `VerifyEmailViewModel.isBrandNewAccount()`, `ClaimAccountViewModel`. Ties to AUTH-19 (stable identity). See docs/decisions.md "Deterministic auth-outcome state machine (AUTH-22)".

- `[P2]` **Linking an identity to a guest succeeds silently — no confirmation.** When an anonymous user links Google/Apple/email, the link succeeds with no feedback (OAuth/Apple just `goBack()`; email routes to VerifyEmail). Add a "your account is saved / linked" confirmation on the `Linked` outcome. Depends on AUTH-22.
  **Acceptance:** a successful anon→identity link shows a clear confirmation dialog (name/provider), for all three providers, distinct from the sign-in and sign-up paths.
  **Hints:** `ClaimAccountViewModel` / `OnboardingViewModel` link branches; reuse the `Dialog`/`WelcomeDialog` DS surface.

---

## Billing (BILL)

- `[P1]` **A StoreKit purchase made before a fresh-install identity rollover is not recovered.** The replay failure loop is fixed (a terminal `receipt_rejected` now finishes the stuck transaction so it stops shadowing new purchases), but the chips the user actually paid for are still lost: on a fresh install the anon userId AND the install_id both rotate, so the transaction's `appAccountToken` (a prior identity) matches neither the caller nor `findInstallLineage` (which keys on install_id) → `apple_account_mismatch` → the entitlement is discarded, not credited.
  **Acceptance:** a genuine paid purchase whose `appAccountToken` is a prior, unlinkable anon identity is reconciled to the account that now owns the device and the chips are granted — without reopening the "one user redeems another's receipt" hole. Needs a device-stable purchaser link that survives reinstall (the AUTH-19 identity-churn work): e.g. bind `appAccountToken`→install at purchase time, or persist a stable purchaser id the server can match a replayed receipt against.
  **Hints:** server `AppStoreReceiptValidator` account binding + `PostgresProfileRepository.findInstallLineage`; client `DefaultPurchaseChipPackUseCase.redeemOutstanding` `purchase.discarded`; ties to AUTH-19; related BILL-11/BILL-12, CARDS-96; case `docs/agent/feedback-cases/e452cfd17fe94266bd2bd5fcc730a34e.md`; Sentry CARDS-AA.
