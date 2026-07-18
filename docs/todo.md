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

## Auth & onboarding (AUTH)

- `[P1]` **Net-new email signup routes to "Welcome back" and traps the user; no "check your email" screen.** With email confirmation required, `signUp` returns no active session (account created, mail sent). The onboarding router reads this as a returning sign-in, lands the user on "Welcome back", and pops the back-stack so back/swipe are dead — even though the confirmation email is delivered.
  **Acceptance:** a net-new email signup with confirmation pending lands on a dedicated "check your email" screen that keeps a working back path to the landing page (not "Welcome back", not trapped); confirming the email resumes into the app (pairs with AUTH-26).
  **Hints:** onboarding router / `VerifyEmail` routing; builds on AUTH-21 (already-registered fake-success) and AUTH-22 (`resolveIsNewAccount`) which don't cover the confirmation-required net-new path. Case `docs/agent/feedback-cases/3d6fed54c9354b7da64a1c3591a8ba71.md`; Sentry CARDS-AH.
- `[P1]` **Confirm-email deep link is dropped when the app was killed mid-signup.** The pending-auth handle is in-memory only; after a process kill, relaunching via `cards://login-callback` hits `completeOAuthRedirect: no pending OAuth handle — ignoring stray redirect` and the token is never exchanged, leaving the user unauthenticated with no feedback.
  **Acceptance:** tapping the confirmation link after killing the app establishes a session (exchange the code carried in the link, or persist the pending handle across cold start) and lands the user in the app; no silent "ignoring stray redirect" dead-end.
  **Hints:** `AuthRepository.completeOAuthRedirect`; deep-link handler for `cards://login-callback`. Pairs with AUTH-25. Case `docs/agent/feedback-cases/aa56dd10495649edbb97d3108a706879.md`; Sentry CARDS-AK.
- `[P2]` **Account deletion doesn't fully clear on-device preferences.** After delete + re-onboard-as-guest, the shop's "new products" notification dot is missing because the local shop-seen flag (and other prefs) survived the delete, so the new-user dot logic reads a stale "already seen". Client log corroborates leftover local state: `Dropped 1 orphan equipment row(s): [badge_founding_member_1000]` (InventorySync) and a 5s `onboarding.completed` on the second pass.
  **Acceptance:** deleting the account clears local per-user preferences (shop-seen/unseen, inventory cache, and any other user-scoped prefs) so a fresh guest onboarding presents true new-user state, incl. the shop notification dot; add a regression test for the delete→re-onboard path.
  **Hints:** account-deletion teardown vs. the shop-seen pref store and `InventorySync` cache; ties to the onboarding "new user" dot logic. Case `docs/agent/feedback-cases/e5ff0d5d8f164eef8ef1edd60a5b838c.md`; Sentry CARDS-AF.
- `[P2]` **Return a single typed `AuthOutcome` from the auth layer.** Each call site reconstructs new-vs-returning as a boolean (`isBrandNewAccount()` + an `isAnonymous`/link check) instead of receiving a typed `SignedUp`/`SignedIn`/`Linked`.
  **Acceptance:** `AuthRepository` sign-in/link entry points return a typed `AuthOutcome`; onboarding/verify/claim branch on it rather than recomputing new-vs-returning locally.
  **Hints:** `OnboardingViewModel.isBrandNewAccount()`, `VerifyEmailViewModel.isBrandNewAccount()`, `ClaimAccountViewModel`; ties to AUTH-19. See docs/decisions.md "Deterministic auth-outcome state machine (AUTH-22)".

---

## Billing (BILL)

- `[P1]` **A StoreKit purchase made before a fresh-install identity rollover is not recovered.** On a fresh install the anon userId and install_id both rotate, so a replayed receipt's `appAccountToken` (a prior identity) matches neither the caller nor `findInstallLineage` (which keys on install_id) → `apple_account_mismatch` → the paid entitlement is discarded, not credited.
  **Acceptance:** a genuine paid purchase whose `appAccountToken` is a prior, unlinkable anon identity is reconciled to the account that now owns the device and the chips are granted — without reopening the "one user redeems another's receipt" hole. Needs a device-stable purchaser link that survives reinstall (AUTH-19 identity-churn work).
  **Hints:** server `AppStoreReceiptValidator` binding + `PostgresProfileRepository.findInstallLineage`; client `DefaultPurchaseChipPackUseCase.redeemOutstanding`; ties to AUTH-19. Case `docs/agent/feedback-cases/e452cfd17fe94266bd2bd5fcc730a34e.md`; Sentry CARDS-AA.
