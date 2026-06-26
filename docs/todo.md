# TODO

**Last reviewed:** 2026-06-26 (reconcile: MP-22 removed — public matchmaking shipped server + client) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

## A. UX gaps

**ROOM-4 — Finding-a-table: drop the persistent "Still looking for players" line** `[P2]`
- Problem: The finding-a-table screen keeps a permanent "Still looking for players" text view *and* the rotating status copy — two views doing one job.
- Acceptance: Open with "Looking for players", then start rotating the status messages after ~5–10s. Remove the persistent second line entirely.

**ROOM-5 — Remove the Public / Invite-only chip from finding-a-table and the private-room screen** `[P2]`
- Problem: Both screens show a top-right chip reading "Public" or "Invite only" that isn't earning its space.
- Acceptance: Remove the chip from both screens.

**ROOM-6 — Lobby: style the "You" marker as a chip** `[P2]`
- Problem: The lobby seat list renders the local player's "You" label as plain text, out of step with the rest of the row's visual language.
- Acceptance: Render "You" as a chip.

**GAME-6 — Add a game-speed setting with an "instant" option** `[P2]`
- Problem: A tester asked to be able to speed the table up — specifically an "instant" mode that skips/shortens the deal, chip, and reveal animations. There's no speed control today; everything plays at one fixed pace.
- Recommendation: Add a play-speed preference (e.g. Normal / Fast / Instant) that scales or skips the card-deal, chip-move, and result animations. "Instant" should collapse the cosmetic delays so action resolves immediately. Confirm the option set, then wire it through the play-screen animation timings.
- Hints: Owner directive, no telemetry investigation. Sentry issue https://elijah-dangerfield.sentry.io/issues/CARDS-4H (reporter SlyTen50, comment "let's add a bit speed 'instant'").

## B. Rooms & matchmaking

**ROOM-7 — Let a player invite a friend into a public game (shareable room code / link)** `[P2]`
- Problem: A player who lands in a public game has no way to pull in a friend. Open question: should public games also expose a room code so people can find them directly?
- Recommendation: Give *every* room a shareable code / invite link regardless of visibility; "public" just means it's *also* surfaced in Find a Table. The in-game share affordance then works the same in public and private rooms. Confirm the direction, then ship the share affordance + deep-link join.

## C. Content & data

**ENG-3 — Run the unslop-text pass on DB product copy, keep prod and dev in sync** `[P1]`
- Problem: Product descriptions and other user-facing text in the database read as AI-generated; prod and dev should also hold identical copy.
- Acceptance: Run the `unslop-text` skill over product descriptions and other DB text, apply the rewrites to dev, mirror the same final copy to prod so the two stay in sync.
- Hint: Treat the prod write as sensitive — review the dev diff before mirroring.

**AUTH-7 — Thorough Terms/Privacy review, finish the Cards→Dealt rename, refresh the logo** `[P1]`
- Problem: [`pages/terms.html`](../pages/terms.html) + [`pages/privacy.html`](../pages/privacy.html) want a careful content review. The rename to "Dealt" is half-done — those two pages already say "Dealt", but [`pages/index.html`](../pages/index.html) still titles/headers "Cards" (title, `<h1>`, `alt`, meta description), and the `app-icon.png` / `favicon.png` / `apple-touch-icon.png` assets are the old mark.
- Acceptance: Review + update both legal docs; finish Cards→Dealt across everything under `pages/`; swap the logo/icon assets to the Dealt mark.
- Hint: A material content change should bump [`LegalUrls.LEGAL_VERSION`](../libraries/core/src/commonMain/kotlin/com/cards/libraries/core/LegalUrls.kt) (currently `1`) so the planned "Terms changed, re-accept" gate fires.

## D. Config admin

**ENG-4 — Config admin: targeting rules error out and don't render after adding** `[P1]`
- Problem: Adding a targeting rule can fail server-side (400 `invalid_value` when the rule's value doesn't match the flag's declared type, or 409 `unknown_flag`), and the failure path in [`Support.kt`](../apps/admin/src/jsMain/kotlin/com/dangerfield/cards/admin/Support.kt) (`launchOp` `onFailure`) sets an error banner but skips `reload()` — so the rule list never refreshes and the rule looks like it didn't render. Worse, "+ Add rule" silently writes a DB base override (`upsertFlag(seed)`) *before* the rule write ([`RuleEditor.kt:166-174`](../apps/admin/src/jsMain/kotlin/com/dangerfield/cards/admin/RuleEditor.kt)), so a failed rule-add can still leave a base override the operator never intended.
- Acceptance: Surface the real server message, reload the flag on success *and* failure so state never goes stale, and stop writing a base override as a side effect of adding a rule. Reproduce the error + no-render with a failing test first.
- Hint: Server rule write, FK check, 409 path at [`ConfigAdminRoutes.kt:98-127`](../apps/server/src/main/kotlin/com/cards/server/routes/ConfigAdminRoutes.kt); rules render from `row.rules` in `FlagsView`.

**ENG-5 — Config admin: stop presenting the shipped default as a freely-editable "base value"** `[P1]`
- Problem: The site shows three layers (in-code default → DB base value → resolved) but makes editing the "base value" a primary action and even mints one behind your back (see ENG-4). Owner's read: the app *ships* the base/default; the site should mainly do *targeting*, not silently own the base.
- Recommendation: Keep the server-override capability — changing a default without an app release is genuinely useful (remote retunes) — but reframe the UX: render the shipped in-code default **read-only** ("what the app ships with"), relabel the editable layer as an explicit, optional **"global override (all targets)"**, and let a flag carry targeting rules without first minting a base override (server lazily creates the flag row from the manifest default, or the FK is relaxed). Make each flag clearly render its rules once added.
- Hint: Layers + base editor at [`FlagsView.kt:128-159`](../apps/admin/src/jsMain/kotlin/com/dangerfield/cards/admin/FlagsView.kt); resolve order `rule → base → in-code default` in the `/resolve` endpoint of [`ConfigAdminRoutes.kt`](../apps/server/src/main/kotlin/com/cards/server/routes/ConfigAdminRoutes.kt).

