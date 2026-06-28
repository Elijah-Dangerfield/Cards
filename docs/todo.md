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

- `[P2]` **AUTH-10 — Welcome/landing: declutter the sign-in actions + dark social buttons.** Owner ask: on the landing page the bottom section is cramped — "Sign in" sits jammed between the Google button and the Terms-of-Service link, making it hard to tap; space the actions out, and use dark variants of the Apple/Google buttons if the SDKs allow. *(owner directive, 2026-06-28)*
  **Acceptance:** the landing page's sign-in / Google / Terms actions have clear separation (comfortable tap targets, no mis-taps); Apple + Google buttons use dark styling where the provider button supports it.
  **Hints:** the welcome/landing layout shipped in #82; CARDS-5V. Provider buttons may constrain styling — make the call and ship a slice.

_Other follow-ups live in [developer-todo.md](./developer-todo.md); deferred ideas in [backlog.md](./backlog.md). (AUTH-9 — Google browser-OAuth redesign — shipped 2026-06-27, see [decisions.md](./decisions.md).)_

---

## C. Engineering & tooling

- `[P2]` **ENG-8 — Wiretap captures the gameplay WebSocket.** Wiretap (the shake-to-open network inspector in `:libraries:networking:impl`) only captures HTTP traffic today; the multiplayer gameplay WebSocket — where the hardest MP bugs live — is invisible in the inspector. Hook Wiretap into the gameplay socket so sent/received frames are captured and browsable alongside HTTP calls. *(proposed 2026-06-28)*
  **Acceptance:** opening Wiretap during an MP game shows the gameplay WS connection with its inbound/outbound frames, plus connect / close / error events.
  **Hints:** the room gameplay socket client (`RemotePokerSessionFactory` consumes its `gameplayFrames`); Wiretap's interception lives in `:libraries:networking:impl` next to the HTTP capture. Mind the iOS noop/release split (`cards.wiretap.ios`).

---

## D. Multiplayer hardening

- `[P2]` **MP-28 — Evaluate per-hand opt-in for multiplayer tables.** Owner proposal: right now an MP hand continues no matter what you do, and a player who wants to leave is guaranteed to forfeit a posted blind. Consider requiring each player to opt in to each hand (or be auto-sat-out / booted) so leaving between hands is clean. Owner explicitly invited push-back — "if you push back I want it mentioned in the PR description." *(owner directive, 2026-06-28)*
  **Acceptance:** a design decision is made and documented (in the PR description if pushing back); if adopted, players opt in per hand and can leave between hands without forfeiting an unwanted blind.
  **Hints:** overlaps the existing sit-out / auto-fold machinery and the ROOM-4-secondary backlog item (leave before next blinds post). Pairs with MP-26's hand-boundary handling. Sentry CARDS-5X.

---

## E. Rooms & matchmaking

- `[P1]` **ROOM-11 — Joining a found public table lands on the radar/searching screen, not a lobby.** Picking a table from the matchmaking chooser and tapping Join keeps the user on the "searching" radar UI until the server deals — there's no distinct joined-table/lobby state — so it reads as "I joined a game but got dumped back into search." *(feedback 2026-06-28)*
  **Acceptance:** after joining a chosen candidate, the user sees a joined-table / pre-deal lobby (seated players, waiting-for-deal) that is visibly distinct from the still-hunting radar.
  **Hints:** `PublicSearchingViewModel.JoinCandidate` → `joinAndWatch` → `watchRoom` reuses `SearchPhase.Searching`; add a joined/lobby phase. Directional UI call — recommend + ship a slice. Case `docs/agent/feedback-cases/98a0f24a398841ceac4e8c87afee9f50.md`; Sentry CARDS-63.

- `[P1]` **ROOM-12 — Public search stops discovering new tables after it falls through to a fresh waiting table.** The candidates re-poll only runs while the chooser is showing; when the first browse is empty the VM seats the user into its own waiting table and never browses `/candidates` again. Two people who start searching seconds apart sit in two separate tables forever and never match — confirmed in telemetry (a table created mid-search was never found). *(feedback 2026-06-28)*
  **Acceptance:** two users who start searching within the window land in the same room; a table that appears after a searcher has fallen through to waiting is still discovered. Reproduce with a failing test first.
  **Hints:** `PublicSearchingViewModel.armCandidatesPoll` is gated on `SearchPhase.Choosing`; keep polling (or have the server match a later `find` into an existing waiting table) during the genuine-wait phase too. Case `docs/agent/feedback-cases/3de8930dc5aa49a2bdb3926ff014b403.md`; Sentry CARDS-5S.

- `[P2]` **ROOM-13 — Sanity-check the create-room default buy-in + blinds/stakes.** Owner review question: on the create-table screen, are the initial buy-in value and the small/big-blind + stakes defaults appropriate for a new user? Audit the defaults and adjust to sensible starting values. *(owner directive, 2026-06-28)*
  **Acceptance:** the create-room screen opens with defended, documented defaults (buy-in, blinds, stakes) that make sense for a first-time host.
  **Hints:** create-room/stake config (`StakeTier`, the create-room screen + VM). Make a recommendation and ship it. Sentry CARDS-65.

