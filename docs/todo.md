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

## Gameplay (GAME)

- `[P2]` **The achievement / level-up review ask fires on top of the celebration sheet.** In bot mode a hand that unlocks a rare+ achievement (or levels the player up) requests the OS review prompt immediately at hand-end, so it can step on the achievement-celebration reveal.
  **Acceptance:** defer the achievement / level-up ask so it fires when the celebration sheet is dismissed, not at hand-end. When no celebration shows (silenced popups / no surfaced unlock), keep the immediate behaviour.
  **Hints:** ask is requested in `maybeRequestReviewPrompt` (`PlayPokerViewModel`); the bot-mode celebration dismisses via `AchievementCelebrationSheet` `onContinue` in `PlayPokerScreen`; a `pendingReviewTrigger` stash flushed on a new `CelebrationDismissed` action is the intended shape.

- `[P2]` **Give mid-hand joiners a real spectator view, not a blank table.** A player who joins a room mid-hand lands in a bare spectating state with no player area rendered and no explanation, so it reads as broken.
  **Acceptance:** a mid-hand joiner sees the seated players and table rendered with a clear "you'll be dealt in on the next hand" affordance until they're dealt in.
  **Hints:** play-screen spectator/seated rendering and the mid-hand join path; Sentry CARDS-B7 (owner request).

## Progression (PROG)

- `[P2]` **Surface achievements earned during an MP game when the player returns home.** Achievements unlocked mid-multiplayer-game show no celebration (the in-game reveal is suppressed for MP), so the player never learns they earned them.
  **Acceptance:** achievements earned during an MP session are detected and shown as a dismissable celebration on return home; already-seen achievements don't re-fire.
  **Hints:** achievement unlock + celebration surface (progression layer, see `docs/wiki` progression map); Sentry CARDS-B5 (owner request).

## Auth + onboarding (AUTH)

- `[P2]` **A returning guest is re-run through new-user onboarding and shown a stale level-up.** An install with an existing level-2 guest identity was classified as new (`onboarding.auth_selected returning=false`), dropped back to pick-identity, and on "continue as guest" immediately shown a level-up congrats for progression it already had.
  **Acceptance:** a returning guest skips new-user onboarding and never replays a level-up for a level already reached; new-vs-returning is driven by the server new-account signal, not defaulted to new.
  **Hints:** new-vs-returning classification on the guest path (`AuthOutcomeClassifier`, AUTH-22); level-up reveal trigger; case `docs/agent/feedback-cases/9711bcd910fa4890a148036fb6c03abc.md`; Sentry CARDS-AS.

## Multiplayer (MP)

- `[P1]` **Matchmaking pairs two humans but the game never starts.** Two players who find a table together get stuck — one on the searching screen, the other on "dealing you in any moment now" — and the hand never begins; the search-screen title also stays "finding you a table" after a table is found.
  **Acceptance:** once matchmaking seats enough humans the first hand auto-starts for both clients and the searching UI advances to the table; a server/service test covers matched-pair → started game.
  **Hints:** matchmaking join + `GameSession` auto-start path (server logged `/v1/matchmaking/find` + repeated `/candidates` 200 with no room-start/seat for the pair); MP-34 shipped find-or-create; case `docs/agent/feedback-cases/82f7e6a8fefd4ac0bb96f859b0576366.md`; Sentry CARDS-B0 (also CARDS-AW, CARDS-AX).

## Engineering (ENG)

- `[P2]` **Give Android an explicit way to open feedback on the play screen.** On Android the right-edge swipe that opens feedback collides with the system back gesture, so there's no reliable way in; iOS keeps the swipe.
  **Acceptance:** the play screen renders a visible feedback/bug affordance on Android (or a shake / global option), while iOS keeps swipe-from-right.
  **Hints:** feedback entry on the play screen; `ShakeHandler` already exists for a shake path; Sentry CARDS-B9 (also CARDS-AQ) — owner request.
