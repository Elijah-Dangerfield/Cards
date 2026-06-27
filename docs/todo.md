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

_No open engineering items. (AUTH-9 — the Google browser-OAuth redesign to suspend-until-redirect, with link ≠ sign-in — shipped 2026-06-27, see [decisions.md](./decisions.md). End-to-end device QA + the Supabase dashboard config it depends on remain in [developer-todo.md](./developer-todo.md).)_

_Other follow-ups live in [developer-todo.md](./developer-todo.md); deferred ideas in [backlog.md](./backlog.md)._

---

## C. Gameplay & table

- `[P1]` **GAME-7 — Solo hole cards don't render; player sees one face-down card.** A brand-new user's first PlayBots hand showed a single face-down card instead of their two face-up hole cards; the engine had dealt them (the user could Call), so it's a render/flip defect, not a deal failure. Bad first impression — new users can't see their hand.
  **Acceptance:** a failing test reproduces "first solo hand → both hole cards render face-up" (covers the first-emission/flip path); fix makes both cards show. Verify on a fresh install's first PlayBots hand.
  **Hints:** client-only — `LocalBotsSession`/`GameEngine` deal → `TableUiState` projection → `PlayingCard`/hole-card flip in `:features:room:impl`; suspect a first-hand initial-emission race in the flip animation. **Telemetry gap:** the session log is engine-level only — there was no view-side line to confirm what the table actually rendered, so the render state couldn't be read from telemetry. Add one (per AGENTS.md): a once-per-hand log of the hole-card projection (e.g. dealt-cards count vs face-up-rendered count for the human seat) — not per-frame. Case `docs/agent/feedback-cases/2e3eea34a7fb42a3996498a3833031d2.md`; Sentry [CARDS-53](https://elijah-dangerfield.sentry.io/issues/CARDS-53).

## D. Progression & rewards

- `[P1]` **PROG-3 — Level-up (and achievement) celebration doesn't show after a hand.** A solo hand ended, `LevelUpRewardGranter` granted the level-3 reward and two achievements (logged), but no celebration screen ever presented — the user dropped straight back to Home. The grant is correct (no data loss); the fanfare is silently dropped. Reported as "randomly not seeing the level up screens anymore," so it's intermittent.
  **Acceptance:** a failing test reproduces "hand-end level-up → celebration route enqueued"; fix makes it present reliably. Achievement-earn celebration covered by the same path if it shares the trigger.
  **Hints:** client-only — `LevelUpRewardGranter` fires the grant; the celebration presentation (queued event / pendingProfileHighlight on return to Home, or a play-screen trigger) is what's not firing. Suspect the hand-end → `go back`-to-Home transition racing past the highlight, or it being cleared before shown. **Telemetry gap:** the log shows the grant (`Granted 1 reward(s) for level 3`) but nothing about whether the celebration was enqueued, shown, or skipped — so the failure point was invisible. Add one (per AGENTS.md): a single line at the celebration-presentation decision (`level-up celebration enqueued` vs `skipped because <reason>`) — once per level-up, not per emission. Case `docs/agent/feedback-cases/7c4909e461ec425dac6fece2c9fbb2d4.md`; Sentry [CARDS-4V](https://elijah-dangerfield.sentry.io/issues/CARDS-4V).

## E. Multiplayer

