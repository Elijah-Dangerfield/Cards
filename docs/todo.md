# TODO

**Last reviewed:** 2026-05-30 · **Companion to:** [product/product-spec.md](./product/product-spec.md), [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

> **🎯 Top priority (2026-05-30): finish multiplayer (§B).** MP is not playable today — the server runs authoritative hands but the client drops every gameplay frame. The gate is **B1** below; everything else in §B is the finish-out behind it.

The live punch list of actionable engineering work. Append, check off, and **delete** done items — they don't live here as history.

**Minimum viable context.** Every item is one bold title line + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long. (The `todo-maintainer` enforces this nightly.)

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct. Skip only if marked `(blocked on X)` and the blocker is real (waiting on another system, not on judgment).

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). When an item points at a file/system, assume it exists — the work is the gap, not a greenfield build.

---

## A. UX gaps observed in the build

### Achievements

- `[P2]` **MP achievement grants — server-side hand-count floor (blocked).** Multiplayer achievements need the server to gate grants on a real hand count, but there's no `hands_finished` signal server-side yet. Blocked on server-authoritative gameplay (Phase 4.2). Bot achievements (client self-grant) are the permanent shape and are not in scope here.

### Auth & account onboarding

- `[P1]` **Gate real-money IAP behind account claim** (decided 2026-05-30 — see [decisions.md](./decisions.md)). An anonymous user can't make a real purchase until they've claimed their account (email / Apple). The shop's purchase action, when anonymous, routes to the claim flow instead of the store. This removes the "paid then lost the account" risk at the source. *(proposed 2026-05-30)*
  **Acceptance:** an anonymous user tapping buy on a chip pack is sent to claim-account, not the platform purchase sheet; a claimed user purchases normally.
  **Hints:** purchase entry in `:features:shop:impl`; `IdentityState` exposes anonymous-vs-claimed. **Out of scope:** changing what claimed users can buy.

- `[P1]` **Wire the native Apple sign-in button into the onboarding/claim flow.** The `createAppleSignInButton` primitive (`NativeViewFactory.kt`) + its Swift `ASAuthorizationAppleIDButton` impl exist, but the Apple slot still renders a custom `ButtonSecondary` ([`OnboardingScreen.kt:283`](../features/onboarding/impl/src/commonMain/kotlin/com/cards/features/onboarding/impl/OnboardingScreen.kt)) that App Review rejects. Render the native button there, capture the authorization, and exchange the ID token for a Supabase session — no id-token sign-in path exists today (`RealSupabaseAuthGateway` only runs the web OAuth flow).
  **Acceptance:** the iOS Apple slot shows the system button; tap opens the system sheet; success authenticates the linked Apple identity; cancel returns silently; error surfaces via the onboarding/claim state's error.
  **Hints:** `createAppleSignInButton` in `NativeViewFactory.kt`; `RealSupabaseAuthGateway.kt`. **Out of scope:** Google native button on iOS.

### Gameplay & table UX

- `[P2]` **Hand-end XP/coin particle overlay.** When the hand-result / celebration overlay dismisses, fly an XP particle up to the `LevelPill` and a coin particle down to the chip stack, tied to the moment the gated values release. Polish on top of the existing deferred-animation gating.

- `[P2]` **Per-hand decision capture + batch upload (for a future heat-map).** Capture per-hand attributes (folded / called / raised / bluffed / showdown-won + outcome) to a local Room table at hand-end; batch-upload when 50 entries accumulate or 24h passes; server stores rows + exposes a snapshot endpoint.
  **Acceptance:** local capture on every resolved hand; batch fires on threshold or timer; endpoint returns a snapshot.
  **Hints:** `:libraries:storage`; achievement counter logic is precedent. **Out of scope:** the heat-map visual; bot tracking. **Worker note:** sketch the architecture in the in-flight Approach line before coding — direction is ambiguous.

- `[P1]` **Tap-an-opponent sheet — remaining affordances.** Add the human-variant "Add friend" affordance (pairs with the friend graph) and "view full profile" tap-through once profile-of-a-stranger is a real route.

### Social graph + friends — load-bearing for V1.x

Home exposes three surfaces that need this system to work: the friends strip with presence, the "recently played with" shelf with add-friend, and the friend-requests inbox on profile. All currently fake or no-op.

**Locked rule:** the only way to friend someone is the "recently played with" shelf — no search-by-handle, no suggestions. Empty states must say so.

