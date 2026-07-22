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

- `[P1]` **MP-37: Table wedges when leavers + a busted bot leave fewer than 2 funded seats.** The next-hand deal (`dealFundedHand`) calls `GameSession.startHand` unguarded; with one funded seat left it throws `Need at least 2 active seats with chips to start a hand`, kills the socket loop, and strands the last human on a dead table with no moves and no practice-mode transition.
  **Acceptance:** a server test (3 humans + bot → two leave, bot busts) shows the next hand boundary resolves the table — match-over/settlement or rebuy/bot-refill per the practice rules — instead of throwing; the remaining client always has an actionable state.
  **Hints:** `RoomSocketRoutes.dealFundedHand` (line ~797) → `GameSession.startHandLocked` (GameSession.kt:735); case `docs/agent/feedback-cases/ab1154ae8d64459693840214b4af9453.md`; Sentry CARDS-AM/CARDS-AN.

## Rooms (ROOM)

- `[P1]` **ROOM-21: Public find can search a band the user never chose (default band survives/reads as ~0-500).** Owner set what read as a 0-500 range but all three searches opened 1,000-chip tables — the client sent the default 500-2,000 band: the linear 100..100k slider scale crams those thumbs into the leftmost ~2% so they read as ~zero, and `remember(effectiveMax)` silently resets a dragged range to the default when the balance resolves.
  **Acceptance:** a dragged selection survives balance load (re-mapped, not reset); thumb positions, chip labels, and the searched band always agree (non-linear scale or tier-snapped presets); `PublicFindScreenLogicTest` covers the reset case.
  **Hints:** `PublicFindScreen.kt` (`remember(effectiveMax)` range state, `DEFAULT_BAND_MIN/MAX`, `buyInFor`/`fractionForBuyIn`); case `docs/agent/feedback-cases/7c68b58951cb4a92a055c99ef98be5fb.md`; Sentry CARDS-BB/CARDS-BC.

- `[P2]` **ROOM-20: Show locked felts and card backs on the create-room screen.** Owner directive: the create-a-room screen should render cosmetics the user doesn't own as locked entries; tapping one opens the same unlock dialog the profile hub uses.
  **Acceptance:** locked felt/card-back options render (visually locked) alongside owned ones on create-room; tap opens the existing profile-hub unlock dialog.
  **Hints:** create-room screen in `features/lobby` (felt/card-back pickers) + profile hub's locked-cosmetic dialog for reuse; Sentry CARDS-BD/CARDS-BE.

## Engineering (ENG)

- `[P2]` **ENG-33: Let testers attach screenshots/photos to in-app feedback.** Owner directive from the QA menu: the feedback form should offer a screenshot/photo picker so reports can carry images (Sentry attachments already ride the carrier event).
  **Acceptance:** feedback form offers attach-photo (and ideally auto-captured screenshot); attached images arrive on the Sentry carrier event.
  **Hints:** `FeedbackScreen`/`FeedbackViewModel` + `AppTelemetry.captureUserFeedback` attachment plumbing (`session-log.txt` shows the pattern); note `READ_MEDIA_IMAGES` currently not granted on Android; Sentry CARDS-BB/CARDS-BC (second ask in the report).
