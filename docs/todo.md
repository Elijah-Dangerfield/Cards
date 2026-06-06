# TODO

**Last reviewed:** 2026-06-05 · **Companion to:** [product/product-spec.md](./product/product-spec.md), [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

> **🎯 Top priority (2026-05-30): bulletproof multiplayer (§B).** **B1 shipped** — two humans can now play a full hand against each other end-to-end. The new top priority is **B6 (test coverage)** — MP is the load-bearing feature of the app, the V1 stack shipped with significant test gaps, and the testing plan in [`testing-plan.md`](./testing-plan.md) lays out six rounds of work that take it to "brooklyn-bridge-solid." B2–B4 (persistence / gameplay items / spectator) are the remaining MP finish-out behind that.

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

- `[P1]` **Wire the native Apple sign-in button into the onboarding/claim flow.** The `createAppleSignInButton` primitive (`NativeViewFactory.kt`) + its Swift `ASAuthorizationAppleIDButton` impl exist, but the Apple slot still renders a custom `ButtonSecondary` ([`OnboardingScreen.kt:365`](../features/onboarding/impl/src/commonMain/kotlin/com/cards/features/onboarding/impl/OnboardingScreen.kt)) that App Review rejects. Render the native button there, capture the authorization, and exchange the ID token for a Supabase session — no id-token sign-in path exists today (`RealSupabaseAuthGateway` only runs the web OAuth flow).
  **Acceptance:** the iOS Apple slot shows the system button; tap opens the system sheet; success authenticates the linked Apple identity; cancel returns silently; error surfaces via the onboarding/claim state's error.
  **Hints:** `createAppleSignInButton` in `NativeViewFactory.kt`; `RealSupabaseAuthGateway.kt`. **Out of scope:** Google native button on iOS.

### Layout & responsiveness

- `[P2]` **Landscape/horizontal layouts — improve the screens that read poorly.** Every main screen (Home, Profile, Shop, PlayPoker, Lobby, Onboarding) now has a landscape `@Preview`; single-column screens stretch edge-to-edge on a wide canvas and the table needs bespoke short/wide seating. Judge each against its landscape preview and improve the layouts that read poorly (e.g. cap readable content width on wide layouts). *(proposed 2026-05-30)*
  **Acceptance:** screens that read poorly horizontally get an improved layout.
  **Hints:** landscape previews use `@Preview(widthDp = 800, heightDp = 380)`. **Worker note:** the layout-tuning half needs Studio to render the previews — pair with a visual pass.

### Gameplay & table UX

- `[P2]` **Hand-end XP/coin particle overlay.** When the hand-result / celebration overlay dismisses, fly an XP particle up to the `LevelPill` and a coin particle down to the chip stack, tied to the moment the gated values release. Polish on top of the existing deferred-animation gating.

- `[P2]` **Per-hand decision capture + batch upload (for a future heat-map).** Capture per-hand attributes (folded / called / raised / bluffed / showdown-won + outcome) to a local Room table at hand-end; batch-upload when 50 entries accumulate or 24h passes; server stores rows + exposes a snapshot endpoint.
  **Acceptance:** local capture on every resolved hand; batch fires on threshold or timer; endpoint returns a snapshot.
  **Hints:** `:libraries:storage`; achievement counter logic is precedent. **Out of scope:** the heat-map visual; bot tracking. **Worker note:** sketch the architecture in the in-flight Approach line before coding — direction is ambiguous.

- `[P1]` **Tap-an-opponent sheet — remaining affordances.** Add the human-variant "Add friend" affordance (pairs with the friend graph) and "view full profile" tap-through once profile-of-a-stranger is a real route. *(This sheet is the at-table **Player Card** surface — see the Player Card feature below + `decisions.md` 2026-06-06.)*

- `[P2]` **Emote button glyph isn't optically centered.** The play-poker emote trigger ([`TopBarEmojiButton`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/EmojiTray.kt) → DS [`EmojiButton`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/icon/EmojiButton.kt)) centers its circular bounding box correctly, but the emoji glyph sits slightly up-and-left inside it — the text line-box midpoint ≠ the glyph's visual midpoint (the KDoc already notes the vertical half). *(proposed 2026-05-31)*
  **Acceptance:** the glyph reads optically centered in the circle at every `Size`.
  **Hints:** the `Box`/`Text` in `EmojiButton.kt`; likely needs a glyph-vs-line-box offset, not just `Alignment.Center`. **Worker note:** needs Studio to eyeball against the size-scale `@Preview`.

- `[P1]` **Home active-room banner — back the reactive flow with a server-pushed source.** `observeActiveRooms()` is a client-side projection: room changes that don't originate on this device (host closes the room elsewhere, server-side forfeit/grace expiry, a second device) don't reflect until the next `getActiveRooms()`. Hang it off the durable membership source so it's authoritative regardless of who mutated the room. *(proposed 2026-05-31)*
  **Acceptance:** the banner reflects room changes made off-device without a manual refresh.
  **Hints:** [`RoomRepositoryImpl.observeActiveRooms`](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/RoomRepositoryImpl.kt) holds the in-memory `MutableStateFlow` today; the durable source is [B2](#b2--persisted-room-membership) (persisted membership), and presence pushes pair with the [online-presence WS signal](#social-graph--friends--load-bearing-for-v1x).

### Stats & progression

### Profile, cosmetics & sheets

- `[P1]` **Cosmetic detail sheets: richer felt/emote-pack preview.** Felts + emote packs should get a richer (animated) preview in `CosmeticDetailSheet` to match the card-back flip — the felt sheet shows a static `FeltVignette` today, emote packs a thumbnail. *(proposed 2026-06-05)*
  **Acceptance:** felt/emote-pack detail sheets show a richer (animated) preview.
  **Hints:** `FlippableCard` is the card-back precedent in `CosmeticDetailSheet`. **Worker note:** the buyable-tap → purchase-sheet half already shipped.

### Player Card — Phase 1 (V1)

The owner-facing slice of the **Player Card** feature: the public at-the-table identity others see when they tap your avatar. Full product call (scope, phasing, terminology) is in [`decisions.md`](./decisions.md) 2026-06-06; Phase 2 (opponent cards over the wire) and Phase 3 (scouting-stats perk) are in [`backlog.md`](./backlog.md). Ship Phase 1 in dependency order — the shared component first, since everything else renders it. *(proposed 2026-06-06)*

- `[P1]` **Shared `PlayerCard` DS component.** One composable — avatar (emoji + background), display name, equipped title, featured badges — used by both the at-table tap sheet and the editor/profile preview, so the preview can never drift from what others see.
  **Acceptance:** a single `PlayerCard` in `:libraries:ui`; [`PlayerProfileSheet`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayerProfileSheet.kt) renders the owner's identity block through it.
  **Hints:** avatar + badge primitives already exist (`AvatarCircle`, `AchievementMedal`). **Out of scope:** stats (Phase 3).

- `[P1]` **Featured badges — selection + `/v1/me` persistence.** Owner picks up to 3 earned badges to feature on the card; persisted as an additive `featuredBadgeIds` profile field.
  **Acceptance:** selection capped at 3, persists across launches + reinstall (server-owned), defaults to most-recent earned when unset.
  **Hints:** earned set is `AchievementProgress.earned`; additive `/v1/me` field + a `ProfileRepository.update` path. **Out of scope:** surfacing other players' featured badges (Phase 2).

- `[P1]` **Edit Profile → two tabs (Profile / Player Card).** Restructure [`EditProfileScreen`](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/edit/EditProfileScreen.kt) into a **Profile** tab (name, avatar, background) and a **Player Card** tab (a "this is what other players see when they tap your avatar in a game" banner + featured-badge show/hide toggles + a live `PlayerCard` preview).
  **Acceptance:** two tabs; the Player Card tab shows the banner, the toggles, and the shared preview reflecting current selection.

- `[P1]` **Profile screen — live Player Card preview + edit entry.** A compact live `PlayerCard` preview on [`ProfileScreen`](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt) with an "Edit" affordance that deep-links to the Player Card tab.
  **Acceptance:** profile renders the live card; "Edit" opens Edit Profile on the Player Card tab.

- `[P2]` **Tap your own avatar at the table → your Player Card.** The own seat is inert today; open the owner's `PlayerCard` (read-only) with an Edit affordance into the Player Card tab.
  **Hints:** the human seat is suppressed in [`PlayPokerScreen`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayPokerScreen.kt) because `seatMuteKey(seat)` returns null for `isHuman`.

- `[P2]` **Edit Profile — drop the avatar-pack marketplace; link to Shop.** Avatar picker shows only owned/unlocked packs; replace the locked/for-sale packs with a single "Get more avatar packs in the Shop →" link.
  **Hints:** the locked-pack grid + per-pack "Get in shop" buttons in `EditProfileScreen`. **Depends on / pairs with:** the shop category-anchor item below (until that lands, the link just opens the Shop tab).

- `[P2]` **Shop — anchor/scroll to a category (e.g. avatars).** So the Edit Profile "Get more avatar packs" link can land on the avatar section. The shop grid is a flat list today; needs category grouping + an optional scroll-anchor arg on the shop route.

### Cross-app consistency

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

**Architecture (2026-05-29):** snapshot-only state, OTel for debugging — see [decisions.md](./decisions.md). **B1 shipped — MP is playable; sequence to *shippable* MP is now B6 (the test gate) → B2 → B3 → B4.**

**State of play (2026-05-30):** rooms work end-to-end (create / join / leave / seat allocation / presence), the server runs **fully authoritative** hands, and the client now consumes server-driven gameplay (B1 shipped). Two humans can play a full hand against each other end-to-end with auto-promotion when the host disconnects. The remaining work is **B6 (test coverage)** — bulletproofing MP before real users — and B2–B4 (persistence, gameplay items, spectator).

### B0 — Server-side state durability

_Shipped._ `room_sessions` is written through the per-session mutex on every mutation; the registry lazy-hydrates on a code-miss.

### B1 — Client gameplay loop

_Shipped._ Room socket exposes `gameplayFrames` on a sibling flow; [`RemotePokerSessionFactory`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/RemotePokerSessionFactory.kt) implements `PokerSession` over the server's broadcasts; [`PlayMultiplayerRoute`](../features/room/src/commonMain/kotlin/com/cards/features/room/PlayMultiplayerRoute.kt) + its entry point drive the existing `PlayPokerViewModel` against it; lobby's "Start hand" sends `ClientFrame.StartHand` and auto-promotes the effective host on disconnect. Hardening + tests live in **B6**.

### B2 — Persisted room membership

- `[P1]` **Persist room registry to Postgres.** Move [`InMemoryRoomService`](../apps/server/src/main/kotlin/com/cards/server/data/InMemoryRoomService.kt) onto durable `rooms` + `room_members` tables (becomes a hydrated cache); create / join / leave write through before responding.
  **Acceptance:** room codes survive restart; `GET /v1/rooms/{code}` and `POST .../join` read durably. **Out of scope:** discovery / lobbies.

### B3 — Gameplay items

- `[P1]` **Buy-in / stack / re-buy mechanic.** Spec [§4.1](./product/product-spec.md#wallet-stack--buy-ins). Mutate `room_sessions.state_jsonb` inside the per-session mutex; wallet ledger stays a separate write. Anti-smurf gate rejects sit-down if buy-in > 25% of wallet. Client: re-buy dialog at stack 0 + sit-out toggle. **Depends on:** B0.

- `[P1]` **Per-turn time limit in multiplayer.** A player shouldn't be able to stall the table by sitting on their action. Give each turn a deadline; on expiry, auto-check if checking is legal, otherwise auto-fold. Surface the countdown to the table. *(proposed 2026-05-30)*
  **Acceptance:** a seat that doesn't act within the limit is auto-checked/folded and play continues; the active seat shows a visible countdown.
  **Hints:** turn resolution lives in the gameplay engine + `room_sessions.state_jsonb`; deadline is enforced server-side. **Depends on:** B0. **Out of scope:** per-player time banks / configurable clocks.

- `[P1]` **Orphaned-room policy — forfeit-then-spectator.** Last human leaving still kills the room. On disconnect, keep the seat warm via the existing grace; if it expires mid-hand, mark `SeatForfeited`, auto-fold the rest of the session, downgrade the WS subscription to read-only. `GET /v1/me/active-rooms` drives the Rejoin / Forfeit banner. **Depends on:** B0 + B4.

### B4 — Spectator role

- `[P2]` **Spectator = WS subscriber without a seat.** Extend the auth check so a non-seated subscriber gets the scrubbed-for-everyone view (no hole cards) and the server rejects `SubmitIntent` from them. Friend rooms stay closed to non-members.
  **Hints:** auth check in `RoomSocketRoutes.kt`. **Out of scope:** public-room discovery, spectator chat.

### B6 — Bulletproof MP + engine test coverage

- `[P0]` **Implement the multiplayer + gameplay-engine testing plan in [`testing-plan.md`](./testing-plan.md).** MP is the load-bearing feature of the app; the V1 stack shipped with major test gaps in the new wiring (lobby's new MP paths, `RemotePokerSessionFactory`'s seat-derivation logic, end-to-end wire-format contract). Six rounds of work, ordered by impact-per-hour: Round 1 closes the silent-failure surfaces on the new MP code; Round 2 stands up a new `:integration` JVM module that brings up a real Ktor server in-process and points real clients at it (KMP + same-repo server makes this feasible where most codebases can't); Round 3 SUPER-tests the engine via property-based invariants + cross-product action tables + edge scenarios; Round 4 fills the missing server gameplay-flow plumbing tests; Round 5 chaos / fault injection (reconnects mid-hand, host promotion races); Round 6 adds Compose UI tests for `PlayPokerScreen`. *(proposed 2026-05-30)*
  **Acceptance:** every round checkbox in `testing-plan.md` is ticked. Don't pick this up as a single sprint — interleave each round with other feature work; the doc IS the running history.
  **Hints:** [`docs/testing-plan.md`](./testing-plan.md) — Rounds 1, 3 (engine property invariants + edge-case scenarios + cross-product action tables), and 4 are shipped; only Round 3's hand-history fixtures remain, gated on a real production playtest. Round 5 (chaos / fault injection) is the open round here; Round 2 (the `:integration` module) is parked in [`developer-todo.md`](./developer-todo.md). **Out of scope:** emulator-based UI tests (captured in the plan's Deferred section with re-visit conditions).

### B5 — Parked

- Rolling event tail for smoother reconnect animations — resurrect only if users complain post-launch.
- Hand-history endpoint — only if we ship a "review last hand" surface.
- Sticky-routed multi-process scale-out — until the single-machine ceiling is visible.
- Supabase Realtime for ambient social channels — re-pick alongside the friend-graph rollout.

---

## C. Engineering / structural

### Module sprawl

- `[P2]` **Audit and split `libraries/cards`.** It's become a dumping ground overlapping `libraries/gameplay` (engine types) and `libraries/game` (session abstraction). Audit what's truly cross-feature primitive vs. what landed there for lack of a home; capture as a deliberate refactor pass. Don't entangle with feature work.

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
- **OAuth UI gated by the `identity.*` config flags** (`GoogleSignInEnabled` / `AppleSignInEnabled`) — Apple/Google buttons wired but flagged off until dashboard credentials exist.
- **Username / bot-name localization** — V1.x / V2.
