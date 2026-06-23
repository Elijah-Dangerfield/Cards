# TODO

**Last reviewed:** 2026-06-23 · **Companion to:** [product/product-spec.md](./product/product-spec.md), [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

> **🎯 Top priority: bulletproof multiplayer (§B).** MP is now playable and durable — two humans play full hands end-to-end (B1), the room registry is persisted (B2), real-chip escrow is live (B3), and public matchmaking shipped. The top priority is **B6 (test coverage)** — MP is the load-bearing feature of the app and the V1 stack shipped with test gaps; [`testing-plan.md`](./testing-plan.md) lays out the remaining rounds. **B4 (pure non-member spectator)** is the main remaining gameplay slice.

The live punch list of actionable engineering work. Append, check off, and **delete** done items — they don't live here as history.

**Minimum viable context.** Every item is one bold title line + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long. (The `todo-maintainer` enforces this nightly.)

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct. Skip only if marked `(blocked on X)` and the blocker is real (waiting on another system, not on judgment).

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). When an item points at a file/system, assume it exists — the work is the gap, not a greenfield build.

---

## A. UX gaps observed in the build

### Progression & XP (server)

- `[P2]` **Graduate lifetime hand + achievement-progress counters to the server.** The `progression` hand counters (handsPlayed/won/folded/lostAtShowdown/botHandsPlayed) and the achievement *progress counters* (no-bust streak, per-bot wins, …) are client-local — they zero on account switch / reinstall and aren't re-hydrated, so a switched-in account shows correct XP/level + earned badges but zeroed hand counts. Decision is to lift them (`decisions.md` 2026-06-15 — accept-reset rejected for these); carry the counters in their respective syncs. The hand counters double as the server `hands_finished` the MP-achievement floor wants. *(proposed 2026-06-14)*

- `[P2]` **XP anti-cheat hardening — when stakes rise (not now).** The server stores client-computed XP deltas with a per-event clamp (fine for play-money). When XP gates ranked status or IAP-equivalent rewards, switch the server to **derive** XP from synced hand facts + caps/rate-limits/claw-back instead of trusting the client delta. See `docs/wiki/state-authority-and-sync.md`. *(proposed 2026-06-14)*

### Auth & account onboarding

- `[P1]` **Session-expiry → blocking retry/logout screen (not a snackbar).** When a session the user *used to have* can't be authed, falling back to a guest profile or bouncing to onboarding is wrong — we should **block** and try to restore their real session. Replace today's error snackbar + auto-route-to-onboarding with a full blocking screen modeled on `BlockingErrorScreen`: **Retry** (re-attempt the token refresh / session re-init) and **Logout** (explicit sign-out → onboarding). Anonymous-aware copy: if the rejected session was anonymous, warn that logging out loses their progress (no recovery path) before they confirm. Only an explicit Logout tears down to onboarding. Covers both the running-session 401 and the cold-boot case (a persisted-but-dead session that fails on the first authed call). *(found in 2026-06-19 playtest; supersedes the earlier "accept the snackbar" call — [`decisions.md`](./decisions.md) 2026-06-19)*
  **Acceptance:** a rejected session shows a blocking screen with a working Retry (recovers in place, identity intact, when the token refresh succeeds) + Logout; anonymous users see the "you'll lose your progress" warning before logging out; no blind authed syncs fire behind it.
  **Hints:** seam is `SessionRejectionBus` + `SupabaseAuthRepositoryImpl.onSessionRejected` (carries `wasAnonymous`) → `AppViewModel.sessionExpired` → `App.kt` (currently the snackbar). Present like `BlockingErrorScreen` (`:libraries:navigation:impl`, routed `screen<…>`, hides the bottom bar). Retry re-runs the token refresh in `GatewayAuthTokenProvider` / session re-init.

- `[P2]` **Degraded "account-creation pending" UX — remaining polish.** The pending path is functional + safe; remaining nice-to-haves: a richer dialog vs. the thin `AccountSetupBanner`; device-verify banner copy/placement; optionally mirror the Retry near `SaveProgressBanner` on Profile/Settings. *(proposed 2026-06-09)*
  **Hints:** observe `GuestAccountCreator.state`; banner lives in `apps/compose/AccountSetupBanner.kt`.

