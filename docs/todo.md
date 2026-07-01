# TODO

**Last reviewed:** 2026-06-28 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

_Other follow-ups live in [developer-todo.md](./developer-todo.md); deferred ideas in [backlog.md](./backlog.md). (AUTH-9 — Google browser-OAuth redesign — shipped 2026-06-27, see [decisions.md](./decisions.md).)_

---

## MP. Multiplayer hardening

**MP-29 [P0] — Leaving a table should be a synchronous cash-out, not a fire-and-forget wallet pull.** Root cause of recurring "balance still shows the buy-in gone after I leave" reports (Sentry CARDS-5R, and the CARDS-3E/3G/3W/4C/4G/58/5C cluster). MP money is server-authoritative; the client only learns the settled balance via a separate `sync()` fired on exit, which can race ahead of the server's cash-out commit — and the one-shot `walletReconciled` latch then blocks any retry, so it stays stale until the next foreground.
- **Problem:** `reconcileWalletAfterGame()` / `reconcileWalletAfterRoom()` fire a speculative `sync()` at exit; if the server hasn't committed settlement yet, the pull returns the pre-settlement balance and never retries.
- **Acceptance:** Leaving reflects the authoritative post-settlement balance without a foreground/background. Reproduce the race with a failing test first (settlement commits *after* the leave pull) — red, then green. Cover the involuntary teardown paths too (match-over / opponents-left / host-closed / kick), which currently rely on the same racy pull or, in the lobby, no reconcile at all.
- **Hints:** Preferred shape — `DELETE /v1/rooms/{code}/me` cashes out synchronously and returns the new balance in its body so the leave call *is* the reconcile (all REST, no socket). For teardown-while-connected, fold the settled balance into the terminal room frame the per-room socket already delivers (there is no global socket). Retire the single-shot latch or make it retry.

**MP-30 [P1] — Expose a wallet reconciling/loading state so a stale balance renders as "updating," not confidently-wrong.** Today `ChipsRepository.observeBalance()` is `Long?` where null only means "not hydrated"; there's no "server hasn't confirmed yet" signal, so during any post-game reconcile window the UI shows a wrong-but-confident number the user trusts (worse than a spinner).
- **Problem:** No way for Home/Shop to tell "this balance is settling" from "this balance is final."
- **Acceptance:** An `isReconciling`/`syncing` flow that's true while a post-game `sync()` (or the MP-29 leave settlement) is in flight; Home + Shop render the balance as updating during it.
- **Hints:** Complements MP-29 — MP-29 removes the race, this covers the residual window honestly. Broad MP wallet/payout test coverage (pot splits, who-gets-paid, sit-out settlement — Sentry CARDS-62) is already filed in [backlog.md](./backlog.md); don't duplicate it here.
