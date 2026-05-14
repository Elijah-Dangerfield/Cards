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

## 2026-05-13 — Client/server boundary: server-first, auth is the only exception

**Decision:** The mobile client talks directly to **Supabase Auth** for the Apple/Google sign-in flow and that's it. Everything else — profile, leaderboards, room create/join, game state, chips, XP, connections, AppConfig, the future hand history and notifications register — goes through the Kotlin Ktor server. The server is the only thing that talks to Postgres.

The split, concretely:

| Concern | Path |
|---|---|
| Sign in with Apple / Google | Client → Supabase Auth (direct, via the OS OAuth flow) |
| JWT validation | Server validates the Supabase JWT on every HTTPS request and every WS connect |
| Profile read/write, leaderboards, rooms, XP, connections, app config | Client → Ktor server (HTTPS, JWT-authenticated) |
| Realtime game state during a hand | Client ↔ Ktor WebSocket (one channel per room) |
| Postgres queries | Server only, via direct DB connection with the service role key |
| Supabase Realtime | Not used in V1. Possible future use for low-stakes row subscriptions (e.g. "friend started a game") but never for in-hand game state. |

**Why server-first:**

1. **Poker forces it.** Shuffle, deal, betting validation, hand evaluation must be server-authoritative. Half the code already goes through the server — making the rest match removes the split brain.
2. **Schema changes don't break clients.** When a column is added or renamed, the server adapts the response shape; old binaries keep working. Direct-to-Supabase welds each client version to its schema version, which is painful with App Store / Play Store update lag.
3. **Business logic stays in one place.** "Award XP on hand completion" touches multiple tables and must be atomic. One Ktor transaction is bulletproof; three Supabase calls from a phone are fragile (network drops, partial writes).
4. **Anti-abuse and provably-fair primitives need server enforcement.** Rate limiting, intent nonces, the shuffle commit-reveal protocol, turn-timer enforcement — none of these can be done with RLS alone.
5. **Migration optionality.** If we ever outgrow Supabase, swapping the server's DB driver is one PR. Direct-to-Supabase means every shipped client has `supabase.co` welded in.

**Why realtime through Ktor, not Supabase Realtime:**

