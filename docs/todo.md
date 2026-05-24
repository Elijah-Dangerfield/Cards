# TODO

**Last reviewed:** 2026-05-24 · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md), [backlog.md](./backlog.md)

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

- **Top-accessory generalization for dialogs + bottom sheets.** Today both `Dialog(emoji = DialogEmoji)` and `BottomSheet`'s emoji drag-handle bake in an emoji-shaped affordance: the only "what sits on the lip of the surface" affordance is `EmojiBubble`. That over-commits the DS — a future surface might want a `CircleIcon` (vector / chip-coin / avatar tile), a stacked-coin glyph, a logo mark, etc. Refactor toward a `topAccessory: TopAccessory?` parameter on both primitives, where `TopAccessory` is a sealed type that can be `Emoji(...)`, `Icon(...)`, `Image(...)`, or `Custom(@Composable () -> Unit)`. The shape carve-out (notch geometry) is the shared bit — anything that fits within a circle / squircle slot of the configured size should be a valid accessory. **Files / hints:** [EmojiBubble.kt](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/dialog/EmojiBubble.kt), [Dialog.kt](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/dialog/Dialog.kt), [BottomSheet.kt](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/dialog/bottomsheet/BottomSheet.kt). Migrate the existing two factories (`dialogEmoji`, `dialogChipBubble`) to be `topAccessory` constructors over the new sealed type. **Out of scope:** redesigning the notch shape — the `NotchedSheetShape` half-circle / rounded-rect carve stays; only what fills it changes.
- **Extend `Dialog` / `BottomSheet` with preset overloads + content slots, then migrate `Base*` callsites off the escape hatch.** Mental model: `BaseDialog` / `BaseBottomSheet` are the "base model of a car" — no frills, full control. `Dialog` / `BottomSheet` are the trim packages that bake in the app's conventions (title typography per level, body slot, primary/secondary button slots, optional `topAccessory`) so callsites get a congruent look for free. Today `Dialog` callsites hand-roll their own title typography and `HandRankingsCheatSheet` opts into `@LowLevelDSComponent` because the opinionated `BottomSheet` doesn't expose enough hooks for its content. Work splits in two: (a) extend the opinionated wrappers with preset constructors / slots — `title: String` *or* `title: @Composable () -> Unit`, body slot defaulting typography + color via `LocalContentColor`/`LocalTextStyle`, primary/secondary button slots — so the common case is a single preset call; (b) sweep current `@LowLevelDSComponent` opt-ins and migrate the ones that just want the standard look. Genuine one-offs stay on `Base*`. **Out of scope:** redesigning the chrome itself — this is about hoisting existing informal conventions into the DS. Pairs with the top-accessory bullet above.
- **Snackbar redesign — bubbly, big, type-driven; match the rest of the app.** Default-Material chrome leaks through today; doesn't fit the dark, minimal vibe of the dialogs / sheets. Vibe is "a more mature Duolingo" — friendly, generous touch targets, type-driven, never saturated. One component, used app-wide. **Files / hints:** grep `Snackbar` callsites + the host composable in `:libraries:ui`.

### DS consistency — bake defaults into components