- `[P1]` **Reconcile local bot-play progress when a degraded account is finally created.** While creation is pending (offline) the user plays bots and accrues XP/chips locally against `Profile.Fallback`. **Decided:** degraded play stays **purely local** until the account is created — it does not write the server-bound ledger. When `GuestAccountCreator` succeeds the server is authoritative: replay the pending local deltas on top of the server balance **once**, never re-granting the provisional starter grant (`OnboardingStarterGrant`). *(proposed 2026-06-09)*
  **Hints:** `ChipsRepositoryImpl.sync` already replays pending `wallet_events`; progression/XP sync is the riskier half — same local-until-creation rule.


- `[P2]` **Route new OAuth/email sign-ups through onboarding.** Deferred creation sends *guests* through onboarding, but a brand-new Apple/Google/email sign-up still goes straight to Home (returning sign-ins correctly skip). Routing new sign-ups through PickIdentity/grant needs a reliable new-vs-returning signal (`walletCreated` on first wallet sync, or a server "profile just created" flag). *(proposed 2026-06-09)*
  **Hints:** OAuth/Apple paths in `OnboardingViewModel` (`handleOAuth` / `finishAppleSignIn`) set `hasUserOnboarded=true` → Home.

- `[P2]` **Record ToS/Privacy consent acceptance.** Onboarding now shows a passive "by continuing, you agree to Terms + Privacy" line on the Welcome step (links via `LegalUrls`), and Settings links both docs — display requirement met. Remaining: persist an acceptance record (accepted version + timestamp) for a real audit trail if/when legal wants one. *(proposed 2026-06-15)*

- `[P2]` **Surface responsible-play help beyond Settings.** A "Responsible play" row (→ NCPG / 1-800-GAMBLER, via `LegalUrls.RESPONSIBLE_PLAY`) now sits in Settings. Two contextual placements were deferred: (a) a quiet "Play responsibly" link on the chip purchase confirm sheet (`features/shop/.../PurchaseConfirmSheet.kt`), where real money is spent; (b) a gentle, dismissible nudge after risky patterns (repeated chip buys after going bust, rapid repeat purchases) — needs an economy-event hook, which doesn't exist yet (`AppEventBus` is lifecycle-level only). *(proposed 2026-06-23)*

- `[P2]` **Verify network-required surfaces honor the `Profile.Fallback` gating rule.** Walk Home / Shop / Profile / Edit Profile / Claim / Inventory / Multiplayer / Settings and confirm each matches the rule — most already do; a verification pass, not a redesign. *(proposed 2026-06-09)*
  **Acceptance:** reads render cached content; server-mutating surfaces soft-gate (visible, affordances disabled with an offline hint); money + multiplayer hard-gate.
  **Hints:** the genuinely network-required surfaces are multiplayer, real-money purchase, and account claim. Edit Profile's avatar picker falls back to a hardcoded starter list when the pack fetch never landed — confirm a patchMe from there surfaces errors cleanly. Offline-first model: [`state-authority-and-sync.md`](./wiki/state-authority-and-sync.md).

- `[P1]` **Cold-boot-offline load + fallback misbehaves — wrong errors, no cached profile.** A no-internet cold boot shows the "connection issues" banner correctly, but downstream is wrong: creating an MP room pops the "account needed" dialog (should read as a connection/offline problem off a cached profile — not as account-less); Edit Profile shows "couldn't save, sign in first" *even after connection returns*; and sign-out → continue-as-guest skips the "new here" banner. Evaluate the load/fallback chain end-to-end — what we load, what we fall back on, and how each fallback colors error copy + gating; offline writes should queue and send on reconnect, not hard-error. *(found in 2026-06-20 cold-boot playtest)*
  **Acceptance:** offline MP entry reads as a connection problem (not "account needed"); a returning user offline uses their cached profile; Edit Profile saves when online / queues offline instead of a false "sign in first"; sign-out→guest shows the "new here" banner.
  **Hints:** pairs with the `Profile.Fallback` gating-verification item above + the session-expiry blocking screen; offline write-through is the [state-authority-and-sync](./wiki/state-authority-and-sync.md) reconcile path. **Open product call:** should MP require a real account? (→ `developer-todo.md`).