- `[P0]` **Friend graph — server schema + endpoints.** Tables: `friend_relations(user_a, user_b, state, created_at)`, `state ∈ {requested, accepted, blocked}`, `user_a` = lexicographically smaller id (row unique regardless of direction). Endpoints: `POST /v1/friends/requests`, `POST /v1/friends/requests/{id}/accept|decline|block`, `GET /v1/friends`, `GET /v1/friends/requests`. Rate-limit outbound requests/day; block dominates accept/decline.
  **Hard dep:** only ids surfaced by the recently-played-with shelf can be friended (next item).

- `[P0]` **Recently-played-with tracking.** Server records the human seats at every MP hand a user finishes; client `RecentOpponentsRepository.observeRecent(limit = 10)` returns deduped most-recent-first; bots excluded server-side.
  **Acceptance:** [`RecentlyPlayedWithStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/RecentlyPlayedWithStrip.kt) renders real data and add-friend works end-to-end.

- `[P1]` **Friend-via-play empty state on the Profile social section.** When the friend-requests inbox lands on `ProfileScreen.kt`, carry the "you can only friend people you've played with" empty-state copy there too. (Home strips already done.)

- `[P1]` **Online-presence signal.** Server emits presence on WS connect/disconnect + stores last-seen / current-room; client subscribes once per session, filtered to friend ids. [`FriendsStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) already takes `List<FriendOnline>`.

- `[P1]` **Friend requests inbox — Profile section + Home badge.** Inbox section on `ProfileScreen.kt` (pending inbound, accept/decline). `FriendsStrip.kt` shows an "N friend requests" badge when `pendingRequests > 0`; tap routes into the inbox. Strip survives with zero friends online if requests are pending.

- `[P1]` **Block-relation behavior** (blocked on the friend-graph endpoints). Blocking removes any existing `accepted` relation and the public-rooms matchmaker filters blocked pairs out of the same room. Implement defaults.

- `[P2]` **Banned / suspended account enforcement — server gate + client blocking screen.** A dashboard ban only sets `auth.users.banned_until`; the server verifies JWT signature/exp only, so a banned user keeps playing until their token fails to refresh — and the client then maps that to a generic `SessionExpired`, with no ban-specific UI. Build the [§7.2](./product/product-spec.md) contract: protected routes return `403` with a typed body `{reason: "banned"|"suspended", until: ISO-8601|null, appeal_url}`; client parses `reason` and shows `BlockingErrorScreen`. **Locked:** the wire carries machine-readable data only; user-facing copy stays client-side in `:libraries:resources` keyed off `reason` (the server is plain JVM Ktor and must not depend on the Compose resources module). Implies an app-level moderation table, since the native flag carries no reason/appeal_url.
  **Acceptance:** a flagged account gets `403 {reason}` (not a silent expiry) and sees `BlockingErrorScreen` with localized copy + appeal; a clean account is unaffected.
  **Hints:** [`Authentication.kt`](../apps/server/src/main/kotlin/com/cards/server/plugins/Authentication.kt); `BlockingErrorScreen` in `:libraries:navigation:impl`. **Worker note:** ship the minimum slice first — route banned→`BlockingErrorScreen` instead of silent expiry. **Out of scope:** auto-ban triggers, shadow-bans, the review dashboard.

- **Out of scope for V1.x:** friend suggestions, invite-via-share-link, push notifications for requests, group chat.

---

## B. Multiplayer hardening

**Architecture (2026-05-29):** snapshot-only state, OTel for debugging — see [decisions.md](./decisions.md). **Sequence to a playable, shippable MP: B1 (the gate) → B2 → B3 → B4.**

**State of play (2026-05-30):** rooms work end-to-end (create / join by code / leave / seat allocation / presence), and the server runs **fully authoritative** hands — deals, validates every `SubmitIntent` through the engine, broadcasts scrubbed `GameStateSnapshot` frames, dedupes nonces, persists to Postgres, rehydrates on restart. The **only** reason two humans can't play a hand is B1: the client silently drops the gameplay frames, so the Play screen always runs the local bot engine. Server side of MP is essentially done; the remaining work is client-side plumbing + the finish-out items.

### B0 — Server-side state durability

_Shipped._ `room_sessions` is written through the per-session mutex on every mutation; the registry lazy-hydrates on a code-miss.