Supabase Realtime broadcasts row changes. The game state during a hand lives in an in-memory coroutine on the server, not in a Postgres row — persisting every state transition just to fan it out would be wasteful and would expose intermediate states (the moment hole cards are dealt, they'd briefly land in a row before any RLS could hide them). Server-driven turn timers need code, not row triggers. Ktor WebSockets give us a per-room channel where the server publishes JSON diffs when it wants to. Standard pattern.

**Supabase's role in this architecture:**

We're using Supabase for:
- Managed Postgres (hosted DB, point-in-time recovery, backups)
- Auth (JWT issuer + Apple/Google OAuth dance)
- Maybe Storage later for avatar uploads

We're not using:
- PostgREST (the auto-generated REST API)
- Supabase SDK on the server (we connect to Postgres directly)
- Realtime (we have our own WS)

This makes Supabase feel like "managed Postgres + hosted auth" rather than "all-in-one backend," which is the right framing for an app with its own game-logic server.

**How to apply:**

- When adding a new client capability, the default answer is "add a Ktor endpoint" not "query Supabase directly from the client."
- The one exception is the Sign-in-with-Apple / Google flow, which has to happen client-side because Apple/Google's OAuth UI runs on-device.
- New realtime features inside a room (emotes, chat, sit-out signals) go through the existing per-room WS channel, not a new Supabase subscription.
- Realtime features *outside* a room (notifications about friends, leaderboard ticks) can use Supabase Realtime if it's the simpler answer, but evaluate per case.

**Status:** Locked.

---

## 2026-05-13 — Two Supabase projects: dev and prod

**Decision:** Maintain two separate Supabase projects from the start:
- `cards-dev` — used by debug builds and local development. Safe to reset, seed with fake data, test migrations against.
- `cards-prod` — used by release builds (Play Store / TestFlight external / App Store). Real users, real chips.

No shared project. No staging tier in V1 (overkill at our scale).

**Why:**
- Testing schema migrations against prod is how teams lose user data.
- RLS policy changes can lock real users out — must be tested in dev first.
- "Reset the table" during development is a common need; doing it in prod is a disaster.
- Auth tokens are per-project, so dev logins don't clutter prod.
- Different rate limits, quotas, and extensions can be exercised independently.

**How to apply:**
- Provision `cards-dev` when the first server work begins (Phase 2).
- Provision `cards-prod` right before the first invite to real users (after V1 internal testing).
- The build picks the project per Android variant: debug → `supabase.dev.*`, release → `supabase.prod.*`. Extend `loadSupabaseMetadata` in `build-logic/src/main/java/com/cards/util/Versioning.kt` to read variant-specific keys.
- CI gets two pairs of GitHub secrets: `SUPABASE_DEV_PROJECT_ID` / `SUPABASE_DEV_ANON_KEY` and `SUPABASE_PROD_PROJECT_ID` / `SUPABASE_PROD_ANON_KEY`.
- Service role keys (for the Ktor server) get the same dev/prod split, stored on whatever host runs the server (Fly.io secrets, Railway env vars, etc.).

**Optional third leg:** Supabase local CLI (`supabase start`) for offline schema iteration. Worth it once we're iterating heavily on Postgres schema; not needed before then.

**Status:** Locked.

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


---

## 2026-05-14 — Training mode for new players (deferred — capturing the shape)

**Decision:** Not building training mode now. Captured here so the V1-polish session that takes it on starts with the shape already thought through.

When we revisit, the rough sketch is:

1. **Onboarding picks experience level.** First-launch flow asks "new to poker / know the basics / experienced" and toggles a `trainingMode` flag accordingly. Training mode is also toggleable later in profile settings. Default ON only for self-identified new players.

2. **Behavior heatmap on the profile.** Track per-decision tendencies (VPIP, PFR, aggression, fold-to-cbet, etc. — we already track most of these for opponent modeling on bots, just reuse the math for the human). Surface as a "your playstyle" panel on the profile: a 2D placement on aggressive↔passive × tight↔loose, or a small radar chart. Updates as the user plays.

3. **Custom tips section on profile**, generated from the heatmap. Not generic advice — specific to what they actually do. e.g. "You fold to 78% of 3-bets — try defending more with suited connectors and pairs." 3-5 tips, refreshed as their stats shift.

4. **In-game training nudges (when trainingMode = ON):** the lean version from the earlier discussion — always-visible equity %, one-line post-hand verdict, optional "?" hint button on your turn. No tooltips, no forced walkthroughs.

**Why deferred:** Phase 3 (auth) and Phase 4 (multiplayer) are bigger unlocks for user value right now. Training mode is an enhancement of bot play, and bot play is already playable. The heatmap requires persistent stats per user, which requires Phase 3 anyway — so this work naturally slots in *after* auth lands.

**Status:** Deferred. Revisit after Phase 3.


---

## 2026-05-14 — Chips, rank, XP are three separate concepts

**Decision:** Cards has three independent progression/value axes. They do not collapse into each other.

1. **Chips** — buy-in currency.
   - **Multiplayer:** persistent, "sacred" (no random refills, no daily free spins). Going broke = rate-limited recovery grant (one-shot, server-enforced) per the V1 plan's bottom-out path.
   - **Bot mode:** practice chips. Auto-rebuy to `startingStack` between hands if the seat busted (already shipped — `LocalBotsSession.lastSeatsForRotation`). No real consequence.

2. **Rank** — Elo-style skill rating, **multiplayer-only**.
   - Bots don't move rank because they're static heuristics — beating Jane 100 times says nothing about your skill vs humans.
   - Floors around 800 (real Elo behavior), can't hit zero.
   - For V1 (bots only), displayed but with a "Play multiplayer to earn rank" hint. Doesn't change.

3. **XP** — lifetime engagement counter, **both modes**.
   - Always goes up. Cannot decrease, cannot bottom out.
   - Bot games earn at **0.5×** the multiplayer rate (per the V1 plan's anti-farm rule).
   - Drives level progression / achievements / cosmetics unlocks (future).
   - This is the "I made progress" signal every session, decoupled from win/lose.

**Why:** Every successful poker app (Offsuit, PokerStars, even Zynga) separates these. Collapsing them — e.g., "rank = chips won" — creates the "I went broke, I'm starting over" experience that kills new-player retention. Three lanes means a beginner can lose chips, see XP go up, see rank stay flat, and still feel like they're moving forward.

**How to apply:**
- Treat any new feature touching one axis as not touching the others. A chip refill doesn't affect XP. An XP bonus doesn't move rank. Etc.
- When rendering profile/home: show all three, never merge into one summary metric.
- For Phase 3 persistence: the `xp_events` ledger from the V1 plan covers XP. Chips and rank go in their own server-authoritative tables.
- For V1, surface XP as a number; level/progress-bar UI lands when we have enough data to know what XP thresholds feel right.

**Status:** Locked for V1.

---

## 2026-05-14 — XP earning formula and local-only persistence (V1)

**Decision:** XP scales with **engagement intensity**, not outcome. The base formula (multiplayer rate, halved for bots) per finished hand is:

| Source | Amount | Condition |
|---|---|---|
| BASE | +10 | every finished hand (even a fold) |
| INVESTMENT | +1 per BB committed, capped at +20 | chips voluntarily put in this hand |
| SHOWDOWN | +10 | reached showdown |
| HAND_STRENGTH | (categoryOrdinal + 1) × 2 (1..20) | hand shown at showdown — winning or losing |

Bots earn 0.5× of every component (per the locked anti-farm rule). Multiplayer earns 1.0×. The `wonPot` flag is **not** an input — winning and losing the same hand at the same engagement level earn identical XP.

**Persistence in V1:** XP and lifetime hand counters live in **on-device Room tables** (`progression` singleton + `xp_events` ledger). Schema matches the eventual server `xp_events` table so Phase 3 can backfill on first login.

**Why this shape:**
- "Scale by hand strength / pot size" (per user) felt better than flat per-hand, but the engagement-intensity framing keeps the decoupling-from-outcome invariant intact.
- Hand-strength bonus at showdown rewards "showing up and showing a real hand" — naturally tracks skill and play depth without rewarding luck.
- Cap on investment (20 BB) prevents one all-in lottery hand from dwarfing a session of solid play.
- Local persistence now (vs. waiting for Phase 3) means the XP detail sheet ships with real, growing numbers; users see progress from day one. Migration to server is a one-shot import once auth lands.

**How to apply:**
- New XP sources must follow the rule: amount may depend on what the player did, never on what the opponent did or who won.
- When tuning numbers (everything in `XpCalculator.kt`), preserve order-of-magnitude — a normal hand should feel like "10-30 XP" against bots and "20-60 XP" in multiplayer.
- Level thresholds remain deferred (per the previous entry) until we have a session's worth of real XP numbers to anchor them.

**Status:** Locked for V1. Phase 3 migration will lift this to a server-authoritative `xp_events` table — the formula moves to the server unchanged.

---

## 2026-05-14 — Shop unlock gating deferred

**Decision:** The shop screen renders the live chip balance via `ChipsRepository`, but **no XP- or rank-gated items exist yet**. Locking cosmetics or features behind progression thresholds is deferred until we have:

1. A real chip economy — multiplayer win/loss deltas, a defined "going broke" recovery grant, prices that mean something.
2. Actual purchasable items (card backs, avatars, table themes — all "coming soon" today).
3. Real XP / rank data from live sessions so threshold numbers aren't pulled from thin air.

**Why:** Designing gating thresholds before the economy and inventory exist would mean retuning everything later. The infrastructure to support it is already in place — `ChipsRepository`, `ProgressionRepository`, and (future) a rank repo — so wiring an "Unlocks at XP 1,000" badge is a small additive change when we're ready.

**How to apply:**
- When adding shop items, default them to "available to all" and only introduce gating once we have at least one item we're confident shouldn't be available day-one.
- Don't sprinkle XP/rank checks into UI ad hoc — when gating ships, put it behind a single `ShopItem.unlockRequirement` field so the rule lives in one place.
- The `RankDetailSheet` and `XpDetailSheet` already promise "future updates will unlock cosmetics, titles, and achievements" — that copy is the user-facing contract for when this lands.

**Status:** Deferred. Revisit when multiplayer chip economy is live and the first sellable shop item is designed.

---

## 2026-05-14 — Known limitations after V1 achievement system

**Decision:** Three known sharp edges we intentionally shipped with the V1 achievement system. Each is small enough that fixing it can wait for the next time the area is touched, but tracking here so they don't get lost.

1. **Per-bot wins counter is liberally credited.** In a 4-seat bot table (1 human + 3 bots), a winning hand credits a +1 to the `wins_vs_bot_<name>` counter for *every* bot at the table. The natural reading of "Beat Jane 10 times" when she's one of three opponents is "you won 10 hands at a table that included Jane", which we credit; the strict reading would be "you specifically beat Jane heads-up", which we don't currently track. Tighten this when bot identity becomes first-class in the engine's per-pot attribution (likely Phase 3 alongside multiplayer's per-player Elo tracking).

2. **Mid-multiplayer-tournament criteria are not modeled.** The [`Criterion`](libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/Achievement.kt) sealed class handles per-hand counters and custom cross-hand counters, but Phase 3 multiplayer will need new criterion types for tournament-specific events (final-table appearance, bubble survival, heads-up wins). Add new `Criterion` subtypes then; the achievement engine's evaluator picks them up automatically as long as `Custom` is the only escape hatch.

3. **Achievement toasts only fire at hand-end.** Because all V1 criteria are hand-end triggered, the "Achievement unlocked" callout lives inside the showdown / bust dialogs. If a future criterion fires mid-hand (e.g. "made an aggressive bet on every street" or anything time-bounded), we'll need a separate on-table toast — the current data path goes through `recentlyEarned` in `PlayBotsState`, cleared on `AdvanceNextHand`, and only rendered by the hand-end dialogs.

**How to apply:** Don't preemptively fix any of these — they're sharp but cheap to live with. When you next touch the relevant area for an unrelated reason, pull the corresponding fix in.

**Status:** Tracked.


