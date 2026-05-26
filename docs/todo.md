# TODO

**Last reviewed:** 2026-05-25 · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md), [backlog.md](./backlog.md)

The live punch list of actionable engineering work. Append, check off, and **delete** done items — they don't need to live here as history. Add a [decisions.md](./decisions.md) entry **only** when an item resolved a non-trivial architectural call worth not re-litigating (see decisions.md header for what qualifies). Most items just get crossed off and removed.

When an item points at a file path or system, the assumption is that path/system already exists — the work is the gap, not a greenfield build.

**Loose item template** (freeform — no enforcement, but the more of these you fill in, the safer it is for an automated worker to pick up):

> **Problem:** what's wrong / missing.
> **Acceptance:** how we know it's done.
> **Files / hints:** where to start looking.
> **Out of scope:** what NOT to drag in.

Everything in this file is worker-pickable. Items that need a human action — device QA, dashboard config, content writing, product decisions — live in [`docs/developer-todo.md`](./developer-todo.md) instead. Per-cycle follow-ups tied to a specific PR's diff live in the PR's "Heads up" section.

---

## A. UX gaps observed in the build

These are bugs / polish items found playing the app or scanning the code. Cheap individually; collectively the V1 quality bar.

### Design system — dialog & sheet primitives

- **Extend `Dialog` / `BottomSheet` with preset overloads + content slots, then migrate `Base*` callsites off the escape hatch.** Mental model: `BaseDialog` / `BaseBottomSheet` are the "base model of a car" — no frills, full control. `Dialog` / `BottomSheet` are the trim packages that bake in the app's conventions (title typography per level, body slot, primary/secondary button slots, optional `topAccessory`) so callsites get a congruent look for free. Today `Dialog` callsites hand-roll their own title typography and `HandRankingsCheatSheet` opts into `@LowLevelDSComponent` because the opinionated `BottomSheet` doesn't expose enough hooks for its content. Work splits in two: (a) extend the opinionated wrappers with preset constructors / slots — `title: String` *or* `title: @Composable () -> Unit`, body slot defaulting typography + color via `LocalContentColor`/`LocalTextStyle`, primary/secondary button slots — so the common case is a single preset call; (b) sweep current `@LowLevelDSComponent` opt-ins and migrate the ones that just want the standard look. Genuine one-offs stay on `Base*`. **Out of scope:** redesigning the chrome itself — this is about hoisting existing informal conventions into the DS. Pairs with the top-accessory bullet above.

### DS consistency — bake defaults into components

- **DS components should own their visual defaults — typography, radius, surface, shape — so callsites rarely override.** Today a lot of callsites pass `typography = Body.B500`, `RoundedCornerShape(12.dp)`, `Color(0xFF...)`, `Box { background, clip, padding }` because the DS primitive doesn't bake in the right default for its role. Goal: every DS component (`Text`, `Button`, `ListItem`, `Dialog`, `BottomSheet`, `BasicTextField`, etc.) chooses a sensible default for its slot, and only one-off escapes pass overrides. When N callsites pass the same override, that's a DS-default gap — fix it at the component, not at every callsite. A consistent UI should be the path of least resistance for new code. Pairs with the `Dialog` / `BottomSheet` preset bullet above.

### Achievements — bot-vs-human split

- **Add `FIRST_BUST_DEALT_MP` / `BUST_DEALT_5_MP` sibling ids per [`docs/achievements-spec.md`](./achievements-spec.md) — gated on Phase 4.2.** The two tier-blind re-anchors (DONT_CALL_IT_COMEBACK → ≤10 BB / ≥100 BB; POT_5000 → ≥25× BB) have landed. The MP-keyed sibling ids depend on Phase 4.2 (server-authoritative gameplay) so the grant can fire from a trustworthy seam — re-pick this once Phase 4.2 work starts. **Files / hints:** registry + `AchievementMode` live in `:libraries:cards`; counter logic in `AchievementRepositoryImpl.kt`. **Out of scope:** brand-new achievements, V2 deferrals, calibration of XP/chip rewards (Phase-8 economy modeling).