- **DS components should own their visual defaults — typography, radius, surface, shape — so callsites rarely override.** Today a lot of callsites pass `typography = Body.B500`, `RoundedCornerShape(12.dp)`, `Color(0xFF...)`, `Box { background, clip, padding }` because the DS primitive doesn't bake in the right default for its role. Goal: every DS component (`Text`, `Button`, `ListItem`, `Dialog`, `BottomSheet`, `BasicTextField`, etc.) chooses a sensible default for its slot, and only one-off escapes pass overrides. When N callsites pass the same override, that's a DS-default gap — fix it at the component, not at every callsite. A consistent UI should be the path of least resistance for new code. Pairs with the `Dialog` / `BottomSheet` preset bullet above.
- **Stop reaching past the DS for Material primitives.** Several callsites are pulling `androidx.compose.material.icons.Icons.*` + `androidx.compose.material3.{Icon, IconButton}` directly instead of `:libraries:ui`'s `Icon` / `IconButton` / `Icons`. Result: inconsistent tint, sizing, and tap target; one-offs leak Material's defaults into screens that otherwise honor the DS. Examples to start with — [PlayPokerScreen TopBar](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayPokerScreen.kt#L421) (back + help icons), the in-flight Stats info-button refactor that just swapped *from* the DS *to* Material, anywhere `Icons.AutoMirrored.Filled.*` / `Icons.Default.*` / `Icons.Outlined.*` is imported. The fix is routing through the DS components and adding any missing icon entries to `:libraries:ui:components:icon:Icons`. **Out of scope:** redesigning the DS icon set; this is migration, not authoring.

### Catalog gating — unlock-only vs purchasable

- **Wire the earned-grant path + render unlock-only items as Earned.** [product-spec.md §4.2](./product/product-spec.md#42-the-unlock-only-catalog) is structurally load-bearing: legendary achievement cosmetics, league-tier cosmetics, RFT cosmetics, achievement-chain cosmetics are **never in the shop, ever.** Locked decision: no separate Trophy Case surface — earned items land in My Items. Remaining engineering:
  - **Server:** `InventoryRepository.recordEarnedGrant(...)` is exposed but has **no callsites**. Wire it into the achievement-reward + league-finish hooks (today those grant chips / XP only). Use `ProductCatalogSource.readById(id, context)` for validation (it bypasses the `unlock_only = false` filter so the server can resolve any id regardless).
  - **Client:** a celebratory unlock dialog at the moment of earning — prestige lives at earn-time, not on the shelf. Pairs with the "Earn-source attribution" entry in [backlog.md](./backlog.md) for the deeper "from Comeback Kid achievement" subtitle work.
  - **Design lever — "unlock and buy":** a family can have both an earnable variant and purchasable variants. Earning the first is a taste-test that pre-qualifies the user as a buyer for the rest.

### Screen / chrome consistency

- **Previews on every user-facing composable.** Rough rule: every public/internal screen-level composable should have at least one `@Preview`. Private helpers don't need their own preview unless the parent doesn't already exercise the visual. CI doesn't enforce yet — this is a convention.
- **Game-screen previews across the felt color set.** The play screen has no previews exercising the different felt / accent colors, so visual regressions there only surface on-device. Add `@Preview` variants for `PlayPokerScreen` that exercise each felt/color option the cosmetic system supports — one per color, ideally driven off the same source of truth the runtime reads from so adding a color auto-adds a preview. **Files / hints:** `features/room/impl/...` for the screen, `:libraries:cards` (cosmetics) for the felt color enum.
- **Tab re-selection should propagate to the active tab.** Today re-tapping the active bottom-nav tab is a no-op. Common pattern: re-select scrolls the surface to top (Home), dismisses any open sheet, or otherwise gives the tab a chance to react. Cleanest seam is the navigation/entry-point layer — either the existing `Screen<>()` convention exposes an `onReselected` callback or we introduce a sibling `TabScreen` / `RootTabScreen` that adds it. Each tab opts in. **Files / hints:** whatever owns the bottom-nav graph today.

### Strings — loose-leaf literals everywhere

- **Sweep inline string literals into a centralized strings layer.** Most UI copy is currently hardcoded at the callsite ("OWNED", "Claim your account", "Long-press to copy", "Stats", section titles, error snackbars, etc.). That's load-bearing-by-accident: it blocks localization, makes voice-and-copy edits a repo-wide find-and-replace, and lets two screens drift on the same idea. Pick the KMP-friendly approach (compose-multiplatform-resources `Res.string.*`, or a typed `Strings` object generated from a single source-of-truth file) and migrate. Start with the surfaces that share copy (shop snackbars, owned state, claim CTAs) so the consolidation surfaces drift immediately. **Out of scope:** translating anything. This is about giving strings *a home*, not a second language. **V1-polish** rather than blocker.

### Animations / table polish

- **XP / coin earned distribution animation.** Today the showdown dialog overlays the XP/coin badges, so the user never sees the odometer count up. Idea: defer the XP/coin badge animation until *after* the showdown/bust dialog dismisses, then play it as a small "zip" — XP particle flying up to the XP badge, coin particle flying down to the chip badge, each landing into an odometer count-up. Open to pushback: alternative is to render the earned values inside the dialog and skip the badge animation entirely.

### Gameplay & table UX

- **Tap-an-opponent → mini profile sheet (humans + bots).** Today tapping another seat opens the mute sheet only. Expand it into a proper "who is this" sheet: avatar, display name, level (or rank, for ranked players), human-vs-bot label, "playing since {createdAt}" / "{N} hands at the table" duration line, and recent hand-style cues if available. The bot variant surfaces their personality + difficulty tier, so the player can read the table without guessing. The human variant is the seed for the "Add friend" affordance (pairs with the social-graph todo) and the "view full profile" tap-through once profile-of-a-stranger is a real route. Mute toggle moves into this sheet as one row among several rather than being the only thing in there. **Files / hints:** [MutePlayerSheet.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/MutePlayerSheet.kt) is today's surface — rename + extend, or replace with a new `PlayerProfileSheet`. Seat metadata: [SeatView.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/TableUiState.kt) currently doesn't carry level / member-since / hand count — extend the projection in `SeatView.fromSeat(...)` to pull those from the profile + room-membership rows.
- **Show level (or rank) on the play screen — small badge under the avatar.** Closely tied to the bullet above — even before the profile sheet exists, the at-a-glance "this is a Lvl 14 / Rank 1320 opponent" cue helps the player read the table. Tiny pill below each seat's avatar; collapses to "Bot · Standard" for bots. Avoid clutter: only render when the seat is in-hand and the player has enough vertical room. **Files / hints:** [OpponentsRow.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/OpponentsRow.kt) for the layout slot; reuse the same `levelFor(xp)` derivation [HomeHeader / Profile use](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt) so the value stays consistent across surfaces. **Out of scope:** showing chip balance, hand history, win rate at the seat — those belong in the mini-profile sheet, not the table itself.

### Shop polish

- **Auto-grant the default 'black/Default' felt to new users in `inventory` on signup**, so My Items shows something from day one. Charcoal stays purchasable in the shop unchanged. Use the same `recordEarnedGrant` path the unlock-only catalog uses (or a starter-inventory seed at user creation). Pairs with the unlock-only earned-grant wiring above.
- **Add a green felt to the purchasable catalog.** Seed a new `felt_green` (or similar) product via the Supabase admin API — the catalog is server-authoritative, so this is a SQL migration + admin-side data entry, not a client change. Pick a muted, table-believable green that still fits §3.1 (never "saturated casino-green"). Pairs with the previews bullet below.
- **"Only visible to you" callout on personal cosmetics.** Felts, card backs (marble, etc.), avatar tints, anything that only the wearer sees at the table needs a small label/badge on the product card *and* on the My Items tile so the user isn't paying expecting other seats to see it. Mirror the spec's "personal cosmetic" language. The opposite class (emote packs, achievements with visible badges) doesn't get the label. **Files / hints:** product card in `:features:shop:impl`, equipped-item tile in My Items.
- **Previews on cosmetics in the shop + My Items.** Big visual gap right now — the marble card back, every felt, future card backs all render only as a name + icon emoji. People should know what they're buying. Minimum bar: tap-to-preview that swaps the table backdrop or shows a rendered swatch of the cosmetic. Felts → a `FeltSwatch` tile that paints the real `feltSurfaceColor`. Card backs → a single `PlayingCardBack` at its design size. Pairs with the green-felt item above (no point shipping it without a preview). **Files / hints:** `:libraries:ui:components:poker` already has `PlayingCardBack` + felt resolution; the shop product detail / sheet is the consumer.
### Edit profile

- **Save as floating bottom button + colors moved to top + bigger color circles.** Three layout changes: (a) the save button should float at the bottom of the screen (with enough bottom padding on the scrollable content above so the last row scrolls clear of the button); (b) move the color picker to the top of the form; (c) make the color circles substantially bigger — if that means two rows or a horizontal scroll, that's fine, the goal is "big bubbly UI." **Files / hints:** `EditProfileScreen` / its sub-components.
- **Display-name uniqueness — verify the server-side check + client error surface.** When a user changes their display name, do we check it's not already taken and surface an error if so? Confirm: (a) server validation in the profile-update path; (b) the client maps the rejection into a user-facing error on the field, not a generic snackbar. If either is missing, add it. **Files / hints:** profile update endpoint on the server; `EditProfileViewModel` for the client mapping.


### Email & deep linking

- **Friend-game link previews.** [product-spec.md §5.2](./product/product-spec.md#52-friend-games) promises iMessage/WhatsApp previews showing a Cards-branded card with stakes + seat count. Needs (a) iOS Universal Links + Android App Links configured for the friend-code URL, (b) a small web endpoint serving Open Graph meta (`og:title`, `og:image`, `og:description`) keyed by the code, (c) image rendering for the preview card (static-with-placeholders is fine for V1). **V1-polish** — friend games work today via copy-code; the rich preview is a social-virality nicety, not a blocker.
- **In-app "I confirmed" email button is a no-op.** Repro'd 2026-05-24: tapped the button on an unconfirmed account, was admitted, used the app normally. The button needs to re-check the auth state (e.g. `auth.refreshSession()` + inspect `user.emailConfirmedAt`) and bounce back with an error if still unconfirmed. **Files / hints:** `VerifyEmailScreen` / `VerifyEmailViewModel`, and whatever wires the verified-gate into the post-auth route. (The Supabase-dashboard half of this bug — wrong email link target — lives in [`developer-todo.md`](./developer-todo.md).)

### Claim Account screen

- **Confirmation dialog before email-claim submit.** Add a `ConfirmSwitchToExisting`-style dialog: "this turns your guest into a real account; chips/XP/avatars come with you." Mirrors the existing OAuth conflict copy but framed positively. Lift when a designer's in the loop; the engineering safety net (`linkEmailIdentity` preserves progress) is already in place either way.

### Cron cleanup
- [ ] Replace cron-driven room sweep with in-process reaper
    - Today: `POST /v1/admin/sweep-disconnected-room-members` runs every 5 min via GHA. Wrong tool for live gameplay state — worst-case seat-block is ~10 min (TTL + cron interval), and it adds an external dep for state that lives in RAM on the same Fly instance as the socket.
    - Do: in the websocket disconnect path (`RoomSocketRoutes.kt`, after `markConnected(false)`), launch a delayed reaper on an app-scoped coroutine — `delay(5.min)` then reap iff the member is still disconnected with the same `disconnectedAt` stamp (so reconnect+redrop schedules a fresh timer).
    - Cleanup: delete the admin route, the GHA workflow, and the `ROOM_DISCONNECTED_MEMBER_TTL_MINUTES` HTTP plumbing. Keep `RoomService.sweepDisconnected` as a test utility or inline it.


### Achievements

- **Bot-vs-human achievement split — per-achievement design call (not a straight duplicate).** `FIRST_BUST_DEALT` / `BUST_DEALT_5` are bot-only via `mode = BOTS`; the "Beat Jane 10 times" entries are bot-keyed by personality name; the volume / endurance / stack-swing / pot-size achievements default to `mode = EITHER`. The work isn't "duplicate every achievement for humans" — it's a per-achievement audit asking *which mode does this actually belong in, and if both, do they need separate ids with different thresholds?* Concrete example: "be at a table with a pot over 5K" is trivial at the Challenging bots tier (stakes are 100/200/20k, so a 5K pot is normal) but a real accomplishment in MP at lower stakes — so a single shared id with one threshold misrepresents both modes. Two ways out: (a) split into `POT_5K_BOTS` (tier-aware threshold) and `POT_5K_MP`, or (b) rebalance the bot table stakes so the bot variant means something. Probably some of both. Decide at MP-launch time for prestige-bearing ones (Comeback, Don't Call It a Comeback, Pot 5K) whether human-only variants with separate ids are warranted. **Pairs with:** the buy-in / stack mechanic bullet in §B — stake tiers are the lever for both bot difficulty and achievement thresholds.
- **Locked-tile treatment on the Achievements page.** Today's locked tiles render the rarity-color gradient at 0.45 alpha plus a "$progress / $target" chase chip — they read as "faded," not "locked." Spec asks for a fully separate "greyscale silhouette + lock glyph + '???'" treatment. Also still open: a My Items "Earned" filter pair so users can scope the view. Designer call on whether to push further; the at-a-glance bar is mostly met by today's treatment.

### Rank screen

- **Rank/league surface isn't built out.** XP screen exists; the rank page is a stub. Either build the V1 form (current tier, what unlocks at each tier, no league mechanic yet) or be explicit it's gated until V1.1 leagues. Decide before V1 ship.

### Home screen redesign

- ~~**Whole-screen redesign of Home.**~~ Shipped — Home now follows [product-spec.md §2.4](./product/product-spec.md): slim header (avatar + chips) → activity ticker → four primary CTAs → recent unlocks → friends → recently-played-with → featured drop. Several shelves are fake-data-driven for V1; follow-up engineering below.

### Home wire-up — replace fake data with real reactive sources

- **Activity ticker — feed real signals.** [ActivityTicker.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/ActivityTicker.kt) renders `signals: List<String>` on a 4.2s rotation. V1 ships canned spec-honest copy ("Bots warm and waiting", "Season 1 of the Hall — 5 weeks left"). Once the underlying signals exist, wire them in — candidates: number of public-room sessions in the last hour, biggest pot settled today, season countdown from a config endpoint, current limited-drop name. Per spec §2.4, never fake; if a signal doesn't have a real source yet, omit it. **V1.x** once Quick Match + season framework land.
- **Recent unlocks strip — wire to ProgressionRepository.** [RecentAchievementsStrip.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/RecentAchievementsStrip.kt) takes `items: List<RecentAchievement>` with id / name / icon / rarity color. Today the call site hands it canned data. Add `ProgressionRepository.observeRecentUnlocks(limit = 5)` (or read from the existing earned-achievements table sorted by `earnedAtEpochMs` desc) and pass through. Rarity → color mapping already exists in the Achievements grid; lift it to a shared resolver. Strip auto-hides when empty, so it's a one-line wiring change.
- **Featured drop — server-driven.** [FeaturedCosmeticCard.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FeaturedCosmeticCard.kt) is hard-coded to "Slate Felt". Needs a `featuredDrop` endpoint (single product id + tagline + start/end window) on the catalog server, mirrored to the client via the product-catalog repository. Pairs with the "previews on cosmetics" todo — once that lands, the swatch swap uses the real renderer (felt swatch, card-back render, avatar tile, etc.) instead of a flat color block.

### Social graph + friends — load-bearing for V1.x

Home now exposes three surfaces that need the friends/recents system to actually work: the friends strip with online presence, the "recently played with" shelf with add-friend affordances, and the friend-requests inbox on profile. All currently fake or no-op; here's the engineering shape.

- **Friend graph — server schema + endpoints.** New tables: `friend_relations(user_a, user_b, state, created_at)` with `state ∈ {requested, accepted, blocked}` and `user_a` always the lexicographically smaller id (so the row is unique regardless of direction). Endpoints: `POST /v1/friends/requests` (target user id), `POST /v1/friends/requests/{id}/accept|decline|block`, `GET /v1/friends` (accepted), `GET /v1/friends/requests` (inbound, pending). Anti-abuse: rate-limit outbound requests per user/day; block-relations dominate accept/decline. **Hard dep:** the user-search / id-resolution flow — see Friend Game lobby flow today for what's wired; humans there can already exchange room codes, so a "person you just played with" id is available on the client.
- **Online-presence signal.** Cheapest path: server emits a presence event when a user's WS connects/disconnects and stores last-seen + current-room (if any). Client subscribes once per session to a presence stream filtered to friend ids. The friends strip [FriendsStrip.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) already takes `List<FriendOnline>` shaped for this (display name, avatar, table label) — drop the real list in and the surface lights up.
- **Recently-played-with tracking.** Server records the human seats present at every multiplayer hand a user finishes; on the client, `RecentOpponentsRepository.observeRecent(limit = 10)` returns deduped most-recent first. Bots are excluded server-side (you can't friend the house). [RecentlyPlayedWithStrip.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/RecentlyPlayedWithStrip.kt) renders the list; tile flips to "Sent" when [RecentOpponent.requestSent] flips true, which the friends-graph repository can compute by joining recents against outbound requests. Until then both the tile tap *and* the "See all" tap surface a `ComingSoonSheet`.
- **Friend requests inbox — lives on Profile, surfaced on Home.** The inbox itself is a section on [ProfileScreen.kt](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt) (manage your relationships) — list of pending inbound requests with accept/decline buttons. Home doesn't get its own inbox surface; instead [FriendsStrip.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) already renders a "N friend requests" badge when `pendingRequests > 0`, with the tap routing into Profile's inbox section. The strip survives even with zero friends online so long as there are pending requests — fresh users with no friends but a request from someone who knows their handle still see the strip.
- **Voice + safety pass before V1.x ship.** Spec voice rules: no urgency, no "X people are waiting!", no begging. Block-relation behavior needs a quick call too — does blocking a user remove existing accepted-friend rows? (Probably yes.) Does it prevent matchmaking into the same public room? (Probably yes.) Decide before the schema locks.
- **Out of scope for V1.x:** friend suggestions ("people you might know"), in-app invite-via-share-link, push notifications for requests, group chat. All Phase 2+ design questions.

### `Profile.Fallback` per-feature audit

This is the only remaining cold-boot work — the auth-failure → `Profile.Fallback` path is already wired ([SupabaseProfileRepositoryImpl](../libraries/identity/impl/src/commonMain/kotlin/com/cards/libraries/identity/impl/profile/SupabaseProfileRepositoryImpl.kt)). On bad-network first launch, the user reaches `Profile.Fallback(id = clientLocalUuid)` and can play bots immediately; the audit is per-surface UX polish for that (rare but real) state.

**The audit:** per surface, decide one of:
- **Cached browse works** — inventory list, equipment list, achievements page, XP screen. These can render off the local DB; no server identity required.
- **Hard-gate** — anything that mutates server state (Edit Profile, Shop purchase, Claim Account, Sign In, Multiplayer). Should refuse with a "you need to be online" / "we couldn't reach the server" message.
- **Soft-gate / read-only** — surfaces that *can* render but where mutations would silently fail. Better to disable the mutation affordances explicitly.

**Surfaces to walk through:**
- Home (chip badge, XP, active-rooms list — already gracefully handle null/empty)
- Shop (catalog browse vs purchase)
- Profile (display name + avatar — Fallback has none; show "Guest" or similar?)
- Edit Profile (already hard-gates on `Profile.Authenticated.filterIsInstance`)
- Claim Account
- Inventory / My Items
- Multiplayer (Lobby create/join, in-room)
- Settings / Feedback / Bug Report (currently seed email from profile — Fallback should render empty form)

The global offline banner sets baseline expectations; this audit is per-surface polish. **Plays best as a designer-in-the-loop pass** — engineering picks up the screens after the per-surface behavior is decided.

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
  - App dies / disconnect → keep the seat warm via the existing `disconnectedAt` grace timer. The sweep cron (`POST /v1/admin/sweep-disconnected-room-members`, default 5 min) already evicts. After eviction the user's next launch should *(a)* tell them the seat was forfeited and *(b)* show what their stack returned.
  - On app launch, before allowing a Join → check `GET /v1/me/active-rooms`. Client-side Home-screen `ActiveRoomBanner` already wires Rejoin/Forfeit; verify end-to-end on device once the create-flow blocker above is unstuck.
  - **Treat >1 active rooms as recovery, not a normal state.** Client-side reconciliation is in place (Home auto-leaves stale rooms). **Still open — server side:** tighten the contract so the multi-room state can't happen in the first place. WS heartbeat (Ktor has ping-pong built in) plus a sweep that hard-evicts after N missed pings instead of just marking `disconnectedAt`. Client reconciliation stays as belt-and-suspenders. The post-eviction "sit out vs remove" product call is in [`developer-todo.md`](./developer-todo.md).
  - The reconnecting-while-mid-hand path inside `ReconnectingRoomSocket` already exists; that's not the gap. The gap is the *user surface* for "you have an ongoing game."
- **Forfeit-then-spectator behavior after timeout.** Today the sweep evicts and the seat opens. Alternative: after timeout, auto-fold the user's hand for the rest of the session, leave them subscribed read-only, let them reconnect into spectate. Phase 4.2 question — noted here so we don't re-derive it.

---

## C. Engineering / structural

Quality issues the user has flagged across the codebase. None are blockers, but they compound. Track them here; pull each in when the surrounding area is open.

### Config plumbing — `featureValue` vs DI-bound `ConfiguredValue`

**Question on the table:** `FeatureConfig` declares values with `by featureValue(...)` (the current pattern). The user previously preferred DI-bound `ConfiguredValue` objects, each able to advertise itself to the QA menu autonomously.

Decision options:
- **Keep `featureValue`.** Cheap. Works. The QA-menu autonomy concern is real but small; QA menu already auto-discovers.
- **Switch to DI-bound singletons per value.** Each value is its own `@Inject` singleton; QA menu takes a `Set<ConfiguredValue<*>>` multibinding. Adding a value is one class with one annotation; QA discovery is automatic and decentralized.

Lean: revisit when we add the next feature config. Not blocking V1.

### Post-rework identity follow-ups

- `SupabaseProfileRepositoryImpl`'s `ProfileCache` overlaps supabase-kt's own session cache. The new `Catching { server }.fold(success → it.also(write), failure → cache.read())` pattern means we only *consult* the cache on failure, which is correct — but we still *write* on every success, so the storage cost remains. Worth measuring before optimizing.
- Profile-as-DI: rather than each consumer awaiting `ProfileRepository.observe().first()`, inject a `Lazy<Profile.Authenticated>` (the way `AppConfig` is treated) that should be initialized at boot, with `runBlocking` as worst-case fallback. Makes consumer code straight-line and removes a class of "what if the profile isn't ready" bugs.

### Read-path caching policy — accuracy vs. consistency per surface

**Problem:** Two related symptoms reported 2026-05-24:
1. **Offline reads show empty / name-only state instead of cached content.** My Items offline only renders names, when we already have the full inventory cached locally. Reader's expectation: show whatever the local DB has *immediately*, then refresh from server in the background and reconcile. We don't do that consistently — some surfaces wait for the network and render a degraded state until it lands.
2. **We probably over-fetch on hot routes.** Example: `GET /v1/avatars` (or whatever the catalog read is) fires on **every** Edit Profile visit, even though the avatar catalog changes rarely. Same suspicion for several other catalog-shaped endpoints.

**The policy call needed.** Per surface, pick one of:
- **Accuracy > consistency** (no cache): chip balance, anything mutating money/state. Always show the freshest server value or a loading state — never a stale number.
- **Consistency > accuracy** (cache-first, refresh in background): My Items, inventory, achievements page, avatar catalog, product catalog, anything reference-shaped. Render the cache immediately, kick a refresh, reconcile on success. Acceptable to be a few seconds stale.
- **Cache with TTL gate** (don't even fetch if recent): catalog-shaped reads where the data genuinely doesn't change often. Skip the network if the last response is < N minutes / hours old. HTTP caching headers on the server side are the cleaner path here than client-side bookkeeping — server sets `Cache-Control: max-age=...` and the Ktor client honors it. Decide per endpoint; some will keep TTL = 0 (chips), others can take a generous TTL (avatars).

**Sketch of the work:**
- List every Repository read method + its current behavior (`always-network`, `cache-then-network`, `network-only`).
- For each, pick a policy from the three above (this is the executive decision — needs a human pass with the user).
- Implement the cache-first pattern uniformly via a small helper (most are similar enough to share one).
- For the TTL bucket, set `Cache-Control` on the server endpoints and verify the Ktor client config respects it; otherwise client-side `lastFetchedAt` per endpoint.

**Files / hints:** the inventory / catalog / avatar repositories on the client; the matching Ktor routes on the server.
**V1-polish** rather than blocker — the app works, it just feels worse offline and probably burns more bandwidth than it needs to.

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
