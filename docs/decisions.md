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


---

## 2026-05-18 — Identity pivot: server-managed device-keyed identity (supersedes 2026-05-13 Supabase Auth anon)

**Decision:** The mobile client never talks to Supabase Auth. Identity is owned end-to-end by the Ktor server in `:apps:server`. First launch sends a best-effort device id to `POST /v1/identity`; the server matches it (or creates a fresh identity with a random emoji + random username), persists the link, and returns a server-issued JWT pair (access + refresh). Apple/Google "claim" — added in Phase 3.1 — exchanges a native OAuth token at our server for an upgraded identity bound to the same `identities.id`.

This **supersedes** the 2026-05-13 "Auth: anonymous-by-default with claim flow" decision (which had the client talk directly to Supabase anonymous sign-in) and **amends** the 2026-05-13 "Client/server boundary: server-first, auth is the only exception" decision — there is no longer an exception. The client talks only to our server. Supabase's role narrows to "managed Postgres" (and maybe Storage later); no Supabase SDK ships in the client framework.

**Why pivot:**
1. **Custom identity logic doesn't fit Supabase Auth cleanly.** Device-keyed anonymous identity with deterministic recovery on reinstall (within best-effort device-id limits), random emoji + collision-checked username generation, anti-abuse rate limits on identity creation, and later OAuth claim that preserves XP/chips/achievements — all of this is custom server code either way. Going through Supabase first just adds a layer.
2. **One auth system to reason about.** With Supabase Auth on the client, we'd have *two* JWTs in flight (Supabase's, ours) or we'd be passing Supabase JWTs through to Ktor and validating them there — neither is simpler than just issuing our own.
3. **Migration optionality is now total.** Every shipped client holds a `cards.app`-issued JWT, never a `supabase.co` JWT. Swapping providers is a server-only change.
4. **Smaller iOS framework.** No Supabase iOS SDK in the embedded `ComposeApp.xcframework`.

**Trade-offs we accept:**
- We own JWT rotation, refresh-token rotation, and revocation. Standard but non-trivial — covered by `ktor-server-auth-jwt` + a `refresh_tokens` table.
- We don't get Supabase Auth's built-in MFA, magic links, or rate limiting. Not needed for V1; revisit if we add a sensitive surface.
- Anonymous-identity-on-reinstall is *best-effort* and platform-bounded — `Settings.Secure.ANDROID_ID` survives most uninstalls but resets on factory reset; iOS `identifierForVendor` survives uninstall only if other apps from the same vendor are installed. This matches the device-id-best-effort framing in the original spec.

**Schema (Flyway migration `V1__auth.sql`):**
- `identities (id uuid pk, created_at timestamptz, last_seen_at timestamptz)`
- `device_links (device_id text, platform text, identity_id uuid fk identities, first_seen_at timestamptz, primary key (device_id, platform))`
- `profiles (identity_id uuid pk fk identities, display_name text unique, avatar_emoji text, created_at, updated_at)`
- `refresh_tokens (token_hash text pk, identity_id uuid fk identities, expires_at timestamptz, revoked_at timestamptz)` — only hashes stored, raw token shown to client once

**Endpoints (V1):**
- `POST /v1/identity` — body `{deviceId, platform}` → `{identity, profile, accessToken, refreshToken}`. Idempotent on `(deviceId, platform)`.
- `POST /v1/auth/refresh` — body `{refreshToken}` → fresh pair, rotates refresh token.
- `GET /v1/me` — JWT-auth, returns current profile.
- `POST /v1/auth/claim` — added in Phase 3.1.

**How to apply:**
- Any new client-side data access goes through Ktor with a Bearer JWT, full stop.
- The client framework does not (and will not) include `supabase-kt` or any Supabase SDK.
- When adding a new protected route, mount it under the `authenticated { ... }` block; the JWT plugin populates `call.identityId` for handlers.
- Anonymous-vs-claimed status is recorded on the identity (claimed via `auth_identities` table when claim flow lands); anti-abuse rules from the original Auth decision (smaller chip grants, leaderboard exclusion until claimed) still apply.

**Status:** Locked. Original Supabase-Auth-anonymous decision is now historical.

---

## 2026-05-18 — Server hosting target: Fly.io

**Decision:** Production Ktor server runs on Fly.io. App-config and code-level wiring is host-agnostic (12-factor env vars) so a future move to Railway / Hetzner / Render is a redeploy, not a refactor.

**Why Fly.io:**
- Cheap-to-free at our scale and easy to scale up.
- Native IPv6 outbound — Supabase moved their direct DB host to IPv6-only on free tier and Fly speaks IPv6 natively, so the server can use the direct Supabase Postgres host without the Session Pooler hop.
- Simple secrets management (`fly secrets set`), simple deploy (`fly deploy`), simple health checks.
- Multi-region is a one-line config change if we ever need it.

**Standard env vars the server reads (12-factor):**
- `DATABASE_URL` — Postgres connection string. Production points at Supabase direct (IPv6). Local dev points at Session Pooler (IPv4-compatible) or Testcontainers (in tests).
- `JWT_SECRET` — HS256 signing secret, ≥ 64 bytes of entropy.
- `JWT_ACCESS_TTL_MINUTES` (default 15), `JWT_REFRESH_TTL_DAYS` (default 30).
- `SERVER_PORT` (default 8080).
- `LOG_LEVEL` (default `INFO`).

**Local dev:** values come from a gitignored `apps/server/.env` loaded at startup via a tiny `EnvLoader`. `.env.example` is checked in with safe defaults and the Session Pooler URL template.

**Status:** Locked for V1 production. Hosting deploy itself happens after the auth code lands and we've tested locally.

---

## 2026-05-18 — Server query layer: Exposed + Flyway + HikariCP + Testcontainers

**Decision:** The server's database layer is JetBrains Exposed (Kotlin SQL DSL) on top of HikariCP, with schema managed by Flyway migrations under `apps/server/src/main/resources/db/migration/`. Integration tests use Testcontainers + Postgres so every test runs against a real Postgres instance, matching the production engine.

