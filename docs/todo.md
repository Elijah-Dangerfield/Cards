# TODO

**Last reviewed:** 2026-07-01 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

- `[P1]` **PROG-8 — Make the level-up celebration fire exactly once and stay long enough to see.** Owner still gets no full-screen celebration on some level-ups (CARDS-7K) and saw the level-5 one play twice (CARDS-7N); CARDS-86 breadcrumbs show the notification emitted twice for the same level and `LevelUpRoute` navigated then immediately popped.
  **Acceptance:** every earned level shows its celebration exactly once, not auto-dismissed early; enqueue/consume logged at Info so future misses are diagnosable.
  **Hints:** `HomeViewModel` level-up notification → `DelegatingRouter` LevelUpRoute enqueue/go-back; `LevelUpRewardGranter`; supersedes retired PROG-3/PROG-5; case `docs/agent/feedback-cases/f5eb19e9da6c4d129454a5751c682509.md`; https://elijah-dangerfield.sentry.io/issues/CARDS-7J

- `[P2]` **ROOM-14 — Rework the create-room screen layout.** The card-back preview isn't centered in the selected option and the tiles read over-justified/clunky; owner wants the minimalist-bubbly "Duolingo but black/gold/white" feel (CARDS-7F).
  **Acceptance:** create-room option tiles center their preview with balanced DS spacing — no clipped or justified-edge look.
  **Hints:** create-room screen in `:features:rooms`; owner directive; https://elijah-dangerfield.sentry.io/issues/CARDS-7E

- `[P2]` **GAME-13 — Make the opponent player-card sheet scrollable.** The sheet that opens when you tap an opponent can't scroll, so taller content is cut off (CARDS-7H).
  **Acceptance:** the tapped-opponent sheet scrolls when its content exceeds the sheet height.
  **Hints:** `PlayerProfileSheet` / player-card sheet in the play feature; owner directive; https://elijah-dangerfield.sentry.io/issues/CARDS-7G

- `[P2]` **GAME-14 — Customize the feedback-sent confirmation text.** After submitting in-app feedback the snackbar says "Got it, thank you", which reads flat (CARDS-7Z).
  **Acceptance:** the feedback-sent confirmation uses warmer, on-brand copy.
  **Hints:** feedback submit snackbar (FeedbackRoute / feedback feature); owner directive; https://elijah-dangerfield.sentry.io/issues/CARDS-7X

- `[P2]` **GAME-15 — Speed up the per-turn hand timer.** Owner feels the turn timer runs too long and wants it quicker, toward a poker-standard duration (CARDS-85).
  **Acceptance:** turn-timer duration reduced to a sensible standard (make the call, note the value in the PR); applies to solo + MP.
  **Hints:** turn/hand timer config in the play/session layer; owner directive; https://elijah-dangerfield.sentry.io/issues/CARDS-84

- `[P2]` **GAME-16 — Animate the emote-options tray appearance.** The emote picker options pop in with no transition (CARDS-87).
  **Acceptance:** the emote-options tray animates in (fade/scale/stagger consistent with DS motion).
  **Hints:** emote picker/tray in the play feature; owner directive; https://elijah-dangerfield.sentry.io/issues/CARDS-86

- `[P2]` **SHOP-9 — Fix card-back right-side padding in shop and profile.** Rendered card backs show uneven padding on the right in both the shop and profile (CARDS-83).
  **Acceptance:** card-back thumbnails render centered with even padding in shop + profile.
  **Hints:** shared card-back render component (`CardBackStyle` / card-back tile) used by shop + profile; https://elijah-dangerfield.sentry.io/issues/CARDS-7Y

- `[P2]` **AUTH-14 — Point users to the full tutorial from the how-to-play screen.** The how-to-play screen doesn't tell users the full tutorial lives in Settings (CARDS-82).
  **Acceptance:** the how-to-play screen surfaces a reminder/link that the full tutorial is available in Settings.
  **Hints:** how-to-play screen + settings tutorial entry; owner directive; https://elijah-dangerfield.sentry.io/issues/CARDS-7T

- `[P2]` **MP-31 — Require leave-confirmation when exiting an active real-chip MP game to Home.** Owner got into a "weird state" in a bots-for-chips MP game and could back out to Home with no confirmation (CARDS-7S); pairs with the MP freeze (MP-26).
  **Acceptance:** backing out of an active real-chip MP game prompts a leave/forfeit confirmation, including when the table is stuck/degraded.
  **Hints:** play-screen back handler + MP leave-confirm dialog; relates to MP-26 freeze; incident session not pinnable (report filed later, no room_code) — degraded correlation; https://elijah-dangerfield.sentry.io/issues/CARDS-7R
