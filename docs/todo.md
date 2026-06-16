# TODO

**Last reviewed:** 2026-06-15 · **Companion to:** [product/product-spec.md](./product/product-spec.md), [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

- `[P2]` **MP achievement grants — server-witnessed hand-count signal.** Multiplayer achievements should gate grants on a real hand count, but the server doesn't yet expose a per-user `hands_finished` (server-witnessed) count. Server-authoritative gameplay has shipped, so this is now buildable — persist a per-user hand-finished count from the authoritative hand loop and gate MP grants on it. Bot achievements (client self-grant) are the permanent shape and not in scope here.

### Progression & XP (server)

- `[P2]` **Graduate lifetime hand counters + achievement progress counters to the server (decision + do).** XP `total_xp` (Slice 1) and the achievement *earned set* (Slice 2) are server-authoritative, but the `progression` hand counters (handsPlayed/won/folded/lostAtShowdown/botHandsPlayed) and the achievement *progress counters* (no-bust streak, per-bot wins, …) are still client-local — they reset on a switch and aren't re-hydrated, so after switching accounts the stats page shows correct XP/level + earned badges but **zeroed hand counts** and restarted in-progress streaks. **Decision:** carry the counters in their respective syncs, or document accept-reset. **Lean: graduate the hand counters** (monotonic + tiny; the Phase 4.2 MP-achievement floor wants a server `hands_finished` anyway); progress counters are lower-value. *(proposed 2026-06-14)*

- `[P2]` **Re-hydrate the recent-XP history feed across switch / reinstall.** XP `total_xp` is server-authoritative (Slice 1) and the per-hand `xp_events` are uploaded, but the recent-XP detail-sheet feed (`XpEventRepository.observeRecent` / `observeSince`) reads the **local** `xp_events` rows — wiped on account switch / reinstall and never re-hydrated (the sync response returns only `totalXp` + per-event outcomes, not the event list). So after a switch/reinstall the total is right but the "recent XP" list is empty until new hands. **Acceptance:** add a read-back (e.g. `GET /v1/me/progression/events`, or extend the sync response) of recent server `xp_events`; client inserts them into the local ledger as synced on reconcile. The server already stores the rows. *(proposed 2026-06-14)*

- `[P2]` **Backfill on claim — fire a sync right after claim — Phase 3 Slice 3.** A guest claiming an account keeps the same `userId`, so `AppEvent.UserChanged` doesn't fire and their pending XP/chips flush only on the next foreground. **Acceptance:** the link-Apple / `refreshSession` claim path nudges `ProgressionRepository.sync()` (and chips/inventory/equipment) so a just-claimed account's progress lifts promptly; idempotency keys already make the replay safe. *(proposed 2026-06-14)*

- `[P2]` **XP anti-cheat hardening — when stakes rise (not now).** The server stores client-computed XP deltas with a per-event clamp (fine for play-money). When XP gates ranked status or IAP-equivalent rewards, switch the server to **derive** XP from synced hand facts + caps/rate-limits/claw-back instead of trusting the client delta. See `docs/wiki/state-authority-and-sync.md`. *(proposed 2026-06-14)*

### Auth & account onboarding

- `[P2]` **Session-expiry — remaining follow-ups.** The core seam shipped: a refresh the auth server *rejects* (`RestException` 401/403) signals `SessionRejectionBus` → `AuthRepository` tears down to `Unauthenticated(reason = SessionExpired, wasAnonymous)` (same `UserChanged(prev→null)` teardown as sign-out), `ProfileRepository` drops the cached-profile ghost, and **`AppViewModel` observes the edge → boots to `OnboardingRoute` (clear stack) + sets `hasUserOnboarded=false` + fires an error snackbar** (guest vs claimed copy). Transient/network/5xx keep the session. The **witnessed-reachability offline signal** also shipped: `NetworkReachability` watches the HTTP client's round-trip outcomes (any response = reachable, incl. 4xx/5xx; a no-response failure = unreachable after 2 in a row) and `AppState.isOffline` now combines it with the OS `ConnectivityObserver`, so the banner reflects "actually online" not just "OS thinks there's a path." **Remaining:** (a) the **cold-boot ghost** (no session at launch + cached profile surfaced as `Authenticated`, `reason = None`) — decide whether to surface `Fallback` so authed syncs don't fire blind; (b) optional richer anon UX (a "guest session ended" dialog with explicit Start-fresh vs Sign-in) — today the error toast + onboarding landing already carry the why + the how; (c) the planned **403 ban gate** can reuse the same `AppViewModel` session-edge observer. *(updated 2026-06-16)*
  **Hints:** seam is `SessionRejectionBus` (`:libraries:networking`) + `GatewayAuthTokenProvider` + `SupabaseAuthRepositoryImpl.onSessionRejected`; classifier `RefreshFailureClassifier`; routing in `AppViewModel.sessionExpired` + `App.kt`; offline signal is `NetworkReachability` (reported from `NetworkClientImpl`'s `HttpResponseValidator`) combined in `AppStateImpl`. Full rationale in [`decisions.md`](./decisions.md) 2026-06-16.

- `[P2]` **Degraded "account-creation pending" UX — remaining polish.** The degraded path is now functional + safe: creation self-heals (resume on relaunch via `PendingGuestAccountStore` + retry on the offline→online flip), the app-wide `AccountSetupBanner` communicates the pending state, and `PlayMultiplayerFeatureEntryPoint` bounces Unauthenticated sessions instead of hanging. Remaining nice-to-haves: (a) a manual **Retry** affordance on Profile/Settings near `SaveProgressBanner` (the banner + auto-retry already cover the common case); (b) consider a richer dialog vs. the thin banner; (c) device-verify the banner copy/placement. *(proposed 2026-06-09, mostly done 2026-06-09)*
  **Hints:** observe `GuestAccountCreator.state` (app-scoped, globally injectable); banner lives in `apps/compose/AccountSetupBanner.kt`.

- `[P1]` **Reconcile local bot-play progress when a degraded account is finally created.** While creation is pending (offline) the user plays bots and accrues XP/chips locally against `Profile.Fallback`. When `GuestAccountCreator` later succeeds, the server is authoritative: its balance + the pending `wallet_events` replay must converge **without double-counting** the provisional starter grant (`OnboardingStarterGrant`). Server balance wins; replay pending deltas on top; never re-grant. *(proposed 2026-06-09)*
  **Hints:** `ChipsRepositoryImpl.sync` already replays pending `wallet_events`; progression/XP sync is the riskier half. Decide whether degraded play mutates the server-bound ledger at all or stays purely local until creation.

- `[P2]` **Route new OAuth/email sign-ups through onboarding.** Deferred creation sends *guests* through onboarding, but a brand-new Apple/Google/email sign-up still goes straight to Home (returning sign-ins correctly skip). Routing new sign-ups through PickIdentity/grant needs a reliable new-vs-returning signal (`walletCreated` on first wallet sync, or a server "profile just created" flag). *(proposed 2026-06-09)*
  **Hints:** OAuth/Apple paths in `OnboardingViewModel` (`handleOAuth` / `finishAppleSignIn`) set `hasUserOnboarded=true` → Home.

- `[P2]` **Hold the splash for onboarding config + cycling load messages.** `OnboardingStarterGrant`/`OnboardingSuggestedName` ride the unauthed app-config tree but the splash doesn't wait for the fetch. Hold the splash until app-config resolves (or a timeout) and, after ~5s, show the `CyclingLoadingMessage` ("Shuffling the deck…", already built in `:libraries:ui`). Android's native splash can't show custom text, so this is a Compose loading gate handing off from the native/iOS splash. *(proposed 2026-06-09)*
  **Hints:** `AppViewModel.isReady` / `MainActivity` `keepOnScreenCondition`; `appConfigFlow` first emission; `DefaultBootLoadingMessages`.

- `[P1]` **ToS + Privacy consent in onboarding + Settings links.** A public launch (and both stores) needs the user to see/accept Terms + Privacy. Add a consent checkpoint in onboarding (links out, records acceptance) and "About / Privacy / Terms" entries in Settings. **Gated on:** the hosted ToS/Privacy URLs existing (see developer-todo legal). *(proposed 2026-06-15)*

### Gameplay & table UX

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

- `[P1]` **Full-screen level-up celebration.** A teal celebratory takeover — `RotatingDial` burst + the new level number + a warm line + Continue — with haptics + an entrance animation, in the level/XP teal identity (`accentSecondary` / `LevelProgressGradient`). Product call (when it shows, how it avoids spam) is in [`decisions.md`](./decisions.md) 2026-06-06; mock is [`level-up-screen.png`](./todo-assets/README.md). *(proposed 2026-06-06)*
  **Acceptance:** appears **only on Home**, never at the table (bots or MP); triggered by a persisted `AppData.lastCelebratedLevel` watermark — on Home, if `levelProgressFor(progression.totalXp).level > lastCelebratedLevel`, show for the current level then advance the watermark; a multi-level jump shows **once** (net level); existing users are seeded to current level on first run with **no** celebration; haptics + entrance animation fire.
  **Hints:** reuse [`RotatingDial`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/RotatingDial.kt) (re-skinned teal via `rayColor`/`glowColor`) and the shake→burst+haptics pattern from `AchievementUnlockReveal`; watermark mirrors `pendingProfileHighlight` in [`AppCache.kt`](../libraries/cards/src/commonMain/kotlin/com/cards/libraries/AppCache.kt); host overlay sits on Home like [`UserMessageOverlay`](../apps/compose/src/commonMain/kotlin/com/cards/UserMessageOverlay.kt). **Coexists with:** the at-table `AchievementCelebrationSheet` (different surface) and the server-dialog `InAppMessageManager` (level-up takes the Home foreground first). **Out of scope (need data — see [`backlog.md`](./backlog.md)):** per-level names ("Calculated"), the "better than N% of players" percentile, the level-gated "Unlocked" callout.

- `[P2]` **Level-up rewards — `level → reward` table + idempotent grant.** The keystone that makes "certain level-ups give a prize" real: a static `level → reward` table (chips / cosmetic / XP boost / Pick-a-Card chest) and an idempotent grant **on level-cross** — keyed `levelup_<level>` with a `highestLevelRewarded` watermark **separate from** the celebration's `lastCelebratedLevel`. Existing users backfill per policy (grant-from-now, no retro spam). Grant the prize when earned (offline-OK), celebrate later on Home. Full call (XP is client-local; reuse the chip-ledger / achievement-reward paths; server-derive when stakes rise) is in [`decisions.md`](./decisions.md) 2026-06-06 (level-up addendum) + [`state-authority-and-sync.md`](./wiki/state-authority-and-sync.md). *(proposed 2026-06-06)*
  **Acceptance:** crossing a rewarded level grants its prize exactly once (survives retries / reinstall / multi-level jump); existing users aren't blasted with historical rewards; rewards can be chips, a cosmetic, or a consumable.
  **Hints:** chips → [`ChipsRepository.addChips(idempotencyKey=…)`](../libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/ChipsRepository.kt); cosmetic → the achievement-reward grant path; consumables → see the Consumables items below. **Pairs with:** the level-up celebration (reveals what was granted).

### Consumables & rewards (V1.x / monetization)

Buyable, level-up-giftable consumables. Product + grant-model call is in [`decisions.md`](./decisions.md) 2026-06-06 ("Consumable reward items"); both lean on the grant models in [`state-authority-and-sync.md`](./wiki/state-authority-and-sync.md), and the level→reward table from the level-up decision can grant either. *(proposed 2026-06-06)*

- `[P2]` **XP Boost (2× XP for 30 min).** A chip-priced (and level-up-giftable) consumable modeled as a **time window**, not an owned count: buying/granting sets-or-extends a persisted `boostExpiresAt`; `XpCalculator` doubles awards while active; a "2× XP" countdown shows near the level/XP UI. Offline-friendly (XP is client-local).
  **Acceptance:** buying doubles XP for 30 min, extends on re-buy, persists across restart, shows an active indicator; the chip spend rides the wallet ledger.
  **Hints:** multiplier insertion is the per-mode `multiplier` in [`XpCalculator`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/XpCalculator.kt); store `boostExpiresAt` like `pendingProfileHighlight` in [`AppCache.kt`](../libraries/cards/src/commonMain/kotlin/com/cards/libraries/AppCache.kt). **Out of scope:** inventory quantity (it's a timer, not an owned count).

- `[P2]` **Pick-a-Card reward chest — server-rolled prize + shuffle animation.** A consumable chest: open → a magician-style card-shuffle/reveal → a prize (chips / card back / felt / boost) from a **weighted, server-owned loot table**. Server rolls + grants on open (idempotent); the client only animates + reveals the server's result; the "pick" is theatrical. **Online to open; ownable offline** ("opens when you reconnect"). Giftable on level-up. *(Bigger lift — phase it.)*
  **Phase A — inventory quantity + consumable kind:** add `quantity` (stockpile) + a consume path to inventory (today it's one permanent row per product) and a `chest_` product kind.
  **Phase B — server chest-open:** `POST /v1/me/chest/{id}/open` rolls the weighted loot table, grants the prize (chips → wallet ledger, cosmetic → inventory grant), idempotent per open.
  **Phase C — the pick screen:** full-screen pick/shuffle + reveal showing the server-rolled prize; offline "connect to open" gating.
  **Hints:** grant precedent is `grantApi.grantAchievement` / `GrantsRoutes`; chips prize via `ChipsRepository.addChips(idempotencyKey=…)`. **Interacts with:** wallet, inventory / my-items, shop, and level-up rewards.

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
  **Hints:** [`docs/testing-plan.md`](./testing-plan.md) — Rounds 1, 3, 4 are shipped (only Round 3's hand-history fixtures remain, gated on a real production playtest). **The `:apps:integration` module (Round 2) is built** — 7 e2e files bring up a real in-process Ktor server and play two-client hands, incl. fault injection (`ChaosPlayTest` + `FaultInjectingTransport`). Plan reconciled 2026-06-15: Round 2 is 7/10 (open: lossless-snapshot + all-`GameEvent`-variant wire round-trips, two-client nonce race); Round 5 chaos is partly covered (reconnect-resync + server-restart hydration) with the harder fault cases open; Round 6 (Compose UI for `PlayPokerScreen`) is the remaining unstarted round. **Out of scope:** emulator-based UI tests (captured in the plan's Deferred section with re-visit conditions).

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

### Remote config / feature flags

- `[P2]` **`PostgresAppConfigSource` + a targeted flag/rollout engine + a local admin UI.** Today app config is hardcoded in `InMemoryAppConfigSource` — every change is a server redeploy, and a value is all-or-nothing for every user. Replace it with a DB-backed source that can serve **different values to different audiences** and ramp rollouts, plus a small local UI to drive it. Big item; ship in slices. *(proposed 2026-06-08)*
  - **Phase 1 — DB-backed source (small, high value):** implement `PostgresAppConfigSource` bound to `ServerScope` (drops in for `InMemoryAppConfigSource` via the same `@ContributesBinding`); read the `key → value` tree from a Postgres table with a short in-memory TTL cache. Editable in the Supabase table editor → flags flip with **no redeploy**, live on the next client config refresh. This alone kills the redeploy pain.
  - **Phase 2 — targeting + rollout:** per-flag **rules** evaluated **server-side** in `GET /v1/app-config` (the endpoint already returns *resolved* values, so the client `ConfiguredValue` model is untouched — it just receives the resolved value). Rules match on an **evaluation context** in a defined order (first-match / priority wins), with an always-available **kill-switch** override.
    - **Axes:** platform (iOS/Android), **app version** (≥ / range), user id (allow/deny lists), location (country/region), locale, OS version, release channel (internal/beta/prod), account type (anon vs claimed), install/cohort date (new vs existing user), device class (phone/tablet). *(Decide each input's source — JWT claims (user id, anon), client headers (platform, app version, install id, locale — see `ClientHeaders`), IP-geo or a profile field (location).)*
    - **% rollout / A/B:** deterministic bucketing via a stable hash (`hash(userId + flagKey) % 100 < rolloutPct`) so a user keeps the same bucket across sessions and you can ramp 1% → 100%; optionally mutually-exclusive experiment variants.
    - **Audit:** a who/what/when change log.
  - **Phase 3 — local admin UI:** a small local web app (run on demand) that shows **(a)** every flag that exists (from the `ConfiguredValue` registry), **(b)** the value currently served per app version / audience, and **(c)** an editor to set values + rules along the axes and dial rollout %. Talks to an authenticated admin write-path (or directly to the config table).
  **Acceptance:** flipping a flag for "iOS, app version ≥ N, 10% of users" takes effect with no client release and no server redeploy; everyone else reads the client default; the admin UI lists flags + per-version served values and can edit rules.
  **Hints:** the seam already exists — `AppConfigSource` (server) + `ConfiguredValue` / `AppConfigMap` (client); some eval inputs live in `ClientHeaders` (install id, platform, app version) + the JWT. **Out of scope / decide first:** buy-vs-build — a hosted service (PostHog / Statsig / LaunchDarkly) may beat hand-rolling the rule engine before Phase 2; this todo is the thin in-house version over the existing seam.

### Billing integrity (monetized-launch blocker)

- `[P0]` **Server-side IAP receipt validation + server-authoritative purchase ledger.** Today `ShopViewModel.ConfirmPendingPurchase` drives `billingClient.purchase(...)` and credits chips **locally** on success — the server never validates the receipt, so a forged receipt mints chips. Before any real-money sale: `POST /v1/billing/redeem` validates against the Apple App Store Server API / Google Play Developer API, then grants chips through the server wallet ledger, idempotent per store transaction id. **Gated on:** store IAP products + store API credentials existing (developer-todo). *(proposed 2026-06-15)*
  **Hints:** grant precedent is `ChipsRepository.addChips(idempotencyKey=…)` / the wallet ledger; verify-before-credit — never trust the client for paid chips. Same "derive server-side when stakes rise" principle as the XP anti-cheat note above.

---

## D. Already on the books elsewhere

For completeness; don't re-derive these here.

- **Known sharp edges** — [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_known_sharp_edges.md) (auto-loaded).
- **Real platform billing impls (Play Billing / StoreKit 2)** — client scaffold + `FakeBillingClient` in place; provisioning store listings is the gate for the *client* path. **Server-side receipt validation is separate engineering and a monetized-launch blocker — see §C Billing integrity.**
- **OAuth UI gated by the `identity.*` config flags** (`GoogleSignInEnabled` / `AppleSignInEnabled`) — Apple/Google buttons wired but flagged off until dashboard credentials exist.
- **Username / bot-name localization** — V1.x / V2.
