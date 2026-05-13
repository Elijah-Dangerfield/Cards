# Decision Log

Decisions made about Cards' product direction and architecture. Append new decisions; do not rewrite history. Each entry: date, decision, context, status.

The canonical V1 plan lives at `~/.claude/plans/this-is-going-to-vast-kahn.md` outside the repo; this log is for in-repo continuity and future sessions.

---

## 2026-05-13 — V1 product positioning

**Decision:** Cards V1 ships as a focused **Texas Hold'em poker-with-friends** app. Marketed entirely as a poker app despite the generic name; other card games are post-V1.

**Why:** Single sharp value proposition is easier to market and easier to ship correctly.

**Status:** Locked.

---

## 2026-05-13 — Server architecture: Kotlin Ktor in `:server`

**Decision:** Use the existing empty `:server` module slot for a Kotlin Ktor server. Game engine (shuffle, deal, hand evaluation, betting state machine, timers) is server-authoritative. Shared types live in a new `:libraries:gameplay` KMP module consumed by both client and server.

**Rejected alternative:** Supabase Edge Functions (Deno/TS) — would have meant duplicate hand evaluators and types across client and server. The shared-types win of Kotlin-on-both-sides outweighed the infra cost.

**Status:** Locked. Hosting target (Fly.io / Railway / Hetzner) is TBD; doesn't affect code.

---

## 2026-05-13 — Auth: anonymous-by-default with claim flow

**Decision:** New users get Supabase anonymous sign-in on first launch — no auth UI shown. They play bots and join rooms with a generated `Anon-XXXX` handle and random avatar. "Claim your account" links to Apple/Google later (Supabase Auth identity linking), preserving XP and chip balance.

**Why:** Lowest possible friction for first session. Removes the auth-screen drop-off entirely. Supabase supports this natively.

**Anti-abuse measures:**
- Anonymous users get a smaller chip grant than claimed users.
- Anonymous users are excluded from friends-only leaderboards until claimed.
- Anonymous users don't create "connections" on the receiving side.

**Status:** Locked.

---

## 2026-05-13 — AI bots: heuristic, not LLM-backed

**Decision:** Bots make decisions via heuristic rules (Chen formula preflop, equity lookup table postflop, per-personality tightness/aggression/bluff knobs) with opponent modeling (per-seat VPIP/PFR/aggression/shove-rate). No LLM calls.

**Why:** Deterministic, free, fast, testable. LLM bots would be slow, expensive, and non-reproducible in tests.

**Status:** Locked. Five named V1 personalities: Jane (tight-passive), David (loose-aggressive), Gina (tight-aggressive), Steve (loose-passive), Mike (maniac).

---

## 2026-05-13 — Bot strength target derived from competitor reviews

**Decision:** Bots must counter a naive "all-in every hand" exploit. This is the #1 complaint against our nearest competitor (Offsuit). Opponent modeling adapts calling ranges to the active player's profile; bots are not memoryless.

**Why:** Offsuit reviews repeatedly cite "I can shove every hand and win" — if we ship bots with the same flaw we'll inherit the complaint.

**Status:** Locked as a Phase-1 acceptance criterion (bots beat naive all-in-shover in test suite).

---

## 2026-05-13 — Monetization deferred

**Decision:** V1 is play-money only with no in-app purchases. No "buy chips" pack, no subscription, no ads.

**Why:** We're focused on shipping a clean product; monetization requires its own product thinking and adds App Store / Play Store review complexity. Competitor Offsuit monetizes via $19.99 chip pack and $35.99/year subscription but several reviewers cite the *absence* of microtransactions as a positive — there's room for a no-IAP V1.

**Status:** Locked for V1. Revisit after first 1k MAU.

---

## 2026-05-13 — "Sacred chips" principle

**Decision:** Going broke is a real consequence. No random refills, no daily login bonuses, no free spins. Bottom-out path: claimed users can request a one-time recovery grant if balance hits zero, server-rate-limited (e.g. once per 24h, decaying amount). Anonymous users get their initial float and that's it until they claim.

**Why:** Borrowed from Offsuit reviewer feedback ("chips feel sacred" cited as a positive). Reinforces seriousness of the game without monetization gates.

**Status:** Locked for V1.

---

## 2026-05-13 — Defensive infra ships as Phase 2 (before features)

**Decision:** Force-upgrade kill switch, remote `AppConfig`, and maintenance-mode banner are V1 foundation, built before auth or multiplayer. Implemented in `:libraries:appconfig` + `:features:upgrade` + a `GET /v1/app-config` server endpoint.

**Why:** Retrofitting a kill switch mid-incident is painful. `AppConfig.featureUnlocks` gives ad-hoc kill switches per subsystem without full feature-flagging infrastructure.

**Status:** Locked. Specifically NOT full feature flagging — no targeting, no rollouts, just named server-driven booleans.

---

## 2026-05-13 — Three distinct versioning concerns

**Decision:** Don't conflate the global force-upgrade with room compatibility:
- `AppConfig.minSupportedClientVersion` — global kill switch
- `room.schema_version` + `room.min_compatible_client_version` — per-room compatibility
- `:libraries:gameplay` constant — wire-format version bumped via `feat!:` commits

**Status:** Locked.

---

## 2026-05-13 — Tournaments deferred to V2

**Decision:** V1 ships cash games only. The "2-week race" leaderboard idea is a leaderboard, not a tournament. True tournaments (blind escalation, knockout, prize distribution, late registration) are V2.

