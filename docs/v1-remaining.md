# V1 — What's Left

**Last reviewed:** 2026-05-19 · **Status:** Active punch list · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md)

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
- **Single-equip felt invariant.** Verify only one felt can be equipped at a time end-to-end (data model already enforces single-equip per category; the worry is whether the picker UI honors it). Same check for card backs and titles.
- **Auto-equip on purchase.** When a user buys a cosmetic, equip it by default unless a conflicting slot is already occupied. Currently the purchase confirms but the equip step is a separate trip to Your Items.
- **Button color adaptation against felt.** When the user equips a colored felt, the play-screen action buttons can clash. Either pin the buttons to a felt-independent surface, or token the buttons against a `surfaceOnFelt` color that the felt defines.

### Typography & DS consistency
- **Audit text sizes across the app.** XP and Rank screens have copy at the bottom that's noticeably smaller than the rest of the UI. Sweep every screen and confirm: (1) only DS typography tokens are in use, (2) the default `Text` component picks a sensible body size when no `typography` is passed. If we have to override `typography = …` in 90% of call sites, the default is wrong.
- **DS-first text component default.** Whatever `Text` resolves to when called without a typography argument should match what a screen wants in 95% of cases. Building DS-aware screens should mostly *just work*.

### Onboarding / app-config
- **Bouncing to onboarding when app-config changes.** When AppConfig refreshes, the app appears to recompose from the root and lands a previously-onboarded user back on onboarding. Fix root cause (likely a `key()` or remember not surviving the config-change recomposition), then add a hard guard: `hasUserOnboarded` flag in `AppData` (already exists) should short-circuit any path that would send a returning user to onboarding. Verify the guard runs *before* the nav graph evaluates the start destination.

### Maintenance banner placement
- **Banner overlaps content instead of pushing it down.** `AppGuardBanner` already exists [features/upgrade/impl/.../AppGuardLayer.kt:91](features/upgrade/impl/src/commonMain/kotlin/com/dangerfield/cards/features/upgrade/impl/AppGuardLayer.kt) and is *designed* to sit in a Scaffold `topBar` slot ("Place this in a Scaffold's topBar slot so the Scaffold owns status-bar inset propagation"). If it's currently overlaying, the wiring at the call site is wrong — find it and move the banner from `Box`-overlay into the `topBar` slot, or wrap a Column around the nav graph and put the banner above.

### Sound
- **Sound feedback doesn't work at all.** Already captured in [backlog.md](./backlog.md#audio-infrastructure-sound-cues-bgm). Setting persists, only Vibrate is wired (via Compose haptics); Sound is a no-op. Decision: either (a) build the `:libraries:audio` KMP module before V1 ships so the toggle is honest, or (b) hide the Sound option for V1 and ship Vibrate-only.

### Animations / table polish
- **Bust animation for other players.** Today we have the bust dialog for the human; need a visible bust treatment on a remote seat (avatar dims, "BUSTED" stamp, chip stack collapses).
- **Skip-to-end / instant-bots after human fold.** When a human folds in a bot game, the rest of the hand is just bots — show a "Skip to end" affordance, and drop bot think-time to ~0ms so the skip-or-watch experience is fast either way.

### Email & deep linking
- **Email confirmation link points to `localhost`.** Supabase email template is on the default. Set the project's site URL + redirect URLs in the Supabase dashboard (dev *and* prod). While there, swap the default Supabase template for a Cards-branded one (copy in [voice-and-copy.md §5.x](./product/voice-and-copy.md)).

### Claim Account screen
- **Email/password missing from Claim flow.** The Claim screen only surfaces Apple / Google buttons. We ship email/password auth too, so the Claim screen needs to route to the same email sign-up / sign-in surface as new-user onboarding does — otherwise an anonymous user who only has email can't claim.

### Achievements
- **"Made another player bust" achievement.** Not in the registry today; add it.
- **Bot-vs-human duplication.** Several existing achievements treat busting/wins against bots and humans the same; the prestige value is different. Audit `AchievementRegistry` and either (a) tag each entry as bot-eligible / human-eligible / both, or (b) duplicate the entries with separate ids ("bust 10 bots" + "bust 10 humans"). The duplication path is simpler; the tagging path is cleaner. Decide at the moment we touch the file.

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
- **Connection-lost UX.** The existing `ConnectionState` on `RemoteGameSession` is wired; the screen-level treatment ("Connection lost. We're keeping your seat warm — back in a moment." from [voice-and-copy.md §4.3](./product/voice-and-copy.md#43-error-messages)) is not.

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
- Uses `try { } catch` throughout instead of [`Catching { }`](AGENTS.md#coding-guidelines) — repo convention is `Catching`.
- Maintains its own cache; supabase-kt has its own session cache. Are we double-caching? Should we trust theirs?
- An identity-as-DI question: rather than each consumer awaiting `IdentityRepository.state`, inject a `Lazy<Identity>` (the way `AppConfig` is treated) that *should* be initialized at boot, with `runBlocking` as the worst-case fallback. Makes consumer code straight-line and removes a class of "what if the state isn't ready" bugs.

Pull this audit in next time we're in the auth code. Note: the get-or-create pattern is correct, so the structural concern is style + consolidation, not correctness.

### Dead code: navigation tracker
`AppNavigationTracker` increments per-route visit counters into `AppCache`, but nothing reads them. The infra is in [libraries/navigation/impl/.../AppNavigationTracker.kt](libraries/navigation/impl/src/commonMain/kotlin/com/cards/libraries/navigation/impl/AppNavigationTracker.kt), `NavigationTracker` interface, `TrackableRoute` marker, and the visit-counting fields in `AppData`. Delete the tracker, the interface, the trackable-route bookkeeping, and the AppData fields. If we ever want per-route analytics we'll wire something real.

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
- **Real platform billing impls (Play Billing v6+ / StoreKit 2)** — billing scaffold + `FakeBillingClient` is in place; provisioning store listings is the gate, not engineering.
- **OAuth UI gated by `IdentityFeatureConfig`** — Apple/Google buttons are wired but flagged off until dashboard credentials exist.
- **Username localization, bot name localization** — V1.x / V2 problems.