### Catalog gating — unlock-only vs purchasable

- **Wire the earned-grant path + render unlock-only items as Earned.** [product-spec.md §4.2](./product/product-spec.md#42-the-unlock-only-catalog) is structurally load-bearing: legendary achievement cosmetics, league-tier cosmetics, RFT cosmetics, achievement-chain cosmetics are **never in the shop, ever.** Locked decision: no separate Trophy Case surface — earned items land in My Items. Remaining engineering:
  - **Server (achievement side — landed, security gap open):** `POST /v1/me/grants/achievement/{id}` resolves the achievement id through `AchievementRewards` and grants via `recordEarnedGrant`. Client posts after every `AchievementRepositoryImpl.recordHand` newly-earned, then triggers an inventory sync. **Open:** see the "Harden the client-grant endpoint" bullet below — today any authed user can request any mapped id with no proof.
  - **Server (league-finish side — still open):** wire the league season-end tally to call `recordEarnedGrant` for any tier-tied cosmetic. League-finish has no client-trigger seam — needs to fire from the server-side close routine.
  - **Client:** a celebratory unlock dialog at the moment of earning — prestige lives at earn-time, not on the shelf. Pairs with the "Earn-source attribution" entry in [backlog.md](./backlog.md) for the deeper "from Comeback Kid achievement" subtitle work.
  - **Design lever — "unlock and buy":** a family can have both an earnable variant and purchasable variants. Earning the first is a taste-test that pre-qualifies the user as a buyer for the rest.

- **Rethink the "earned cosmetic" surface — title pill stays for now, build the next-shape catalog.** **Problem:** the title pill (gold "You · Pot Magnet" suffix on the player's name at the seat) is a poor shape for displaying earned status — seat is already tight, title competes with the display name on one line, opponents barely register it. The unlock-only pathway is structurally landed; the shapes we put through it should be designed for the actual surfaces. **Direction — each sub-shape turns into its own engineering item when it becomes the next ship target:**
  - **Avatar unlocks** — special emoji variants on top of the standard avatar-pack pool, *hidden* in the Edit Profile emoji picker until earned. On the Achievements page, the rarity tile can preview the unlocked avatar with an "unlocks special avatar" label. Tiny visual footprint at the seat (already an avatar circle), reads cleanly as ownership.
  - **Permanent badge on seat bottom-left.** Small icon slot underneath the avatar — distinct from the existing turn-indicator ring (which already uses the around-avatar slot). Content TBD: tier badge, single-achievement-pin glyph, "founding member" mark. Pin one shape once a designer picks.
  - **Pinned achievement showcase on the tap-an-opponent profile sheet.** Pairs with the `Tap-an-opponent → mini profile sheet` bullet below — top 3 achievements visible to opponents when tapped, doesn't crowd the seat.
  - **Emote / blast-pack unlocks.** Extra emoji blasts in the in-game tray; reachable only by people who earned them.

  **Acceptance per surface (when each lands):** cosmetic appears in My Items on grant, equips/unequips like felt/cardback, renders at its target surface (avatar picker, seat bottom-left badge, tap-profile sheet, blast tray), hidden until earned where the design calls for it. **Files / hints:** seat bottom-left badge needs a new slot in [PlayerArea.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayerArea.kt); avatar unlocks gate the `availableEmojis` pool in `ProfileScreen.kt`'s emoji picker by inventory; pinned-achievement showcase routes through the `MutePlayerSheet.kt → PlayerProfileSheet` rename in the tap-an-opponent bullet. **Out of scope:** removing the title pill — keep it wired since it's an ownable item already; replacing it is a future product call once the next-shape unlocks have a chance to prove they read better.
- **Seed an opening pool of unlock-only catalog *content*.** The unlock-only path is structurally ready (per the bullet above); what's missing is the actual cosmetic library to grant from. Acceptable to ship V1 empty, but a handful of well-placed unlocks would make the spec read true on day one. Starter directions to pick from:
  - ~~**Legendary-achievement unlocks (1–3 max).**~~ **Landed.** V17 seeded `title_pot_magnet` (↔ POT_5000) + `title_short_stack_hero` (↔ COMEBACK_FROM_5BB). V20 seeded `cardback_comeback_kid` (↔ DONT_CALL_IT_COMEBACK), the first earnable card back — different cosmetic slot from the two titles, so the unlock-only catalog flexes across more than one shape. All three are in [`ClientGrantableAchievements.Default`](../apps/server/src/main/kotlin/com/cards/server/domain/ClientGrantableAchievements.kt) and route through `POST /v1/me/grants/achievement/{id}` on unlock. Existing users who earned an achievement before its unlock-only row landed don't get the cosmetic — server-side achievement tracking (Phase 4.2) is what would close that gap.
  - **League-tier rewards.** Per the spec, top-7-of-30 promote each week. Seed one cosmetic per league tier (silver/gold/diamond) granted at season end. Felts work well here — a tier-tinted variant of the base black felt, so the visual flex is muted but legible to people at the table.
  - **Achievement-chain capstones.** End-of-chain reward for completing all bot-personality bounties ("Beat Jane / Maverick / etc 10×"). Granting a single "Bot Whisperer" title is cheaper than designing five distinct cosmetics.
  - **RFT (rare-from-the-floor) drops.** A small set of cosmetics that drop at a low probability at the end of any won hand. Lottery feel; visual prestige scales with rarity. Out of scope for V1 unless RFT-roll plumbing already exists server-side.
  - **What to skip for V1:** brand-collab cosmetics, time-bound seasonal drops, anything requiring net-new art beyond a hex / glyph swap. The point is *that* the unlock catalog isn't empty, not that it's deep.
  - **Files / hints:** seed via the same `V5__products.sql`-pattern migration (set `unlock_only = TRUE`); add ids to the client's `feltForProductId` / `cardBackForProductId` / `titleForProductId` resolvers in [EquippedFelt.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/EquippedFelt.kt) only if they're felts/card backs/titles.
  - **Out of scope:** the *which-cosmetics-ship* product call — see backlog if a designer wants to weigh in. Engineering can ship a sensible default set without blocking on that.

### Screen / chrome consistency

- **Previews on every user-facing composable.** Rough rule: every public/internal screen-level composable should have at least one `@Preview`. Private helpers don't need their own preview unless the parent doesn't already exercise the visual. CI doesn't enforce yet — this is a convention.

### Strings — loose-leaf literals everywhere

- **Sweep inline string literals into a centralized strings layer.** Most UI copy is currently hardcoded at the callsite ("OWNED", "Claim your account", "Long-press to copy", "Stats", section titles, error snackbars, etc.). That's load-bearing-by-accident: it blocks localization, makes voice-and-copy edits a repo-wide find-and-replace, and lets two screens drift on the same idea. Pick the KMP-friendly approach (compose-multiplatform-resources `Res.string.*`, or a typed `Strings` object generated from a single source-of-truth file) and migrate. Start with the surfaces that share copy (shop snackbars, owned state, claim CTAs) so the consolidation surfaces drift immediately. **Out of scope:** translating anything. This is about giving strings *a home*, not a second language. **V1-polish** rather than blocker.

### Animations / table polish

- **XP / coin earned distribution animation.** Today the showdown dialog overlays the XP/coin badges, so the user never sees the odometer count up. Defer the XP/coin badge animation until *after* the showdown/bust dialog dismisses, then play it as a small "zip" — XP particle flying up to the XP badge, coin particle flying down to the chip badge, each landing into an odometer count-up.

### Gameplay & table UX

- **Tap-an-opponent → mini profile sheet (humans + bots).** Today tapping another seat opens the mute sheet only. Expand it into a proper "who is this" sheet: avatar, display name, level (or rank, for ranked players), human-vs-bot label, "playing since {createdAt}" / "{N} hands at the table" duration line, and recent hand-style cues if available. The bot variant surfaces their personality + difficulty tier, so the player can read the table without guessing. The human variant is the seed for the "Add friend" affordance (pairs with the social-graph todo) and the "view full profile" tap-through once profile-of-a-stranger is a real route. Mute toggle moves into this sheet as one row among several rather than being the only thing in there. **Files / hints:** [MutePlayerSheet.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/MutePlayerSheet.kt) is today's surface — rename + extend, or replace with a new `PlayerProfileSheet`. Seat metadata: [SeatView.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/TableUiState.kt) currently doesn't carry level / member-since / hand count — extend the projection in `SeatView.fromSeat(...)` to pull those from the profile + room-membership rows.

### Social graph + friends — load-bearing for V1.x

Home now exposes three surfaces that need the friends/recents system to actually work: the friends strip with online presence, the "recently played with" shelf with add-friend affordances, and the friend-requests inbox on profile. All currently fake or no-op; here's the engineering shape.

- **Friend graph — server schema + endpoints.** New tables: `friend_relations(user_a, user_b, state, created_at)` with `state ∈ {requested, accepted, blocked}` and `user_a` always the lexicographically smaller id (so the row is unique regardless of direction). Endpoints: `POST /v1/friends/requests` (target user id), `POST /v1/friends/requests/{id}/accept|decline|block`, `GET /v1/friends` (accepted), `GET /v1/friends/requests` (inbound, pending). Anti-abuse: rate-limit outbound requests per user/day; block-relations dominate accept/decline. **Hard dep:** the user-search / id-resolution flow — see Friend Game lobby flow today for what's wired; humans there can already exchange room codes, so a "person you just played with" id is available on the client.
- **Online-presence signal.** Cheapest path: server emits a presence event when a user's WS connects/disconnects and stores last-seen + current-room (if any). Client subscribes once per session to a presence stream filtered to friend ids. The friends strip [FriendsStrip.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) already takes `List<FriendOnline>` shaped for this (display name, avatar, table label) — drop the real list in and the surface lights up.
- **Recently-played-with tracking.** Server records the human seats present at every multiplayer hand a user finishes; on the client, `RecentOpponentsRepository.observeRecent(limit = 10)` returns deduped most-recent first. Bots are excluded server-side (you can't friend the house). [RecentlyPlayedWithStrip.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/RecentlyPlayedWithStrip.kt) renders the list; tile flips to "Sent" when [RecentOpponent.requestSent] flips true, which the friends-graph repository can compute by joining recents against outbound requests. Until then both the tile tap *and* the "See all" tap surface a `ComingSoonSheet`.
- **Friend requests inbox — lives on Profile, surfaced on Home.** The inbox itself is a section on [ProfileScreen.kt](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt) (manage your relationships) — list of pending inbound requests with accept/decline buttons. Home doesn't get its own inbox surface; instead [FriendsStrip.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) already renders a "N friend requests" badge when `pendingRequests > 0`, with the tap routing into Profile's inbox section. The strip survives even with zero friends online so long as there are pending requests — fresh users with no friends but a request from someone who knows their handle still see the strip.
- **Voice + safety pass before V1.x ship.** Audit every friend-system copy string (request prompts, request-sent confirmations, accept/decline banners, empty states) against the spec voice rules: no urgency, no "X people are waiting!", no begging. Block-relation behavior defaults: blocking a user removes any existing accepted-friend row and prevents matchmaking into the same public room. Implement the defaults; adjust later if product flags otherwise.
- **Out of scope for V1.x:** friend suggestions ("people you might know"), in-app invite-via-share-link, push notifications for requests, group chat. All Phase 2+ design questions.

---

## B. Multiplayer hardening

**MP doesn't work end-to-end today** — the bullet below blocks the rest of §B from being end-to-end testable. Fix it before working on anything else in this section.

- **Creating a new MP game flips the room into "Reconnecting" immediately and surfaces socket errors.** Smell: [RoomRepositoryImpl.kt:36](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/RoomRepositoryImpl.kt#L36) returns from `createRoom()` as soon as the POST succeeds, and the client then attaches to `ReconnectingRoomSocket.observe(code)` ([ReconnectingRoomSocket.kt:63](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocket.kt#L63)), which emits `Connecting` and — if the handshake doesn't land — `Reconnecting` with backoff. Likely the WS attach is racing the room being fully provisioned server-side (missing membership-row commit before the WS handler is reachable). Diagnostic logging is in place: the socket logs the handshake status code, and 4xx rejections classify as `Closed(Rejected)` terminal instead of looping. **What's left:** fix the underlying server-side race so the create-flow doesn't bounce off a 4xx. Then decide how `Closed(Rejected)` is handled by the collector — today it surfaces as the same "room closed" treatment as `RoomDeleted`; whether to auto re-POST `/join` + re-subscribe is still open.

Once the create-flow above is fixed, the rest of §B becomes exercisable:

- **Implement buy-in / stack / re-buy mechanic.** Spec landed in [product-spec.md §4.1 → Wallet, stack & buy-ins](./product/product-spec.md#wallet-stack--buy-ins); engineering still needs to do it. Sketch:
  - **Server:** new "table reservations" concept — buy-in moves wallet → table-held balance on sit; reverses on stand / sweep-evict. Hand resolution moves chips between table-held balances (no wallet touch mid-hand). Wallet sync is unchanged.
  - **Client:** play screen shows *stack* (not wallet); home / shop / profile keep showing wallet. Re-buy dialog on stack=0 (auto-prompt, free if wallet covers). Bust-protection path remains as-is. Sit-out toggle in seat menu.
  - **Bot tables:** stakes are derived from the bot-difficulty entry on the home screen. Mapping (Casual → `StakeTier.Casual`, Standard → `StakeTier.Standard`, Challenging → `StakeTier.High`) is already in place via `SoloBotsPokerSessionFactory.toStakeTier()`. `StakeTier` carries five tiers (Practice / Casual / Standard / High / Premium) for the upcoming MP matchmaking work. The V1 mechanic itself — rebuy on bust=0, stack-vs-wallet display, sit-out toggle — and the MP buy-in flow are still open.
  - **Toast on sweep-evict refund:** "your stack came home — N chips returned to your wallet." Gap noted in product-spec.md §5.6.
  - **Anti-smurf gate** ([§5.3](./product/product-spec.md#53-public-rooms)): server rejects sit-down if buy-in > 25% of wallet. Client surfaces the "you need ≥ N chips for this tier" message before the network call.
  - **Out of scope for V1:** host-customizable blinds (V1.x), antes, rake, voluntary forfeit surface (V1.x).
- **MP credit by table composition** ([§5.4](./product/product-spec.md#mp-credit-by-table-composition)). The "≥2 humans AND humans ≥ bots" rule for granting MP XP / league credit / achievements is documented but not enforced anywhere. Without it, two friends + four bots is a chip-farm exploit. Enforcement lives wherever post-hand XP / progression hooks fire — likely the server's hand-resolution path, gating which progression events get emitted for which seats. Client also needs the visible "Practice tier · bots present" label per [§5.3](./product/product-spec.md#53-public-rooms). **V1-blocker** for integrity once MP is end-to-end playable.
- **Orphaned room policy — robust, simple.**
  - Last human leaves → kill the room.
  - User taps back → leave the room (currently the WS may stay attached; verify the back path tears down).
  - App dies / disconnect → keep the seat warm via the existing `disconnectedAt` grace timer. The in-process reaper scheduled in `RoomSocketRoutes` (`DEFAULT_REAPER_GRACE`, 5 min) already evicts. After eviction the user's next launch should *(a)* tell them the seat was forfeited and *(b)* show what their stack returned.
  - On app launch, before allowing a Join → check `GET /v1/me/active-rooms`. Client-side Home-screen `ActiveRoomBanner` already wires Rejoin/Forfeit; verify end-to-end on device once the create-flow blocker above is unstuck.
  - **Treat >1 active rooms as recovery, not a normal state.** Client-side reconciliation is in place (Home auto-leaves stale rooms). **Still open — server side:** tighten the contract so the multi-room state can't happen in the first place. WS heartbeat (Ktor has ping-pong built in) plus a sweep that hard-evicts after N missed pings instead of just marking `disconnectedAt`. Client reconciliation stays as belt-and-suspenders. The post-eviction "sit out vs remove" product call is in [`developer-todo.md`](./developer-todo.md).
  - The reconnecting-while-mid-hand path inside `ReconnectingRoomSocket` already exists; that's not the gap. The gap is the *user surface* for "you have an ongoing game."

---

## C. Engineering / structural

Quality issues the user has flagged across the codebase. None are blockers, but they compound. Track them here; pull each in when the surrounding area is open.

### Caching + config plumbing

- **Replace `FeatureConfig`'s `by featureValue(...)` with DI-bound `ConfiguredValue<T>` singletons.** Today new tunable values land as another delegate on a growing `FeatureConfig` object — fine, but the QA menu has to know about each new field by name to render an override row. Decision: nix `FeatureConfig` in favor of one-class-one-`ConfiguredValue<T>` (each `@Inject` + `@ContributesBinding` into a multibinding `Set<ConfiguredValue<*>>`), so the QA menu auto-discovers every tunable value via the set. Adding a new value becomes a single class with one annotation, no central file edit. **Acceptance:** existing `FeatureConfig` callsites read from injected `ConfiguredValue<T>` singletons; QA menu enumerates the multibinding set and renders the right override widget per type (Boolean / Int / String / Long); old `featureValue` delegate is deleted; tests cover the override path. **Files / hints:** `:libraries:config` owns the current `FeatureConfig` + override repo; QA menu lives in `features/profile/impl/.../QaMenuScreen.kt`. **Out of scope:** `AppConfig` (server-driven config — see bullet above) and any per-feature flag that isn't user-tunable. Just the QA-overrideable values move.

### Post-rework identity follow-ups

- `SupabaseProfileRepositoryImpl`'s `ProfileCache` overlaps supabase-kt's own session cache. The new `Catching { server }.fold(success → it.also(write), failure → cache.read())` pattern means we only *consult* the cache on failure, which is correct — but we still *write* on every success, so the storage cost remains. Worth measuring before optimizing.

### Module sprawl: `libraries/cards`, `gameplay`, `game`

**Problem:** `libraries/cards` was originally the "highly shared" dumping ground. It has grown to be too big. We now also have `libraries/gameplay` (engine types) and `libraries/game` (session abstraction). The three overlap in confusing ways for new readers.

Not a V1 blocker, but worth a deliberate pass:
- Audit what's in `libraries/cards` today. Which entries are *truly* cross-feature primitives, and which were dumped there because no better home existed.
- Likely splits: progression (XP/achievements/ranks) belongs in `libraries/progression` or stays — but if it stays, the cosmetics + chips + identity etc. need their own homes.
- The high-cohesion / low-coupling goal probably means tearing this down and putting up 3–5 narrower libraries.

Capture as a deliberate refactor pass. Do not entangle it with feature work.

---

## D. Already on the books elsewhere

For completeness; don't re-derive these here when the link below tracks them.

- **Known sharp edges** — [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_known_sharp_edges.md) (auto-loaded).
- **Phase 4.2 server-authoritative gameplay** — out of scope until we choose to start it. See [docs/decisions.md](./decisions.md) and the `:libraries:gameplay` JVM-target blocker noted in memory.
- **Real platform billing impls (Play Billing v6+ / StoreKit 2)** — billing scaffold + `FakeBillingClient` is in place; provisioning store listings is the gate, not engineering. `DevBillingClient` currently bridges the gap so debug builds render chip-pack tiles end-to-end; once the real platform bindings land, both `DevBillingClient` and `NoOpBillingClient` become candidates for removal.
- **OAuth UI gated by `IdentityFeatureConfig`** — Apple/Google buttons are wired but flagged off until dashboard credentials exist.
- **Username localization, bot name localization** — V1.x / V2 problems.
