# TODO

**Last reviewed:** 2026-05-30 · **Companion to:** [product/product-spec.md](./product/product-spec.md), [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

- `[P1]` **Loss-disclosure on the Stats page.** Once a user passes level 1, show a small disclosure on the Stats page encouraging them to claim their account so they don't lose progress.
  **Acceptance:** an anonymous user past L1 sees it; a claimed user doesn't.
  **Hints:** the claim card in [`ProfileScreen.kt`](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt) is a copy starting point.

- `[P1]` **Sign in with Apple on iOS — native button via `NativeViewFactory`.** Apple requires the system `ASAuthorizationAppleIDButton` (App Review rejects custom buttons). Render it on iOS through [`NativeViewFactory`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/native/NativeViewFactory.kt), capture the authorization, hand the ID token to `SupabaseAuthGateway.signInWithOAuthIdToken(provider = "apple", …)`.
  **Acceptance:** tap opens the system sheet; success authenticates with the linked Apple identity; cancel returns silently; error surfaces via `ClaimAccountState.error`.
  **Hints:** `apps/ios` native button surface; `SupabaseAuthGateway.kt`. **Out of scope:** Google native button on iOS; the existing Android Apple flow.

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

- **Out of scope for V1.x:** friend suggestions, invite-via-share-link, push notifications for requests, group chat.

---

## B. Multiplayer hardening

**Architecture (2026-05-29):** snapshot-only state, OTel for debugging — see [decisions.md](./decisions.md). Order: B0 → B2 → B3; B1 can interleave.

### B0 — Server-side state durability

_Shipped._ `room_sessions` is written through the per-session mutex on every mutation; the registry lazy-hydrates on a code-miss.

### B1 — WS reconnect protocol

- `[P1]` **Snapshot-on-reconnect — client subscriber.** The server emits a fresh `GameStateSnapshot` frame on reconnect, but the client drops it: [`ReconnectingRoomSocket.kt`](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocket.kt) treats `GameStateSnapshot` / `GameEventOccurred` / `IntentAck` as passthroughs. Wire a gameplay channel that consumes them and feeds the gameplay VM. **Out of scope:** event-tail catch-up (B5).

### B2 — Persisted room membership

- `[P1]` **Persist room registry to Postgres.** Move [`InMemoryRoomService`](../apps/server/src/main/kotlin/com/cards/server/data/InMemoryRoomService.kt) onto durable `rooms` + `room_members` tables (becomes a hydrated cache); create / join / leave write through before responding.
  **Acceptance:** room codes survive restart; `GET /v1/rooms/{code}` and `POST .../join` read durably. **Out of scope:** discovery / lobbies.

### B3 — Gameplay items

- `[P1]` **Buy-in / stack / re-buy mechanic.** Spec [§4.1](./product/product-spec.md#wallet-stack--buy-ins). Mutate `room_sessions.state_jsonb` inside the per-session mutex; wallet ledger stays a separate write. Anti-smurf gate rejects sit-down if buy-in > 25% of wallet. Client: re-buy dialog at stack 0 + sit-out toggle. **Depends on:** B0.

- `[P1]` **MP credit by table composition** ([§5.4](./product/product-spec.md#mp-credit-by-table-composition)). Grant MP XP / league / achievements only when ≥2 humans AND humans ≥ bots, read from `state_jsonb` at `HandComplete`. Client shows a "Practice tier · bots present" label from the same source. **Depends on:** B0.

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

---

## D. Already on the books elsewhere

For completeness; don't re-derive these here.

- **Known sharp edges** — [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_known_sharp_edges.md) (auto-loaded).
- **Phase 4.2 server-authoritative gameplay** — out of scope until we choose to start it. See [decisions.md](./decisions.md) and the `:libraries:gameplay` JVM-target blocker in memory.
- **Real platform billing impls (Play Billing / StoreKit 2)** — scaffold + `FakeBillingClient` in place; provisioning store listings is the gate, not engineering.
- **OAuth UI gated by `IdentityFeatureConfig`** — Apple/Google buttons wired but flagged off until dashboard credentials exist.
- **Username / bot-name localization** — V1.x / V2.
