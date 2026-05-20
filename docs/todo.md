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

1. **MP buy-in / ante mechanic.** Multiplayer needs a chip sink at the table — otherwise chips are a one-way faucet and there's no economic loop. Open questions before engineering: (a) flat buy-in to enter the room (returned on graceful leave?) or per-hand ante? (b) host-set or fixed? (c) does the bot table mirror this so the mechanic is discoverable, or are bot tables explicitly chip-free? (d) refund behavior on disconnect / forfeit. Lands in product-spec.md §5 once decided.

---

## B. UX gaps observed in the build

These are bugs / polish items found playing the app or scanning the code. Cheap individually; collectively the V1 quality bar.

### Design system — dialog & sheet primitives
- **Reshape Dialog / BottomSheet primitives around a `Base*` escape hatch + opinionated default, and force theme-awareness on emoji bubbles.** Today's layering doesn't match the names: `Dialog` is already DS-opinionated (surface, animation, emoji affordance baked in), and the truly raw escape (`HostedDialog`) is hidden as `internal`. Bottom sheets invert the convention — `BottomSheet` is raw, `BasicBottomSheet` is the opinionated one — so the parallel doesn't hold either. Step 1 of the plan landed (`BasicDialog` flattened into `Dialog` overloads, file deleted, both `:libraries:ui` callsites migrated). Remaining steps:
   1. **Promote `HostedDialog` to public as `BaseDialog` behind a `@LowLevelDialogApi` opt-in.** Same body, just published. Opt-in annotation is the discoverable signal that the caller is deliberately escaping DS defaults.
   2. **Rename bottom sheets to match:** `BottomSheet` → `BaseBottomSheet` (also gated by `@LowLevelDialogApi`); `BasicBottomSheet` → `BottomSheet`. ~5 callsites.
   3. **Force theme-awareness on bubbles.** Make `DialogEmoji` `internal`; expose only the composable factory `dialogEmoji(...)`. Update 7 `DialogEmoji(...)` callsites to `dialogEmoji(...)`. Same treatment for the bottom-sheet emoji handle equivalent. Pin both to the same surface token (today they differ — dialogs default to `surfacePrimary`, the factory defaults to `surfaceTertiary`) so dialogs and sheets visually agree. **Note:** the surface-token pin is a visual design call — a worker should bring it to the human before flipping the default.
   4. **Document in AGENTS.md → Design system.** One paragraph naming the layers: "for 99% of dialogs, use `Dialog(...)`; for raw escape opt into `@LowLevelDialogApi` and use `BaseDialog`. Bubbles are created via `dialogEmoji(...)` so theme defaults always apply." Then future callsites have a single decision tree.

   **Out of scope:** the underlying animation / scrim / sizing behavior — that stays. This is naming, layering, and emoji-bubble theme-awareness only.

### Edit profile
- **Offline-first reads for profile-editable data.** When the user opens Edit Profile, the avatar picker fetches the pack fresh from the server every time — slow, and impossible offline. Drive the picker (and similar profile-editable surfaces) from the local DB; reconcile to the server in the background. Edits should write locally first, queue a sync, and trust eventual consistency. **Out of scope:** server-authoritative things that need server confirmation before they're real (products / purchases / chip wallet — those keep their current server-roundtrip semantics).

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

### Email & deep linking
- **Email confirmation link points to `localhost`.** Supabase email template is on the default. Set the project's site URL + redirect URLs in the Supabase dashboard (dev *and* prod). While there, swap the default Supabase template for a Cards-branded one (copy in [voice-and-copy.md §5.x](./product/voice-and-copy.md)).

### Claim Account screen
- **Email-claim semantics — link vs. sign-in.** OAuth claim uses `linkOAuthIdentity`, which attaches the OAuth identity to the *current* (anonymous) user and preserves guest progress. Email/password has no equivalent — `IdentityRepository.signInWithEmail` replaces the session, so claiming with email currently orphans guest chips/XP. That mirrors `ConfirmSwitchToExisting`'s OAuth-conflict semantics, but it's *every* email claim instead of the rare conflict path. Honest fix: add `linkEmailIdentity` to `IdentityRepository` (Supabase exposes `updateUser(email = ...)` on an anonymous user) and a confirmation-style claim flow on the email signup surface. Pre-V1 if email-claiming guest users is a meaningful share of the funnel; otherwise V1.x.

### Achievements
- **Bot-vs-human duplication for the rest of the registry.** `FIRST_BUST_DEALT` / `BUST_DEALT_5` are bot-only via `mode = BOTS` — same pattern needs to be applied to the rest of the registry when MP ships in Phase 4.2+. The "Beat Jane 10 times" entries are already bot-keyed by personality name; the volume / endurance / stack-swing / pot-size achievements default to `mode = EITHER`, which is fine until MP arrives. Decide at MP-launch time whether prestige-bearing ones (Comeback, Don't Call It a Comeback, Pot 5K) deserve human-only variants with separate ids.

### Rank screen
- **Rank/league surface isn't built out.** XP screen exists; the rank page is a stub. Either build the V1 form (current tier, what unlocks at each tier, no league mechanic yet) or be explicit it's gated until V1.1 leagues. Decide before V1 ship.

### Stats page (renamed from XP)
- **Rebrand the XP page as the Stats page.** The screen currently framed as "XP" should evolve into a broader player Stats page — XP + Rank + lifetime numbers (hands played, biggest pot, biggest comeback, etc.) in one surface, with XP one section among several. Tasks: rename the route + screen + entry + navigation strings, restructure the screen content into Stats sections with XP as the lead section, update any references in voice-and-copy.md. Don't widen the scope to add new stats *yet* — first land the rebrand cleanly, then add stats sections in follow-ups.

### Home screen redesign
- **Whole-screen redesign of Home.** The current Home doesn't match the brand — feels generic compared to the Card Hall positioning in [product-spec.md](./product/product-spec.md). Direction: "Duolingo big-surface energy, but more elegant, less kiddy" — large primary CTAs (Play with bots, future Play with friends / Find a room), prominent progression visibility (XP, Rank, daily/seasonal pull), but never feel like a casino skin or a kids' app. **Needs design pass first** — pull from product-spec.md §3 (the Card Hall positioning) and §7 (Home as the entry point), the existing voice-and-copy.md, and the brand notes in §3.1 ("dark mode, muted accents, type-driven moments, never saturated casino-green"). Out of scope until the design pass is done — engineering follows. **Future state to keep in mind during design:** Home eventually has two MP entry points — "Play with friends" (room code / direct invite) and "Find a room" (public matchmaking). Even if those aren't wired in V1, the design should accommodate them without restructuring.

---

## C. Multiplayer hardening

The lobby + reconnect-grace foundation landed (per project memory); these are the gaps before we trust strangers to share a room.

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