### B1 — Client gameplay loop · **the playability gate**

- `[P0]` **Make multiplayer hands actually render and play on the client.** The socket receives the server's live hand, but [`ReconnectingRoomSocket.kt`](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocket.kt) drops `GameStateSnapshot` / `GameEventOccurred` / `IntentAck` as a deliberate `-> Unit` ("Phase 2b") no-op, and only `SoloBotsPokerSessionFactory` is ever injected — so nothing remote reaches the gameplay VM. Three pieces:
  1. **Fan gameplay frames to a second channel** on the room socket (keep the lobby flow — presence / snapshot — untouched), so a gameplay consumer can subscribe without lobby concerns.
  2. **Write a `RemotePokerSessionFactory`** that implements the `PokerSession` contract: feed `GameStateSnapshot` → `StateFlow<GameState>` and `GameEventOccurred` → the event flow, send `RoomClientFrame.{StartHand, SubmitIntent, RequestNextHand}` back, and correlate `IntentAck` for rejection feedback. `PlayPokerViewModel` already takes any `PokerSessionFactory`, so this is additive.
  3. **Add a multiplayer entry point + route** (sibling to [`PlayPokerFeatureEntryPoint`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayPokerFeatureEntryPoint.kt)) that takes a room code, builds the remote factory, and drives `PlayPokerViewModel`.
  **Acceptance:** two humans in the same room can play a full hand against each other — deal, bet/call/fold/raise, showdown — with state rendering on both clients and rejected intents surfaced. **Out of scope:** event-tail catch-up / fast-forward (B5); reconnect animation replay.

### B2 — Persisted room membership

- `[P1]` **Persist room registry to Postgres.** Move [`InMemoryRoomService`](../apps/server/src/main/kotlin/com/cards/server/data/InMemoryRoomService.kt) onto durable `rooms` + `room_members` tables (becomes a hydrated cache); create / join / leave write through before responding.
  **Acceptance:** room codes survive restart; `GET /v1/rooms/{code}` and `POST .../join` read durably. **Out of scope:** discovery / lobbies.

### B3 — Gameplay items