### Gameplay & table UX

- `[P2]` **Per-hand decision capture + batch upload (for a future heat-map).** Capture per-hand attributes (folded / called / raised / bluffed / showdown-won + outcome) to a local Room table at hand-end; batch-upload when 50 entries accumulate or 24h passes; server stores rows + exposes a snapshot endpoint.
  **Acceptance:** local capture on every resolved hand; batch fires on threshold or timer; endpoint returns a snapshot.
  **Hints:** `:libraries:storage`; achievement counter logic is precedent. **Out of scope:** the heat-map visual; bot tracking. **Worker note:** sketch the architecture in the in-flight Approach line before coding — direction is ambiguous.

- `[P1]` **Tap-an-opponent sheet — remaining affordances.** Add the human-variant "Add friend" affordance (pairs with the friend graph) and "view full profile" tap-through once profile-of-a-stranger is a real route. *(This sheet is the at-table **Player Card** surface — see the Player Card feature below + `decisions.md` 2026-06-06.)*

- `[P2]` **Multiplayer game summary + recent-games history (MP only).** No post-game summary exists — the end-of-hand XP/achievement dialog is transient and nothing is stored. When an MP game ends (or a player leaves), show a summary: chips won/lost, XP gained, achievements earned during that game. Persist per-game results so Home can show a "recent games" list, each tapping into its summary. Multiplayer only. *(found in 2026-06-19 playtest)*
  **Acceptance:** finishing/leaving an MP game shows a summary (chips +/-, XP, achievements that game); Home lists recent MP games, each opening its summary.
  **Hints:** greenfield — needs a per-game result record (client Room table or a server endpoint; the server already logs `hands_finished`). Distinct from the friend-graph `RecentlyPlayedWithStrip` (opponents-to-friend, not results). The chips delta depends on the MP wallet settlement (B3 buy-in/cash-out above) being real. **Worker note:** sketch the data model in the in-flight Approach before coding.

- `[P2]` **Emote button glyph isn't optically centered.** The play-poker emote trigger ([`TopBarEmojiButton`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/EmojiTray.kt) → DS [`EmojiButton`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/icon/EmojiButton.kt)) centers its circular bounding box correctly, but the emoji glyph sits slightly up-and-left inside it — the text line-box midpoint ≠ the glyph's visual midpoint (the KDoc already notes the vertical half). *(proposed 2026-05-31)*
  **Acceptance:** the glyph reads optically centered in the circle at every `Size`.
  **Hints:** the `Box`/`Text` in `EmojiButton.kt`; likely needs a glyph-vs-line-box offset, not just `Alignment.Center`. **Worker note:** needs Studio to eyeball against the size-scale `@Preview`.

