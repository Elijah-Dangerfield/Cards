# TODO

**Last reviewed:** 2026-05-27 · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md), [backlog.md](./backlog.md)

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

**Locked design:** achievement grants follow the gameplay surface they fire from. **Bot games** run the engine client-side and always will — the client posts achievement unlocks to `POST /v1/me/grants/achievement/{id}` and that's the permanent shape (not a security gap to harden, just how bot achievements work). **MP achievements** wait for Phase 4.2 server-authoritative gameplay, where the server witnesses the hand and grants directly — no client POST. The registry needs `AchievementMode.{BOTS_ONLY, MP_ONLY, ANY}` to keep these wired correctly so an MP-only achievement can't be self-granted via the client endpoint once the server takes over.

### Catalog gating — unlock-only vs purchasable

- `[P2]` **More emote / blast-pack unlocks beyond the seeded Eliminator + Baller + Iron Stack + Convincer packs.** Four pairings shipped: `emotes_eliminator` for `BUST_DEALT_5` (V25), `emotes_baller` for `TRIPLE_UP` (V27), `emotes_iron_stack` for `NO_BUST_100` (V28), `emotes_convincer` for `WIN_BY_FOLD_10` (V29). Remaining work: extend the catalog — pick the next achievement → pack pairing each time a new themed bundle makes sense. Candidates worth considering: `BEAT_*_10` per-bot signature packs (one pack per personality), a multi-suit theme tied to `SHOW_*` rare-hand achievements (`SHOW_STRAIGHT_FLUSH` / `SHOW_ROYAL_FLUSH`), or a discipline-themed pack pairing with `GOOD_FOLD_25` (the "I read you cold and folded" identity, complement to the Convincer pack's bluff energy). **Out of scope:** the existing pathway itself.

- `[P2]` **League-tier rewards (blocked on league mechanic).** One cosmetic per league tier granted at season end. Genuinely blocked — re-pick once the league system has a real surface.

- `[P2]` **RFT (rare-from-the-floor) drops (blocked on server-side roll plumbing).** Low-probability cosmetic drops at hand-end. Genuinely blocked — re-pick once the server can roll the dice.

### Strings — centralize everything in `:libraries:resources`

- `[P0]` **Sweep every inline user-facing string into `:libraries:resources` Compose Multiplatform resources.** Most UI copy is hardcoded at the callsite. That's load-bearing-by-accident: blocks future localization, makes voice-and-copy edits a repo-wide find-and-replace, lets two screens drift on the same idea. Bootstrap landed 2026-05-27: `:libraries:resources/src/commonMain/composeResources/values/strings.xml` now hosts the catalog; the entire `:features:shop:impl` user-facing surface is migrated — snackbars (RedeemSucceeded / AlreadyOwned / OfferExpired / Chips added / Restored / Store unavailable / Sign in first / Purchase failed titles + messages), owned-state surfaces (OWNED badge / "Unlocks at Level N" / "Need N more"), hero copy ("Shop" / "Spend chips. Stock up. Flex." / "Got an idea? Tell us" / "Only you see this"), and the empty + error states ("Shop is empty for now" + helper / "Couldn't load shop" + "Retry"). The `:features:lobby:impl` Composable surface migrated 2026-05-28: top-bar titles, idle hero + create/join CTAs, in-room code surface + share hint + player count + start/leave CTAs + waiting-for-host hint + member seat label, and the connection-status banner (Disconnected / Connecting / Reconnecting / Connected). **Pattern proven:** Composable callsites read via `stringResource(Res.string.foo, args)`; non-Composable contexts (snackbar fire-and-forget) read via `getString(Res.string.foo, args)` from a `suspend` scope. **Remaining work:** sweep `:features:profile`, `:features:room`, `:features:home`, `:features:onboarding`, `:libraries:ui` snackbar / dialog / banner copy; also `LobbyViewModel`'s error-state strings (currently raw `String` in `LobbyState.error` — needs typed `ErrorReason` or a `suspend` resolve before the migration can land). Each module needs `implementation(projects.libraries.resources)` added to its `build.gradle.kts`. New strings always go straight to the resource — no inline. **Files / hints:** the resource module is `:libraries:resources`; consumers add `implementation(projects.libraries.resources)` to pick up the auto-generated `Res.string.*` accessor. **Out of scope:** translating anything (give strings a *home*, not a second language); bot-name copy (V1.x / V2 problem per §D); preview-only / test-only strings.

### Screen / chrome consistency

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

**Architecture revisit landed 2026-05-27.** Decision: accept the eval's Option B (event-sourced game state, sequence-numbered events over the existing WS, snapshot-on-hand-end compaction, room membership persisted to Postgres). Transport split stays; managed realtime layers stay out of the game path. Full reasoning + alternatives in [docs/decisions.md](./decisions.md) entry **"2026-05-27 — Multiplayer: event-sourced game state + persisted room membership"**. The sub-items below are the migration path. Sequencing is **not strict** — phases can interleave once the foundation is in place — but B0 → B1 → B3 is the natural order for the durability fix.

### B0 — Foundation: event log primitives

- `[P0]` **Add `game_events` table + `seq` field on `GameEventOccurred`.** New Flyway migration creates `game_events(session_id UUID, seq BIGINT, occurred_at TIMESTAMPTZ, event_type TEXT, event_jsonb JSONB, PRIMARY KEY(session_id, seq))` with index on `session_id`. `GameEventOccurred` (the outbound WS frame) and the underlying `GameEvent` payload both gain a `seq: Long` field, with a `version: Int` discriminator on the JSONB envelope so future event-shape migrations are explicit. **Acceptance:** schema lands; existing in-memory `GameSession` writes nothing yet — this PR just provisions the durable surface. Compatibility shim: WS clients on the old protocol see `seq = 0` and ignore. **Files / hints:** migration under `apps/server/src/main/resources/db/migration/`, model field in `libraries/gameplay/src/.../Events.kt`, WS DTO mirror in the server routes. **Out of scope:** writing events, reading them, or compaction — that's B1 / B2.

- `[P0]` **Persist `GameSession` events to Postgres inside the per-session mutex.** Every accepted action's resulting `GameEvent`(s) get appended to `game_events` *before* the existing `MutableSharedFlow<GameEvent>` emit, transactionally with the state mutation. Server restart now retains hand history; the in-memory `StateFlow<GameState>` becomes a derived view (still served live, just no longer the source of truth). **Acceptance:** writes land before the WS broadcast; integration test asserts that crash-mid-hand replay yields the same `GameState` as the in-memory cache had pre-crash. **Files / hints:** `apps/server/src/main/kotlin/com/cards/server/game/GameSession.kt` is the surface; existing per-room mutex covers the new write. **Out of scope:** the WS reconnect protocol change (B3) and the snapshot-compaction story (B2).

### B1 — Snapshot compaction + cold-start replay

- `[P1]` **Snapshot the materialized `GameState` at every hand-end checkpoint.** New `game_state_snapshots(session_id UUID, cursor_seq BIGINT, state_jsonb JSONB, captured_at TIMESTAMPTZ, PRIMARY KEY(session_id, cursor_seq))` with index on `session_id`. After each `HandComplete` event lands in `game_events`, the session writes the full `GameState` JSON snapshot at the same cursor (still inside the mutex so the snapshot/event-tail invariant holds). **Acceptance:** cold-start of a server process loads the latest snapshot per active session and replays the event tail past that cursor to reconstruct the in-memory `StateFlow`; restart mid-hand is recoverable. **Files / hints:** new migration; new helper alongside `GameSession.kt`. **Out of scope:** snapshot retention/pruning (keep all snapshots for V1 — the volume's nothing).

- `[P1]` **Bootstrap-from-Postgres path in `GameSessionRegistry`.** Today the registry is process-local and a restart loses every active session. After B1 lands a snapshot table, the registry's first read for a given `session_id` should hydrate from `(latest snapshot, replay events after cursor)` before returning the in-memory session. **Acceptance:** server restart leaves all open rooms reconnectable mid-hand; the existing 5-min reconnect grace continues to work for the disconnected player. **Files / hints:** `apps/server/src/main/kotlin/com/cards/server/game/GameSessionRegistry.kt`. **Out of scope:** the WS reconnect protocol change (B3) — this is server-side bootstrap only.

- `[P2]` **Periodic invariant check: snapshot at cursor N equals replay-from-0-to-N.** Per the eval, "two sources of truth need invariants." Add a `:apps:server` test (and a debug-only admin endpoint) that, given a session id, replays the full event log from zero, compares to the snapshot at the highest matching cursor, and fails loudly on drift. **Acceptance:** runs in CI on a sample synthetic session; admin endpoint returns OK / drift report. **Out of scope:** production-time periodic enforcement; that's a follow-up once we see real volume.

### B2 — WS reconnect protocol upgrade

- `[P1]` **WS `RequestEventsSince(cursor)` inbound frame + `EventTail(events, currentSeq)` outbound frame.** Today the reconnect path rebroadcasts a full `Snapshot` regardless of how brief the disconnect was. With B0+B1 in place, the client can ask "send me events since cursor N" and short-circuit the snapshot when the gap is small. Server replies with `EventTail` for cheap-catchup, or falls back to `Snapshot + currentSeq` when the gap exceeds a threshold (configurable, default 200 events ~= 8 hands). **Acceptance:** brief disconnect = `EventTail`-only round trip; long disconnect = `Snapshot + tail` as today. Client tracks `lastSeenSeq` per session in [`ReconnectingRoomSocket.kt`](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocket.kt). **Files / hints:** WS DTOs in `apps/server/src/main/kotlin/com/cards/server/routes/RoomSocketRoutes.kt`; client receives in `:libraries:rooms:impl`. **Out of scope:** spectator role (that's B5).

### B3 — Persisted room membership

- `[P1]` **Move room registry from in-memory `InMemoryRoomService` to Postgres.** New `rooms` + `room_members` tables; the `InMemoryRoomService` becomes a hydrated cache rather than the source of truth. Membership operations (create / join / leave) write through Postgres before responding. **Acceptance:** room codes survive restart; `GET /v1/rooms/{code}` and `POST .../join` read durably. **Files / hints:** `apps/server/src/main/kotlin/com/cards/server/data/InMemoryRoomService.kt` (rename / split likely). **Out of scope:** discovery / room lobbies (Phase 2+).

### B4 — Reshaped gameplay items (now have a home in the new architecture)

- `[P1]` **Buy-in / stack / re-buy mechanic — event-sourced.** Spec at [§4.1](./product/product-spec.md#wallet-stack--buy-ins). Now lives entirely in the event log: `BuyInRequested → ChipsHeld(walletDelta) → SeatStackInitialized` on sit; `RebuyRequested → ChipsHeld → StackTopUp` on a `stack=0` rebuy; `SeatStandSweep → ChipsReleased(walletDelta)` on stand / forfeit. Wallet/table double-entry stays as a wallet ledger row; the event log is the authoritative trigger. Anti-smurf gate ([§5.3](./product/product-spec.md#53-public-rooms)) rejects sit-down if buy-in > 25% of wallet. Bot tables already map difficulty → `StakeTier` via `SoloBotsPokerSessionFactory.toStakeTier()`. Re-buy dialog on stack = 0 + sit-out toggle in seat menu are the client-side pieces. **Depends on:** B0 (event log primitives).

- `[P1]` **MP credit by table composition — server-side enforcement at the post-hand event-handler seam** ([§5.4](./product/product-spec.md#mp-credit-by-table-composition)). The "≥2 humans AND humans ≥ bots" rule for granting MP XP / league credit / achievements gates the hook the server fires when `HandComplete` lands on the event log. Read seat composition from the snapshot at the same cursor (B1) so the gate is deterministic across replay. Client also needs the visible "Practice tier · bots present" label rendered from the same composition source. **Depends on:** B0 + B1 (event log + snapshot composition).

- `[P1]` **Orphaned-room policy — forfeit-then-spectator** (decided 2026-05-27, see in-flight prior cycle). Last-human-leaves still kills the room. App-dies / disconnect path: keep the seat warm via the existing `disconnectedAt` grace; if the grace expires mid-hand, emit a `SeatForfeited` event on the log, auto-folding for the rest of the session, and downgrade the WS subscription to read-only (spectator). On launch, `GET /v1/me/active-rooms` drives the Rejoin / Forfeit banner — reconnecting after forfeit comes back as a spectator. **Depends on:** B0 (event log) + B5 (spectator subscriber model).

### B5 — Spectator role

- `[P2]` **Spectator = WS subscriber without a seat.** Today the snapshot scrubbing already personalizes hole cards per viewer; extend the auth check so a non-seated WS subscriber receives the scrubbed-for-everyone view. Friend rooms stay closed to non-members; public rooms (Phase 2+) can open. The reconnect-after-forfeit path from B4 lands here. **Acceptance:** a forfeit'd or non-seated client receives `Snapshot` / `GameEventOccurred` without hole cards or personalized state; cannot submit `SubmitIntent` (server rejects). **Files / hints:** auth check in `RoomSocketRoutes.kt`; scrubbing already exists. **Out of scope:** public-room discovery, spectator chat (both Phase 2+).

### B6 — Future / parked

- **Sticky-routed multi-process scale-out.** Park. Per the eval, "easy to retrofit on top of (B). Don't pay this cost until you have load." Pick once the single-machine ceiling becomes visible.

- **Supabase Realtime for ambient social channels** (lobby activity, friend presence "X started a game" toasts). Park. Per the eval: managed realtime is a fit for low-stakes broadcast where server validation isn't required. Re-pick alongside the friend-graph rollout in §A so the channels go in with the social system, not before.

- **Hand history endpoint** `GET /v1/rooms/{code}/history` — once B0 lands, this is a small REST endpoint reading `game_events` filtered by `session_id` and ordered by `seq`. Park until a UI surface wants it (likely a "review last hand" affordance on the room screen).

---

## C. Engineering / structural

Quality issues flagged across the codebase. None are blockers; they compound.

### Caching + config plumbing

- `[P2]` **Replace `FeatureConfig`'s `by featureValue(...)` with DI-bound `ConfiguredValue<T>` singletons.** Today new tunable values land as another delegate on a growing `FeatureConfig` object — fine, but the QA menu has to know about each new field by name to render an override row. **Acceptance:** existing `FeatureConfig` callsites read from injected `ConfiguredValue<T>` singletons (each `@Inject` + `@ContributesBinding` into a multibinding `Set<ConfiguredValue<*>>`); QA menu enumerates the multibinding set and renders the right override widget per type (Boolean / Int / String / Long); old `featureValue` delegate is deleted; tests cover the override path. **Files / hints:** `:libraries:config` owns the current `FeatureConfig` + override repo; QA menu lives in `features/profile/impl/.../QaMenuScreen.kt`. **Out of scope:** `AppConfig` (the server-driven `GET /v1/app-config` channel — different cascade, different consumers) and any per-feature flag that isn't user-tunable.

### Module sprawl: `libraries/cards`, `gameplay`, `game`

- `[P2]` **Audit and split `libraries/cards`.** Originally the "highly shared" dumping ground; now too big and overlaps with `libraries/gameplay` (engine types) and `libraries/game` (session abstraction). Audit what's *truly* cross-feature primitive vs what landed there for lack of a better home. Likely splits: progression (XP / achievements / ranks) into `libraries/progression` or stays — but if it stays, cosmetics + chips + identity etc. need their own homes. Capture as a deliberate refactor pass. Do not entangle with feature work.

---

## D. Already on the books elsewhere

For completeness; don't re-derive these here when the link below tracks them.

- **Known sharp edges** — [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_known_sharp_edges.md) (auto-loaded).
- **Phase 4.2 server-authoritative gameplay** — out of scope until we choose to start it. See [docs/decisions.md](./decisions.md) and the `:libraries:gameplay` JVM-target blocker noted in memory.
- **Real platform billing impls (Play Billing v6+ / StoreKit 2)** — billing scaffold + `FakeBillingClient` is in place; provisioning store listings is the gate, not engineering. `DevBillingClient` bridges the gap so debug builds render chip-pack tiles end-to-end; once the real platform bindings land, both `DevBillingClient` and `NoOpBillingClient` become candidates for removal.
- **OAuth UI gated by `IdentityFeatureConfig`** — Apple/Google buttons are wired but flagged off until dashboard credentials exist.
- **Username localization, bot name localization** — V1.x / V2 problems.