- `[P1]` **Buy-in / stack / re-buy mechanic.** Spec [§4.1](./product/product-spec.md#wallet-stack--buy-ins). Mutate `room_sessions.state_jsonb` inside the per-session mutex; wallet ledger stays a separate write. Anti-smurf gate rejects sit-down if buy-in > 25% of wallet. Client: re-buy dialog at stack 0 + sit-out toggle. **Depends on:** B0.

- `[P1]` **MP credit by table composition** ([§5.4](./product/product-spec.md#mp-credit-by-table-composition)). Grant MP XP / league / achievements only when ≥2 humans AND humans ≥ bots, read from `state_jsonb` at `HandComplete`. Client shows a "Practice tier · bots present" label from the same source. **Depends on:** B0.

- `[P1]` **Per-turn time limit in multiplayer.** A player shouldn't be able to stall the table by sitting on their action. Give each turn a deadline; on expiry, auto-check if checking is legal, otherwise auto-fold. Surface the countdown to the table. *(proposed 2026-05-30)*
  **Acceptance:** a seat that doesn't act within the limit is auto-checked/folded and play continues; the active seat shows a visible countdown.
  **Hints:** turn resolution lives in the gameplay engine + `room_sessions.state_jsonb`; deadline is enforced server-side. **Depends on:** B0. **Out of scope:** per-player time banks / configurable clocks.

- `[P1]` **Orphaned-room policy — forfeit-then-spectator.** Last human leaving still kills the room. On disconnect, keep the seat warm via the existing grace; if it expires mid-hand, mark `SeatForfeited`, auto-fold the rest of the session, downgrade the WS subscription to read-only. `GET /v1/me/active-rooms` drives the Rejoin / Forfeit banner. **Depends on:** B0 + B4.

### B4 — Spectator role

- `[P2]` **Spectator = WS subscriber without a seat.** Extend the auth check so a non-seated subscriber gets the scrubbed-for-everyone view (no hole cards) and the server rejects `SubmitIntent` from them. Friend rooms stay closed to non-members.
  **Hints:** auth check in `RoomSocketRoutes.kt`. **Out of scope:** public-room discovery, spectator chat.

### B5 — Parked

- Rolling event tail for smoother reconnect animations — resurrect only if users complain post-launch.
- Hand-history endpoint — only if we ship a "review last hand" surface.
- Sticky-routed multi-process scale-out — until the single-machine ceiling is visible.
- Supabase Realtime for ambient social channels — re-pick alongside the friend-graph rollout.

---

## C. Engineering / structural

### Config plumbing

- `[P2]` **Replace `FeatureConfig`'s `by featureValue(...)` with DI-bound `ConfiguredValue<T>` singletons.** Today the QA menu must know each tunable field by name to render an override row. Make callsites read injected `ConfiguredValue<T>` singletons contributed into a multibinding `Set`; QA menu enumerates the set and renders the right widget per type; delete the old delegate; test the override path.
  **Hints:** `:libraries:config`; `QaMenuScreen.kt`. **Out of scope:** `AppConfig` (server-driven); non-tunable flags.

### Module sprawl

- `[P2]` **Audit and split `libraries/cards`.** It's become a dumping ground overlapping `libraries/gameplay` (engine types) and `libraries/game` (session abstraction). Audit what's truly cross-feature primitive vs. what landed there for lack of a home; capture as a deliberate refactor pass. Don't entangle with feature work.

### Observability

- `[P1]` **OTel spans on the server — `broadcast` + per-recipient `ws-send`.** The SubmitIntent / start-hand / next-hand paths are traced; the publisher → `sendJson` fan-out isn't. Propagate the `submit_intent` context through the publisher's `StateFlow → flatMapLatest → merge → map` chain (`asContextElement`, or a `Flow` carrying the `Context` per emission).
  **Hints:** the publisher loop in [`RoomSocketRoutes.kt`](../apps/server/src/main/kotlin/com/cards/server/routes/RoomSocketRoutes.kt); `withSpan` in `plugins/Tracing.kt`.

### Lint / static analysis

- `[P1]` **Stand up detekt as the project's custom-rule framework, gated in CI + pre-push.** The point is a growable set of AGENTS.md conventions the build mechanically enforces — both in CI and on `.githooks/pre-push` — so neither humans nor the nightly agents can violate them. Land the framework + the first rule now; the rest are cheap follow-ons. *(proposed 2026-05-30)*
  - **Framework:** add detekt to `gradle/libs.versions.toml` + a `build-logic/` convention plugin, wire `detekt` into `check` (so CI's existing gradle run catches it) and into a new `.githooks/pre-push`. Land behind a baseline file so the gate is green on day one.
  - **Rule #1 — `verifyStrings`:** fail on inline user-facing string literals (`Text("…")`, `placeholder = "…"`, VM-emitted copy) outside `:libraries:resources`, with an allowlist for glyph-only / preview / server-supplied strings (per [`AGENTS.md` §strings](../AGENTS.md)).
  - **Next rules (each a small follow-on, not this item):** `Catching {}` instead of `try/catch` / `runCatching`; `DispatcherProvider` instead of direct `Dispatchers.{Main,IO,Default,Unconfined}`; raw `Color(0xFF…)` / `Color.White.copy(alpha=)` / one-off `RoundedCornerShape(N.dp)` for semantic surfaces. All are mechanical AGENTS.md conventions a rule can pin.
  **Acceptance:** adding `Text("Hello")` to a feature `:impl` fails both `./gradlew check` and the pre-push hook; `stringResource(...)` passes; a documented suppress annotation clears a flagged line; adding a second rule is a localized change (new rule class + config entry), no framework rework.
  **Hints:** convention plugins live in `build-logic/`; existing `.githooks/` has `commit-msg`. **Out of scope:** migrating the existing string violations (`PurchaseConfirmSheet.kt`, `AppGuardLayer.kt`, …) — separate cleanup once the gate exists.

---

## D. Already on the books elsewhere

For completeness; don't re-derive these here.

- **Known sharp edges** — [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_known_sharp_edges.md) (auto-loaded).
- **Phase 4.2 server-authoritative gameplay** — out of scope until we choose to start it. See [decisions.md](./decisions.md) and the `:libraries:gameplay` JVM-target blocker in memory.
- **Real platform billing impls (Play Billing / StoreKit 2)** — scaffold + `FakeBillingClient` in place; provisioning store listings is the gate, not engineering.
- **OAuth UI gated by `IdentityFeatureConfig`** — Apple/Google buttons wired but flagged off until dashboard credentials exist.
- **Username / bot-name localization** — V1.x / V2.
