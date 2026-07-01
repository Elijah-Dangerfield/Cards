# TODO

**Last reviewed:** 2026-06-30 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

**AUTH-12 [P1] — Google claim reports success but the app still says "sign in and claim your account."** A guest claimed his account with Google, the link reported success, yet the claim prompts persisted — the in-app session stayed anonymous while the identity was attached at Supabase (Sentry [CARDS-76](https://elijah-dangerfield.sentry.io/issues/CARDS-76)). Worse than cosmetic: the user believes progress is saved while the local `AuthState` is still an anon session.
- **Problem:** After a successful `linkOAuthIdentity(Google)`, `AuthState.Authenticated.isAnonymous` didn't flip to false, so every `isAnonymous`-gated surface (claim CTAs, Save-your-progress) still shows the claim prompt.
- **Acceptance:** Completing a Google link flips the observed `AuthState` to non-anonymous and clears all claim CTAs, no app restart. Reproduce with a failing test first: guest → Google link Success → assert `observe()` emits `Authenticated(isAnonymous=false)`.
- **Hints:** `completeOAuthRedirect` (Link path) in `SupabaseAuthRepositoryImpl.kt` *already* force-`refreshSession()`s for this exact hazard, so this is a residual gap, not the base fix — check (a) a screen caching pre-link `isAnonymous` and not recomposing off the new emission, or (b) the `cards://login-callback` redirect not routing back into `completeOAuthRedirect`. Case `docs/agent/feedback-cases/483178fee6a648949011f79134b8d50f.md`.

---

## GAME. Gameplay & table UX

**GAME-11 [P2] — The in-app feedback dialog renders behind bottom sheets.** The global "sun" feedback surface is occluded when opened while a bottom sheet is up; it should sit on top of everything, including sheets and dialogs (Sentry [CARDS-6Y](https://elijah-dangerfield.sentry.io/issues/CARDS-6Y)).
- **Problem:** The feedback presenter draws at a compositing layer below Compose `ModalBottomSheet`, so a sheet occludes it.
- **Acceptance:** Opening feedback with a bottom sheet (or dialog) already visible shows the feedback surface on top of it.
- **Hints:** App-root overlay/presenter layering — `libraries/ui/.../snackbar/SnackbarHost` and the `Screen`/root overlay wiring in `libraries/ui`, plus the `FeedbackRoute` presentation. The reporter's "global setting" instinct is right: present it at the top-most app overlay. Case `docs/agent/feedback-cases/4e58157fc813433a9b84edda1ff2ad5c.md`.

---

## SHOP. Consumables & rewards

**SHOP-6 [P2] — Cosmetic horizontal rows (emotes, felts) start flush with the screen edge; give them the card-back row's start padding (owner directive).** The felt and emote rows begin at the screen edge, while the card-back row's tiles line up under the section header. Match the card-back treatment for emotes, felts, and the other horizontal rows — a start padding — while keeping the edge-to-edge scroll (tiles still scroll off to the edge) (Sentry [CARDS-6T](https://elijah-dangerfield.sentry.io/issues/CARDS-6T)).
- **Acceptance:** Emote/felt/other cosmetic rows start-align with the section header like the card-back row, and still scroll edge-to-edge.
- **Hints:** `EdgeToEdgeRow` (`libraries/ui/.../components/EdgeToEdgeRow.kt`) content-vs-first-item padding; the shop/profile cosmetic shelves in `ShopComponents.kt` / `ShopScreen.kt` / `ProfileScreen.kt`. Owner directive, no case file.

**SHOP-8 [P2] — Cosmetic detail bottom sheets should adopt the bigger, "bubbly" achievement-sheet style; also unslop backend cosmetic strings (owner directive).** The bottom sheets for belts, card backs, and similar cosmetics should match the larger bubbly UI of the achievement-tap sheet. Separately, run the unslop-text pass over the backend/Supabase cosmetic strings — an em-dash is showing through in the copy (Sentry [CARDS-70](https://elijah-dangerfield.sentry.io/issues/CARDS-70)).
- **Acceptance:** Cosmetic detail sheets use the achievement-sheet visual treatment; Supabase-served cosmetic strings are cleaned of em-dashes / slop.
- **Hints:** Restyle `features/profile/impl/.../items/CosmeticDetailSheet.kt` to match the achievement-tap sheet treatment; the string cleanup is a content pass on the Supabase-served cosmetic copy (run `unslop-text`). Owner directive, no case file.