**Why:** Tournaments add ~30% scope and many edge cases; ship cash games rock-solid first. Note: Offsuit shipped tournaments and reviewers immediately demanded "increasing blinds" — expect tournaments to be the most-requested V2 feature.

**Status:** Locked for V1.

---

## 2026-05-13 — Other V2 deferrals

- Push notifications (V1.1 candidate, additive)
- Public lobbies / friend-of-friend discovery
- Variants beyond Hold'em
- Spectator mode
- Voice / text chat (emotes only)
- Run-it-twice for all-ins

---

## 2026-05-13 — AI fairness is a perception problem, not just a math problem

**Decision:** Treat "the AI feels rigged" as a first-class V1 design problem, distinct from "the AI is actually rigged." Heuristic bots that semi-bluff or chase draws will inevitably win some runner-runner pots; users remember those hands and conclude cheating.

**V1 countermeasures:**
1. **Showdown transparency for bot games** — at end of hand, show what the bot held + its equity at each decision point.
2. **Bot-thought hand history** — replay any past hand in the session and see each bot's decision rationale per street.
3. **Provably-fair shuffle for multiplayer** — server publishes `SHA-256` commit of shuffled deck at hand start, reveals seed at showdown so anyone can verify.
4. **Three difficulty tiers for bot games** (`Casual / Standard / Challenging`) that change personality mix AND parameters (preflop aggression, semi-bluff frequency, draw-chasing conservatism).
5. **Casual-tier bots** never speculatively chase draws — only with pot-odds-positive math. Specifically reduces the "they hit the perfect river" feeling for newcomers.
6. **Opponent modeling stays opt-in by difficulty** — Casual bots don't adapt to opponents; Standard and Challenging do.

**Why:** From 16 competitor reviews surveyed, "AI cheats" appears in roughly two-thirds of the negative ones. Even mathematically-fair bots will inherit this complaint unless we proactively defuse it. Transparency turns a perceived black box into something verifiable.

**Status:** Locked for V1.

---

## 2026-05-13 — Bet input UX is V1, not V2

**Decision:** The betting UI ships with all of these together:
- Numeric bet input (typed amount) alongside a slider.
- Quick-action buttons: Fold / Check / Call / 1/2 Pot / 3/4 Pot / Pot / All In.
- The 1/2-pot math must be exactly right (compounding pot odds, not just "half of the current pot").
- Pre-actions (act-out-of-turn): pre-fold, pre-check, pre-call. Toggleable, applies on the user's next action.

**Why:** Competitor reviewers cite all of these as missing or broken. "Slider too small," "no all-in button," "1/2 pot calculates wrong," "let me fold before my turn comes around." Each is small individually; together they're the difference between "modern poker app" and "1.0 release."

**Status:** Locked for V1.

---

## 2026-05-13 — Fixed three pre-existing template bugs blocking compilation

While building out `:libraries:gameplay` and `:libraries:bots` I hit three template defects that blocked the build. Fixing them was a prerequisite to verifying any of my own code. All three are one-line fixes:

1. **`build-logic/.../ModuleBoundaries.kt`** — boundary check tripped on self-deps contributed by KSP. Skip `dep.path == path`.
2. **`libraries/networking/impl/.../NetworkClientImpl.kt:80`** — Ktor `Logger.log` returns `Unit`, but the override used a single-expression body whose inferred return type was non-Unit. Changed to a block body.
3. **`apps/compose/src/androidMain/.../AndroidActivityProvider.kt`** — `@ContributesBinding` could not infer the bound type because the class implements both `ActivityProvider` and `Application.ActivityLifecycleCallbacks`. Added explicit `boundType = ActivityProvider::class`.

**Why these existed:** the template was likely never built end-to-end after some refactor. The convention plugin would have caught (1) on any prior build attempt; (2) and (3) likely landed in unmerged-but-merged state during a rename or dep bump.

**How to apply:** next session, do a clean `./gradlew :apps:compose:assembleDebug -Dcards.skipGitHooksCheck=true` early to surface any new template defects before they get conflated with feature bugs.

**Status:** Landed. App now assembles cleanly on Android target.

---

## 2026-05-13 — Conventions: package naming, testing

**Decision:**
- New modules use the existing **`com.dangerfield.cards.<baseDir>.<moduleName>`** package namespace in source files (e.g. `package com.dangerfield.cards.libraries.gameplay`). The Android namespace in `build.gradle.kts` matches.
- **Caveat:** physical directory paths use `com/cards/<baseDir>/<moduleName>/` (mismatched with the package declarations — leftover from past renames including a prior `merizo` namespace). Kotlin tolerates this. Follow the directory pattern for new files to match the rest of the codebase. The dual-naming oddity should be cleaned up in a separate change, not as part of feature work.
- The `./scripts/create_module.main.kts` script generates the correct `com.dangerfield.cards.*` package, but matches the directories to it (which is technically more correct than the existing state). For now, new modules will mirror the prevailing convention (mismatched paths) for consistency until a unifying cleanup PR lands.
- The server module is at `:apps:server`, not `:server`. (Earlier plan entries said `:server` — that was wrong; corrected here.)
- Tests use `kotlin.test` with the project's existing KMP common test setup. No additional test frameworks added.
- `Catching {}` from `libraries/core` is used instead of `runCatching` (existing convention from AGENTS.md).
- No comments in code (existing convention from AGENTS.md). Only document the WHY of non-obvious decisions in this log or in commit messages.

**Status:** Locked.
