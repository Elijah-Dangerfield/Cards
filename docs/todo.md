# TODO

**Last reviewed:** 2026-05-20 · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md), [backlog.md](./backlog.md)

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
- **Force theme-awareness on emoji bubbles.** Steps 1, 2, and 4 of the primitive reshape landed (`BaseDialog` published behind `@LowLevelDialogApi`; bottom sheets renamed so `BottomSheet` is the DS default and `BaseBottomSheet` is the raw escape hatch; AGENTS.md → Design system documents the layers). Step 3 remains: make `DialogEmoji` `internal`; expose only the composable factory `dialogEmoji(...)`. Update 7 `DialogEmoji(...)` callsites to `dialogEmoji(...)`. Same treatment for the bottom-sheet emoji handle equivalent. Pin both to the same surface token (today they differ — dialogs default to `surfacePrimary`, the factory defaults to `surfaceTertiary`) so dialogs and sheets visually agree. **Note:** the surface-token pin is a visual design call — a worker should bring it to the human before flipping the default.

   **Out of scope:** the underlying animation / scrim / sizing behavior — that stays. This is emoji-bubble theme-awareness only.

### Economy — chip flow promises not yet built
Surfaced 2026-05-20 by a spec-vs-build audit. All three are spec promises in [product-spec.md §4.1](./product/product-spec.md#41-chips--the-only-currency) with zero implementation. None are in catastrophic territory on their own, but together they're load-bearing for the "chips feel sacred / we're generous, not punitive" economy narrative.

- **Soft bust protection** — when a user's wallet hits 0, auto-grant 1,000 chips with copy *"Welcome back to the table."* No timer, no claim prompt. Hooks into `ChipsRepository` / wallet bootstrap; trigger on transition-to-zero, not on every zero state (otherwise users get re-granted every time they reload the app at zero). **V1-blocker** for the chip-economy story — without this, a new player who busts their starter grant has no free path back. **Files:** likely a new method on `ChipsRepository` + a server-side ledger event with reason `BUST_PROTECTION`.
- **First-week welcome chips** — 500/day × 7 silent grants for the first 7 days post-install / sign-up. No claim prompt; chips just appear in the wallet. No streak, no expiry, no "you missed yesterday" copy. **Files:** server-side scheduled grant tied to account creation timestamp; client just observes the wallet delta. **V1-polish** rather than blocker — the 10K starter covers immediate play; this is retention sweetener.
- **Tip the dealer** — 50–500 chip post-hand action as flavor sink. Lives on the showdown / hand-end dialog. **V1-polish.** Pure flavor; not load-bearing. Worth doing for the chip-sink narrative but skippable if scope tightens.

### Catalog gating — unlock-only vs purchasable
- **`unlock_only` flag on products + Trophy Case surface.** [product-spec.md §4.2](./product/product-spec.md#42-the-unlock-only-catalog) is structurally load-bearing: legendary achievement cosmetics, league-tier cosmetics, RFT cosmetics, achievement-chain cosmetics are **never in the shop, ever.** The shop catalog and the unlock catalog must be *disjoint sets.* Today the `products` table has no `unlock_only` (or equivalent) column, the shop has no gating logic to hide unlock-only entries, and there's no Trophy Case UI to display unlock-only items. This collapses the "no pay-to-win prestige" principle ([§4.5](./product/product-spec.md#45-no-pay-to-win--the-hard-rule)) the moment an achievement-tier cosmetic ships.
   - **Server:** add `unlock_only BOOLEAN` to `products` (migration V7+). Shop catalog query filters `WHERE unlock_only = FALSE`. Inventory-grant path for unlocks goes via achievement reward / league finish hooks (already partially wired for chip rewards on achievements).
   - **Client:** Trophy Case screen as a peer to Shop / My Items (probably under Profile or as a tab on the existing My Items surface). Shows owned unlock-only items, display-only, with the achievement / league that earned each. Locked entries shown as silhouettes (already the pattern for achievements themselves).
   - **V1-blocker** for any prestige cosmetic. Acceptable to ship V1 if the unlock-only catalog is *empty for V1* and Trophy Case is deferred — but that's a content decision. Flag now.

### Edit profile
- **Offline-first reads AND writes for profile-editable data.**
   - **Reads:** when the user opens Edit Profile, the avatar picker fetches the pack fresh from the server every time — slow, and impossible offline. Drive the picker (and similar profile-editable surfaces) from the local DB; reconcile to the server in the background.
   - **Writes:** avatar and display-name updates today await the server before reflecting in UI ([EditProfileViewModel.kt:98](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/edit/EditProfileViewModel.kt#L98)). Should be optimistic: apply locally, queue the network call, and on failure roll back + surface a snackbar. Display-name conflicts (`DisplayNameTaken`, server-enforced via 409) still need to roll back; that's the one rollback path with a user-facing reason.
   - **Out of scope:** server-authoritative things that need server confirmation before they're real (products / purchases / chip wallet — those keep their current server-roundtrip semantics).
- **Newly purchased avatars don't land in the Edit Profile picker.** Bought a food avatar pack, equipped it, opened Edit Profile — the new avatars weren't in the picker grid. Picker drives off `identityRepository.fetchAvatarPack()` which doesn't include the user's purchased inventory. Open question while fixing: is "equip an avatar" even the right model for *every* purchase, or are some purchases (consumables, decorations) inherently not equippable? Sort the catalog into equippable vs. non-equippable before rebuilding the picker so the UI matches the data model.

### Purchases / shop feedback
- **Purchase snackbar polish.** Today's success snackbar ([ShopFeatureEntryPoint.kt:30](../features/shop/impl/src/commonMain/kotlin/com/cards/features/shop/impl/ShopFeatureEntryPoint.kt#L30)) is plain text ("Unlocked! …", "+X chips"). Make it more delightful: small emoji bubble pinned on the right side of the snackbar (not the over-the-lip dialog style — a contained right-aligned bubble), and where the item is equippable, an inline "Equip" action. Reuses the snackbar action slot we already have. Decide-while-doing which products are equippable (see avatar picker item above).

### Home / Shop chrome
- **Chip balance pill positioned identically on Home and Shop top-right.** Today the layouts differ — Home wraps Rank + XP + Chips in a flex row left-aligned ([HomeScreen.kt:100](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/HomeScreen.kt#L100)); Shop has title + subtitle on the left and Chips on the right ([ShopScreen.kt:223](../features/shop/impl/src/commonMain/kotlin/com/cards/features/shop/impl/ShopScreen.kt#L223)). The chip pill should land in the *exact* same screen coordinates on both surfaces so it reads as a shared element — eventually a real `SharedTransitionScope` element when we cross-navigate, but for V1 just pin the position. Likely means lifting a small `BalancePillSlot` composable into a shared top-bar primitive that both screens render in the same trailing position.

### Navigation animations
- **Back animation should mirror forward.** [Route.kt:44](../libraries/navigation/src/commonMain/kotlin/com/cards/libraries/navigation/Route.kt#L44) defines an `opposite()` mapping (SlideUp → SlideDown, etc.) but the pop-exit transition isn't actually using it — both forward enter and back exit currently slide the *same* direction. Desired model: a screen pushed with `SlideUp` slides up *over* the current screen (current stays put); on back, the pushed screen slides back *down* to reveal the unmoved underneath. That's the standard "cover and uncover" pattern (matches platform sheet semantics). Audit the `NavHost` enter/exit/popEnter/popExit wiring and make popExit = `opposite(enter)` while keeping the previous screen's exit/popEnter as `EnterTransition.None` / `ExitTransition.None` so it doesn't budge.

### Screen / chrome consistency
- **Previews on every user-facing composable.** Rough rule: every public/internal screen-level composable should have at least one `@Preview`. Private helpers don't need their own preview unless the parent doesn't already exercise the visual. First sweep landed previews on the obvious gaps — `OnboardingScreen`, `SignInScreen`, `SignUpScreen`, `VerifyEmailScreen`, `BotTableSetupDialog`, `WinOddsBadge`, `CountdownBadge`, `ProductIcon` / `BadgePill` / `OverhangBadge` (shop helpers). Future contributions should add a preview alongside any new screen-level composable; CI doesn't enforce yet (no static-analysis lint plugged in), so this is a convention.

### Privacy policy / terms of service
- **Write the actual content.** The profile screen already deep-links to a web page; the page itself is empty/placeholder. Probably one of the last items before TestFlight. Hosting can stay on the existing web link target.

### Typography & DS consistency
- **Audit text sizes across the app.** XP and Rank screens have copy at the bottom that's noticeably smaller than the rest of the UI. Sweep every screen and confirm: (1) only DS typography tokens are in use, (2) the default `Text` component picks a sensible body size when no `typography` is passed. If we have to override `typography = …` in 90% of call sites, the default is wrong.
- **DS-first text component default.** Whatever `Text` resolves to when called without a typography argument should match what a screen wants in 95% of cases. Building DS-aware screens should mostly *just work*.

### Onboarding / app-config
- **Bouncing to onboarding when app-config changes — root cause still open.** Hard guard landed (`OnboardingViewModel` self-corrects to Home when `hasUserOnboarded` is already true), so the symptom is now self-healing rather than a dead end. Still open: the actual root cause. Likely a `key()` / `remember` not surviving an app-config-driven recomposition that reconstructs the `NavHost` and re-pushes the start destination. Next step is a hands-on repro (toggle a value via the QA menu and watch the back stack), then fixing whichever composable is recomposing past the `AppGuardGate` / `SplashGate` insulation.

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
- **Wire native review APIs at positive moments.** New must-have ([spec §2.6](./product/product-spec.md#26-app-store-review-prompts), [v1-mvp.md §2.6](./product/v1-mvp.md)). Shape:
  - New `:libraries:review` (api + impl) with a KMP `ReviewPromptCoordinator` and platform `expect`/`actual` calling `SKStoreReviewController.requestReview()` on iOS and `ReviewManager.launchReviewFlow()` on Android.
  - Hook into existing event streams — achievement unlock (`AchievementRepository`), level-up (`ProgressionRepository`), session-end (the play-screen ViewModel emits a session-summary event). Coordinator checks the eligibility gate (install age, session count, last-prompt date, last-hand outcome) and only then signals the OS.
  - Persist `lastPromptAt` + `sessionCount` + `installAt` locally. No server round-trip — purely a client concern.
  - **Important:** never write a self-built rating dialog as a fallback. If the OS declines to show the prompt, that's the system working as designed — see [spec §2.6](./product/product-spec.md#26-app-store-review-prompts).
  - **V1-must-have** — small but load-bearing for ASO. Ship before TestFlight.

### Notifications — Phase 6, not started
- **§8 opt-in event-driven push notifications.** League placement, friend activity, battle-pass tier, Rare/Legendary achievement unlock. Never time-of-day modeled, never "your chips are lonely," never "come back" pings — see [product-spec.md §8](./product/product-spec.md#8-notifications) for the bright lines. Includes opt-in granularity (per-category toggle, not just global on/off). **Not a hidden gap** — explicitly Phase 6 on the roadmap — but worth listing so it doesn't slip after Phase 4.2.

### Email & deep linking
- **Friend-game link previews.** [product-spec.md §5.2](./product/product-spec.md#52-friend-games) promises iMessage/WhatsApp previews showing a Cards-branded card with stakes + seat count. Needs (a) iOS Universal Links + Android App Links configured for the friend-code URL, (b) a small web endpoint serving Open Graph meta (`og:title`, `og:image`, `og:description`) keyed by the code, (c) image rendering for the preview card (can be static-with-placeholders for V1 — full dynamic rendering is overkill). **V1-polish** — friend games work today via copy-code; the rich preview is a social-virality nicety, not a blocker.

- **Email confirmation link points to `localhost`.** Supabase email template is on the default. Set the project's site URL + redirect URLs in the Supabase dashboard (dev *and* prod). While there, swap the default Supabase template for a Cards-branded one (copy in [voice-and-copy.md §5.x](./product/voice-and-copy.md)).

### Claim Account screen
- **Email-claim semantics — link vs. sign-in.** OAuth claim uses `linkOAuthIdentity`, which attaches the OAuth identity to the *current* (anonymous) user and preserves guest progress. Email/password has no equivalent — `IdentityRepository.signInWithEmail` replaces the session, so claiming with email currently orphans guest chips/XP. That mirrors `ConfirmSwitchToExisting`'s OAuth-conflict semantics, but it's *every* email claim instead of the rare conflict path. Honest fix: add `linkEmailIdentity` to `IdentityRepository` (Supabase exposes `updateUser(email = ...)` on an anonymous user) and a confirmation-style claim flow on the email signup surface. Pre-V1 if email-claiming guest users is a meaningful share of the funnel; otherwise V1.x.

### Achievements
- **Bot-vs-human duplication for the rest of the registry.** `FIRST_BUST_DEALT` / `BUST_DEALT_5` are bot-only via `mode = BOTS` — same pattern needs to be applied to the rest of the registry when MP ships in Phase 4.2+. The "Beat Jane 10 times" entries are already bot-keyed by personality name; the volume / endurance / stack-swing / pot-size achievements default to `mode = EITHER`, which is fine until MP arrives. Decide at MP-launch time whether prestige-bearing ones (Comeback, Don't Call It a Comeback, Pot 5K) deserve human-only variants with separate ids.

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
  - On app launch, before allowing a Join → check `GET /v1/me/active-rooms` (doesn't exist yet; needs a one-line query on the in-memory room store). If the user has an active membership, offer rejoin / forfeit. Don't silently strand them.
  - The reconnecting-while-mid-hand path inside `ReconnectingRoomSocket` already exists; that's not the gap. The gap is the *user surface* for "you have an ongoing game."
- **Forfeit-then-spectator behavior after timeout.** Today the sweep evicts and the seat opens. Alternative: after timeout, auto-fold the user's hand for the rest of the session, leave them subscribed read-only, let them reconnect into spectate. That's a Phase 4.2 question — note it here so we don't re-derive it.

---

## D. Engineering / structural

Quality issues the user has flagged across the codebase. None are blockers, but they compound. Track them here; pull each in when the surrounding area is open.

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
**Problem:** Various repositories / services fire authed network calls on `ColdBoot` / `OnForeground`. If the user is still moving through onboarding (anonymous sign-in pending), those calls hit an unauthenticated client and either fail loudly or silently no-op until a retry.

**Sync bootstrappers (closed):** `ChipsSyncBootstrapper`, `InventorySyncBootstrapper`, and `EquipmentSyncBootstrapper` now suspend on `IdentityRepository.awaitIdentity()` before invoking their sync services. Lazy provider breaks the `IdentityRepository → AppEventBus → AppEventDispatcher → bootstrapper` DI cycle, same pattern `NetworkClientImpl` uses for `AuthTokenProvider`.

**Still open — broader audit:** the same fragility exists anywhere a class fires an authed call without first waiting for identity. The structural fix is either (a) make `NetworkClient.authenticatedClient` itself block until a token is available (instead of falling through and 401'ing), or (b) introduce a `NetworkClient.authedCall { client -> … }` helper that does the await + standard retry classification + logging. Either way, the goal is "auth-required network calls can't accidentally race onboarding." Sweep candidate after V1 ships.

### Identity cold-boot resilience
**Problem:** Anonymous sign-in roundtrips Supabase, and on a fresh install with poor / no network the `/auth/v1/token` call times out (`HttpRequestException`, 10s default) and the user is stranded in a `SessionState.Unknown` — they can't even play bots. Repro: fresh install on a heavily throttled connection.

This is a poker app. A user shouldn't need internet to play bots, even on first launch.

**Sketch — pick one or layer them:**
1. **Cached-identity fallback.** If we have a previously-cached identity from a prior session, fall back to it and treat sign-in as a background reconciliation. Doesn't help true first-launch.
2. **Local `ErrorIdentity` / `OfflineIdentity`.** Generate a local-only id so bot play is fully unlocked. On next successful auth, migrate any local progress (chips, XP, achievements) into the real account. Riskier — needs a real migration story.
3. **Defer anon sign-in until we actually need it.** Bots don't strictly need a server-side identity; only purchase / MP / leaderboard surfaces do. Treat anon-auth as a *prerequisite for those surfaces*, not for app launch. Boot offers bots immediately; sign-in happens lazily before the first networked feature.

**Lean:** option 3 is the most honest — it matches what the app actually needs from a server identity. Option 2 is technically possible but introduces a non-trivial reconciliation surface we'd otherwise avoid.

**Files / hints:** [SupabaseIdentityRepository.kt:113](../libraries/identity/impl/src/commonMain/kotlin/com/cards/libraries/identity/impl/SupabaseIdentityRepository.kt#L113) (`ensureInitialized`), [SplashGate](../libraries/identity/api/src/commonMain/kotlin/com/cards/libraries/identity/api/SessionState.kt) wiring. **Discuss approach with the human before implementing** — this is an executive decision call, not a worker pickup.

### CI — cache & artifacts
- **Gradle cache is already wired** via `gradle/actions/setup-gradle@v4` with `cache-read-only` on PRs, plus a `~/.konan` cache for Kotlin/Native ([ci.yml:71](../.github/workflows/ci.yml#L71)). That's the right shape; no change needed there.
- **Worth adding — artifact uploads:** test reports (JUnit XML / HTML) and built APK / IPA on green main. Today nothing is uploaded, so post-mortem on a CI failure means re-running locally. Use `actions/upload-artifact@v4` with sane retention (7–14 days for test reports, 30 days for release-train APKs).

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
