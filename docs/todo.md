# TODO

**Last reviewed:** 2026-06-27 · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

**AUTH-9 — Redesign the Google browser-OAuth flow (async redirect; link ≠ sign-in)** `[P1]`
- Problem: `signInWithOAuth`/`linkOAuthIdentity` emit auth state right after the browser opens (before the redirect returns), and `completeOAuthRedirect` assumes a sign-in session lives in the redirect URL. Result on device: claiming stayed anonymous (banner persisted) and the link redirect threw `emitAuthenticatedFromGatewayLocked called without a session`. Flag `identity.googleSignInEnabled` disabled (default false) to hide the broken button.
- Acceptance: tap Google → browser opens (no emit yet); on `cards://login-callback` the session is captured and auth flips to claimed/non-anon — sign-in: parse+import the session; link/claim: refresh/upgrade the existing session (a link redirect carries no session fragment). Re-enable the flag. Device-verify sign-in + claim + cancel on Android and iOS (see developer-todo).
- Hint: supabase-kt 3.6.0; the repo drives `AuthState` via manual emits (no `sessionStatus` observer). Files: `SupabaseAuthRepositoryImpl` (signInWithOAuth/linkOAuthIdentity/completeOAuthRedirect), `RealSupabaseAuthGateway`, `App.kt` deep-link collector. Also consider the dormant native id-token path (`signInWithGoogleIdToken`) as the better-UX alternative.

_Other follow-ups live in [developer-todo.md](./developer-todo.md); deferred ideas in [backlog.md](./backlog.md)._

