# TODO

**Last reviewed:** 2026-07-21 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

## Multiplayer (MP)

- `[P1]` **Matchmaking pairs two humans but the game never starts.** Two players who find a table together get stuck — one on the searching screen, the other on "dealing you in any moment now" — and the hand never begins; the search-screen title also stays "finding you a table" after a table is found.
  **Acceptance:** once matchmaking seats enough humans the first hand auto-starts for both clients and the searching UI advances to the table; a server/service test covers matched-pair → started game.
  **Hints:** matchmaking join + `GameSession` auto-start path (server logged `/v1/matchmaking/find` + repeated `/candidates` 200 with no room-start/seat for the pair); MP-34 shipped find-or-create; case `docs/agent/feedback-cases/82f7e6a8fefd4ac0bb96f859b0576366.md`; Sentry CARDS-B0 (also CARDS-AW, CARDS-AX).

## Engineering (ENG)

- `[P2]` **ENG-34: Stop reporting expected-offline network failures as Sentry errors.** A single offline device (no network route) generated 59 error-level `DarwinHttpRequestException` NSURLError -1009 events in 43 min from background sync retries, inflating the Pulse client-error panel and creating an "escalating" issue.
  **Acceptance:** connectivity-class failures (-1009 et al., plus Android UnknownHost/Connect equivalents) become breadcrumbs/info instead of Sentry error events, and offline background sync backs off; a real non-connectivity network error still reports.
  **Hints:** SentryLogTree error-capture path (same hygiene class as ENG-29's AuthUnready filter, which this build already has) + the HomeRoute `/v1/equipment/sync` retry cadence; case `docs/agent/feedback-cases/CARDS-BA.md`; Sentry https://elijah-dangerfield.sentry.io/issues/CARDS-BA
