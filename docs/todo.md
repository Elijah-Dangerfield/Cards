# TODO

**Last reviewed:** 2026-05-21 · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md), [backlog.md](./backlog.md)

The live punch list of actionable engineering work. Append, check off, and **delete** done items — they don't need to live here as history. Add a [decisions.md](./decisions.md) entry **only** when an item resolved a non-trivial architectural call worth not re-litigating (see decisions.md header for what qualifies). Most items just get crossed off and removed.

When an item points at a file path or system, the assumption is that path/system already exists — the work is the gap, not a greenfield build.

**Loose item template** (freeform — no enforcement, but the more of these you fill in, the safer it is for an automated worker to pick up):

> **Problem:** what's wrong / missing.
> **Acceptance:** how we know it's done.
> **Files / hints:** where to start looking.
> **Out of scope:** what NOT to drag in.

Anything in §A is **off-limits to automated workers** — those items need a human call first. Everything below §A is fair game.

---

## A. 🚫 Blocked — needs human decision

**Do not pick these up in any automated run.** Contradicts the locked spec or requires an executive call before engineering can start.

*(No open items at this time.)*

---

## B. UX gaps observed in the build

These are bugs / polish items found playing the app or scanning the code. Cheap individually; collectively the V1 quality bar.

### Design system — dialog & sheet primitives
- **Pin emoji-bubble surface token across dialogs and sheets.** Factory chokepoint landed 2026-05-21 (`DialogEmoji` and `BottomSheetDragHandle.Emoji` constructors are now `internal`; every external caller routes through `dialogEmoji(...)` / `bottomSheetEmojiHandle(...)`). The 7 migrated dialog callsites and the 1 sheet callsite explicitly pass `surface = null` to preserve current visuals — they still differ from the factory's `surfaceTertiary` default and from `HandLabelExplainer`'s factory-default rendering. Decide which surface token both layers should pin to (today: dialogs default to `surfacePrimary` via the null fallback, factories default to `surfaceTertiary`), drop the explicit `surface = null` overrides from the migrated callsites, and align the factory defaults. **Visual design call** — needs human eye before flipping.
- **Dialog emoji bubble — distinct surface, lighter than the dialog body.** Today the bubble defaults render close to the dialog surface, which reads flat. Pick a bubble surface that's *lighter* than the dialog surface (not the same token). Pairs with the surface-pinning decision in the bullet above — this is the visual call that should drive it.
- **Chip-themed bubble variant for poker chip dialogs — factory landed; migrate remaining callsites.** `dialogChipBubble()` shipped 2026-05-21 in [Dialog.kt](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/dialog/Dialog.kt) — paints a casino-gold circle with `$`, routes through the same `DialogEmoji`/`EmojiBubble` chokepoint as `dialogEmoji(...)`. `BustDialog` ([HandResultDialogs.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/HandResultDialogs.kt)) migrated as the first callsite (previously `💸`). Executive call documented inline: the spec's "lean: sibling primitive" was deferred — `$` already renders cleanly at the shared `H1100` typography, and a fully separate render path would have added two abstractions for a treatment that's currently single-callsite. Revisit if a future dialog needs a different glyph size or weight for chip-themed copy. Remaining callsites are the in-spec dialogs that don't exist yet (rebuy / chip rewards / soft-bust grant / tip-the-dealer) — wire each one to `dialogChipBubble()` as it's built.
- **Beef up `BottomSheet` so the existing `BaseBottomSheet` callsites can migrate.** Today `HandRankingsCheatSheet` opts into `@LowLevelDSComponent` and uses `BaseBottomSheet` because it owns its own padding / scrolling — the opinionated `BottomSheet` doesn't expose enough hooks to host that content without re-doing the chrome. The DS goal is the opposite: every real sheet uses the opinionated wrapper and `BaseBottomSheet` is reserved for genuine one-offs. Audit current `@LowLevelDSComponent` usages (start with `HandRankingsCheatSheet`), figure out what they need that `BottomSheet` doesn't give them, and **extend `BottomSheet`** — extra content slots, an override for the gutter / top padding, maybe a `text:` overload vs a `content:` overload that defaults the typography + color via `LocalContentColor` / `LocalTextStyle` so sheets read consistently without each caller spelling it out. Then migrate the callsites and re-tighten the `@LowLevelDSComponent` blast radius. **Out of scope:** removing `BaseBottomSheet` — the escape hatch stays; this is about making it genuinely rare.