- `[P1]` **Home active-room banner — back the reactive flow with a server-pushed source.** `observeActiveRooms()` is a client-side projection: room changes that don't originate on this device (host closes the room elsewhere, server-side forfeit/grace expiry, a second device) don't reflect until the next `getActiveRooms()`. Hang it off the durable membership source so it's authoritative regardless of who mutated the room. *(proposed 2026-05-31)*
  **Acceptance:** the banner reflects room changes made off-device without a manual refresh.
  **Hints:** [`RoomRepositoryImpl.observeActiveRooms`](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/RoomRepositoryImpl.kt) holds the in-memory `MutableStateFlow` today; the durable source is [B2](#b2--persisted-room-membership) (persisted membership), and presence pushes pair with the [online-presence WS signal](#social-graph--friends--load-bearing-for-v1x).

### Stats & progression

- `[P3 — gate on leagues/leaderboards]` **Server-derive level-up grants instead of trusting the client.** Today the client grants offline `levelup_<level>` rewards (chips → `/v1/me/wallet/sync`, cosmetics → `/v1/me/grants/level-cosmetic/{id}`) and the server doesn't re-derive the level: the wallet sync applies whatever chip `delta` the client sends (only guard is no-below-zero), and the cosmetic grant gates by *product* allowlist but not by *level reached*. So a tampered client can mint level rewards it didn't earn. **For play money with no cash-out this is "the cheater cheats themselves" — fine until XP/chip totals feed leagues/leaderboards (or anything IAP-equivalent).** That's the trigger to do this; not before. The fix: on progression-sync the server derives level from its own reconciled `total_xp` against the curve config it already serves, grants the `levelup_<level>` rewards itself (idempotent), and ignores/caps client-claimed amounts. The client keeps granting optimistically for instant offline UX; the two reconcile on the idempotency key. This is exactly the "server-derive when stakes rise" step in [`state-authority-and-sync.md`](./wiki/state-authority-and-sync.md).
  **Note on the curve math:** the server already *owns* the curve — it serves `progression.levelCurve` as app-config; the client just decodes it with `DefaultLevelCurve` as the offline fallback. The only thing missing server-side is the ~15-line interpreter (`levelProgressFor` / `xpToLevelUpFrom` in [`Level.kt`](../libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/Level.kt)). **Duplicate those into `:apps:server` with the [`LevelTest`](../libraries/cards/src/commonTest/kotlin/com/cards/libraries/cards/LevelTest.kt) vector copied alongside — do *not* extract a shared module for 15 stable lines.** Level is a pure function of (XP, curve); both sides derive from inputs they agree on after sync, so they converge by construction. See [`decisions.md`](./decisions.md) 2026-06-21.
  **Hints:** curve lives in `ProgressionConfig.levelCurve()`; client grant path is `LevelUpRewardGranter`; server grant precedents are the wallet ledger (`PostgresWalletRepository.apply`) and `LevelGrantableProducts` / `GrantsRoutes`.

- `[P2]` **Level-up celebration — bigger entrance ceremony.** The reveal lands flat. The dial currently scale+fades in via a `MediumBouncy` spring with one `LongPress` haptic. Make it *land*: a "slam in" (overshoot scale that snaps, or a quick fall-and-settle), a punchier/sequenced haptic (impact on the slam, not just the start), and confetti on arrival. Keep it on the teal/progression identity and bake the feel into the DS component, not the host. **File:** `LevelUpCelebration.kt` in `:libraries:ui`. Confetti is a new DS primitive — check whether one exists before writing it. *(proposed 2026-06-21)*

### Consumables & rewards (V1.x / monetization)

Buyable, level-up-giftable consumables. Product + grant-model call is in [`decisions.md`](./decisions.md) 2026-06-06 ("Consumable reward items"); both lean on the grant models in [`state-authority-and-sync.md`](./wiki/state-authority-and-sync.md), and the level→reward table from the level-up decision can grant either. *(proposed 2026-06-06)*

- `[P2]` **Pick-a-Card reward chest — server-rolled prize + shuffle animation.** A consumable chest: open → a magician-style card-shuffle/reveal → a prize (chips / card back / felt / boost) from a **weighted, server-owned loot table**. Server rolls + grants on open (idempotent); the client only animates + reveals the server's result; the "pick" is theatrical. **Online to open; ownable offline** ("opens when you reconnect"). Giftable on level-up. *(Bigger lift — phase it.)*
  **Phase A — inventory quantity + consumable kind:** add `quantity` (stockpile) + a consume path to inventory (today it's one permanent row per product) and a `chest_` product kind.
  **Phase B — server chest-open:** `POST /v1/me/chest/{id}/open` rolls the weighted loot table, grants the prize (chips → wallet ledger, cosmetic → inventory grant), idempotent per open.
  **Phase C — the pick screen:** full-screen pick/shuffle + reveal showing the server-rolled prize; offline "connect to open" gating.
  **Hints:** grant precedent is `grantApi.grantAchievement` / `GrantsRoutes`; chips prize via `ChipsRepository.addChips(idempotencyKey=…)`. **Interacts with:** wallet, inventory / my-items, shop, and level-up rewards.

### Social graph + friends — load-bearing for V1.x

Home exposes three surfaces that need this system to work: the friends strip with presence, the "recently played with" shelf with add-friend, and the friend-requests inbox on profile. All currently fake or no-op.

**Locked rule:** the only way to friend someone is the "recently played with" shelf — no search-by-handle, no suggestions. Empty states must say so.

- `[P1]` **Friend-via-play empty state on the Profile social section.** When the friend-requests inbox lands on `ProfileScreen.kt`, carry the "you can only friend people you've played with" empty-state copy there too. (Home strips already done.)

- `[P1]` **Online-presence signal.** Server emits presence on WS connect/disconnect + stores last-seen / current-room; client subscribes once per session, filtered to friend ids. [`FriendsStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) already takes `List<FriendOnline>`.

- `[P1]` **Friend requests inbox — Profile section + Home badge.** Inbox section on `ProfileScreen.kt` (pending inbound, accept/decline). `FriendsStrip.kt` shows an "N friend requests" badge when `pendingRequests > 0`; tap routes into the inbox. Strip survives with zero friends online if requests are pending.

- `[P2]` **Banned / suspended account enforcement — server gate + client blocking screen.** A dashboard ban only sets `auth.users.banned_until`; the server verifies JWT signature/exp only, so a banned user keeps playing until their token fails to refresh — and the client then maps that to a generic `SessionExpired`, with no ban-specific UI. Build the [§7.2](./product/product-spec.md) contract: protected routes return `403` with a typed body `{reason: "banned"|"suspended", until: ISO-8601|null, appeal_url}`; client parses `reason` and shows `BlockingErrorScreen`. **Locked:** the wire carries machine-readable data only; user-facing copy stays client-side in `:libraries:resources` keyed off `reason` (the server is plain JVM Ktor and must not depend on the Compose resources module). Implies an app-level moderation table, since the native flag carries no reason/appeal_url.
  **Acceptance:** a flagged account gets `403 {reason}` (not a silent expiry) and sees `BlockingErrorScreen` with localized copy + appeal; a clean account is unaffected.
  **Hints:** [`Authentication.kt`](../apps/server/src/main/kotlin/com/cards/server/plugins/Authentication.kt); `BlockingErrorScreen` in `:libraries:navigation:impl`. **Worker note:** ship the minimum slice first — route banned→`BlockingErrorScreen` instead of silent expiry. **Out of scope:** auto-ban triggers, shadow-bans, the review dashboard.

- **Out of scope for V1.x:** friend suggestions, invite-via-share-link, push notifications for requests, group chat.

### Rooms redesign follow-ups

The rooms handoff (`docs/design-handoff/rooms/SPEC.md`) shipped as UI. These are the deferred slices.

- **`[P3]` Delete the orphaned `PublicLobby` / `PublicNextRound` screens.** Public matchmaking shipped, but Searching now routes **directly to the play screen**, so `PublicLobbyScreen.kt` and `PublicNextRoundScreen.kt` (+ their routes still registered in [`RoomsFeatureEntryPoint`](../features/rooms/impl/src/commonMain/kotlin/com/cards/features/rooms/impl/RoomsFeatureEntryPoint.kt)) are dead — nothing in the live flow navigates to them. Remove the screens + route registrations, *or* deliberately repurpose `PublicNextRound` if you want a mid-hand-join interstitial (the server already supports scrubbed mid-hand spectate). Confirm no other entry point references the routes before deleting.

- **`[P2]` Deep-link + share-invite in the private lobby.** The placeholder "Share invite" button (which only copied the code, same as Copy) was removed. The real feature is a shareable **deep link into the lobby** (`PrivateJoinRoute` / `LobbyRoute(prefilledCode=…)`) opened via a cross-platform OS share sheet — so a tapped link lands the invitee straight in the join/lobby flow, not just a bare code. **Note:** this is the real home for the cross-platform OS share-sheet infra — the matchmaking "Share" CTA was dropped from the Open-to-anyone plan for the same missing-infra reason (see [decisions.md](./decisions.md) 2026-06-23). Build it once here and both surfaces can use it.

- **`[P2]` Matchmaking should present a *list of found tables* and let the user choose which to join.** **Problem:** today the flow auto-joins the *first* server-side match — `findOrJoinPublic` returns one room (join-or-create) and `PublicSearchingScreen` seats the user silently. With range-based matching (the Find screen is a free-form range slider, `room.buyIn in minBuyIn..maxBuyIn`) several tables at different stakes can qualify, and the user has no say in which. **Owner direction (2026-06-23):** a results/chooser flow is the better model — show the matches found (each with its buy-in + seat count) and let the user pick one to join, accepting that table's buy-in explicitly.
  **Acceptance:**
  - **Matches found →** a new chooser screen lists qualifying tables (buy-in, players seated/max, maybe humans-vs-bots); tapping one joins it. (Today's silent auto-join becomes "join the one you picked.")
  - **None found →** the existing honest fallback (`SearchPhase.BotFallbackOffer` in `PublicSearchingScreen`): play disclosed bots for real chips, keep waiting, or bow out.
  - **A real player matches while you're deciding / playing bots →** they can still join later (the bot table already steps aside for real players; the chooser should reflect newly-appeared tables live, not a frozen snapshot).
  **Hints:** server side, this means returning *candidates* (a list) rather than join-or-create-one — likely a new `GET`/`find` shape alongside `findOrJoinPublic` in `InMemoryRoomService`, leaving create-on-accept to a follow-up call. Client: new route + screen between `PublicSearchingRoute` and the table, wired in `RoomsFeatureEntryPoint`; `PublicSearchingViewModel` grows a "matches" phase. Applies to **both** Open and Public tables, not just Open. Supersedes the old "snap host stakes to canonical tiers" idea — **don't** snap a host's deliberate buy-in. Background in [decisions.md](./decisions.md) (2026-06-23 "Open to anyone").

- **`[P3]` Room socket decode drops frames on an unknown enum *value* (forward-compat).** **Problem:** room snapshots flow over the websocket as `RoomSocketEventDto.Snapshot(room: RoomDto)`, decoded with `RoomSocketJson`, which sets `ignoreUnknownKeys` but **not** `coerceInputValues`. So a future server enum *value* the client doesn't know (e.g. a 4th `RoomStatus`/`RoomVisibility`) throws `SerializationException` and the frame is dropped at the call site (`ReconnectingRoomSocket`) — the documented socket strategy, but it means the room UI stops updating after such a server change until the client ships a matching enum. (The HTTP path is already safe: release `NetworkJson` coerces, and `RoomDto.status`/`visibility` now have defaults for coercion to land on — see the 2026-06-23 fix.) **Options:** (a) give `RoomSocketJson` `coerceInputValues = true` so unknown enum values coerce to the property default instead of dropping the whole frame — but **build-gate it** like `NetworkJson` (`= !BuildInfo.isDebug`) so debug stays strict and catches contract drift loud; (b) leave as-is and rely on the drop-frame strategy + shipping clients ahead of server enum additions. **Hints:** [`RoomSocketJson.kt`](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/RoomSocketJson.kt); the drop happens in `ReconnectingRoomSocket`. Pre-existing, not introduced by "Open to anyone"; surfaced during its wrap-up. Background in [decisions.md](./decisions.md) (2026-06-23).

### From the 2026-06-22 owner playtest

A batch of small UX directives the owner filed via in-app feedback in one session. Grouped here for skimmability; the maintainer can redistribute into the topic sections above. Each links its Sentry report.

- `[P2]` **Stats page: distinct-players-played-with count — client wiring.** Server slice shipped: `RecentOpponentsRepository.countDistinctOpponents` + `GET /v1/me/stats` now returns `{distinctOpponentsPlayed}`. Remaining: call that endpoint client-side, carry the count onto `StatsState` (its own field, or fold into `Progression` if/when it gains a sync), and render a `StatTile` in `StatsScreen.LifetimeStatsGrid`. *(feedback CARDS-P)*
  **Hints:** `StatsScreen.LifetimeStatsGrid` / `StatsViewModel` (today driven only by `Progression` — needs a new read path for the server-only stat). Count is MP-only (bot hands don't populate the table). Sentry [CARDS-P](https://elijah-dangerfield.sentry.io/issues/CARDS-P).

- `[P2]` **Debug feedback swipe is unreliable inside scroll views.** The right-edge swipe to open the feedback screen often takes a few tries, mostly when the user is already in a scroll view (gesture conflict). *(feedback CARDS-Y; debug-only feature from `fd5aeec8`)*
  **Hints:** the right-edge swipe detector competing with scroll containers. Sentry [CARDS-Y](https://elijah-dangerfield.sentry.io/issues/CARDS-Y).

- `[P2]` **Remove the "Neon" table theme too.** Owner confirmed (2026-06-22): like the Sunset table theme already removed this cycle, the `table_neon` table theme is useless — a "table theme" and a "felt" are visually identical in V1 (both just recolor the felt). Delete it. Unlike sunset, `table_neon` is unlock-only (not in the `GET /v1/products` shop catalog as of V51), so there's no purchasable product to pull — but the same client + grant plumbing applies. *(feedback CARDS-18)*
  **Hints:** mirror the sunset removal — append-only `DELETE FROM products WHERE id = 'table_neon'` migration, drop the `table_neon` arm from `feltForProductId` (`:libraries:ui`), and repoint any catalog/preview references. `EquippedFelt.Neon` can go too (no felt uses it, unlike Sunset). Update `CosmeticCategoryTest` / `EquippedFeltMappingsTest` / `PostgresProductCatalogSourceTest` the same way the sunset removal did. Sentry [CARDS-18](https://elijah-dangerfield.sentry.io/issues/CARDS-18).

---

## B. Multiplayer hardening

**Architecture (2026-05-29):** snapshot-only state, OTel for debugging — see [decisions.md](./decisions.md).

**State of play (2026-06-23):** rooms work end-to-end (create / join / leave / seat allocation / presence) and are **persisted** (B2); the server runs **fully authoritative** hands; the client consumes server-driven gameplay (B1); real-chip **escrow is live** (B3 — sit-down debits, funded-only deal, cash-out on every exit, disclosed-bot subsidy); and **public matchmaking shipped**. Remaining: **B6 (test coverage)** — bulletproofing MP before real users — and **B4 (pure non-member spectator)**.

### B0 — Server-side state durability

_Shipped._ `room_sessions` is written through the per-session mutex on every mutation; the registry lazy-hydrates on a code-miss.

### B1 — Client gameplay loop

_Shipped._ Room socket exposes `gameplayFrames` on a sibling flow; [`RemotePokerSessionFactory`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/RemotePokerSessionFactory.kt) implements `PokerSession` over the server's broadcasts; [`PlayMultiplayerRoute`](../features/room/src/commonMain/kotlin/com/cards/features/room/PlayMultiplayerRoute.kt) + its entry point drive the existing `PlayPokerViewModel` against it; lobby's "Start hand" sends `ClientFrame.StartHand` and auto-promotes the effective host on disconnect. Hardening + tests live in **B6**.

### B2 — Persisted room membership

_Shipped._ [`InMemoryRoomService`](../apps/server/src/main/kotlin/com/cards/server/data/InMemoryRoomService.kt) is a write-through hydrated cache over durable `rooms` + `room_members` (`V65`); create / join / leave persist before responding, and the registry lazy-hydrates on a code-miss.

### B3 — Gameplay items

_Buy-in / escrow shipped._ The cash-game escrow is live: sit-down debits the buy-in, the deal funds only affordable seats (`dealFundedHand`), every exit path cashes the current stack back (`cashOut` on leave/disconnect), `Rebuy` busts back in, and the matchmaking disclosed-bot subsidy (`V68`) is wired. Full design in [`mp-chip-buyin-economy.md`](./plans/mp-chip-buyin-economy.md) — **its top banner still says "dormant"; correct it to shipped.**

_Multiplayer leave / last-human-left shipped._ Server frees the seat on leave (`removePlayer` ghost-seat fix + `forfeitSeat` fold + `cashOut`); the client routes off a dead table via `opponentsLeft` ("last human standing") and `roomClosed`, with the real-MP bust dialog.

- `[P2]` **Orphaned-room policy — read-only spectator downgrade.** Seat-forfeit on grace expiry already lands (`forfeitSeat`); the remaining half is downgrading the forfeited member's WS subscription to **read-only spectator** instead of closing the socket, with `GET /v1/me/active-rooms` driving a Rejoin / Forfeit banner. **Depends on:** B4 (pure non-member spectator — the socket auth that allows a seatless subscriber).

### B4 — Spectator role

- `[P2]` **Pure non-member spectator = WS subscriber without a seat.** The mid-hand-join flavor of spectating already works and is tested (a matched joiner sees the scrubbed, no-hole-cards view, then is dealt in at the next boundary). What's missing is a *non-member* spectator: the socket still rejects a subscriber who isn't a room member (`RoomSocketRoutes` closes the socket with "not a member"). Extend the auth check so a seatless subscriber gets the scrubbed-for-everyone view and the server rejects `SubmitIntent` from them. Friend rooms stay closed to non-members.
  **Hints:** the membership gate in `RoomSocketRoutes.kt` (the early "not a member" close). **Out of scope:** public-room discovery, spectator chat.

### B6 — Bulletproof MP + engine test coverage

- `[P0]` **Implement the multiplayer + gameplay-engine testing plan in [`testing-plan.md`](./testing-plan.md).** MP is the load-bearing feature of the app; the V1 stack shipped with major test gaps in the new wiring (lobby's new MP paths, `RemotePokerSessionFactory`'s seat-derivation logic, end-to-end wire-format contract). Six rounds of work, ordered by impact-per-hour: Round 1 closes the silent-failure surfaces on the new MP code; Round 2 stands up a new `:integration` JVM module that brings up a real Ktor server in-process and points real clients at it (KMP + same-repo server makes this feasible where most codebases can't); Round 3 SUPER-tests the engine via property-based invariants + cross-product action tables + edge scenarios; Round 4 fills the missing server gameplay-flow plumbing tests; Round 5 chaos / fault injection (reconnects mid-hand, host promotion races); Round 6 adds Compose UI tests for `PlayPokerScreen`. *(proposed 2026-05-30)*
  **Acceptance:** every round checkbox in `testing-plan.md` is ticked. Don't pick this up as a single sprint — interleave each round with other feature work; the doc IS the running history.
  **Hints:** [`docs/testing-plan.md`](./testing-plan.md) tracks per-round status — the doc IS the running history. Rounds 1/3/4 shipped; Round 2 (`:apps:integration`, in-process Ktor + two-client hands) heavily used — public matchmaking now has front + back + integration coverage plus telemetry spans; Round 5 chaos partly covered; Round 6 (Compose UI for `PlayPokerScreen`) unstarted. **Out of scope:** emulator-based UI tests.

- `[P2]` **Integration tests should play full multi-hand games, not thin slices.** Owner review: the `:apps:integration` coverage feels thin — he expected to see full hands actually played end-to-end. Strengthen Round 2 so the in-process server + clients play complete consecutive hands (deal → streets → showdown → next hand), not just connection/seat/handshake assertions. *(feedback 2026-06-22; sharpens B6 Round 2)*
  **Hints:** the two-client harness in `:apps:integration` / `testing-plan.md` Round 2; the now-fixed bot-room hand-end stall (CARDS-16) is exactly the kind of bug full-hand coverage would have caught. Sentry [CARDS-10](https://elijah-dangerfield.sentry.io/issues/CARDS-10).

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
