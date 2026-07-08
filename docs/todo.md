# TODO

**Last reviewed:** 2026-07-04 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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



## PROG

- `[P2]` **PROG-10 — Stabilize the earned-achievements horizontal pager sizing.** (owner request 2026-07-08) The horizontally scrollable achievements shown after bot games is jumpy while scrolling because the containers differ in size — make them a uniform size or animate the container size change.
  **Acceptance:** swiping across earned achievements doesn't jump or abruptly resize between pages.
  **Hints:** likely `features/room/impl/.../ui/AchievementCelebrationSheet.kt` (HorizontalPager over `earned`); https://elijah-dangerfield.sentry.io/issues/CARDS-8T

## AUTH

- `[P1]` **AUTH-17 — Fix the onboarding header/step chip on the Google sign-in path.** (from feedback 2026-07-08) Onboarding via Google returns to a broken PickIdentity header: the "step N of N" chip floats on top of the content instead of stacking in the column, and the "This is you" title is missing; the guest/email path renders fine. Repro: delete account → onboard → continue with Google.
  **Acceptance:** post-OAuth onboarding renders identically to the guest path (title visible, step chip in its normal position).
  **Hints:** `features/onboarding/impl/.../OnboardingScreen.kt` (`StepProgressChip` overlay ~L218, `PickIdentityStep`), string `onboarding_identity_title`; case `docs/agent/feedback-cases/cdcfbae0290e471a8bde94cc5b58dc1f.md`; https://elijah-dangerfield.sentry.io/issues/CARDS-8R (screenshot on CARDS-8Q)

## GAME

- `[P1]` **GAME-18 — Show the showdown reveal when the human busts in solo play.** (from feedback 2026-07-07) When the human's stack hits 0 at showdown against bots, `BustDialog` replaces `ShowdownDialog` entirely, so the reporter never saw the hand they lost to — the card reveal plays underneath the bust dialog's scrim.
  **Acceptance:** after busting at showdown, the player sees the board and winning/losing hole cards (reveal sequenced before the bust dialog, or the result surfaced inside it) before choosing "deal me in".
  **Hints:** `features/room/impl/.../ui/HandResultDialogs.kt` (~L239 "Shown instead of ShowdownDialog"); case `docs/agent/feedback-cases/53e19e0438c84ebda67ad88133e8f79d.md`; https://elijah-dangerfield.sentry.io/issues/CARDS-8P