### Economy — chip flow promises not yet built
Surfaced 2026-05-20 by a spec-vs-build audit. All three are spec promises in [product-spec.md §4.1](./product/product-spec.md#41-chips--the-only-currency) with zero implementation. None are in catastrophic territory on their own, but together they're load-bearing for the "chips feel sacred / we're generous, not punitive" economy narrative.

- **Soft bust protection — client surface for the welcome dialog.** Server side landed 2026-05-21: both `GET /v1/me/wallet` and `POST /v1/me/wallet/sync` call `maybeApplyBustProtection`; the first time the user's balance hits zero they get `Wallet.BUST_PROTECTION_GRANT` (1,000) chips with a `bust_protection_v1` ledger event AND a Dialog `UserMessage` with copy "Welcome back to the table." Idempotency on the ledger means it's lifetime-once. Remaining work is purely client-side: the dialog gets picked up by the existing UserMessage polling, so it should "just work" — but verify on a real device that the dialog renders correctly with the chip-bubble emoji + body, and that the wallet observer in `ChipsRepository` sees the +1000 delta after the grant lands. If the auto-pop dialog placement is wrong (e.g. fires in the middle of a hand), gate it on session-start instead.
- **Tip the dealer** — 50–500 chip post-hand action as flavor sink. Lives on the showdown / hand-end dialog. **V1-polish.** Pure flavor; not load-bearing. Worth doing for the chip-sink narrative but skippable if scope tightens.

### Catalog gating — unlock-only vs purchasable
- **`unlock_only` flag on products + Trophy Case surface.** [product-spec.md §4.2](./product/product-spec.md#42-the-unlock-only-catalog) is structurally load-bearing: legendary achievement cosmetics, league-tier cosmetics, RFT cosmetics, achievement-chain cosmetics are **never in the shop, ever.** The shop catalog and the unlock catalog must be *disjoint sets.* Server side landed 2026-05-21: `unlock_only BOOLEAN NOT NULL DEFAULT FALSE` column on `products` (migration V10), `ProductsTable.unlockOnly` Exposed column, and `PostgresProductCatalogSource.read` filters `WHERE unlock_only = FALSE` so the shop never surfaces them. Remaining work:
   - **Server (next slice):** inventory-grant path for unlocks goes via achievement reward / league finish hooks (already partially wired for chip rewards on achievements) — extend that path to write the unlock-only product id into `inventory` for the user. The lookup primitive for Trophy Case rendering landed 2026-05-21: `ProductCatalogSource.readById(id, context): Product?` bypasses the `unlock_only = false` filter, so callers (Trophy Case render, inventory-grant validation) can resolve a known id back to its domain model regardless of the flag. Returns null on unknown id.
   - **Client — direction pivot 2026-05-21:** scrap the separate Trophy Case surface. The 2026-05-21 `TrophyCaseRoute` / `TrophyCaseScreen` scaffold gets reverted (or repurposed) once the merged version lands. Instead: extend the inventory model with an `acquisitionSource: Purchased | Earned` field (server already knows — the row's writer knows whether it came from a purchase, an achievement reward, or a league finish), render a *single* `MyItemsScreen` with sectioning / filter chip by source. The "trophy" feeling is carried by (a) an "Earned" badge on the row plus the earn-source (achievement / league name), and (b) a celebratory unlock dialog *at the moment of earning* — that's where prestige actually lives, not on the shelf afterward. The shop must also read this: an unlock-only item the user has earned should render as "Owned" in shop searches, not appear missing. **Bonus design lever — "unlock and buy":** a product can have both an earnable variant (achieve X to unlock) and a purchasable variant of related items. Earning the first one is a taste-test that pre-qualifies the user as a buyer for the rest of the family.
   - **V1-blocker** for any prestige cosmetic. Acceptable to ship V1 if the unlock-only catalog is *empty for V1* and the merged My Items work is deferred — but that's a content decision. The filter is in place either way; V1 can ship with zero unlock-only rows and the shop is unaffected.

### Edit profile
- **Offline-first reads AND writes for profile-editable data.**
   - **Reads:** when the user opens Edit Profile, the avatar picker fetches the pack fresh from the server every time — slow, and impossible offline. Drive the picker (and similar profile-editable surfaces) from the local DB; reconcile to the server in the background.
   - **Writes:** avatar and display-name updates today await the server before reflecting in UI ([EditProfileViewModel.kt:98](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/edit/EditProfileViewModel.kt#L98)). Should be optimistic: apply locally, queue the network call, and on failure roll back + surface a snackbar. Display-name conflicts (`DisplayNameTaken`, server-enforced via 409) still need to roll back; that's the one rollback path with a user-facing reason.
   - **Out of scope:** server-authoritative things that need server confirmation before they're real (products / purchases / chip wallet — those keep their current server-roundtrip semantics).
- **Newly purchased avatars don't land in the Edit Profile picker — root cause fixed, broader catalog question still open.** Server-side bug: the shop catalog had 5 avatar-pack products (`avatars_animals`, `avatars_food`, `avatars_sports`, `avatars_fantasy`, `avatars_mythical` — V5 migration) but only `AvatarPacks.Starter` was registered in `AvatarPacks.all`, so the `GET /v1/avatars` inventory join never matched any premium pack and the picker only ever rendered Starter. Landed 2026-05-21: 5 paid packs added to `AvatarPacks` with the emoji lists from the V5 SQL descriptions, each wired to its `unlockProductId`. New test `avatars_includesPremiumPack_whenInventoryContainsItsProduct` pins the join. Picker chain (`fetchAvatarPack()` → `EditProfileViewModel.avatarPacks` → grid) was already correct and needs no client changes — it'll surface the new packs as soon as the user's inventory has the matching product. **Still open from the original bullet:** the broader "sort the catalog into equippable vs. non-equippable" question — V1 has felts, card backs, table themes, titles, emote packs, tools, and avatars all under `chip_offer` with no kind-discriminator. Edit Profile is avatar-only by construction; the question is what other "equip"-style surfaces exist (My Items list?) and whether they should split similarly. Defer until the next time a non-avatar equippable surface needs work.


### Inventory ↔ equipment consistency
- **Equipped item exists while inventory is empty — done.** Closed out 2026-05-21. End-to-end fix landed across three slices: (1) server `POST /v1/inventory/sync` returns the authoritative `owned: List<OwnedItemDto>`; (2) client `InventoryRepository.applyServerSnapshot(...)` folds the snapshot in with Confirmed-promotion, Confirmed-revoke, and Pending-preserve semantics matching equipment's pattern; (3) `EquipmentRepository.dropOrphanEquipment(ownedProductIds)` enforces "equipped ⇒ owned" — invoked from `InventorySyncServiceImpl` after `applyServerSnapshot`, with drop-count logging so the steady-state rate is visible. Leave entry here as a smoke-test reference until a human verifies on device that the red-felt-without-inventory repro is gone, then delete.
- 
### Purchases / shop feedback
- **Purchase snackbar polish.** Today's success snackbar ([ShopFeatureEntryPoint.kt:30](../features/shop/impl/src/commonMain/kotlin/com/cards/features/shop/impl/ShopFeatureEntryPoint.kt#L30)) is plain text ("Unlocked! …", "+X chips"). Make it more delightful: small emoji bubble pinned on the right side of the snackbar (not the over-the-lip dialog style — a contained right-aligned bubble), and where the item is equippable, an inline "Equip" action. Reuses the snackbar action slot we already have. Decide-while-doing which products are equippable (see avatar picker item above).

### Screen / chrome consistency
- **Previews on every user-facing composable.** Rough rule: every public/internal screen-level composable should have at least one `@Preview`. Private helpers don't need their own preview unless the parent doesn't already exercise the visual. First sweep landed previews on the obvious gaps — `OnboardingScreen`, `SignInScreen`, `SignUpScreen`, `VerifyEmailScreen`, `BotTableSetupDialog`, `WinOddsBadge`, `CountdownBadge`, `ProductIcon` / `BadgePill` / `OverhangBadge` (shop helpers). Future contributions should add a preview alongside any new screen-level composable; CI doesn't enforce yet (no static-analysis lint plugged in), so this is a convention.

### Privacy policy / terms of service
- **Write the actual content.** The profile screen already deep-links to a web page; the page itself is empty/placeholder. Probably one of the last items before TestFlight. Hosting can stay on the existing web link target.

### Typography & DS consistency
- **Audit text sizes across the app.** XP and Rank screens have copy at the bottom that's noticeably smaller than the rest of the UI. Sweep every screen and confirm: (1) only DS typography tokens are in use, (2) the default `Text` component picks a sensible body size when no `typography` is passed. If we have to override `typography = …` in 90% of call sites, the default is wrong.
- **DS-first text component default.** Whatever `Text` resolves to when called without a typography argument should match what a screen wants in 95% of cases. Building DS-aware screens should mostly *just work*.

### Strings — loose-leaf literals everywhere
- **Sweep inline string literals into a centralized strings layer.** Most UI copy is currently hardcoded at the callsite ("OWNED", "Claim your account", "Long-press to copy", "Stats", section titles, error snackbars, etc.). That's load-bearing-by-accident: it blocks localization, makes voice-and-copy edits a repo-wide find-and-replace, and lets two screens drift on the same idea. Pick the KMP-friendly approach (compose-multiplatform-resources `Res.string.*`, or a typed `Strings` object generated from a single source-of-truth file) and migrate. Start with the surfaces that share copy (shop snackbars, owned state, claim CTAs) so the consolidation surfaces drift immediately. **Out of scope:** translating anything. This is about giving strings *a home*, not a second language. **V1-polish** rather than blocker — but a much cheaper sweep now than after another quarter of features land.

### Onboarding / app-config
- **Bouncing to onboarding when app-config changes — root cause still open.** Hard guard landed (`OnboardingViewModel` self-corrects to Home when `hasUserOnboarded` is already true), so the symptom is now self-healing rather than a dead end. Still open: the actual root cause. Likely a `key()` / `remember` not surviving an app-config-driven recomposition that reconstructs the `NavHost` and re-pushes the start destination. Next step is a hands-on repro, then fixing whichever composable is recomposing past the `AppGuardGate` / `SplashGate` insulation. **Fresh repro 2026-05-21:** launched app, quickly navigated to a non-Home screen, ~5s later the `AppConfigRepository` 5s refresh timeout fired (`tryWithTimeout(ConfigRefreshTimeout)` in `OfflineFirstAppConfigRepository`), and the app bounced back to Home. Suggests the configStream's *first non-null emission* (cached or fallback after timeout) is causing the NavHost to re-mount its start destination, even though `AppGuardGate` is structured to isolate the read. Bisect candidates: `AppGuardLayer`'s `AnimatedVisibility` wrapping content, the `unreadNotifications` `collectAsState` inside `AppNavigation`, or the `currentBackStackEntry` read changing under us when the recomposition lands.


### App guard chrome
- **Blocking states intentionally overlay (not in topBar).** `UpgradeRequired` / `MaintenanceBlocking` use a full-screen `Box` overlay inside `AppGuardLayer` so they cover the entire app surface — including any topBar / bottomBar. That's the right model for "stop everything" states; don't refactor to a Column.

### Play screen — opponents row at MP scale
- **Horizontal scroll + auto-scroll for >4 seats.** At 10-seat MP tables the current pack-and-shrink approach makes avatars unreadable. `LazyRow` once `count > 4`, auto-scroll to the active actor when their turn flips, fade gradients on both edges, respect manual user scroll for a few seconds. Keep pack-and-shrink for `count ≤ 4` so casual bot tables show everyone at once.

### Animations / table polish
- **Bust animation for other players.** Today we have the bust dialog for the human; need a visible bust treatment on a remote seat (avatar dims, "BUSTED" stamp, chip stack collapses).
- **XP / coin earned distribution animation.** Today the showdown dialog overlays the XP/coin badges, so the user never sees the odometer count up. Idea: defer the XP/coin badge animation until *after* the showdown/bust dialog dismisses, then play it as a small "zip" — XP particle flying up to the XP badge, coin particle flying down to the chip badge, each landing into an odometer count-up. Open to pushback: the alternative is to render the earned values inside the dialog and skip the badge animation entirely.

### Table-side social
- **Emoji sending in games.** [product-spec.md §5.5](./product/product-spec.md#55-table-side-social) commits to emoji blasts (~12 base emojis, 8s cooldown, mute-this-player) as a V1 feature. Not built yet. Bottom-tray surface, full-screen 1.5s animation per emit.
- **Swipe-up-to-fold.** Gesture on the user's hole cards = fold. First time it triggers, show a confirmation dialog *with* a "Don't show this again" — so the gesture stays discoverable then gets out of the way.

### App-store review prompts
- **App-store review prompts — V1 path wired end-to-end (verify on device before TestFlight).** All three triggers land as of 2026-05-21: `:libraries:review` exposes `ReviewPromptCoordinator` + `ReviewLauncher`; `RealReviewPromptCoordinator` runs the eligibility gate (install age ≥3d, prompt cooldown ≥30d) against `AppCache`. `AdaptedReviewLauncher` delegates `ReviewLauncher.requestReview()` to the long-standing `ReviewPrompter` binding (Android Play Core via `AndroidReviewPrompter`, iOS `SKStoreReviewController` via Swift). `PlayPokerViewModel` fires `AchievementUnlocked` on rare/epic/legendary unlocks, `LevelUp` on hand-driven level changes, and `SessionEnd` via `PlayPokerAction.LeaveTable` (dispatched by the screen's back-handler / top-bar back / confirmed-leave dialog) — gated on `sessionFactory.xpMode == XpMode.BOTS` so MP-disconnects don't masquerade as positive moments. Remaining work is verification only:
  - Smoke-test on Android release flavor: meet the install-age floor + prompt cooldown, then leave a bots table — Play Core decides whether to surface the dialog. Either outcome is correct per the spec.
  - Smoke-test on iOS: same eligibility floor; `SKStoreReviewController` is even stricter about throttling.
  - **Important:** never write a self-built rating dialog as a fallback. If the OS declines to show the prompt, that's the system working as designed — see [spec §2.6](./product/product-spec.md#26-app-store-review-prompts).

### Notifications — Phase 6, not started
- **§8 opt-in event-driven push notifications.** League placement, friend activity, battle-pass tier, Rare/Legendary achievement unlock. Never time-of-day modeled, never "your chips are lonely," never "come back" pings — see [product-spec.md §8](./product/product-spec.md#8-notifications) for the bright lines. Includes opt-in granularity (per-category toggle, not just global on/off). **Not a hidden gap** — explicitly Phase 6 on the roadmap — but worth listing so it doesn't slip after Phase 4.2.

### Email & deep linking
- **Friend-game link previews.** [product-spec.md §5.2](./product/product-spec.md#52-friend-games) promises iMessage/WhatsApp previews showing a Cards-branded card with stakes + seat count. Needs (a) iOS Universal Links + Android App Links configured for the friend-code URL, (b) a small web endpoint serving Open Graph meta (`og:title`, `og:image`, `og:description`) keyed by the code, (c) image rendering for the preview card (can be static-with-placeholders for V1 — full dynamic rendering is overkill). **V1-polish** — friend games work today via copy-code; the rich preview is a social-virality nicety, not a blocker.

- **Email confirmation link points to `localhost`.** Supabase email template is on the default. Set the project's site URL + redirect URLs in the Supabase dashboard (dev *and* prod). While there, swap the default Supabase template for a Cards-branded one (copy in [voice-and-copy.md §5.x](./product/voice-and-copy.md)).

### Claim Account screen
- **Email-claim semantics — link vs. sign-in.** OAuth claim uses `linkOAuthIdentity`, which attaches the OAuth identity to the *current* (anonymous) user and preserves guest progress. Email/password has no equivalent — `IdentityRepository.signInWithEmail` replaces the session, so claiming with email currently orphans guest chips/XP. That mirrors `ConfirmSwitchToExisting`'s OAuth-conflict semantics, but it's *every* email claim instead of the rare conflict path. Honest fix: add `linkEmailIdentity` to `IdentityRepository` (Supabase exposes `updateUser(email = ...)` on an anonymous user) and a confirmation-style claim flow on the email signup surface. Pre-V1 if email-claiming guest users is a meaningful share of the funnel; otherwise V1.x.

### Achievements
- **Bot-vs-human duplication for the rest of the registry.** `FIRST_BUST_DEALT` / `BUST_DEALT_5` are bot-only via `mode = BOTS` — same pattern needs to be applied to the rest of the registry when MP ships in Phase 4.2+. The "Beat Jane 10 times" entries are already bot-keyed by personality name; the volume / endurance / stack-swing / pot-size achievements default to `mode = EITHER`, which is fine until MP arrives. Decide at MP-launch time whether prestige-bearing ones (Comeback, Don't Call It a Comeback, Pot 5K) deserve human-only variants with separate ids.
- **Earned vs. unearned visual differentiation on the Achievements page.** Today the only distinction is dimmed text, which doesn't carry the prestige signal. Treatment to consider: locked rows = greyscale silhouette + lock glyph + "???" placeholder description (no spoiler); earned rows = full color + "Earned: {date}" caption. Designer call on the exact treatment — but the bar is "you can tell at a glance which row you've unlocked." Pairs with the trophy-case-vs-my-items merger above; the same visual states will need to apply on My Items's Earned filter.

### Rank screen
- **Rank/league surface isn't built out.** XP screen exists; the rank page is a stub. Either build the V1 form (current tier, what unlocks at each tier, no league mechanic yet) or be explicit it's gated until V1.1 leagues. Decide before V1 ship.

### Home screen redesign
- **Whole-screen redesign of Home.** The current Home doesn't match the brand — feels generic compared to the Card Hall positioning in [product-spec.md](./product/product-spec.md). Direction: "Duolingo big-surface energy, but more elegant, less kiddy" — large primary CTAs (Play with bots, future Play with friends / Find a room), prominent progression visibility (XP, Rank, daily/seasonal pull), but never feel like a casino skin or a kids' app. **Needs design pass first** — pull from product-spec.md §3 (the Card Hall positioning) and §7 (Home as the entry point), the existing voice-and-copy.md, and the brand notes in §3.1 ("dark mode, muted accents, type-driven moments, never saturated casino-green"). Out of scope until the design pass is done — engineering follows. **Future state to keep in mind during design:** Home eventually has two MP entry points — "Play with friends" (room code / direct invite) and "Find a room" (public matchmaking). Even if those aren't wired in V1, the design should accommodate them without restructuring.

---

## C. Multiplayer hardening

The lobby + reconnect-grace foundation landed (per project memory); these are the gaps before we trust strangers to share a room.

- **Implement buy-in / stack / re-buy mechanic.** Spec landed in [product-spec.md §4.1 → Wallet, stack & buy-ins](./product/product-spec.md#wallet-stack--buy-ins); engineering still needs to do it. Sketch of the work:
  - **Server:** new "table reservations" concept — buy-in moves wallet → table-held balance on sit; reverses on stand / sweep-evict. Hand resolution moves chips between table-held balances (no wallet touch mid-hand). Wallet sync is unchanged.
  - **Client:** play screen shows *stack* (not wallet); home / shop / profile keep showing wallet. Re-buy dialog on stack=0 (auto-prompt, free if wallet covers). Bust-protection path remains as-is. Sit-out toggle in seat menu.
  - **Bot tables:** mirror the same flow so the mechanic is discoverable solo. The existing local-only `BotTableSetupDialog` should grow a stake-tier picker (Practice / Casual / Standard / High / Premium) rather than the current free-form "starting stack" field.
  - **Toast on sweep-evict refund:** "your stack came home — N chips returned to your wallet." Currently a gap noted in product-spec.md §5.6.
  - **Anti-smurf gate** ([§5.3](./product/product-spec.md#53-public-rooms)): server rejects sit-down if buy-in > 25% of wallet. Client surfaces the "you need ≥ N chips for this tier" message before the network call.
  - **Out of scope for V1:** host-customizable blinds (V1.x), antes, rake, voluntary forfeit surface (V1.x).
- **MP credit by table composition** ([§5.4 table](./product/product-spec.md#mp-credit-by-table-composition)). The "≥2 humans AND humans ≥ bots" rule for granting MP XP / league credit / achievements is documented but not enforced anywhere. Without it, two friends + four bots is a chip-farm exploit. Enforcement lives wherever post-hand XP / progression hooks fire — likely in the server's hand-resolution path, gating which progression events get emitted for which seats. Client also needs the visible "Practice tier · bots present" label per [§5.3](./product/product-spec.md#53-public-rooms). **V1-blocker** for integrity once MP is end-to-end playable.
- **MP games don't actually work end-to-end.** Reported 2026-05-20 by the human: joining a multiplayer room doesn't produce a playable game. **Needs human reproduction first** before an automated worker should touch this — the failure mode isn't documented (does the room create, do players join, does the deal happen, do actions propagate?). Reproduction steps + a concrete failure signature need to live in this item before it's worker-pickable. Likely intersects with `RemotePokerSessionFactory` / `RemoteGameSession`, which were scaffolded for Phase 4.2 but may not be fully wired.
- **Orphaned room policy — robust, simple.**
  - Last human leaves → kill the room.
  - User taps back → leave the room (currently the WS may stay attached; verify the back path tears down).
  - App dies / disconnect → keep the seat warm via the existing `disconnectedAt` grace timer. The sweep cron (`POST /v1/admin/sweep-disconnected-room-members`, default 5 min) already evicts. After eviction the user's next launch should *(a)* tell them the seat was forfeited and *(b)* show what their stack returned. None of that surfaces yet.
  - On app launch, before allowing a Join → check `GET /v1/me/active-rooms` — server endpoint landed 2026-05-21 (filters `RoomService.snapshot()` by membership). Client repo surface landed 2026-05-21 (`RoomRepository.getActiveRooms()` + `GetActiveRoomsOutcome` covering Success / NotSignedIn / NetworkError / Unknown). Home-screen consumer landed 2026-05-21: `HomeViewModel` queries on init/refresh; `ActiveRoomBanner` per active room with Rejoin (→ `LobbyRoute(prefilledCode=code)`) and Forfeit (confirms then optimistic-`leaveRoom`, rehydrate on network failure). Remaining: verify on device once the MP "creating a room flips into Reconnecting" repro below is unstuck — until then we can't realistically exercise the rejoin path end-to-end.
  - **Treat >1 active rooms as recovery, not a normal state.** A user should only ever have one active room — anything else is the system failing to clean up after an app crash, force-quit, or network blip. **Client-side reconciliation landed 2026-05-21:** `HomeViewModel.loadActiveRooms()` sorts the server response by `createdAtEpochMs` desc; if there's more than one, it keeps the newest in state and fires `appScope.launch { roomRepository.leaveRoom(code) }` for each stale room — fire-and-forget under the app-lifetime scope so the cleanup outlives navigation away from Home. The Home banner is now single-room by construction. Logged via `KLog.withTag("HomeViewModel")` at WARN so the steady-state rate is visible if it ever becomes non-zero. **Still open — server side:** tighten the contract so the multi-room state can't happen in the first place — WS heartbeat (Ktor has ping-pong built in) plus a sweep that hard-evicts after N missed pings rather than just marking `disconnectedAt`. The client reconciliation is the belt-and-suspenders, not the fix. **Open product call, needs human:** after the sweep eviction, do we *sit out* the user (auto-fold, keep the seat for a longer grace window) or *fully remove* them from the room? "Sit out" is friendlier; "remove" is cleaner. Pick before wiring the sweep behavior.
  - The reconnecting-while-mid-hand path inside `ReconnectingRoomSocket` already exists; that's not the gap. The gap is the *user surface* for "you have an ongoing game."
- **Forfeit-then-spectator behavior after timeout.** Today the sweep evicts and the seat opens. Alternative: after timeout, auto-fold the user's hand for the rest of the session, leave them subscribed read-only, let them reconnect into spectate. That's a Phase 4.2 question — note it here so we don't re-derive it.
-    **Repro 2026-05-21:** creating a new MP game flips the room into "Reconnecting" immediately and surfaces socket errors. Smell: [RoomRepositoryImpl.kt:36](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/RoomRepositoryImpl.kt#L36) returns from `createRoom()` as soon as the POST succeeds, and the client then attaches to `ReconnectingRoomSocket.observe(code)` ([ReconnectingRoomSocket.kt:63](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocket.kt#L63)), which emits `Connecting` and — if the handshake doesn't land — `Reconnecting` with backoff. Likely the WS attach is racing the room being fully provisioned server-side. **2026-05-21 update — diagnostic + classification slice landed:** the socket now logs the handshake status code on every failure (parsed from Ktor's `WebSocketException` message — the only handle we have) AND classifies 4xx as `Closed(Rejected)` terminal instead of looping forever as Reconnecting. The existing `ClosedReason.Rejected` enum value (previously unused) now actually fires. Next step is on-device repro: open the lobby, watch logcat for the new status-suffixed warning; the status code will say whether the handshake is being rejected (4xx — server-side membership/race issue) or the upgrade is just timing out (no status, network-layer issue). Then fix the underlying server-side race (likely a missing membership-row commit before the WS handler is reachable). Collector handling for `Closed(Rejected)` — "auto re-POST /join + re-subscribe" — is still TBD; for now Rejected surfaces as a Closed state and the lobby UI shows the same "room closed" treatment as `RoomDeleted`.
---

## D. Engineering / structural

Quality issues the user has flagged across the codebase. None are blockers, but they compound. Track them here; pull each in when the surrounding area is open.

### ViewModel scope vs. AppScope for fire-and-forget actions
LobbyViewModel `Leave`, HomeViewModel `Forfeit`, FeedbackViewModel `Submit`, and BugReportViewModel `Submit` now route their network calls through the existing `AppCoroutineScope` via `appScope.async { … }.await()`, so the server-side request outlives VM teardown. AGENTS.md → "SEAViewModel Pattern" documents the rule. Follow-up: audit additional candidates as they surface — sign-out / delete-account, end-of-session telemetry writes, any other "must reach the server" action whose UI doesn't care about the result.

### Sign-out data clearing
- **File-side cleanup on sign-out is still ad-hoc.** Originally the proposal had `SignOutDataDeleter` co-own file deletion (app caches, downloaded avatars). Nothing in the codebase deletes files on sign-out today, and no concrete leak path is on fire. Defer until we have actual on-disk caches to clear; the `AppEventListener.onSignedOut` hook is already in place to wire one in.

### Config plumbing — `featureValue` vs DI-bound `ConfiguredValue`
**Question on the table:** `FeatureConfig` declares values with `by featureValue(...)` (the current pattern). The user previously preferred DI-bound `ConfiguredValue` objects, each able to advertise itself to the QA menu autonomously.

The current design: `FeatureConfig` subclasses are aware of the QA menu via convention; the QA menu enumerates declared features.

Decision options:
- **Keep `featureValue`.** Cheap. Works. The QA-menu autonomy concern is real but small; QA menu already auto-discovers.
- **Switch to DI-bound singletons per value.** Each value is its own `@Inject` singleton; QA menu takes a `Set<ConfiguredValue<*>>` multibinding. Adding a value is one class with one annotation; QA discovery is automatic and decentralized.

Lean: revisit when we add the next feature config. Not blocking V1. Capture the open question here so it doesn't get re-derived.

### Network retry / `authedCall`
**Problem:** Today each repository runs its own auth + retry pattern around `authenticatedClient`. Easy to drift. The original intent was a `NetworkClient.authedCall { client -> … }` that took a Ktor lambda, ran it, classified the failure, and re-issued if appropriate (401 with retry-after token refresh, 5xx with backoff, etc.). That builder would mirror the Ktor builder API so callers write `authedCall { get("/v1/me") }` instead of `authenticatedClient.get("/v1/me")` + their own try/catch.

**Question:** what is the current retry shape across repos? Audit, then either (a) centralize behind `authedCall` and migrate one repo at a time, or (b) document the per-repo pattern in `AGENTS.md` and stop talking about it.

Not blocking V1, but worth deciding before we add more repos.

### `SupabaseIdentityRepository` review
Open critiques from the field log:
- **No tests yet.** The impl has no commonTest sources — a real test pass would need fakes for `SupabaseClient`, `ProfileApi`, `IdentityCache`, and `AppEventBus`. Worth doing before any further changes to the outcome-mapping logic; downstream feature tests use the `IdentityRepository` interface via fakes so they don't exercise these mappings.
- Maintains its own cache; supabase-kt has its own session cache. Are we double-caching? Should we trust theirs?
- An identity-as-DI question: rather than each consumer awaiting `IdentityRepository.state`, inject a `Lazy<Identity>` (the way `AppConfig` is treated) that *should* be initialized at boot, with `runBlocking` as the worst-case fallback. Makes consumer code straight-line and removes a class of "what if the state isn't ready" bugs.

Note: the get-or-create pattern is correct, so the remaining structural concerns are consolidation, not correctness.

### Authed calls firing before auth resolves
**Closed 2026-05-21 — structural fix landed.** Two changes:
- `SupabaseIdentityRepository.init` now runs `ensureInitialized()` eagerly, and `ensureInitialized()` is idempotent on the joint condition `state == SignedIn && supabase has a session in memory`. The previous early-return on cached state alone was what masked the race.
- `NetworkClient.authenticatedClient`'s `loadTokens` block now calls `AuthTokenProvider.awaitAccessToken(5s)` instead of the synchronous `getAccessToken()`. Requests during the cold-boot resolve window suspend up to 5s for a token rather than firing without a bearer and 401'ing.

Per-bootstrapper `awaitIdentity()` calls (Chips / Inventory / Equipment) were removed — the network-client gate makes them redundant. See [decisions.md 2026-05-21](./decisions.md). The original symptom that opened this entry (cold-boot 401 against `/v1/inventory/sync`) was the trigger.

**Follow-up — `authedCall { client -> … }` helper (still open).** Independent of the auth race: a network-client method that takes a Ktor builder lambda and does standard retry classification + structured logging would consolidate the per-repo try/catch boilerplate. Out of scope for the race fix; pick up when the network layer next opens.

### Identity cold-boot resilience
**Problem:** Anonymous sign-in roundtrips Supabase, and on a fresh install with poor / no network the `/auth/v1/token` call times out (`HttpRequestException`, 10s default) and the user is stranded in a `SessionState.Unknown` — they can't even play bots. Repro: fresh install on a heavily throttled connection.

This is a poker app. A user shouldn't need internet to play bots, even on first launch.

**Sketch — pick one or layer them:**
1. **Cached-identity fallback.** If we have a previously-cached identity from a prior session, fall back to it and treat sign-in as a background reconciliation. Doesn't help true first-launch.
2. **Local `ErrorIdentity` / `OfflineIdentity`.** Generate a local-only id so bot play is fully unlocked. On next successful auth, migrate any local progress (chips, XP, achievements) into the real account. Riskier — needs a real migration story.
3. **Defer anon sign-in until we actually need it.** Bots don't strictly need a server-side identity; only purchase / MP / leaderboard surfaces do. Treat anon-auth as a *prerequisite for those surfaces*, not for app launch. Boot offers bots immediately; sign-in happens lazily before the first networked feature.

**Lean:** option 3 is the most honest — it matches what the app actually needs from a server identity. Option 2 is technically possible but introduces a non-trivial reconciliation surface we'd otherwise avoid.

**Files / hints:** [SupabaseIdentityRepository.kt:113](../libraries/identity/impl/src/commonMain/kotlin/com/cards/libraries/identity/impl/SupabaseIdentityRepository.kt#L113) (`ensureInitialized`), [SplashGate](../libraries/identity/api/src/commonMain/kotlin/com/cards/libraries/identity/api/SessionState.kt) wiring. **Discuss approach with the human before implementing** — this is an executive decision call, not a worker pickup.

### Module sprawl: `libraries/cards`, `gameplay`, `game`
**Problem:** `libraries/cards` was originally the "highly shared" dumping ground. It has grown to be too big. We now also have `libraries/gameplay` (engine types) and `libraries/game` (session abstraction). The three overlap in confusing ways for new readers.

Not a V1 blocker, but worth a deliberate pass:
- Audit what's in `libraries/cards` today. Which entries are *truly* cross-feature primitives, and which were dumped there because no better home existed.
- Likely splits: progression (XP/achievements/ranks) belongs in `libraries/progression` or stays — but if it stays, the cosmetics + chips + identity etc. need their own homes.
- The high-cohesion / low-coupling goal probably means tearing this down and putting up 3–5 narrower libraries.

Capture as a deliberate refactor pass. Do not entangle it with feature work.

---

## E. Already on the books elsewhere

For completeness; don't re-derive these here when the link below tracks them.

- **Known sharp edges** — [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_known_sharp_edges.md) (auto-loaded).
- **Phase 4.2 server-authoritative gameplay** — out of scope until we choose to start it. See [docs/decisions.md](./decisions.md) and the `:libraries:gameplay` JVM-target blocker noted in memory.
- **Real platform billing impls (Play Billing v6+ / StoreKit 2)** — billing scaffold + `FakeBillingClient` is in place; provisioning store listings is the gate, not engineering. `DevBillingClient` currently bridges the gap so debug builds render chip-pack tiles end-to-end; once the real platform bindings land, both `DevBillingClient` and `NoOpBillingClient` become candidates for removal.
- **OAuth UI gated by `IdentityFeatureConfig`** — Apple/Google buttons are wired but flagged off until dashboard credentials exist.
- **Username localization, bot name localization** — V1.x / V2 problems.