**Why:**
- **Exposed** is the idiomatic Kotlin choice and keeps the server stack pure-Kotlin (matches the rest of the project). Type-safe enough without the codegen complexity of jOOQ. Backend engineers joining the team recognize it as "the Kotlin JetBrains one."
- **HikariCP** is the default connection pool in JVM-land. No alternatives worth considering at this scale.
- **Flyway** is industry-standard, file-based, deterministic. Plays well with Supabase Postgres. Avoids the "migrations live in code that runs once and you hope" footgun.
- **Testcontainers + Postgres** catches bugs that an in-memory or H2 fallback would mask — Postgres-specific syntax, types, RLS, etc. Slower than mocks but much higher signal. The project already uses real impls in tests as a convention (see `feedback_use_catching.md`'s ethos).

**Layout:**
```
apps/server/src/main/kotlin/com/dangerfield/cards/server/
  db/
    Database.kt          ← Hikari + Exposed wiring, transaction helpers
    Tables.kt            ← Exposed table definitions
    DbConfig.kt          ← parsed connection settings
  data/                  ← repository implementations (DI-bound)
  domain/                ← interfaces + value types (no Exposed types leak out)
apps/server/src/main/resources/db/migration/
  V1__auth.sql           ← initial schema
```

**Domain repositories return plain Kotlin types** — never `ResultRow` or `Op<Boolean>`. The Exposed surface stops at `data/`. This keeps the option open to swap Exposed for jOOQ or raw JDBC later without touching domain code.

**Status:** Locked.

---

## 2026-05-18 — V1 client token storage: file-backed cache, not OS-encrypted

**Decision:** The client stores its server-issued JWT access + refresh token pair in the same `:libraries:storage` file-backed cache used for `AppData` (DataStore on Android, file-backed JSON on iOS). **Not** EncryptedSharedPreferences (Android) or Keychain (iOS).

**Why this is acceptable for V1:**
- All identities in V1 are anonymous. A stolen refresh token grants access to a device-bound anonymous account with no PII, no real money, only play chips. The user's recovery path is "reinstall, get a new identity."
- The OS already sandboxes app storage. A non-rooted/jailbroken device with screen-lock is well-defended; a rooted/jailbroken device with tokens in Keychain isn't materially safer than with them in DataStore.
- Android encrypted storage is straightforward; iOS Keychain from Kotlin requires either cinterop boilerplate or a Swift Twin. Landing both alongside the rest of V1 auth wasn't worth the time at this risk level.

**When this becomes unacceptable (and the trade-off resets):**
The moment Apple/Google "claim" lands (Phase 3.1). A claimed account binds to a real human and the refresh token unlocks their persistent state across devices — at that point a leaked token has user-visible consequences.

**Upgrade path:**
- Add `androidx.security:security-crypto` to `:libraries:identity:impl` androidMain deps. Bind an `EncryptedSharedPreferencesTokenStore` with `@ContributesBinding(replaces = [TokenStoreImpl::class])` in the same source set.
- Add an iOS Keychain wrapper. Easiest route: Swift Twin (per `docs/swift-kotlin-communication-patterns.md`) — interface stays in commonMain, Swift implements it and passes it into the DI graph via `IosAppComponentFactory.create(...)`. Bind with the same `replaces` annotation in iosMain.
- The interface (`com.dangerfield.cards.libraries.identity.TokenStore`) doesn't change; only the wiring does. Existing on-device tokens get re-written into the new store on the next refresh (or first run after the upgrade).

**Status:** Accepted V1 trade-off. Bump to OS-encrypted storage before the claim flow ships.

---

## 2026-05-18 — Networking: `NoOpAuthTokenProvider` lives in the api module, not impl

**Decision:** The default no-op `AuthTokenProvider` binding lives in `:libraries:networking` (the api module), not `:libraries:networking:impl`. This is unusual — `@ContributesBinding` impls usually live in `:impl` modules.

**Why:** anvil's `replaces` annotation argument requires the replaced class to be reference-able from the replacing module. Combined with the project's strict "only `:apps:*` may depend on `*:impl`" rule (enforced by `build-logic/.../ModuleBoundaries.kt`), an auth feature's impl module (e.g. `:libraries:identity:impl`) can't reference anything in `:libraries:networking:impl`. Putting the default binding in the api module is the cleanest way to make `replaces = [NoOpAuthTokenProvider::class]` work.

**Trade-off:** the api module now has DI dependencies (`moduleConfig { di() }` in `libraries/networking/build.gradle.kts`). Acceptable — the api was a "naked Ktor wrapper" before and now becomes a "naked Ktor wrapper plus one default binding."

**How to apply:** any future "default binding that consuming impls might want to replace" should live in the api module by the same logic. Don't repeat this for *every* class — only the ones with the replacement pattern.

**Status:** Locked.

---

## 2026-05-18 — Identity pivot (REVERSED): back to Supabase Auth on the client

**This supersedes the 2026-05-18 "Identity pivot: server-managed device-keyed identity" entry above.** The earlier reversal of the original 2026-05-13 Supabase-Auth design was made on the assumption that "build claim flow ourselves" was a 2–3 day effort. On a more honest re-estimate (Sign in with Apple's email-privacy-relay handling, name-only-on-first-signin trap, server-side JWKS verification, Google Credential Manager flow on Android, account-linking edge cases), it's 5–7 days plus indefinite maintenance of edge cases.

Phase 3.1 (Apple/Google claim flow) is V1 scope per `docs/product/v1-mvp.md`, so this is a near-term cost, not a deferred one. Supabase Auth handles all of the above out of the box; `supabase-kt` (already in `libs.versions.toml`) is a first-class KMP client. The right call is to commit.

**The new shape:**

| Concern | Owner |
|---|---|
| Sign in (anonymous, Apple, Google, magic-link, etc.) | Supabase Auth, called via `supabase-kt` directly from the client |
| Token issuance + refresh | Supabase Auth (server-side, transparent to us) |
| Token storage on device | `supabase-kt`'s `SettingsSessionManager` (uses multiplatform-settings) |
| JWT validation on our server | `ktor-server-auth-jwt` configured with `SUPABASE_JWT_SECRET` (HS256) |
| Profile (display name, avatar emoji, future game state) | Our Postgres `profiles` table, FK to Supabase's `auth.users(id)` |
| Profile bootstrap on first sign-in | `GET /v1/me` is get-or-create: if no profile row, generate username + emoji and insert |
| Game logic, chips, XP, achievements, rooms | Our Ktor server (server-authoritative, unchanged) |
| Realtime game state during a hand | Our Ktor WebSockets (server-authoritative, unchanged) |

**What we throw away from the prior server-managed-identity design:**
- `JwtTokenService` + Auth0 java-jwt direct usage for minting
- `refresh_tokens` table (Supabase handles refresh)
- `identities` table (Supabase's `auth.users` replaces it)
- `device_links` table (Supabase has no device-keyed recovery; users either claim or accept the orphan-on-reinstall behavior)
- `POST /v1/identity` route (Supabase Auth replaces it)
- `POST /v1/auth/refresh` route (Supabase Auth replaces it)
- Client `IdentityAuthTokenProvider`, `TokenStoreImpl`, `IdentityApi`, `DeviceIdProvider` Kotlin Twin and its Android/iOS impls

**What we keep:**
- Postgres + Hikari + Exposed + Flyway + Testcontainers scaffolding (still valuable for our own data)
- `UsernameGenerator` + `EmojiAvatarGenerator` (called by `/v1/me` on first miss)
- `profiles` table — schema mostly unchanged; FK now points at `auth.users(id)` instead of our own `identities(id)`
- Server's Ktor structure (plugins, routes, observability, error envelope)
- `:libraries:identity` interface module (the contract stays clean; only the impl swaps)
- Onboarding feature module shell — VM now drives Supabase sign-in instead of `/v1/identity` POST
- Network client lazy-provider cycle fix (still correct for any auth backing)

**Anonymous → claim → sign-in conceptual model:**

Supabase splits these into two distinct operations and we expose both:

1. **Claim (link Apple/Google to current anonymous account):** `supabase.auth.linkIdentity(provider)`. Preserves all data (chips, XP, inventory). Fails if that OAuth identity already belongs to another `auth.users`.
2. **Sign in to existing (switch accounts):** `supabase.auth.signInWithOAuth(provider)` (or `signInWith(IDToken)` for native flows). Switches the session. **Anonymous data is orphaned** (no auto-merge) and eventually cleaned up by a TTL sweep.

V1 UX:
- Primary path: "Claim" button → `linkIdentity` → happy or "this OAuth is already on another account — sign in there? (you'll lose guest progress)" prompt.
- Secondary path: "I already have an account" → `signInWithOAuth` → explicit confirmation about losing guest data.

We do **not** build automatic account-merge logic for V1. Picking the "claim first" default for the common case is enough; users who explicitly switch accounts accept the trade-off.

**Trade-offs we accept by re-adopting Supabase:**

- Vendor lock to Supabase Auth + Postgres. Migration cost down the road = export `auth.users`, write a one-time script to map to a new identity provider, swap `supabase-kt` for whatever replaces it. ~1 week of work if we ever do it. Acceptable for V1.
- Anonymous accounts orphaned on `signInWithOAuth` to a pre-existing account. Sharp edge — V1 acceptable. Document a TTL cleanup task to delete anon-only `auth.users` >30 days inactive.
- Our server validates Supabase JWTs but doesn't talk to Supabase Admin API (yet). Future work might add admin operations (account deletion compliance, user lookup) — needs `SUPABASE_SERVICE_ROLE_KEY` server-side then.

**Required Supabase project configuration (manual steps):**
- Authentication → Settings → "Allow anonymous sign-ins" → **on**.
- Project Settings → API → record JWT secret (server `.env` → `SUPABASE_JWT_SECRET`).
- Project Settings → API → record `anon` public key (client config → `SUPABASE_ANON_KEY`).
- Phase 3.1: Authentication → Providers → enable Apple, Google with the respective OAuth credentials.

**Status:** Locked. The earlier "server-managed device-keyed identity" entry is now historical; reading the log top-to-bottom, the third entry on this topic (this one) is the live decision.

---

## 2026-05-18 — Backend stack (consolidated): Supabase + Fly + Ktor

**Recording the live backend stack after the auth flip, in one place, for new contributors.**

| Layer | Choice |
|---|---|
| Auth | Supabase Auth (anonymous sign-in V1; Apple/Google claim in Phase 3.1) |
| Database | Supabase Postgres (managed; we connect via direct IPv6 in prod, Session Pooler in local dev) |
| Application server | Ktor 3.x on JVM 17, deployed to Fly.io |
| Realtime (in-game) | Ktor WebSockets, server-authoritative (NOT Supabase Realtime) |
| Realtime (cross-app — friend signals, notifications) | Deferred to V1.x; could be Supabase Realtime or our own WS |
| Server DI | kotlin-inject + anvil (same as client) |
| Server query layer | Exposed + HikariCP + Flyway migrations |
| Server integration tests | Testcontainers + Postgres (real DB, not mocked) |
| Hosting (server) | Fly.io (`cards-server-dev`, future `cards-server`) |
| Secrets (server) | `fly secrets set ...` in prod; `apps/server/.env` (gitignored) in local dev |
| CI deploy | GitHub Actions on merge to `main` when `apps/server/**` changes |
| Crash + error reporting | Sentry (client already wired via `sentry-kmp`; server wiring pending) |
| Avatar storage (future) | Supabase Storage |

**The client/server boundary, restated:**

- Client talks to **two** services:
  1. **Supabase Auth** — for sign-in, sign-up, token refresh, account linking. Uses `supabase-kt` directly. No proxy through our server.
  2. **Our Ktor server** — for everything else (profile, game state, chips, XP, achievements, room create/join, future leaderboards). Authenticated with the Supabase-issued JWT.

- Server talks to **two** services:
  1. **Supabase Postgres** — direct JDBC, full SQL access via service-role credentials.
  2. **(Future) Supabase Admin API** — for account deletion compliance, user lookups. Only when needed.

- Supabase Realtime is **not** used for game state. In-hand state lives in a server-side coroutine and is fanned out over our own WS.

**Why this split (not "everything through our server"):**

The earlier server-first decision was driven by "we don't want client-side state of record." That still holds for profile/chips/XP/games — those go through our server. But auth JWTs are not state-of-record; they're capabilities. Letting the client talk to Supabase Auth directly is the standard pattern and saves rebuilding OAuth flows.

**Cost model at V1 scale (<100 daily users):**
- Supabase free tier: 500MB DB, 50k MAU, 5GB egress — covers V1 launch comfortably.
- Fly.io: ~$5-15/mo for the smallest shared-cpu-1x machine. Free hobby tier covers dev.
- Total cold: $5-15/mo. Total realistic V1 launch: ~$25/mo (Supabase Pro tier $25 if we hit the free-tier ceiling).

**Status:** Locked.

---

## 2026-05-18 — Delete-account flow deferred to its own chunk

**Decision:** Account deletion lands in the next focused commit, not bundled with edit-profile + sign-out. It has different infra and security requirements that benefit from a focused review.

**Why it's separate:**

1. **Server needs the Supabase service-role key.** Public anon JWTs can only manage their bearer's own data via Supabase REST + RLS. Deleting an `auth.users` row requires the Admin API (`DELETE /auth/v1/admin/users/<id>`), which is service-role-gated. Adding the service-role key means a new env var (`SUPABASE_SERVICE_ROLE_KEY`), a new `fly secrets set` step in production, and a new credential to safeguard. Worth doing carefully.
2. **Audit trail.** When a deletion happens we want to log who / when / from what source for compliance review (GDPR right-to-erasure, Apple's mandatory account-deletion review). That logging path doesn't exist yet — Sentry + structured request logs cover errors, not user actions.
3. **Two-step UX is mandatory.** Apple's review guidelines require an explicit confirm-by-typing flow for destructive actions; we'll match (type "delete" to confirm). A dedicated `DeleteAccountRoute` screen is the right home, not a dialog crammed into edit-profile.

**Shape when we build it:**

Server:
- New env var `SUPABASE_SERVICE_ROLE_KEY` (in `apps/server/.env` locally; `fly secrets set ...` in prod).
- New `SupabaseAdminClient` wrapping service-role REST calls (just `DELETE /auth/v1/admin/users/<id>` for V1).
- New `DELETE /v1/me` route:
  - Auth-required (JWT plugin); server reads `userId` from the JWT's `sub` claim.
  - `DELETE FROM profiles WHERE user_id = ?` (idempotent).
  - Calls `SupabaseAdminClient.deleteUser(userId)`. On success returns 204.
  - On admin-call failure (rate-limited, network) → 503; client retries. The profile row stays deleted (acceptable — user gets a fresh profile on next sign-in if their `auth.users` somehow persists).

Client:
- `IdentityRepository.deleteAccount(): DeleteAccountOutcome` (sealed, like the other auth outcomes).
- `ProfileApi.deleteMe()` issues `DELETE /v1/me`.
- New `DeleteAccountScreen` with type-to-confirm input + prominent destructive copy: "your chips, XP, achievements, and game history are permanently deleted."
- On success: same as sign-out path (clear local cache, reset `hasUserOnboarded`, navigate to onboarding pager).

Sharp edges to track when it lands:
- The TTL sweep for orphaned anonymous `auth.users` (separately tracked) eventually replaces the "user signed in to a different account instead of claiming" cleanup. Until then, account deletion is the only path that actually removes `auth.users` rows.
- Rate-limit `DELETE /v1/me` per-IP (e.g. 5 per hour) to prevent abuse if our JWT validation ever has a bug.

**Status:** Tracked. Pick this up after the current profile chunk lands.

---

## 2026-05-18 — Phase 3.1 backend hardening (delete, claim, sweep, rate limit, Sentry) — landed

**What landed in this pass** (one decision entry so the log doesn't fragment per piece):

1. **`is_anonymous` end-to-end.** Server reads the claim from the JWT and surfaces it on `GET /v1/me`. Client `IdentityRepository` stops hardcoding `isAnonymous = true` — the claim-account UI flips off automatically after `linkIdentity` lands.
2. **DELETE /v1/me.** `SupabaseAdminClient` calls Supabase Admin API first (revokes the JWT immediately so the user can't come back via the same token even if local cleanup fails), then deletes the local profile row. Service-role key is optional — endpoint returns 503 (`delete_not_configured`) when unset.
3. **Type-to-confirm delete UI.** App Store review explicitly requires non-trivial confirmation for destructive actions; matches on Android. Edit-profile screen was already built but not wired — that wiring landed in the same pass.
4. **OAuth claim / sign-in.** `IdentityRepository.linkOAuthIdentity(Google|Apple)` and `signInWithOAuth(Google|Apple)`. Both gated behind `IdentityFeatureConfig.{googleSignInEnabled,appleSignInEnabled}` (default false) — UI hides the buttons until the Supabase dashboard's Providers tab has credentials. Flipping the AppConfig flag turns them on without a client release.
5. **Rate limiting.** IP-based, three buckets: global 600/min, `PATCH /v1/me` 30/hr, `DELETE /v1/me` 5/hr. Per-JWT-subject would be tighter but Ktor's `RateLimit` plugin runs before auth; defer to a future revisit when abuse patterns warrant the plumbing.
6. **Sentry plumbing.** `io.sentry:sentry` on JVM, init guarded by `SENTRY_DSN` (no-op when unset). One project, two environments (`dev`/`prod`) is the recommended shape — distinct projects per env hurts cross-env grouping.
7. **Orphan anon sweep.** `POST /v1/admin/sweep-anonymous-users` gated by `X-Admin-Token`. In-process scheduling skipped because Fly's auto-stop makes background timers unreliable; DEPLOY.md walks through the GitHub Actions cron pattern.

**Status:** Landed.

---

## 2026-05-18 — App integrity attestation (Play Integrity / App Attest) — planned, not enforced

**Decision:** Ship V1 with `AppIntegrityVerifier` scaffolding bound to a no-op default — `NoOpAppIntegrityVerifier` returns `NotConfigured` so no route currently enforces it. Real verification (Google Play Integrity on Android, Apple App Attest on iOS) lands before the first invited-real-users release, not before.

**Why not enforce in V1:**
- Anonymous Supabase users have no PII, no real money, no leaderboard impact. The worst case is a scripted attacker minting throwaway profiles. The rate limiter caps the rate and the orphan sweep cleans them up after 30 days.
- Real verification requires Google Play Console + Apple Developer setup that's outside the dev environment. Wiring it now without those credentials would mean turning it off everywhere or stubbing — same effect, more friction.

**Why scaffold it now:**
- The interface defines the request shape (`X-App-Integrity-Token` header) and the outcome type (`Verified` / `Missing` / `Invalid` / `TransientFailure` / `NotConfigured`). Routes that adopt it later branch on this without restructuring.
- `NoOpAppIntegrityVerifier` is bound via `@ContributesBinding`. Real verifiers swap in with `replaces = [NoOpAppIntegrityVerifier::class]` — no cross-cutting refactor.

**When to enforce:** before public TestFlight / Play Closed Testing. The first protected surface is `/v1/me` get-or-create — that's where the Supabase anon JWT becomes load-bearing for our server. Future surfaces (room create, MP join, chip grants) follow the same pattern.

**Implementation plan (separate session):**

*Android — Play Integrity:*
1. Add `com.google.android.play:integrity` to the client (Android source set).
2. On first launch, call `IntegrityManager.requestIntegrityToken(...)` with the project's Google Cloud project number.
3. Attach the returned token as `X-App-Integrity-Token` on the first `/v1/me` call (and any other gated surfaces).
4. Server-side: create `PlayIntegrityVerifier` that decrypts + verifies the token via Google's Play Integrity API. Required server secret: a Google service account JSON with Play Integrity API scope, stored as `PLAY_INTEGRITY_CREDENTIALS_JSON`.
5. Verify: package name matches our app id, app cert hash matches the upload key fingerprint, request hash matches the request we just made.

*iOS — App Attest:*
1. Client uses `DCAppAttestService` (iOS 14+). Generate key on first launch; persist key id to UserDefaults.
2. For each protected call, generate an `assertion` over the request body hash + a nonce. Attach as `X-App-Integrity-Token`.
3. Server-side: `AppAttestVerifier` validates the assertion against Apple's PKI. The first call also includes the `attestation` (one-time per install) to register the key id; subsequent calls only send the assertion.
4. Server secret: none required — App Attest verification is offline PKI verification. The only setup is whitelisting the Cards bundle id in App Store Connect's App Attest entitlement.

*Server config (when ready):*
- `PLAY_INTEGRITY_CREDENTIALS_JSON` (multi-line env var or path to a file in Fly volumes).
- `APP_ATTEST_ENABLED` boolean — separate flag so we can ship Android-first if iOS is delayed.
- Optional `APP_INTEGRITY_ENFORCED` boolean — when true, missing/invalid tokens hard-fail (403); when false, the verifier still runs but failures only log + Sentry-breadcrumb. Useful for soft-launch.

*Rollout sequence:*
1. Ship the verifiers in soft-launch (log-only) for one release.
2. Watch Sentry for false-positive rates. Common gotchas: emulators (Android), TestFlight builds (iOS App Attest's Production vs Sandbox environment).
3. Flip `APP_INTEGRITY_ENFORCED=true` once the false-positive rate is acceptable.
4. Hard-fail `/v1/me` first-touch without a valid token.

**Sharp edges to remember:**
- Don't gate `/_health` (Fly probes), `/v1/app-config` (would brick the kill switch), or the existing rate-limit-already-protected `DELETE /v1/me`.
- Android emulators can't mint real Play Integrity tokens. Need either a debug bypass (`APP_INTEGRITY_DEBUG_BYPASS_TOKEN=<random>` that the client uses on debug builds) or a soft-launch / log-only mode while QA is on emulators.
- iOS App Attest has Sandbox vs Production environments — TestFlight is Production, Xcode local builds are Sandbox. The server has to pick the right Apple root cert chain per environment.

**Status:** Planned. Scaffolding (`AppIntegrityVerifier` + `NoOpAppIntegrityVerifier`) landed alongside this entry; enforcement and real verifiers land before first invited release.

---

## 2026-05-19 — Server-side inventory persistence

**Decision:** Inventory now persists in Postgres alongside profiles + equipment. `POST /v1/inventory/sync` is JWT-authenticated and idempotently records each pending purchase via `PostgresInventoryRepository` (first-purchase-wins on the `(user_id, product_id)` composite PK). Previously the endpoint just echoed Confirmed without storing anything.

**Why now:** Server-side ownership unlocks two things the client previously couldn't do honestly:
1. Cross-device purchase consistency — buy a card back on Android, see it on iOS after sign-in.
2. Server-side validation of avatar-pack emoji choices on `PATCH /v1/me` — the validator can resolve "does this user own avatars.fantasy?" against persisted state instead of trusting the client.

The `Reverted` branch in `SyncOutcomeDto` is still reserved for the future server-side chip ledger — once spend gets validated, an unaffordable purchase surfaces as Reverted with `chipsToRefund` and the client credits it back.

**Status:** Landed (commit `3c415cb`). V3 Flyway migration; full route + idempotency tests with a JWT-authenticated fake repo. Postgres-backed repo gets its own Testcontainers test when CI next runs.

---

## 2026-05-19 — Multiplayer foundation: rooms + WebSocket presence (Phase 4.1)

**Decision:** Ship a lobby-only multiplayer foundation in V1.x: clients can create rooms, join by 6-char code, see live presence + reconnects, and leave. Server-authoritative gameplay sync (the hand state machine over WebSocket) is Phase 4.2 — out of this slice. The reason to ship 4.1 alone is to bank the auth + transport + reconnect work against the gameplay layer later.

**Architecture:**

| Concern | Choice |
|---|---|
| Server-side room storage | In-memory `InMemoryRoomService`, mutex-guarded. No Postgres yet — rooms are ephemeral and GC when the last member leaves. |
| Room codes | 6-char unambiguous alphabet (no 0/O/1/I/L). ~32^6 ≈ 1B combos, retry on conflict up to 50 attempts. |
| HTTP routes | `POST /v1/rooms`, `GET /v1/rooms/{code}`, `POST /v1/rooms/{code}/join`, `DELETE /v1/rooms/{code}/me`. All behind Supabase JWT. Host name comes from the profile (not the body) so clients can't spoof. |
| WebSocket | `GET /v1/rooms/{code}/socket` upgrade. Same JWT auth. Membership required (must POST /join first — the socket route refuses non-members). |
| Ktor plugin | `installWebSockets()` with 15s ping / 30s timeout. Dead-peer detection is implicit; no hand-rolled heartbeat. |
| Wire format | Sealed `RoomSocketEventDto` with `type` class discriminator: `snapshot`, `member_joined`, `member_left`, `member_presence_changed`, `room_closed`. Server always sends Snapshot + delta so a client that misses one delta still recovers from the next Snapshot. |
| Reconnect | Server-side: seat is held when socket drops (`isConnected=false`); the same userId reopening another socket gets the same `seatIndex`. Client-side: exponential backoff `250ms × 2^(attempt-1)` capped at 16s, jittered ±50%. |
| Forward-compat | Server adds a new event variant → client decode fails → frame dropped with a warning; Snapshot baseline keeps state correct. |

**Why no persistence yet:**
- Rooms are by definition ephemeral in V1 (no "reconnect after closing the app for an hour" expectation).
- In-memory + the per-room mutex + StateFlow gives us the right shape for free.
- Persistence becomes load-bearing the moment gameplay state needs to survive across cold starts. Phase 4.2.

**Critical lifecycle detail in the WebSocket route:** the room-flow collector runs in a child coroutine while the main `webSocket{}` body drains `incoming` until it closes. Without that split, a quiet room (no upstream emissions) blocks the collector indefinitely and the `markConnected(false)` finally block never fires. Caught by the test that asserts post-disconnect `isConnected == false`. Recorded here because it's an easy gun-to-the-foot for future contributors.

**Client surface:** `:libraries:rooms` (api) + `:libraries:rooms:impl`. Repo exposes one API surface — `createRoom() / joinRoom() / leaveRoom() / observeRoom()`. `RoomConnection` sealed type (`Connecting / Connected(room) / Reconnecting(attempt) / Closed(reason)`) is what the UI subscribes to; the underlying HTTP + WS split is invisible upstream. `NetworkClient` got the WebSockets plugin installed on the authenticated HttpClient so the bearer-token chain is reused for free.

**UI:** `:features:lobby` with a single `LobbyScreen` that switches between "Idle" (create + join forms) and "InRoom" (share-this code + presence-dotted member list + leave). Wired to HomeScreen — Start / Join Game CTAs navigate to LobbyRoute. `LobbyRoute(prefilledCode: String?)` is the deep-link entry point: `cards://join/ABC123` → auto-attempt join.

**Tested:**
- `InMemoryRoomServiceTest` (10 invariants including concurrent-join no-duplicate-seats, observe emits on mutation, last-leave reap, idempotent join).
- `RoomRoutesTest` (HTTP + JWT auth + serialization end-to-end, error-code mappings, lowercase code normalization, host name from profile).
- `RoomSocketRoutesTest` (six load-bearing WS invariants: connect → Snapshot, non-member rejected, second join broadcasts, presence flip on disconnect, **reconnect by same userId preserves seatIndex**).
- `RoomRepositoryImplTest` (every HTTP status → outcome mapping).
- `ReconnectingRoomSocketTest` (Connecting → Reconnecting(attempt=1) on handshake failure, transport errors surface as Reconnecting, cancellation stops the loop).
- `LobbyViewModelTest` (codeInput upcasing, gates, create-success enters in-room, create-network-error stays idle, join-full surfaces, blank-code short-circuits, prefilledCode auto-joins, leave returns to idle).

**Sharp edges to address before launching MP to real users:**
- In-memory rooms vanish on server restart. Fly deploys = lost rooms. Acceptable for V1.x soft launch; revisit if room sessions average > 30 min.
- No reconnect grace timer — disconnected seats are held forever (until the user explicitly leaves or the host leaves and the room is GC'd). A sweep that frees seats after N minutes of disconnect should land before public launch.
- Friend discovery, public lobbies, spectator mode all deferred. V1 MP is invite-only by code.
- Gameplay sync (server-authoritative deal / bet / showdown) is Phase 4.2 — the lobby ships standalone.

**Status:** Landed (commits `7a85fca`, `068b66e`, `98384a5`, `999fcad`). Server-authoritative gameplay sync is the next Phase 4 chunk.

---

## 2026-05-19 — Multiplayer: reconnect grace timer + seat sweep

**Decision:** Per-room socket disconnects now stamp `RoomMember.disconnectedAt`; a new `RoomService.sweepDisconnected(maxIdle)` reaps members past the grace window. Exposed via `POST /v1/admin/sweep-disconnected-room-members` and triggered on a cron, matching the orphan-anon-sweep pattern. Default TTL is **5 minutes** (env: `ROOM_DISCONNECTED_MEMBER_TTL_MINUTES`).

**Why now:** The Phase 4.1 sharp-edges note flagged "no reconnect grace timer — disconnected seats are held forever" as a must-fix before launching MP to real users. Mobile networks drop sockets constantly; without a sweep, a single bad day on cellular locks out a 6-seat friend room. The 5-minute default is long enough that a phone-reconnecting-to-Wi-Fi hop preserves the seat, short enough that an abandoned room is reusable within one cron cycle.

**Shape:**

| Piece | Where |
| --- | --- |
| `RoomMember.disconnectedAt: Instant?` | `apps/server/.../domain/Room.kt` — null when connected; set on every disconnect, cleared on every reconnect. Server-internal; never serialized over the wire. |
| `RoomService.sweepDisconnected(maxIdle: Duration): RoomSweepResult` | `apps/server/.../domain/Room.kt` interface; impl in `InMemoryRoomService`. |
| TTL env var | `AdminConfig.disconnectedRoomMemberTtlMinutes`, default 5. |
| Admin route | `POST /v1/admin/sweep-disconnected-room-members`, token-gated like the anon sweep. Returns `{ membersReaped, roomsReaped, roomsSeen }`. |
| Side effect | The room flow re-emits on member removal, so `RoomSocketRoutes` naturally broadcasts a `member_left` delta to every observing client. No new wire event needed. |

**Subtle bits:**

- `create()` and `join()` now stamp `disconnectedAt = now`, treating "joined-but-never-opened-a-socket" the same as a clean drop. Otherwise an abandoned `POST /v1/rooms/{code}/join` would camp a seat indefinitely. Reconnect (`markConnected(true)`) clears it.
- Sweep is idempotent — second pass finds nothing left to reap, returns zero counts.
- Sweep emptying a room GC's it the same as `leave()`'s last-out branch — future joins on the same code return `RoomNotFound`.
- Cron cadence: every 1–5 minutes is recommended (GitHub Actions' minimum is 5 min; an external uptime monitor can hit per-minute). `DEPLOY.md` documents both paths.

**Tested:**

- `InMemoryRoomServiceTest`: stamp on disconnect, clear on reconnect, TTL boundary (3 min / 5 min / 7 min), never-connected reaping, empty-room GC, idempotence, multi-room `roomsSeen` count, flow propagation.
- `AdminRoutesTest` (new file): 401 without token, 401 with wrong token, 401 when server has no token configured (fail-closed), 200 happy path counts, TTL boundary end-to-end through the route.

**Status:** Landed. Sweep cadence wiring (cron workflow at `.github/workflows/sweep-rooms.yml`) is a pre-launch checklist item, not a code change.

---

## 2026-05-19 — MP foundation hardening: cron workflows, host-room cap, admin ops endpoint, auth viewmodel tests

**Decision:** Bundled hardening pass closing four V1.x pre-launch checklist items at once. Pure additive — no behavioral changes for existing callers beyond an extra `Conflict` branch on `POST /v1/rooms`.

**Pieces:**

1. **Cron workflows committed.** `.github/workflows/sweep-anon.yml` and `.github/workflows/sweep-rooms.yml` were documented in DEPLOY.md but lived nowhere on disk. Both now exist as runnable workflows (daily at 05:17 UTC for the anon sweep; every 5 min for the room sweep, GitHub's cron floor). Each fails closed when `CARDS_ADMIN_API_TOKEN_DEV` is unset and surfaces a workflow-summary warning on `failedToDelete > 0` for the anon sweep. Prod equivalents will sit alongside once `cards-server` (prod) is provisioned.

2. **Per-host room cap (`MAX_ROOMS_PER_HOST = 3`).** `RoomService.create` now returns a sealed `CreateResult` (`Success` | `TooManyRooms(activeCount)`). Limits abuse where a single user creates rooms in a loop and exhausts the in-memory code map. Honest workflows are unaffected — three concurrent rooms is well above realistic friend-game patterns; an abandoned room is freed by either the next `leave()` or the disconnect sweep. The HTTP route translates `TooManyRooms` → `409` with `too_many_rooms` problem code so the client UI can render a tailored message ("Leave one before creating another").

3. **`GET /v1/admin/rooms` operational endpoint.** Token-gated, same `X-Admin-Token` header as the sweeps. Returns one summary per live room — code, host, status, seat counts, connected vs disconnected. Used to verify the sweep is doing its job between cron ticks, spot abandoned rooms, answer "how busy is MP right now." Summary-only (no member-level detail), so payload size is bounded.

4. **Client-side auth ViewModel tests.** Closes the sharp-edge note "No client-side tests for the auth screens." 27 new tests across `SignInViewModelTest` / `SignUpViewModelTest` / `VerifyEmailViewModelTest` pinning outcome→state→event mapping for every `SignInOutcome` / `SignUpOutcome` / `RefreshOutcome` / `ResendOutcome` variant. Reusable fakes in `AuthViewModelFakes.kt` model the repository surface; future auth-screen tests can build on the same scaffolding.

**Why now:** All four were marked "before launching MP to real users" in the known-sharp-edges memory. Bundling them keeps the V1 cleanup sweep cohesive — and the room-cap change touches the same `RoomService.create` surface that the admin endpoint introspects, so they cluster naturally.

**Tested:**

- `InMemoryRoomServiceTest` — added 5 tests (cap-enforced, per-host scoping, reclaimable after leave, reclaimable after sweep, snapshot ordering).
- `RoomRoutesTest` — added 1 test (HTTP 409 + `too_many_rooms` envelope).
- `AdminRoutesTest` — added 4 tests for `GET /v1/admin/rooms` (auth gates + happy-path summary counts).
- Auth viewmodels — 27 tests across SignIn / SignUp / VerifyEmail covering every outcome variant + side-effect gates (`hasUserOnboarded` flips only on confirmed paths).

**Status:** Landed. Server tests: 73 (was 64). Onboarding-impl tests: 27 (was 0).

---

## 2026-05-19 — V1 cleanup bundle: account VM tests, small-routes tests, table DS sweep

**Decision:** Bundled cleanup pass closing three known sharp-edge items in
one cohesive sweep. No behavioral changes — coverage + visual consistency
only. Pure additive.

**Pieces:**

1. **`DeleteAccountViewModel` + `ClaimAccountViewModel` tests.** Closes the
   sharp-edge note "DeleteAccountViewModel + ClaimAccountViewModel still
   untested." 22 tests across both VMs pinning outcome→state→event mapping
   for every `DeleteAccountOutcome` / `LinkIdentityOutcome` variant plus
   the load-bearing **no-auto-switch invariant**: `AlreadyOnAnotherAccount`
   stashes the provider and surfaces the guest-progress-loss warning but
   does NOT call `signInWithOAuth` — only `ConfirmSwitchToExisting` does
   that. Reusable fakes in `AccountViewModelFakes.kt` mirror the
   onboarding-impl pattern so future profile tests have scaffolding.

2. **Small server routes pinned.** `/_health`, `/v1/app-config`, and
   `/v1/avatars` were the three server routes still untested. They're
   tiny but load-bearing:
   - `/_health` is the Fly liveness probe — a regression knocks the app
     offline. Pin the un-versioned path + 200 JSON.
   - `/v1/app-config` is the kill-switch surface. Pin shape + the "empty
     object means use defaults" branch documented in the route header.
   - `/v1/avatars` must stay JWT-gated (per-user rate limiting), must
     serve `AvatarStarterPack.values` verbatim, and must set 1-day
     cache-control. 7 tests in `SmallRoutesTest`.

3. **DS sweep on the bot-table surfaces.** The known-sharp-edges memory
   flagged remaining `Color.White.copy(alpha = X)` usage on the table
   chrome. The remaining instances were in `TableActionBar.kt`,
   `PlayerArea.kt`, and `BoardArea.kt` (the memory's PlayBotsScreen
   reference is stale — that screen was renamed to PlayPokerScreen and
   already swept).
   - `TableActionBar`: QuickActionBar pills + the ↑ more-options button
     now use `surfaceSecondary` (enabled) / `surfaceDisabled` (disabled),
     matching the RaiseSheet convention. Disabled text uses
     `textDisabled` instead of `ColorResource.FromColor(Color.White.copy(...))`.
   - `PlayerArea`: player-tile border uses the `border` token.
   - `BoardArea`: the BoardWell outline gets a named
     `PokerPalette.CardSlotOutline` sibling of the existing `CardSlot`
     fill — same poker-table-artifact intent.

**Why now:** All three items were "fix opportunistically" entries in the
known-sharp-edges memory. Bundling them keeps the cadence steady between
larger Phase 4.2 work (which has the JVM-target prerequisite, still
outstanding) and the V1 polish layer.

**Tested:**

- `DeleteAccountViewModelTest` — 10 tests covering canSubmit gating, the
  NotSignedIn-treated-as-success branch, every error outcome, error
  clear-on-edit.
- `ClaimAccountViewModelTest` — 12 tests covering provider-flag gates,
  the no-auto-switch invariant, every link/sign-in outcome, conflict
  resolution flow.
- `SmallRoutesTest` — 7 tests (health 200 + un-versioned path,
  app-config verbatim tree + empty-object branch, avatar JWT gate +
  pack pass-through + cache-control).
- Server-side test counts: 80 (was 73). Profile-impl tests: 22 (was 0).

**Status:** Landed.

---

## 2026-05-19 — ViewModel coverage sweep: progression + home

**Decision:** Backfill tests for the four feature ViewModels that were
shipping untested: `AchievementsViewModel`, `RankDetailSheetViewModel`,
`XpDetailSheetViewModel`, and `HomeViewModel`. All four are thin
"subscribe and map" orchestrators wired to repositories the user touches
every session; a regression on any one ships silently because the wider
test suite doesn't fan out into feature-level state.

**Why now:** With the V1 progression UX shipped and the home screen as
the 60-second-rule entry point, the cost of a silent regression here is
disproportionate — a stale chip count, the wrong anon flag, an XP badge
that doesn't update. The fakes for `ProgressionRepository`,
`XpEventRepository`, `AchievementRepository`, `UserRepository`, and
`ChipsRepository` now live in `features/progression/impl/.../ProgressionFakes.kt`
(reusable across the progression VMs) and inline in `HomeViewModelTest.kt`
(slightly different repository surface).

**Pinned invariants:**

| ViewModel | Invariants pinned |
|---|---|
| `RankDetailSheetViewModel` | rank stays at 0 for anon; flips to V1 placeholder 1200 on claim; reverts to 0 on sign-out / delete-account |
| `AchievementsViewModel` | `isLoading=true` pre-first-emission (NeverEmitting repo); progress updates propagate; load flag clears on first emission |
| `XpDetailSheetViewModel` | the 3-way `combine` waits for all upstreams before clearing `isLoading`; a single-flow change re-emits the merged state |
| `HomeViewModel` | init loads user; null user → `isAnonymous=true`; chips + XP updates propagate; `Refresh` re-reads `getUser()` so a `PATCH /v1/me` edit surfaces before cache fan-out |

**Totals:** 11 progression-impl tests (was 0) + 6 home-impl tests (was 0).

**Status:** Landed.

---

## 2026-05-19 — Billing: provider-agnostic interface, NoOp default, local-credit success path

**Decision:** Introduce `:libraries:billing` (api) + `:libraries:billing:impl`
as the V1 IAP foundation. The api exposes a `BillingClient`, `BillingProduct`,
`PurchaseResult`, `PurchaseTransaction`, and `BillingAvailability`. Shop /
catalog code never imports Play Billing or StoreKit — those plug in behind
the interface per platform.

**Default binding:** `NoOpBillingClient`, which reports `Unavailable` on
connect and an empty product map. The catalog reconciliation pass in
`ProductsRepositoryImpl` therefore drops every IAP pack from the surfaced
catalog. This matches the pre-launch "store listings not yet provisioned"
state — we hide IAP packs rather than render un-buyable ones.

**Reconciliation contract (shop-roadmap §1 — closed):** After fetching the
catalog DTO, `ProductsRepositoryImpl` calls
`BillingAvailability.refresh(skus)`. The result drives two transformations
on the in-memory catalog:

1. ChipPacks whose `StoreSku.sku` is NOT in the store's response are
   dropped.
2. ChipPacks whose SKU IS in the response have their
   `StoreSku.fallbackPriceDisplay` replaced with
   `BillingProduct.displayPrice` (localized by the store).

`NotConnected` and `Failed` outcomes are best-effort: they leave the
cached snapshot in place rather than vaporizing every IAP pack on a
transient network blip.

**Purchase outcome routing:** `ShopViewModel.ConfirmPendingPurchase` for
an IAP pack:

1. Resolves the userId from `IdentityRepository.state`.
2. Calls `billingClient.purchase(sku, userId)`.
3. On `Success` / `AlreadyOwned`, credits chips locally via
   `ChipsRepository.applyDelta(grantsChips)` and acknowledges the receipt.
4. Emits a typed `IapPurchaseOutcome` via `ShopEvent.PurchaseFinished`.
5. `ShopFeatureEntryPoint` observes that event and surfaces a snackbar
   (skipped for `Cancelled`).

**V1 simplification (intentional):** Chips are credited locally on
success without server receipt validation. The roadmap §2 endpoint
(`/v1/billing/redeem`) will move this to server-authoritative when
Apple App Store Connect + Google Play Console accounts are wired. Until
then, `FakeBillingClient` tags receipts with `BillingPlatform.Fake` so a
future production server can reject unverified ones.

**Rejected alternative:** A `PurchaseCoordinator` separate from the VM.
Coupling-wise it's cleaner, but the VM already owns the screen-level
state machine (pending sheet, error state, sync events) and adding an
indirection class doubled the number of moving parts without adding
testability — the same fakes work either way.

**Tested:** 4 new `ProductsRepositoryImplTest` cases + 5 new
`ShopViewModelTest` cases covering the reconciliation + purchase flows.

**Status:** Landed. Real Play Billing + StoreKit impls are out of scope
for V1 — they need provisioned store listings + native dependencies.
Wire them via `@ContributesBinding(replaces = [NoOpBillingClient::class])`
per platform when those land.

---

## 2026-05-19 — Onboarding: pattern-match the underlying error for diagnostics

**Decision:** When `OnboardingViewModel.Finish` fails, route the
underlying `Catching` exception through a `describeFailure()` mapper
that surfaces a specific actionable line for the four common dev/staging
failures: anonymous sign-ins disabled, captcha required, invalid anon
key, network unreachable. Debug builds append the raw exception message
to the toast.

**Why:** The user-reported symptom "I'm not seeing any users in Supabase"
collapses many failure modes into one logcat dive. The most common cause
in fresh dev / staging environments is the dashboard's
Authentication → Providers → "Allow anonymous sign-ins" toggle being off
— the API responds with a 422 + "Anonymous sign-ins are disabled" but
the OnboardingViewModel previously rendered a generic "Couldn't reach
the server" toast, hiding the actionable bit.

The mapper isn't a sealed type because the upstream throws plain
exceptions; pattern-matching the message keeps the change local. If
`IdentityRepository.ensureInitialized` ever moves to a sealed-outcome
return shape, the mapper collapses into a `when` over the outcome.

**Status:** Landed. `OnboardingViewModelTest` (5 tests) pins the routing.

---

## 2026-05-19 — Product catalog: Postgres-seeded, in-memory source deleted

**Decision:** Move the shop catalog out of the hardcoded
`InMemoryProductCatalogSource` and into a `products` table seeded by
`V5__products.sql`. `PostgresProductCatalogSource` becomes the single
binding for `ProductCatalogSource`; the in-memory class is deleted (no
fallback, no DI conditional).

**Why:** The server already owns Postgres for profiles, equipment, and
inventory; the catalog was the lone "fake data" source still living as
in-code constants. Moving it lets an admin re-price, re-name, or stage
a sale by `UPDATE products SET …` — no server redeploy, no client
update. The `Cache-Control: max-age=300` envelope on `GET /v1/products`
already absorbs the per-request DB round-trip.

**Schema choices:**

- One `products` table with a `kind` discriminator (`chip_pack` vs
  `chip_offer`). Two `CHECK` constraints enforce kind-specific NOT NULL
  rules at the DB layer so a half-populated row can't ship. Single table
  beats two-table inheritance because the catalog reads the entire
  surface in one shot per request — denormalizing is the natural fit.
- Localized strings stored as JSONB maps keyed by BCP-47 tag (`{"en":
  "Pocket Stack", "es": "Pila de bolsillo"}`). The existing
  `LocaleMatch.kt` matcher already operates on the same shape, so the
  repo just decodes the column straight into the domain model. Exposed
  surfaces the JSONB as `text()` and we parse with
  `kotlinx-serialization-json` — zero new dependencies.
- Platforms as Postgres `TEXT[]`. Exposed has no first-class array
  column type for our version, so the repo runs a single raw SELECT for
  `id, platforms` and decodes the JDBC `Array` ourselves. The catalog
  is tiny enough that the extra round-trip cost is irrelevant.
- `sort_order` (integer) drives display order. Each category gets a
  decade (chip packs 100s, felts 200s, emotes 300s, …) so reordering
  inside a category is `sort_order = 215` without an enum dance.

**Seed parity:** Every productId from the previous in-memory source is
in the seed, including the previously time-limited demo offers
(`chip_pack_flash_sale`, `felt_sunset_weekend`). The startup-relative
`availableUntilEpochMs` synthesis is gone — both rows are seeded with
NULL availability, and a real sale window now needs an admin
`UPDATE products SET available_until_epoch_ms = …` against a real
wall-clock epoch. A regression test pins the set of `grantsKey` values
the client depends on, so a future seed drift that renames or removes
a row breaks loudly at build time.

**What didn't change:** The wire format
(`routes/ProductsDto.kt`), the locale matcher, the time-based offer
filtering in the route layer, and the client. Switching the source is
invisible to the consumer.

**Tests:** 13 new `PostgresProductCatalogSourceTest` cases pinning
JSON decoding, kind discrimination, store-SKU surfacing, sort order, and
the grantsKey contract. `DatabaseSchemaTest` extended to assert the V5
table exists and is seeded after migrations run.

**Status:** Landed. Future work (out of scope here):

- An admin endpoint or CLI to write into `products` directly without
  hand-rolling SQL.
- Server-side validation of the kind-vs-fields invariant in the repo
  (it's currently in DB constraints — if a future code path bypasses
  the constraint, the route surfaces `error` from `requireNotNull`).
- Achievement catalog migration (parallel story: ~36 hand-coded
  achievements still live in `:libraries:cards/AchievementRegistry.kt`
  because the `Criterion` evaluator is logic-bearing, not data).

---

## 2026-05-19 — Server-authoritative chip wallet (V6 migration)

**Decision:** Chip balance moves out of the client's Room cache and
into a Postgres `wallets` table on the server. V6 migration adds:

- `wallets(user_id PK, balance, created_at, updated_at, CHECK balance >= 0)` —
  one row per user. Lazy-created on first read with [Wallet.STARTER_GRANT]
  (10K).
- `wallet_events(user_id, idempotency_key, delta, reason, applied_at)` —
  append-only ledger keyed by `(user_id, idempotency_key)`. The dedup
  boundary that lets the client retry a sync without double-applying a
  chip movement.

**Why:** Closes the V1 MVP must-have "Server-side XP / chip persistence
via Supabase" + "Starter grant deduplication" lines from `docs/product/
v1-mvp.md`. Pre-V6 a reinstall granted a fresh 10K because the seed
lived in Room; V6 ties the grant to the userId so the second device /
reinstall sees the same wallet. The CHECK on `balance >= 0` is
defense-in-depth — the application layer's [ApplyOutcome.InsufficientChips]
path is the first line of refusal, the constraint is the floor.

**Sync contract (`POST /v1/me/wallet/sync`):** Client posts a batch of
locally-applied `WalletEventDto { idempotencyKey, delta, reason }`.
Server iterates in order, applying each:

| Outcome | Trigger | Wallet effect |
|---|---|---|
| `Applied` | First time the server sees this key | `balance += delta`; ledger row written |
| `AlreadyApplied` | Duplicate idempotency key on the ledger | none |
| `InsufficientChips` | Debit that would dip below zero | none; client surfaces toast |

A failing event does NOT abort the batch — later events still apply.
The response carries the post-batch authoritative balance plus a
per-event result row, mirroring the inventory-sync envelope so the
client's existing sync-result-handling pattern transfers.

**`GET /v1/me/wallet`** returns the current balance, lazy-creating the
row with the starter grant on first contact. Useful as a cheap
foreground hydrate when the client has no pending events to flush.

**Rate limit:** `WALLET_WRITE_LIMIT = 480 / hour / IP`. A heavy user
playing 200 hands could realistically batch ~250 syncs/hour; 480
covers them with ~2× headroom while still capping sustained abuse at
one batch every 7.5 seconds. Per-IP keying mirrors the policy on the
other write endpoints — moving to per-user keying would mean running
the limiter inside the `authenticate` block (Ktor supports it; not
worth the plumbing for V1).

**Delete cascade:** `DELETE /v1/me` now calls
`walletRepository.deleteAllForUser(userId)` before
`profileRepository.delete(userId)`. Order matters: admin call first
(revokes auth + sessions), then local data. Each step is idempotent so
a mid-cascade crash leaves recoverable state. Inventory + equipment
cleanup on delete are a separate sharp edge — they're not wired in
yet, this PR is scoped to wallet.

**What's deferred to a follow-up commit:**

- Client integration. `ChipsRepositoryImpl` still reads/writes Room
  only. A future `ChipsSyncService` will hydrate the local cache on
  cold boot + foreground and flush local deltas to
  `/v1/me/wallet/sync` (same pattern as `InventorySyncService`).
- Server-side XP persistence (the sibling V1 MVP item). The wallet
  schema is the template; XP needs a parallel `xp_ledger` table.

**Tests:** 14 new `PostgresWalletRepositoryTest` cases (testcontainers
Postgres — pins idempotency, starter-grant seeding, the non-negative
balance invariant, per-user key scoping, ordered ledger reads, delete
cascade). 8 new `WalletRoutesTest` cases (JWT-gated route layer —
pins outcome routing, batch continuation past rejected debits, empty-
batch as a hydrate-only call). Server test count: 181 (was 159).

**Status:** Server piece landed. Client follow-up pending.

---

## 2026-05-19 — Client wallet sync (cache-with-flush)

**Decision:** Wire the client to the V6 wallet server. The local Room
chips row stays as the optimistic-write cache; a new
`wallet_events` table (AppDatabase v11) holds outstanding sync receipts;
`ChipsSyncService` flushes them and hydrates the local balance from the
server's authoritative answer on cold boot + foreground.

**Architecture:**

- `ChipsRepository.applyDelta(delta, reason, idempotencyKey?)` is now
  two-step: bump the singleton chips row AND insert a
  `WalletEventEntity`. Callers that don't have a natural idempotency
  key get a generated UUID v4.
- `ChipsRepository.setBalance(authoritativeBalance)` is the inverse —
  used only by the sync service to overwrite the local balance after a
  successful round-trip. Other callers stay on `applyDelta`.
- `ChipsSyncServiceImpl` POSTs all pending events to
  `/v1/me/wallet/sync` (single-flight via mutex) and, per server
  response:
  - `Applied` / `AlreadyApplied` → drop the local row.
  - `InsufficientChips` → drop the row too (no retry possible) and let
    `setBalance` restore the authoritative value.
  - `Unknown` → leave the row pending so a newer client can resolve it.
- After every successful sync, `setBalance(response.balance)` overwrites
  the local row regardless of which events resolved. That's where
  cross-device grants converge — a chip pack purchased on iOS is visible
  on Android after the first sync.
- `ChipsSyncBootstrapper` (an `AppEventListener` multibinding) fires on
  `ColdBoot` and warm `OnForeground` events. Same shape as
  `InventorySyncBootstrapper`.

**Caller-side idempotency keys (so retries don't double-apply):**

| Caller | Key |
|---|---|
| `ShopViewModel.creditChipsFor(IAP)` | `iap.<packId>.<orderId>` |
| `AchievementRepositoryImpl` reward | `achievement.<achievementId>` |
| `InventorySyncServiceImpl` refund | `shop.refund.<productId>` |
| `InventoryRepositoryImpl.redeemForChips` | `shop.<productId>` |
| `ChipsRepositoryImpl.applyDelta` (no key) | generated UUID v4 |

**Why "drop on InsufficientChips" instead of retry:** The optimistic
local write already happened; the server says it can't accept the
debit. Retrying with the same key is a no-op (server has the
idempotency record) and the local balance is going to get reset to
the server's value anyway. The user-visible reconciliation is "your
balance dropped by X" — surfaced via a toast in a follow-up commit
(not in this slice).

**Why route `InventoryRepositoryImpl` debits through `ChipsRepository`
instead of `ChipsDao`:** Previously the inventory repo bypassed the
chips repo to talk directly to the DAO, which meant shop purchases
never produced a wallet event — server-side balance diverged from
client-side over time. Routing through `ChipsRepository.applyDelta`
fixes that.

**Migration model:** AppDatabase already uses
`fallbackToDestructiveMigration(dropAllTables = true)` (see
[RealAppDatabaseProvider.kt](libraries/storage/impl/.../RealAppDatabaseProvider.kt)),
so the v10→v11 bump nukes local user data. Acceptable pre-launch; the
server's authoritative balance is what survives. Post-launch the
destructive default needs to flip to a real migration path — that's a
sibling sharp edge already noted in the codebase.

**Tests:** 7 new `ChipsSyncServiceImplTest` cases (empty-batch hydrate,
all-Applied, AlreadyApplied replay, InsufficientChips, Unknown
outcome, network failure, mixed outcomes). `FakeChipsRepository` /
`FakeChips` test fakes across `InventoryRepositoryImplTest`,
`InventorySyncServiceImplTest`, `HomeViewModelTest`, `ShopViewModelTest`
all updated for the new interface.

**What's still deferred (next slice):**

- Server-side XP persistence — wallet schema is the template; the XP
  path needs its own ledger table + sync service.
- Toast / snackbar on `InsufficientChips` reconciliation. The local
  balance reset is silent today; a user surface is appropriate but
  belongs with the reconciliation copy work (see voice-and-copy.md).
- Toast surface for cross-device chip changes. The
  `setBalance(authoritativeBalance)` call after each sync silently
  overwrites the local balance, which means a chip pack purchased on
  another device shows up without a "+50 chips synced" affordance.
  Belongs with the reconciliation copy work; cheap to add once the
  copy is approved.

**Auth note:** `ChipsSyncServiceImpl` uses
`networkClient.authenticatedClient` (bearer token + 401 refresh), as
the wallet endpoints are JWT-gated. `InventorySyncServiceImpl` is on
the un-auth client today for the same JWT-gated `/v1/inventory/sync`
endpoint — that's a pre-existing inconsistency (server returns 401
for un-bearer requests; current behavior is "next launch retries").
Out of scope here; flagged for a follow-up.

**Status:** Landed. End-to-end wallet sync works: chip movement on
device A → server ledger → device B's next sync picks up the new
balance.

