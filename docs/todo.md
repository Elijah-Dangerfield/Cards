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

- `[P1]` **Email signup POST times out at 10s → "signup failed".** The `POST /auth/v1/signup` to Supabase hit `HttpRequestTimeoutException` (10s) and surfaced as a bare NetworkError; tester saw "signup failed? no idea why" with no retry affordance. Likely the built-in Supabase email sender being slow/rate-limited under repeated signups (custom SMTP still unconfigured — see developer-todo.md). More prominent once the `develop` "Sign up with email" landing button ships.
  **Acceptance:** a slow/timed-out signup shows a clear retryable error (not a dead "failed"), and the auth-call timeout is sized for the confirmation-email round trip (or the send is decoupled from the signup response).
  **Hints:** `HttpRequestTimeoutException` on `.../auth/v1/signup` in session `fdba0e7a`; `RealSupabaseAuthGateway.signUpWithEmail`; ties to custom-SMTP setup; case `docs/agent/feedback-cases/43b40a0c05454f01a836d82cc8eba17d.md`; Sentry CARDS-A6.

- `[P1]` **Signing up with an already-registered email flashes "check your email" for ~1s then drops into the app.** Supabase's anti-enumeration makes `signUp` on an existing email return a fake success (no clear signal), so the client shows VerifyEmail then auto-advances instead of telling the user "this email is already registered — sign in instead." Confusing, and it lands them in an ambiguous account state. The `develop` "Sign up with email" landing button makes this path far more prominent — should be fixed with/before that ships.
  **Acceptance:** an already-registered email routes to sign-in with a clear message rather than a VerifyEmail flash; the "instant confirm" case can't silently drop the user into the app.
  **Hints:** interacts with the `develop` Option-B VerifyEmail branch + the SessionExpired-on-no-session fix; `SignUpViewModel` / `VerifyEmailViewModel`; case `docs/agent/feedback-cases/55f95e228b8540b6a3f25e1539aaeccb.md`; Sentry CARDS-A8.

---

## Billing (BILL)

- `[P1]` **Unfinished StoreKit transactions replay across fresh installs and every redeem fails.** Pending orders `2000001203481803` / `...481555` (SKUs `com.cards.iap.chips.small/large`) survive reinstall and re-post to `POST /v1/billing/redeem` on launch, getting `receipt_rejected` / "Server rejected replayed receipt" against the fresh install's new anon userId → tester reports "every single purchase failing" on a fresh account. Ties to the AUTH-19 identity churn and existing BILL-11/BILL-12; the SKU-unrecognized side is CARDS-96.
  **Acceptance:** a replayed/unmatched StoreKit transaction is finished/acknowledged gracefully (no user-visible failure loop), and receipts validate against the account that actually owns the entitlement rather than whatever anon id is current on a fresh install.
  **Hints:** `PurchaseChipPackUseCase` "Server rejected replayed receipt"; server `/v1/billing/redeem` `receipt_rejected`; sessions `fdba0e7a` + `45264b61`; related BILL-11/BILL-12, CARDS-96; case `docs/agent/feedback-cases/e452cfd17fe94266bd2bd5fcc730a34e.md`; Sentry CARDS-AA.
