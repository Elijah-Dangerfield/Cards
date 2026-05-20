# V1 — What's Left

**Last reviewed:** 2026-05-20 · **Status:** Active punch list · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md)

This is the live list of everything still standing between us and V1 ship. Append, check off, and move done items into the decision log when they land. Most other docs (`product-spec.md`, `decisions.md`, `voice-and-copy.md`) are reference; this one is the working sheet.

When an item points at a file path or system, the assumption is that path/system already exists — the work is the gap, not a greenfield build.

---

## A. Decisions needed (resolve before scheduling)

Two items in this list contradict the locked spec. Resolve before queuing engineering work — the answer shapes the implementation.

1. **Felt visibility.** Note in the field log: *"only you can see your felt."* This conflicts with [product-spec.md §4.3](./product/product-spec.md#43-shop), which lists table felts as *"visible to whole table, high social signal."* The spec direction was deliberate (cosmetics-as-social-signal is the brand). If we're flipping to private felts, update §4.3 + §4.2 first; the engineering follows. If felts stay public, the work is **clearer UI copy that felts are seen by the table** (probably on the equip confirmation).

2. **Daily streak.** Note in the field log: *"one coin per day of streak starting at 5 days, opening the app continues the streak."* This conflicts with [product-spec.md Appendix C.1](./product/product-spec.md#c1-daily-login-streak-rejected-2026-05-16) (login streaks rejected on-brand). The rejection was load-bearing — streaks were explicitly called out as the canonical Zynga / Duolingo pattern we don't want. Options:
   - **No.** Keep the rejection; the "first-week welcome chips" (§4.1) already gives the early-days warmth without daily-obligation framing.
   - **Yes, narrow form.** *Weekly* play-streak (consecutive weeks with ≥1 MP hand) is already on the table in Appendix B item 17 — that's the on-brand version.
   - **Yes, the full form.** Reverse the C.1 rejection. Update the spec, the voice guide, and Appendix C before any engineering.

I'm not going to silently ship either of these against the spec. Both want an executive call.

---

## B. UX gaps observed in the build

These are bugs / polish items found playing the app or scanning the code. Cheap individually; collectively the V1 quality bar.

### Equip / cosmetics
- ~~**Single-equip felt invariant.**~~ ✅ The data model *doesn't* enforce single-equip — the comment on `EquipmentRepository` explicitly defers to the rendering layer. The picker (`MyItemsViewModel.ToggleEquipped`) now retires any same-slot equipped product before turning on the new one, using the new `cosmeticSlotFor` helper in `:libraries:cards`. Same enforcement applies to card backs, titles, and tools. Pinned by `MyItemsViewModelTest`.
- ~~**Auto-equip on purchase.**~~ ✅ `ShopViewModel.confirmChipOfferRedeem` now calls `equipmentRepository.equip(...)` after a successful redeem, *if* the product occupies a recognized cosmetic slot AND no other product in that slot is currently equipped (we don't silently steal an in-use felt; the user keeps control via My Items). Non-slot purchases (avatar packs, emote packs) skip auto-equip. Pinned by three new tests in `ShopViewModelTest`.
- **Button color adaptation against felt.** When the user equips a colored felt, the play-screen action buttons can clash. Either pin the buttons to a felt-independent surface, or token the buttons against a `surfaceOnFelt` color that the felt defines.

### Edit profile
- **Starter avatar pack content review (server-side).** Confirmed the picker reads from `IdentityRepository.fetchAvatarPack()` (server-driven). What still needs review is the *content* of the starter pack returned by `GET /v1/avatars` — the initial set should be intentionally small and must not overlap with packs users can buy. That's a server-config decision, not a client change.
- **Avatar grid — confirm scrolls on small screens.** The outer column wraps the grid in `verticalScroll`, and the grid sizes itself to `ceil(emojis / 4) * tileHeight`, so all rows should reach. Worth a manual pass on a small-screen device with a long pack to confirm nothing's clipping under the IME or the Save button.

### Screen / chrome consistency
- ~~**Most screens should use the `Screen` component + the prebuilt header.**~~ ✅ Audited. Every Screen-level composable now wraps in `Screen` (home/shop/profile keep bespoke chrome on purpose). For the title+back header, the canonical component is `libraries/ui/components/header/TopBar`; `AchievementsScreen`, `XpDetailSheet`, `RankDetailSheet` migrated off the module-local `DetailTopBar` (deleted) onto `TopBar`, matching `FeedbackScreen` / `BugReportScreen`. The remaining "inline back button" screens (`EditProfileScreen`, `MyItemsScreen`, `ClaimAccountScreen`, `DeleteAccountScreen`) are an intentional pattern — they have a centered hero/heading mid-content and don't want a top-bar title competing with it; the inline `IconButton(ArrowBack)` is treated as part of the content surface.
- **Wire `TopBar.scrollState` for lift-on-scroll across all `TopBar` users.** `TopBar` supports a shadow that animates in when content has scrolled, but none of the current callsites (`FeedbackScreen`, `BugReportScreen`, `AchievementsScreen`, `XpDetailSheet`, `RankDetailSheet`) pass a hoisted `scrollState`. Lift-on-scroll is a small UX nicety; do this as one sweep across all five so the contract stays consistent. `AchievementsScreen` uses `LazyVerticalGrid` (LazyGridState, not ScrollState), so doing this right also means extending `TopBar` to accept a `LazyGridState` / `LazyListState`.
- **Previews on every user-facing composable.** Rough rule: every public/internal screen-level composable should have at least one `@Preview`. Private helpers don't need their own preview unless the parent doesn't already exercise the visual. First sweep landed previews on the obvious gaps — `OnboardingScreen`, `SignInScreen`, `SignUpScreen`, `VerifyEmailScreen`, `BotTableSetupDialog`, `WinOddsBadge`, `CountdownBadge`, `ProductIcon` / `BadgePill` / `OverhangBadge` (shop helpers). Future contributions should add a preview alongside any new screen-level composable; CI doesn't enforce yet (no static-analysis lint plugged in), so this is a convention.

### Privacy policy / terms of service
- **Write the actual content.** The profile screen already deep-links to a web page; the page itself is empty/placeholder. Probably one of the last items before TestFlight. Hosting can stay on the existing web link target.

### Typography & DS consistency
- **Audit text sizes across the app.** XP and Rank screens have copy at the bottom that's noticeably smaller than the rest of the UI. Sweep every screen and confirm: (1) only DS typography tokens are in use, (2) the default `Text` component picks a sensible body size when no `typography` is passed. If we have to override `typography = …` in 90% of call sites, the default is wrong.
- **DS-first text component default.** Whatever `Text` resolves to when called without a typography argument should match what a screen wants in 95% of cases. Building DS-aware screens should mostly *just work*.

### Onboarding / app-config
- **Bouncing to onboarding when app-config changes.** When AppConfig refreshes, the app appears to recompose from the root and lands a previously-onboarded user back on onboarding. Fix root cause (likely a `key()` or remember not surviving the config-change recomposition), then add a hard guard: `hasUserOnboarded` flag in `AppData` (already exists) should short-circuit any path that would send a returning user to onboarding. Verify the guard runs *before* the nav graph evaluates the start destination.

### App guard chrome
- ~~**Maintenance banner placement — pushes content down.**~~ ✅ Verified the wiring: `AppGuardLayer` only renders blocking states (`UpgradeRequired`, `MaintenanceBlocking`) as a `Box` overlay; the non-blocking `MaintenanceBanner` state passes through to `AppGuardBanner` which `App.kt:160` slots into `AppNavigation`'s Scaffold `topBar`. That's a real `Scaffold.topBar`, so content pushes down. Nothing to fix here.
- **Blocking states intentionally overlay (not in topBar).** `UpgradeRequired` / `MaintenanceBlocking` use a full-screen `Box` overlay inside `AppGuardLayer` so they cover the entire app surface — including any topBar / bottomBar. That's the right model for "stop everything" states; don't refactor to a Column.

### Sound
- **Sound feedback doesn't work at all.** Already captured in [backlog.md](./backlog.md#audio-infrastructure-sound-cues-bgm). Setting persists, only Vibrate is wired (via Compose haptics); Sound is a no-op. Decision: either (a) build the `:libraries:audio` KMP module before V1 ships so the toggle is honest, or (b) hide the Sound option for V1 and ship Vibrate-only.

### Play screen — chrome
- ~~**Hand-end dialogs could use the icon/emoji top affordance.**~~ ✅ `BustDialog`, `ShowdownDialog`, `LeaveBotsConfirmDialog`, `BlindRolesExplainer`, `HandLabelExplainer`, `LastActionExplainer`, `BotTableSetupDialog`, and `SignOutConfirmDialog` all adopt `DialogEmoji` now. ShowdownDialog picks per outcome (🏆 win / 🫳 fold-out / 🃏 showdown); LastActionExplainer picks per action. Chip-coin explainers (`StackExplainer`, `PotExplainer`, `BetPillExplainer`) kept their gold `ChipCoin` on purpose — the chip-coin's "this is chips" meaning is load-bearing across the app.

### Play screen — opponents row at MP scale
- **Horizontal scroll + auto-scroll for >4 seats.** Already captured in [backlog.md](./backlog.md#multiplayer-table--opponents-row-overflow). Pulling forward: at 10-seat MP tables the current pack-and-shrink approach makes avatars unreadable. `LazyRow` once `count > 4`, auto-scroll to the active actor when their turn flips, fade gradients on both edges, respect manual user scroll for a few seconds.

### Animations / table polish
- **Bust animation for other players.** Today we have the bust dialog for the human; need a visible bust treatment on a remote seat (avatar dims, "BUSTED" stamp, chip stack collapses).
- **XP / coin earned distribution animation.** Today the showdown dialog overlays the XP/coin badges, so the user never sees the odometer count up. Idea: defer the XP/coin badge animation until *after* the showdown/bust dialog dismisses, then play it as a small "zip" — XP particle flying up to the XP badge, coin particle flying down to the chip badge, each landing into an odometer count-up. Open to pushback: the alternative is to render the earned values inside the dialog and skip the badge animation entirely.

### Table-side social
- **Emoji sending in games.** [product-spec.md §5.5](./product/product-spec.md#55-table-side-social) commits to emoji blasts (~12 base emojis, 8s cooldown, mute-this-player) as a V1 feature. Not built yet. Bottom-tray surface, full-screen 1.5s animation per emit.
- **Swipe-up-to-fold.** Gesture on the user's hole cards = fold. First time it triggers, show a confirmation dialog *with* a "Don't show this again" — so the gesture stays discoverable then gets out of the way.

### Email & deep linking
- **Email confirmation link points to `localhost`.** Supabase email template is on the default. Set the project's site URL + redirect URLs in the Supabase dashboard (dev *and* prod). While there, swap the default Supabase template for a Cards-branded one (copy in [voice-and-copy.md §5.x](./product/voice-and-copy.md)).

### Claim Account screen
- ~~**Email/password missing from Claim flow.**~~ ✅ Claim screen now surfaces a "Continue with email" button (always visible — many users have no OAuth account). Tap routes to `SignInRoute` in onboarding, same surface a new user sees.
- **Email-claim semantics — link vs. sign-in.** OAuth claim uses `linkOAuthIdentity`, which attaches the OAuth identity to the *current* (anonymous) user and preserves guest progress. Email/password has no equivalent — `IdentityRepository.signInWithEmail` replaces the session, so claiming with email currently orphans guest chips/XP. That mirrors `ConfirmSwitchToExisting`'s OAuth-conflict semantics, but it's *every* email claim instead of the rare conflict path. Honest fix: add `linkEmailIdentity` to `IdentityRepository` (Supabase exposes `updateUser(email = ...)` on an anonymous user) and a confirmation-style claim flow on the email signup surface. Pre-V1 if email-claiming guest users is a meaningful share of the funnel; otherwise V1.x.

### Achievements
- **Bot-vs-human duplication for the rest of the registry.** `FIRST_BUST_DEALT` / `BUST_DEALT_5` (added) are bot-only via `mode = BOTS` — same pattern needs to be applied to the rest of the registry when MP ships in Phase 4.2+. The "Beat Jane 10 times" entries are already bot-keyed by personality name; the volume / endurance / stack-swing / pot-size achievements default to `mode = EITHER`, which is fine until MP arrives. Decide at MP-launch time whether prestige-bearing ones (Comeback, Don't Call It a Comeback, Pot 5K) deserve human-only variants with separate ids.

### Rank screen
- **Rank/league surface isn't built out.** XP screen exists; the rank page is a stub. Either build the V1 form (current tier, what unlocks at each tier, no league mechanic yet) or be explicit it's gated until V1.1 leagues. Decide before V1 ship.

### Admin tools
- **Grant chips to a specific user.** When something goes wrong in production we need a supported way to credit chips. A small admin endpoint behind the existing admin token (`POST /v1/admin/grant-chips` taking `userId`, `delta`, `reason`) writes a `wallet_event` with reason `admin_grant`. Pairs naturally with the existing wallet ledger; no schema work.

---

## C. Multiplayer hardening

The lobby + reconnect-grace foundation landed (per project memory); these are the gaps before we trust strangers to share a room.

- **Orphaned room policy — robust, simple.**
  - Last human leaves → kill the room.
  - User taps back → leave the room (currently the WS may stay attached; verify the back path tears down).
  - App dies / disconnect → keep the seat warm via the existing `disconnectedAt` grace timer. The sweep cron (`POST /v1/admin/sweep-disconnected-room-members`, default 5 min) already evicts. After eviction the user's next launch should *(a)* tell them the seat was forfeited and *(b)* show what their stack returned. None of that surfaces yet.
  - On app launch, before allowing a Join → check `GET /v1/me/active-rooms` (doesn't exist yet; needs a one-line query on the in-memory room store). If the user has an active membership, offer rejoin / forfeit. Don't silently strand them.
  - The reconnecting-while-mid-hand path inside `ReconnectingRoomSocket` already exists; that's not the gap. The gap is the *user surface* for "you have an ongoing game."
- **Forfeit-then-spectator behavior after timeout.** Today the sweep evicts and the seat opens. Alternative: after timeout, auto-fold the user's hand for the rest of the session, leave them subscribed read-only, let them reconnect into spectate. That's a Phase 4.2 question — note it here so we don't re-derive it.
- ~~**Connection-lost UX.**~~ ✅ `PokerSession.connectionState` now flows into `PlayPokerState.connection` (no-op for solo — `LocalBotsSession` is pinned `Connected`). The play screen renders a slim push-down banner above the TopBar whenever it isn't `Connected`, using the canonical copy from [voice-and-copy.md §4.3](./product/voice-and-copy.md#43-error-messages): *"Connection lost. We're keeping your seat warm — back in a moment."* Pinned by `PlayPokerViewModelTest.sessionConnectionState_mirrorsIntoVmState`. When `RemotePokerSessionFactory` lands in Phase 4.2, it surfaces the underlying `RemoteGameSession.connectionState` via the same `PokerSession.connectionState` field — no screen changes required.

---

## D. Engineering / structural

Quality issues the user has flagged across the codebase. None are blockers, but they compound. Track them here; pull each in when the surrounding area is open.

### Sign-out data clearing
**Problem:** `clearAllUserData()` currently maintains an explicit list of DAOs to wipe. Adding a new DAO means remembering to add it to the list; forgetting leaks data across sessions. The file/DB clear paths are separate, so readers have to grep.

**Proposed shape:**
- A `ClearableDao` interface (`suspend fun deleteAll()`).
- Each `@Dao` extends it; Room's codegen handles `deleteAll()` per table via an `@Query("DELETE FROM …")` or per-DAO method.
- DI binds *every* `ClearableDao` into a multibinding set.
- A `SignOutDataDeleter` singleton receives the set and iterates `deleteAll()` on each. Same singleton handles file deletion (app caches, downloaded avatars, etc.), so the full "what gets cleared on sign-out" lives in one file.
- Adding a new DAO means extending `ClearableDao` — no list edit, no forgetting.

Cost: refactor the existing wipe + DI multibinding wire-up. Worth it before V1 because data-leak-on-sign-out is the kind of bug that survives unless it's structural.

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
- ~~Uses `try { } catch` throughout instead of `Catching { }`.~~ ✅ Migrated to `Catching { … }.fold(onSuccess, onFailure)` across every entry point (`signInWithEmail`, `signUpWithEmail`, `refreshSession`, `resendVerificationEmail`, `updateProfile`, `fetchAvatarPack`, `deleteAccount`, `linkOAuthIdentity`, `signInWithOAuth`). The explicit `catch (e: CancellationException) { throw e }` rethrows the OAuth flows used are now unnecessary — `Catching` already rethrows cooperative cancellation via `shouldNotBeCaught`. No behavior change; all outcome mappings preserved 1:1.
- **No tests yet.** The impl has no commonTest sources — a real test pass would need fakes for `SupabaseClient`, `ProfileApi`, `IdentityCache`, and `AppEventBus`. Worth doing before any further changes to the outcome-mapping logic; downstream feature tests use the `IdentityRepository` interface via fakes so they don't exercise these mappings.
- Maintains its own cache; supabase-kt has its own session cache. Are we double-caching? Should we trust theirs?
- An identity-as-DI question: rather than each consumer awaiting `IdentityRepository.state`, inject a `Lazy<Identity>` (the way `AppConfig` is treated) that *should* be initialized at boot, with `runBlocking` as the worst-case fallback. Makes consumer code straight-line and removes a class of "what if the state isn't ready" bugs.

Note: the get-or-create pattern is correct, so the remaining structural concerns are consolidation, not correctness.

### Authed calls firing before auth resolves
**Problem:** Various repositories / services fire authed network calls on `ColdBoot` / `OnForeground`. If the user is still moving through onboarding (anonymous sign-in pending), those calls hit an unauthenticated client and either fail loudly or silently no-op until a retry.

**Sync bootstrappers (closed):** `ChipsSyncBootstrapper`, `InventorySyncBootstrapper`, and `EquipmentSyncBootstrapper` now suspend on `IdentityRepository.awaitIdentity()` before invoking their sync services. Lazy provider breaks the `IdentityRepository → AppEventBus → AppEventDispatcher → bootstrapper` DI cycle, same pattern `NetworkClientImpl` uses for `AuthTokenProvider`.

**Still open — broader audit:** the same fragility exists anywhere a class fires an authed call without first waiting for identity. The structural fix is either (a) make `NetworkClient.authenticatedClient` itself block until a token is available (instead of falling through and 401'ing), or (b) introduce a `NetworkClient.authedCall { client -> … }` helper that does the await + standard retry classification + logging. Either way, the goal is "auth-required network calls can't accidentally race onboarding." Sweep candidate after V1 ships.

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
