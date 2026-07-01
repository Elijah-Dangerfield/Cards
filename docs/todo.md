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

## MP. Multiplayer hardening

**MP-29 [P0] — Leaving a table should be a synchronous cash-out, not a fire-and-forget wallet pull.** Root cause of recurring "balance still shows the buy-in gone after I leave" reports (Sentry CARDS-5R, and the CARDS-3E/3G/3W/4C/4G/58/5C cluster). MP money is server-authoritative; the client only learns the settled balance via a separate `sync()` fired on exit, which can race ahead of the server's cash-out commit — and the one-shot `walletReconciled` latch then blocks any retry, so it stays stale until the next foreground.
- **Problem:** `reconcileWalletAfterGame()` / `reconcileWalletAfterRoom()` fire a speculative `sync()` at exit; if the server hasn't committed settlement yet, the pull returns the pre-settlement balance and never retries.
- **Acceptance:** Leaving reflects the authoritative post-settlement balance without a foreground/background. Reproduce the race with a failing test first (settlement commits *after* the leave pull) — red, then green. Cover the involuntary teardown paths too (match-over / opponents-left / host-closed / kick), which currently rely on the same racy pull or, in the lobby, no reconcile at all.
- **Hints:** Preferred shape — `DELETE /v1/rooms/{code}/me` cashes out synchronously and returns the new balance in its body so the leave call *is* the reconcile (all REST, no socket). For teardown-while-connected, fold the settled balance into the terminal room frame the per-room socket already delivers (there is no global socket). Retire the single-shot latch or make it retry.

---

## GAME. Gameplay & table UX

