# TODO

**Last reviewed:** 2026-05-29 · **Companion to:** [product/product-spec.md](./product/product-spec.md), [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Append, check off, and **delete** done items — they don't need to live here as history. Add a [decisions.md](./decisions.md) entry **only** when an item resolved a non-trivial architectural call worth not re-litigating (see decisions.md header for what qualifies). Most items just get crossed off and removed.

When an item points at a file path or system, the assumption is that path/system already exists — the work is the gap, not a greenfield build.

**Priority tags** (every item carries one — workers should bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking; pick once your area's P0s are claimed or you can't confidently progress on them.
- `[P2]` — Lower urgency than P1, but **still worker-pickable**. Many of these need a directional call (which shape, which API, which content seed). Make a recommendation, ship a slice, and let the reviewer course-correct — that's the safety net. Don't skip a P2 because it's ambiguous; skip only if it's marked `(blocked on X)` and the blocker is real (waiting on another system, not waiting on judgment).

**Item template** (the more of these you fill in, the safer it is for an automated worker to pick up):

> **Problem:** what's wrong / missing.
> **Acceptance:** how we know it's done.
> **Files / hints:** where to start looking.
> **Out of scope:** what NOT to drag in.

Everything in this file is worker-pickable. Items that need a human action — device QA, dashboard config, content writing, product decisions — live in [`docs/developer-todo.md`](./developer-todo.md) instead. Per-cycle follow-ups tied to a specific PR's diff live in the PR's "Heads up" section.

---

## A. UX gaps observed in the build

These are bugs / polish items found playing the app or scanning the code. Cheap individually; collectively the V1 quality bar.

### Achievements — bot-vs-human split

**Locked design:** achievement grants follow the gameplay surface they fire from. **Bot games** run the engine client-side and always will — the client posts achievement unlocks to `POST /v1/me/grants/achievement/{id}` and that's the permanent shape (not a security gap to harden, just how bot achievements work). **MP achievements** wait for Phase 4.2 server-authoritative gameplay, where the server witnesses the hand and grants directly — no client POST. The registry already carries `AchievementMode.{BOTS, MULTIPLAYER, EITHER}` on every entry and `ClientGrantableAchievements.Default` (server-side) splits `clientGrantable` vs `serverWitnessed` so the two MP-only `*_MP` entries 403 on a client self-grant attempt today. The grant endpoint is also rate-limited via `ACHIEVEMENT_GRANT_LIMIT` (120/hour/IP, see `RateLimits.kt`) as of 2026-05-28. Remaining hardening: hand-count floor on the grant endpoint — genuinely blocked on server-side hand tracking, which is part of Phase 4.2 server-authoritative gameplay (there's no `hands_finished` signal on the server yet to gate against).

### Catalog gating — earnable vs purchasable

The earnable catalog should bias toward axes that *can't* be paid for: usage volume (1000 hands, N sessions), skill ratios (W:L over N hands, fold-to-win rate), and competitive placement (league tier, tournament finishes). RNG-based triggers ("show a rare hand at showdown") aren't a status signal — the deck loved you once. Current `EarnableCosmetics` pairings skew RNG-heavy and need a pass.

- `[P1]` **Render the catalog-axis tier badge on shop / inventory surfaces.** Tagging shipped 2026-05-29 — every `EarnableCosmetics.kt` entry now carries `CosmeticTier.{EARN_ONLY, EARN_OR_BUY}` and the V1 catalog is wholly `EARN_ONLY`. Remaining work: read the tier on the shop product card + the inventory item row and render the right badge ("Earned" pin on EARN_ONLY items in inventory; achievement-badge alongside the EARN_OR_BUY earned-instance variant). Seed entries that flip to `EARN_OR_BUY` (the player heat-map widget on MP-only earn + the win/loss-odds ratio display on harder earn, priced to bias purchase) wait on the heat-map / odds widgets themselves — separate items.

- `[P2]` **League-tier rewards (blocked on league mechanic).** One earn-only cosmetic per league tier granted at season end. Genuinely blocked — re-pick once leagues land.

### Screen / chrome consistency

- `[P2]` **Consolidate chip / coin / stack rendering into `:libraries:ui` primitives.** [`ChipBadge.kt`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/ChipBadge.kt) + [`ChipCoin.kt`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/ChipCoin.kt) exist but 10+ feature files render their own chip/coin styles: `WelcomeDialog`, `OpponentsRow`, `StackExplainer`, `PotExplainer`, `PlayPokerScreen`, `BetPillExplainer`, `PlayerArea`, `PurchaseConfirmSheet`, `ShopScreen`, `OnboardingScreen`. Audit the variants, promote the recurring shapes (3-4 likely), migrate callsites. **Acceptance:** every promoted primitive ships with **thorough previews** — every variant + state, light + dark, RTL where it matters. Thin previews = a regression playground. **Out of scope:** non-chip DS-promotion candidates (see the sweep item below).

- `[P2]` **Sweep features for other DS-promotion candidates.** Chip rendering is the obvious one; survey the rest. Likely candidates: pill-shaped status badges, the "section header with see-all" pattern, the achievement-tile shape, hand-result rows. One-by-one promote what you find, thorough previews on each.

### Auth & account onboarding

- `[P1]` **`install_id` follow-ups — L1 cleanup task + loss-disclosure UX.** Schema (V49), client UUID generation + `X-Install-Id` header on every authenticated call, and `/v1/me` UPDATE of `profiles.install_id` all landed 2026-05-29. Spec §6.1 was already amended ahead of the implementation. Remaining pieces from the original design doc ([`recovery-and-orphaned-accounts.md`](./recovery-and-orphaned-accounts.md)):
  1. **L1 cleanup task:** SQL pre-filter `SELECT p.user_id FROM profiles p LEFT JOIN wallet_events we ON we.user_id = p.user_id AND we.reason LIKE 'iap.%' WHERE p.install_id = :install_id AND p.user_id != :current_user AND p.user_id IN (SELECT id FROM auth.users WHERE is_anonymous = TRUE) AND we.user_id IS NULL`. For each candidate, Kotlin verifies (still-anon, `level <= 1`, zero achievements, no recent wallet/room activity) and deletes via `SupabaseAdminClient.deleteUser` + `ProfileRepository.delete`. Fire from the /v1/me handler in a background launch so it doesn't block the response. The repo's existing `touchInstallId` returns the prior install_id — non-null + non-matching prior is the cue to enqueue this sweep for that install. Verification stays in Kotlin so safety conditions can evolve without rewriting SQL. Sweep code that stays dormant: [`DefaultOrphanAnonymousSweep`](../apps/server/src/main/kotlin/com/cards/server/data/DefaultOrphanAnonymousSweep.kt) — different design (TTL-based), don't reuse.
  2. **Loss-disclosure UX:** thread "sign in to keep this" copy into the surfaces where the consequence of *not* claiming becomes visible — shop pre-purchase confirmation, stats banner, settings account section. Exact placements per the companion doc's "Loss-disclosure UX" section, with designer + product confirmation before they ship. **These are consequence disclosures, not the proactive claim prompts rejected by the [2026-05-20 decision](./decisions.md)** — different shape, different intent.
  **Files / hints:** /me handler in `MeRoutes.kt` (the `touchInstallId` call seam); existing claim card lives in [`ProfileScreen.kt`](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt) as a starting point for the loss-disclosure copy. **Out of scope:** recovery_id, KMP keychain, Welcome-back screen, `/v1/recovery` endpoint, starter-grant dedup gate, L2 parked sweep — all in backlog as Option B/C upgrade paths.


- `[P1]` **Sign in / sign up with Apple — implement using `NativeViewFactory`.** Apple's Human Interface Guidelines require the system-rendered `ASAuthorizationAppleIDButton` for Sign in with Apple — both for visual conformance (Apple rejects custom buttons in App Review) and for the native ID-token issuance flow. On iOS we can't render that with Compose; route through [`NativeViewFactory`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/native/NativeViewFactory.kt) (or the equivalent Compose-to-Swift bridge) so the iOS impl renders the native button, captures the resulting authorization, and hands the ID token back to `SupabaseAuthGateway` for `signInWithOAuthIdToken(provider = "apple", idToken = ...)`. Android keeps the existing Google/Apple-via-web flows (no platform-native Apple button there). **Acceptance:** tapping "Continue with Apple" on iOS opens the system sheet; success path lands the user authenticated with the linked Apple identity; cancel path returns silently; error path surfaces through the existing `ClaimAccountState.error` channel. **Files / hints:** `apps/ios` Swift surface for the native button impl; `:libraries:identity:impl/auth/SupabaseAuthGateway.kt` for the `signInWithOAuthIdToken` shape (currently routes everything through `signInWith(IDToken)` from supabase-kt). **Out of scope:** Google native-button parity on iOS (Google's button on iOS is less strict; Compose-rendered button is acceptable for now); changing the existing Android Apple flow.

### Animations / table polish

- `[P2]` **Particle "zip" overlay for hand-end progression — remaining polish.** XP deferral (2026-05-27) and chip-stack count-up (2026-05-27) both landed: the top-bar `LevelPill` ring fill and the human seat's chip stack both hold at their pre-hand values while either the hand-result dialog or the celebration sheet is on screen, then animate after dismiss. Remaining: an overlay that visually ties the hand result to the destinations — an XP particle flying up from the showdown card to the `LevelPill`, and a coin particle flying down to the chip stack — fired the moment the overlays dismiss and the gated values release. Polish-on-top, not a regression to fix.

### Gameplay & table UX

- `[P2]` **Per-hand attribute tracking + batch upload for the human heat-map.** No per-hand data is captured today for the local human player. To eventually surface a heat-map on the human's own profile, we need to track decisions over time. **Direction (worker recommends, reviewer course-corrects):** capture per-hand attributes (folded / called / raised / bluffed / showdown won, with win-lose outcome) into a local Room table at hand-end; batch-upload to the server when either 50 entries accumulate or 24h passes since last upload, whichever first. Server stores rows in a `player_hand_decisions` table and exposes a derived "heat-map snapshot" endpoint the human profile sheet can render. **Acceptance:** local capture works on every resolved hand; batch policy fires on threshold or timer; server endpoint returns a snapshot. **Files / hints:** Room storage at `:libraries:storage`; new server route; existing achievement counter logic is a precedent for per-hand local capture. **Out of scope:** the actual heat-map visual on the human profile (separate item once the snapshot endpoint lands); bot tracking; historical backfill. **Worker note:** write a 1-paragraph architecture sketch in the in-flight Approach line before committing code — direction ambiguity is high here, that's the safety net.

- `[P1]` **Expand the tap-an-opponent profile sheet — remaining work.** Playing-style + difficulty-tier + "At this table" tenure section landed (hand count for every populated seat, "Playing since {Month Year}" for the local human seat sourced from `Profile.createdAt`). Remaining: human-variant "Add friend" affordance (pairs with the friend graph below) and "view full profile" tap-through once profile-of-a-stranger is a real route. **Acceptance per piece:** each lands as its own commit if natural.

### Social graph + friends — load-bearing for V1.x

Home now exposes three surfaces that need the friends / recents system to actually work: the friends strip with online presence, the "recently played with" shelf with add-friend affordances, and the friend-requests inbox on profile. All currently fake or no-op.

**Locked rule:** friendship is gated on having played together. The only path to friending someone is the "recently played with" shelf — no search-by-handle, no friend-suggestions. The empty-state copy on the social surfaces has to communicate this clearly.

- `[P0]` **Friend graph — server schema + endpoints.** New tables: `friend_relations(user_a, user_b, state, created_at)` with `state ∈ {requested, accepted, blocked}` and `user_a` always the lexicographically smaller id (so the row is unique regardless of direction). Endpoints: `POST /v1/friends/requests` (target user id), `POST /v1/friends/requests/{id}/accept|decline|block`, `GET /v1/friends` (accepted), `GET /v1/friends/requests` (inbound, pending). Anti-abuse: rate-limit outbound requests per user/day; block-relations dominate accept/decline. **Hard dep:** only ids surfaced through the recently-played-with shelf can be friended — see the next bullet.

- `[P0]` **Recently-played-with tracking.** Server records the human seats present at every multiplayer hand a user finishes; on the client, `RecentOpponentsRepository.observeRecent(limit = 10)` returns deduped most-recent first. Bots are excluded server-side (can't friend the house). [`RecentlyPlayedWithStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/RecentlyPlayedWithStrip.kt) renders the list; tile flips to "Sent" when `RecentOpponent.requestSent` flips true. **Acceptance:** the shelf renders real data and add-friend works end-to-end.

- `[P1]` **Carry the friend-via-play explanation into the Profile social section once it ships.** Empty states on `FriendsStrip` + `RecentlyPlayedWithStrip` already explain the rule (Home strips updated 2026-05-27); the Profile social section hasn't been built yet — when the friend-requests inbox section lands on `ProfileScreen.kt`, the matching empty-state copy goes there too. **Out of scope:** anything Home-strip side (already shipped).

- `[P1]` **Online-presence signal.** Cheapest path: server emits a presence event when a user's WS connects/disconnects and stores last-seen + current-room (if any). Client subscribes once per session to a presence stream filtered to friend ids. [`FriendsStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) already takes `List<FriendOnline>` shaped for this — drop the real list in and the surface lights up.

- `[P1]` **Friend requests inbox — section on Profile, badge on Home.** Inbox is a section on [`ProfileScreen.kt`](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt) — list of pending inbound requests with accept/decline buttons. Home doesn't get its own inbox; [`FriendsStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) renders a "N friend requests" badge when `pendingRequests > 0`, tap routes into Profile's inbox section. Strip survives even with zero friends online so long as there are pending requests.

- `[P1]` **Block-relation behavior defaults — blocking removes accepted-friend row + prevents same-room matchmaking** (blocked on the friend-graph endpoints below). The voice/safety audit pass was completed 2026-05-27 — every shipped friend-system copy string (FriendsStrip empty state, RecentlyPlayedWithStrip empty + add-friend pill, the "Friends" / "Friend requests" / "Recently played with" coming-soon sheets, the see-all count labels) reads clean against the spec voice rules. What's left is the block-relation logic itself: when the friend graph endpoints land, blocking a user must remove any existing `accepted` relation row and the public-rooms matchmaker must filter out blocked relationships when seating two users into the same room. Implement defaults; adjust later if product flags otherwise.

- **Out of scope for V1.x:** friend suggestions ("people you might know"), in-app invite-via-share-link, push notifications for requests, group chat. All Phase 2+ design questions.

---

## B. Multiplayer hardening

**Architecture decision landed 2026-05-29.** Snapshot-only state, OTel for debugging. See [docs/decisions.md](./decisions.md) entry **"2026-05-29 — Multiplayer: snapshot-only state, OTel for debugging"** for the full reasoning and what was superseded (the 2026-05-27 event-sourced direction). Sequencing: B0 → B2 → B3 is the natural order; B1 (reconnect) can interleave.

### B0 — Server-side state durability

_All items shipped. `room_sessions(session_id UUID PRIMARY KEY, room_code TEXT UNIQUE, state_jsonb JSONB, updated_at TIMESTAMPTZ)` is written through the per-session mutex on every state mutation; `DefaultGameSessionRegistry` lazy-hydrates from it on a code-miss (post-restart recovery)._

### B1 — WS reconnect protocol

- `[P1]` **Snapshot-on-reconnect — client subscriber to receive it.** Server side shipped 2026-05-29: WS upgrade in `RoomSocketRoutes.kt` calls `gameSessions.findOrHydrate(code)` so a reconnecting client (cold or after a server restart) gets a fresh `GameStateSnapshot` frame emitted from the existing `observeSession(code)` publisher. Animations don't replay — `GameSession.events`' 16-element replay buffer is empty on a hydrated instance. **Remaining:** wire a client-side subscriber for the gameplay channel so the `GameStateSnapshot` frame actually lands somewhere. [`ReconnectingRoomSocket.kt`](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocket.kt) currently drops `GameStateSnapshot` / `GameEventOccurred` / `IntentAck` as "Phase 2b" pass-throughs; the Play screen needs a sibling channel that consumes them and feeds the gameplay VM. This is the larger half of the original B1 work. **Out of scope:** event-tail catch-up (B5).

### B2 — Persisted room membership

- `[P1]` **Move room registry from in-memory `InMemoryRoomService` to Postgres.** New `rooms` + `room_members` tables; `InMemoryRoomService` becomes a hydrated cache. Membership operations (create / join / leave) write through Postgres before responding. **Acceptance:** room codes survive restart; `GET /v1/rooms/{code}` and `POST .../join` read durably. **Files / hints:** [`apps/server/src/main/kotlin/com/cards/server/data/InMemoryRoomService.kt`](../apps/server/src/main/kotlin/com/cards/server/data/InMemoryRoomService.kt) (rename / split likely). **Out of scope:** discovery / room lobbies (Phase 2+).

### B3 — Gameplay items

- `[P1]` **Buy-in / stack / re-buy mechanic.** Spec at [§4.1](./product/product-spec.md#wallet-stack--buy-ins). Mutates `room_sessions.state_jsonb` inside the per-session mutex; wallet ledger row stays a separate write. Anti-smurf gate ([§5.3](./product/product-spec.md#53-public-rooms)) rejects sit-down if buy-in > 25% of wallet. Bot tables already map difficulty → `StakeTier` via `SoloBotsPokerSessionFactory.toStakeTier()`. Re-buy dialog on stack = 0 + sit-out toggle in seat menu are the client-side pieces. **Depends on:** B0.

- `[P1]` **MP credit by table composition** ([§5.4](./product/product-spec.md#mp-credit-by-table-composition)). The "≥2 humans AND humans ≥ bots" rule for granting MP XP / league credit / achievements is read from `state_jsonb` at the `HandComplete` seam. Client also needs the visible "Practice tier · bots present" label rendered from the same composition source. **Depends on:** B0.

- `[P1]` **Orphaned-room policy — forfeit-then-spectator** (decided 2026-05-27). Last-human-leaves still kills the room. App-dies / disconnect path: keep the seat warm via the existing `disconnectedAt` grace; if the grace expires mid-hand, mutate state to `SeatForfeited`, auto-fold for the rest of the session, and downgrade the WS subscription to read-only (spectator). On launch, `GET /v1/me/active-rooms` drives the Rejoin / Forfeit banner. **Depends on:** B0 + B4.

### B4 — Spectator role

- `[P2]` **Spectator = WS subscriber without a seat.** Today the snapshot scrubbing already personalizes hole cards per viewer; extend the auth check so a non-seated WS subscriber receives the scrubbed-for-everyone view. Friend rooms stay closed to non-members; public rooms (Phase 2+) can open. The reconnect-after-forfeit path from B3 lands here. **Acceptance:** a forfeit'd or non-seated client receives `Snapshot` without hole cards or personalized state; cannot submit `SubmitIntent` (server rejects). **Files / hints:** auth check in `RoomSocketRoutes.kt`; scrubbing already exists. **Out of scope:** public-room discovery, spectator chat (both Phase 2+).

### B5 — Future / parked

- **Rolling event tail for smoother reconnect animations.** Park. Snapshot-only reconnect produces a one-frame visual jump; if users complain post-launch, resurrect a bounded event tail (last ~50 events, TTL'd) and replay them to the client in fast-forward. The code path is already mostly written from the 2026-05-28 event-sourced direction.
- **Hand history endpoint.** Park. Only meaningful if we ship a "review last hand" UI surface; deferred from V1 scope.
- **Sticky-routed multi-process scale-out.** Park until the single-machine ceiling becomes visible.
- **Supabase Realtime for ambient social channels** (lobby activity, friend presence "X started a game" toasts). Park; re-pick alongside the friend-graph rollout in §A so the channels go in with the social system, not before.

---

## C. Engineering / structural

Quality issues flagged across the codebase. None are blockers; they compound.

### Caching + config plumbing

- `[P2]` **Replace `FeatureConfig`'s `by featureValue(...)` with DI-bound `ConfiguredValue<T>` singletons.** Today new tunable values land as another delegate on a growing `FeatureConfig` object — fine, but the QA menu has to know about each new field by name to render an override row. **Acceptance:** existing `FeatureConfig` callsites read from injected `ConfiguredValue<T>` singletons (each `@Inject` + `@ContributesBinding` into a multibinding `Set<ConfiguredValue<*>>`); QA menu enumerates the multibinding set and renders the right override widget per type (Boolean / Int / String / Long); old `featureValue` delegate is deleted; tests cover the override path. **Files / hints:** `:libraries:config` owns the current `FeatureConfig` + override repo; QA menu lives in `features/profile/impl/.../QaMenuScreen.kt`. **Out of scope:** `AppConfig` (the server-driven `GET /v1/app-config` channel — different cascade, different consumers) and any per-feature flag that isn't user-tunable.

### Test coverage — pin load-bearing logic

These are surfaces where a regression would silently corrupt user-visible state (wallets, XP, levels, onboarding) and the sibling pattern in the same module already has tests — so the gap is unambiguous.

### Module sprawl: `libraries/cards`, `gameplay`, `game`

- `[P2]` **Audit and split `libraries/cards`.** Originally the "highly shared" dumping ground; now too big and overlaps with `libraries/gameplay` (engine types) and `libraries/game` (session abstraction). Audit what's *truly* cross-feature primitive vs what landed there for lack of a better home. Likely splits: progression (XP / achievements / ranks) into `libraries/progression` or stays — but if it stays, cosmetics + chips + identity etc. need their own homes. Capture as a deliberate refactor pass. Do not entangle with feature work.

### Observability

- `[P1]` **Wire Sentry — single project, platform-tagged.** Add the Sentry SDK to client (Compose Multiplatform via `io.sentry:sentry-kotlin-multiplatform`) and server (Sentry JVM). **Single Sentry project** for the whole product; tag every event with `platform=ios|android|server` + `release=<version>` so cross-platform issue timelines stay unified. Multi-project = fragmented alerts + harder regression triage. **Acceptance:** thrown exceptions on each platform land in Sentry with platform + release tags + a breadcrumb trail; sample crash on each platform shows up. **Files / hints:** initialize in `apps/compose/src/commonMain/.../App.kt` (commonMain init + platform `actual` for the OS-specific glue) and `apps/server/.../Application.kt`. **Prerequisite:** the Sentry project + DSN — captured in [`developer-todo.md`](./developer-todo.md).

- `[P1]` **Expand OpenTelemetry span coverage on the server — remaining `broadcast` + per-recipient `ws-send`.** The SubmitIntent path now produces a four-span tree: `submit_intent` (outer, set by the WS route) → `validate_intent` (actor/seat/turn gate) → `engine.apply_intent` (engine resolution) → `state_mutate` (state flow write + `onStateChange` snapshot persist + event fan-out + nonce recording). Parity spans on `start_hand` and `request_next_hand` also shipped 2026-05-29. **What's left:** `broadcast` + per-recipient `ws-send` spans on the publisher → `sendJson` path. This requires propagating the `submit_intent` OTel context through the `StateFlow → flatMapLatest → merge → map` chain in [`RoomSocketRoutes.kt`](../apps/server/src/main/kotlin/com/cards/server/routes/RoomSocketRoutes.kt) — `asContextElement()` is the primitive, but the publisher coroutine runs in its own scope per subscriber so the context handoff isn't trivial; a wrapping `Flow<T>` carrying the OTel `Context` as part of each emission is one shape. **Files / hints:** the publisher loop is in `RoomSocketRoutes.kt`; the existing `withSpan` helper lives in `apps/server/.../plugins/Tracing.kt` and already covers context propagation across `withContext` boundaries.

- `[P2]` **Add an anon-orphan-count custom metric to the server's OTel export.** A scheduled gauge (daily or hourly is fine) emitting `cards.anon_orphans.over_90d` = `SELECT count(*) FROM auth.users WHERE is_anonymous AND last_sign_in_at < now() - interval '90 days'`. **Why this matters:** the [device-keyed backup sweep](./decisions.md) is parked precisely because we don't know whether orphans actually accumulate in real usage. This metric is what tells us. If the line stays flat under N (pick a threshold once we have weeks of data), the parked sweep stays parked. If it grows, that's the cue to build it.

### Strings enforcement

- `[P2]` **Decide on a strings-enforcement mechanism + wire it up.** [AGENTS.md §strings](../AGENTS.md) (line 389) already says user-facing strings live in `:libraries:resources`. The rule regresses periodically. Investigate the options — Detekt custom rule, a pre-commit grep hook, a pre-push CI check, or a Gradle plugin that fails the build on raw `Text("…")` in `commonMain` outside `:libraries:resources` — pick one, wire it, document it. Worker investigates + recommends; the recommendation lands as a hydrator proposal for human review before implementation.

---

## D. Already on the books elsewhere

For completeness; don't re-derive these here when the link below tracks them.

- **Known sharp edges** — [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_known_sharp_edges.md) (auto-loaded).
- **Phase 4.2 server-authoritative gameplay** — out of scope until we choose to start it. See [docs/decisions.md](./decisions.md) and the `:libraries:gameplay` JVM-target blocker noted in memory.
- **Real platform billing impls (Play Billing v6+ / StoreKit 2)** — billing scaffold + `FakeBillingClient` is in place; provisioning store listings is the gate, not engineering. `DevBillingClient` bridges the gap so debug builds render chip-pack tiles end-to-end; once the real platform bindings land, both `DevBillingClient` and `NoOpBillingClient` become candidates for removal.
- **OAuth UI gated by `IdentityFeatureConfig`** — Apple/Google buttons are wired but flagged off until dashboard credentials exist.
- **Username localization, bot name localization** — V1.x / V2 problems.