- `[P1]` **MP-22 — Don't make the player tap "Next hand" into a refusal toast; gate the button on the table actually being ready.** In heads-up-vs-bot, the user backgrounded the app; their socket flapped and they tapped a stale-snapshot "Next hand," so the server rejected with `error=current hand not complete`. The client collapses *all* next-hand/action refusals into `PlayPokerEvent.NextHandUnavailable` → `room_next_hand_unavailable` ("Waiting for your opponent to rebuy or leave") — wrong copy (both stacks healthy, nobody busted) and the user tapped into a dead no-op until they left. Two things are off: the misleading mapping, and the UX of offering a button that can't work yet.
  **Better UX (owner direction):** don't render "Next hand" while the table can't deal one. When continuation is genuinely blocked (heads-up opponent at 0, deciding to rebuy or leave), show the *waiting* player the same rebuy-grace countdown the busted player sees — "opponent deciding…", same timer — so the state is legible instead of tap-to-find-out. Reveal "Next hand" only once continuation is actually possible. A transient `current hand not complete` (hand still resolving / stale snapshot after reconnect) is not that state — it should resync (re-request the snapshot), never surface the opponent-rebuy copy.
  **Acceptance:** the winner sees no tappable "Next hand" while the table can't deal — they see the shared rebuy-grace countdown; the button appears when continuation is possible. A `current hand not complete` refusal triggers a snapshot re-request, not the rebuy toast; the rebuy copy fires only when the server reports the opponent busted with no rebuy. Failing test pins both the button gating and the refusal→event mapping.
  **Hints:** `PlayPokerContract.kt` (`NextHandUnavailable`, the MP-14 rebuy-grace countdown fields, `IntentFeedbackKind`), the refusal→event mapping in `PlayPokerViewModel` / `RemotePokerSession`, the table action bar's next-hand button gating, string `room_next_hand_unavailable`. **Telemetry gap:** the client logs the server's `intent_ack accepted=false error=…` but never which user-facing event/toast it surfaced — so the wrong-copy mapping was invisible from the log alone. Add one (per AGENTS.md): a single line where a refusal maps to a `PlayPokerEvent` (`refusal <reason> → <event>`), once per refusal, not in a retry loop. Case `docs/agent/feedback-cases/c1849e7e44664723b0bfb7452a28f7c3.md`; Sentry [CARDS-4X](https://elijah-dangerfield.sentry.io/issues/CARDS-4X).

## F. Shop & cosmetics

- `[P2]` **SHOP-2 — Remove the fake "50% off" tag on the Sunset felt.** The Sunset felt is flagged 50% off in the shop (dev, and probably prod config) but isn't actually discounted — it was a placeholder example. Owner directive: drop the tag.
  **Acceptance:** the Sunset felt shows no sale/discount tag; no other item gains a stray one. Check both the dev and prod app-config / product catalog so the tag is gone everywhere it's seeded.
  **Hints:** owner directive (Sentry [CARDS-4N](https://elijah-dangerfield.sentry.io/issues/CARDS-4N)) — find where the discount/sale tag is set for the sunset felt product (app-config or the products catalog seed) and remove it.

- `[P2]` **SHOP-3 — Host-chosen felt + card backs, shown to every player at the table.** Owner directive: let the game creator pick the felt and card backs from their inventory when creating a room, and have *every* player at the table see the host's chosen felt and card backs (incentivizes buying cosmetics). The host's selection already exists per-player; this makes it table-wide.
  **Acceptance:** create-room flow lets the host pick an owned felt + card back; the room snapshot carries them; all clients render the host's felt and card backs in-game. Ship a slice + a directional call on edge cases (host has none equipped → table default; whether a player's own equipped back still applies to their own cards) and let the reviewer course-correct.
  **Hints:** plumbing mirrors the "Player Card — Phase 2: opponent cosmetics over the wire" backlog item — put the host's equipped felt/back on the room/seat snapshot and read it at the play surface instead of `LocalCurrentFelt`/local equip. Owner directive, Sentry [CARDS-4Q](https://elijah-dangerfield.sentry.io/issues/CARDS-4Q).

## G. Rooms & matchmaking

- `[P2]` **ROOM-8 — Soften the "real players" emphasis in the matchmaking search copy; rotate more lines, hold each longer.** Owner directive: the rotating reassurance while searching leans on "real" too hard — it reads as protesting-too-much (`public_searching_rotate_2` = "Every seat at your table is a real person."). Keep the honest framing but stop repeating "real" in every line, cycle through a few more variants, and hold each longer so it feels calm rather than nagging.
  **Acceptance:** `public_searching_rotate_1` drops "real" ("Looking for players at your buy-in…"); the set no longer says "real" in most lines (at most one understated mention); there are a few calm variants; each holds noticeably longer. No claim that any seat is a bot or a person beyond what's honest.
  **Hints:** strings `public_searching_rotate_1..4` (+ the XML comment on line 282) in `libraries/resources/.../strings.xml`; the rotation list + `ROTATE_INTERVAL_MS = 5_000L` (bump it) in [`PublicSearchingScreen.kt:250`](features/rooms/impl/src/commonMain/kotlin/com/cards/features/rooms/impl/PublicSearchingScreen.kt). Owner directive (in-session 2026-06-27), no Sentry issue.

- `[P2]` **ROOM-9 — Rework the "We couldn't find anyone right now" matchmaking UI.** Owner directive: the offer state (when no table is found, pitching "play bots for real chips while you wait") feels off — the banner reads weird and isn't what he'd expect. Needs a design pass, not a copy tweak.
  **Acceptance:** the no-results state is reworked into a treatment that reads naturally (not a weird banner) while keeping the honest "bots step aside when a real player joins" + daily-subsidy disclosure intact. Make a directional design recommendation, ship a slice, let the reviewer/owner course-correct.
  **Hints:** the offer state in [`PublicSearchingScreen.kt`](features/rooms/impl/src/commonMain/kotlin/com/cards/features/rooms/impl/PublicSearchingScreen.kt) (strings `public_searching_offer_*`); keep the subsidy-remaining/exhausted disclosures. Owner directive (in-session 2026-06-27), no Sentry issue; pairs with the approved public-matchmaking build.