**GAME-11 [P2] — The in-app feedback dialog renders behind bottom sheets.** The global "sun" feedback surface is occluded when opened while a bottom sheet is up; it should sit on top of everything, including sheets and dialogs (Sentry [CARDS-6Y](https://elijah-dangerfield.sentry.io/issues/CARDS-6Y)).
- **Problem:** The feedback presenter draws at a compositing layer below Compose `ModalBottomSheet`, so a sheet occludes it.
- **Acceptance:** Opening feedback with a bottom sheet (or dialog) already visible shows the feedback surface on top of it.
- **Hints:** App-root overlay/presenter layering — `libraries/ui/.../snackbar/SnackbarHost` and the `Screen`/root overlay wiring in `libraries/ui`, plus the `FeedbackRoute` presentation. The reporter's "global setting" instinct is right: present it at the top-most app overlay. Case `docs/agent/feedback-cases/4e58157fc813433a9b84edda1ff2ad5c.md`.

---

## PROG. Progression / XP / stats

**PROG-5 [P1] — Build a Home-screen notification arbiter (`GetHomeScreenNotification`) — fixes the recurring missed level-up celebration.** A solo hand ended, the level-up reward was granted, the user dropped to Home with no celebration (Sentry [CARDS-67](https://elijah-dangerfield.sentry.io/issues/CARDS-67)). Don't stack another watermark patch; the class of bug is that every "when the user lands on Home, show X" moment (welcome dialog, level-up celebration, tutorial-achievement dialog, chip-balance odometer, and the new PROG-6 play-style unlock) is gated **independently** in `HomeViewModel`, each with its own flag and its own race. The celebration loses two: seed-vs-real-crossing, and "swept away from Home before it plays."
- **Approach:** Introduce a **pure** use case `GetHomeScreenNotification(snapshot): HomeNotification?` that takes a snapshot of persisted facts (level vs `lastCelebratedLevel`, `walletJustCreated`+`didSeeGrant`, tutorial flags, play-style sample vs unlock-seen watermark, balance vs `lastShownChipBalance`) and returns the single highest-priority pending item. `HomeNotification` is a sealed type split into **blocking** (`LevelUp`, `Welcome` — full-screen, mutually exclusive, queue one at a time) and **ambient** (`ChipDelta` odometer, banners). `HomeViewModel` does `combine(sources) → GetHomeScreenNotification → state.pending`, presents **only when `homeResumed`** (the settled-on-Home signal already used by the chip odometer), and **advances the watermark/flag only after a confirmed present**. Separate "seed on fresh-account / account-switch → returns null (nothing pending)" from "should show," so no silent seed eats a real crossing. `LevelUpRewardGranter` keeps owning the exactly-once *reward*; the celebration watermark becomes purely "last level we actually *showed*."
- **Acceptance:** Reproduce the missed level-up with a failing test first (a level crossing that's pending while Home isn't settled must fire once Home settles, not be silently consumed). One arbiter picks the surface; blocking notifications never double-navigate; the odometer coordinates around a blocking celebration. Welcome + level-up + tutorial-achievement gates all route through the arbiter (delete the independent gates). Pure use case has its own VM-free test class; VM behavior slots into the existing `SEAViewModel`+Turbine+`buildVm(...)` harness in [HomeViewModelTest](../features/home/impl/src/commonTest/kotlin/com/cards/features/home/impl/HomeViewModelTest.kt).
- **Hints:** `SEAViewModel` backbone at `libraries/flowroutines/.../SEAViewModel.kt`; mirror the chip odometer's `homeResumed` gating in `HomeViewModel`; persisted watermarks live in `AppCache`. Start with an investigation subagent to map every current Home presentation gate (welcome, level-up, tutorial-achievement, chip odometer), `LevelUpRewardGranter`, and the `AppCache` watermarks before writing code. Case `docs/agent/feedback-cases/876457704d444376b9f08247ce988382.md`.

**PROG-6 [P2] — Announce play-style unlock with a global dialog (owner directive) — first new client of the PROG-5 arbiter.** After a user plays enough hands to unlock their play-style metric (~20 hands), tell them their style is now unlocked (Sentry [CARDS-6B](https://elijah-dangerfield.sentry.io/issues/CARDS-6B)).
- **Acceptance:** Crossing the play-style-unlock threshold surfaces a one-shot "your play style is unlocked" celebration, shown exactly once while the user is settled on Home.
- **Hints:** Build this as a `HomeNotification.PlayStyleUnlocked` variant + one rule in `GetHomeScreenNotification` + one persisted "unlock-seen" watermark in `AppCache` — **do not** add a fifth bespoke gate. Gate on the play-style sample count (`PlayStyleRepository` unlock threshold). Sequence after PROG-5 lands (or land them together); owner directive, no case file.

---

## SHOP. Consumables & rewards

**SHOP-6 [P2] — Cosmetic horizontal rows (emotes, felts) start flush with the screen edge; give them the card-back row's start padding (owner directive).** The felt and emote rows begin at the screen edge, while the card-back row's tiles line up under the section header. Match the card-back treatment for emotes, felts, and the other horizontal rows — a start padding — while keeping the edge-to-edge scroll (tiles still scroll off to the edge) (Sentry [CARDS-6T](https://elijah-dangerfield.sentry.io/issues/CARDS-6T)).
- **Acceptance:** Emote/felt/other cosmetic rows start-align with the section header like the card-back row, and still scroll edge-to-edge.
- **Hints:** `EdgeToEdgeRow` (`libraries/ui/.../components/EdgeToEdgeRow.kt`) content-vs-first-item padding; the shop/profile cosmetic shelves in `ShopComponents.kt` / `ShopScreen.kt` / `ProfileScreen.kt`. Owner directive, no case file.

**SHOP-8 [P2] — Cosmetic detail bottom sheets should adopt the bigger, "bubbly" achievement-sheet style; also unslop backend cosmetic strings (owner directive).** The bottom sheets for belts, card backs, and similar cosmetics should match the larger bubbly UI of the achievement-tap sheet. Separately, run the unslop-text pass over the backend/Supabase cosmetic strings — an em-dash is showing through in the copy (Sentry [CARDS-70](https://elijah-dangerfield.sentry.io/issues/CARDS-70)).
- **Acceptance:** Cosmetic detail sheets use the achievement-sheet visual treatment; Supabase-served cosmetic strings are cleaned of em-dashes / slop.
- **Hints:** Restyle `features/profile/impl/.../items/CosmeticDetailSheet.kt` to match the achievement-tap sheet treatment; the string cleanup is a content pass on the Supabase-served cosmetic copy (run `unslop-text`). Owner directive, no case file.
