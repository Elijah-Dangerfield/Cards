# TODO

**Last reviewed:** 2026-05-27 · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md), [backlog.md](./backlog.md)

The live punch list of actionable engineering work. Append, check off, and **delete** done items — they don't need to live here as history. Add a [decisions.md](./decisions.md) entry **only** when an item resolved a non-trivial architectural call worth not re-litigating (see decisions.md header for what qualifies). Most items just get crossed off and removed.

When an item points at a file path or system, the assumption is that path/system already exists — the work is the gap, not a greenfield build.

**Priority tags** (every item carries one — workers should bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking; pick once your area's P0s are claimed or you can't confidently progress on them.
- `[P2]` — Good to have, can wait. Usually carries a "designer/architect call needed" or "refactor that needs scope" subtext.

**Item template** (the more of these you fill in, the safer it is for an automated worker to pick up):

> **Problem:** what's wrong / missing.
> **Acceptance:** how we know it's done.
> **Files / hints:** where to start looking.
> **Out of scope:** what NOT to drag in.

Everything in this file is worker-pickable. Items that need a human action — device QA, dashboard config, content writing, product decisions — live in [`docs/developer-todo.md`](./developer-todo.md) instead. Per-cycle follow-ups tied to a specific PR's diff live in the PR's "Heads up" section.

---

## A. UX gaps observed in the build

These are bugs / polish items found playing the app or scanning the code. Cheap individually; collectively the V1 quality bar.

### Notifications

- `[P0]` **Notification badge on the Profile tab + the "Notifications" row inside Profile.** [`NotificationsScreen`](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/notifications/NotificationsScreen.kt) renders unread + read messages from `UserMessageRepository`, but nothing in the UI signals "you have unread" until you navigate there. **Acceptance:** when `UserMessageRepository` has unread non-dialog messages, the Profile tab shows a small accent-tinted dot/count badge, and the "Notifications" list-item inside Profile shows the same. Both clear when the user enters `NotificationsScreen` (mark-as-read fires on screen entry). Dialog-kind messages do **not** trip the badge — they self-clear when the dialog dismisses. **Files / hints:** add `UserMessageRepository.observeUnread()` if it doesn't exist; tab-badge surface lives in the bottom-nav scaffold; the row lives on [`ProfileScreen.kt`](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt). **Out of scope:** push notifications (Phase 6); separate badges for different message categories.

### Achievements — bot-vs-human split

- `[P1]` **Land the registry + tests for `FIRST_BUST_DEALT_MP` / `BUST_DEALT_5_MP` siblings per [`docs/achievements-spec.md`](./achievements-spec.md).** The two tier-blind re-anchors (DONT_CALL_IT_COMEBACK → ≤10 BB / ≥100 BB; POT_5000 → ≥25× BB) landed. The MP-keyed siblings are partially shippable today: registry entry + `AchievementMode.MP` enum value + counter stub + tests can land now; the grant trigger waits for Phase 4.2 (server-authoritative gameplay). **Acceptance:** new ids appear in `AllAchievements` with mode + criterion + reward; counter stub in `AchievementRepositoryImpl.kt` returns 0 with a TODO referencing Phase 4.2; tests pin the registry shape. **Files / hints:** registry + `AchievementMode` live in `:libraries:cards`. **Out of scope:** the actual server-witnessed grant seam (Phase 4.2); brand-new achievements.

### Catalog gating — unlock-only vs purchasable

- `[P1]` **Harden the client-grant endpoint with shippable defenses.** Today any authed user can POST any allowlisted id to `POST /v1/me/grants/achievement/{id}` and receive the mapped cosmetic with no proof of having earned it. The real fix waits for Phase 4.2 (server-witnessing); shippable defenses today: (a) per-user-per-day rate cap on the endpoint, (b) hand-count floor — require `≥ N` recorded hands before any grant is accepted, (c) session-nonce binding the grant to the hand that produced it. **Acceptance:** all three controls in place with tests; rate-cap returns 429, floor returns 403, nonce mismatch returns 403. **Files / hints:** [`ClientGrantableAchievements.kt`](../apps/server/src/main/kotlin/com/cards/server/domain/ClientGrantableAchievements.kt) is the resolution path; routes live in `apps/server/src/main/kotlin/com/cards/server/api/`. **Out of scope:** Phase 4.2 server-witnessing.

- `[P1]` **Promote the inline cosmetic-unlock row into a full-bleed celebration.** Today the showdown / bust dialog's `AchievementUnlockedCallout` carries an inline "🎁 Also unlocked · {label}" row. Prestige earns its own moment — not a one-liner inside the chrome. **Acceptance:** after the hand-result dialog dismisses, a full-bleed sheet/dialog renders the cosmetic with art (felt preview / card-back preview / title pill), name, and source ("from the Comeback Kid achievement"). Auto-dismisses on tap or after N seconds. Sequences with the XP/coin animation below if both fire same hand. **Files / hints:** existing inline row in [`HandResultDialogs.kt`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/HandResultDialogs.kt). **Out of scope:** removing the inline row — keep as a teaser; net-new cosmetic art.

- `[P1]` **Avatar unlocks — hidden picker entries + medallion preview.** Special emoji variants above the standard avatar-pack pool, hidden in the Edit Profile picker until earned, and previewed on their source achievement's medallion (rarity tile with "unlocks special avatar" label). **Acceptance:** picker excludes locked emoji until inventory carries them; medallion back-face advertises the variant; one seed avatar exists, paired with an existing achievement. **Files / hints:** `availableEmojis` pool gating in `ProfileScreen.kt`'s picker; extend [`cosmeticRewardFor`](../libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/EarnableCosmetics.kt)'s return shape if needed; medallion render in [`AchievementMedallion.kt`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/achievement/AchievementMedallion.kt).

- `[P1]` **Pinned achievement showcase on the tap-an-opponent profile sheet.** Top 3 achievements visible to opponents when they tap your seat. **Acceptance:** human seat tap renders top 3 pinned achievements as a row of small medallions; the user gets a picker to choose which 3 to pin (lives in Profile or in the sheet itself). **Files / hints:** [`PlayerProfileSheet.kt`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayerProfileSheet.kt); pinned-ids storage on `AppData`. **Out of scope:** pin/unpin on bot seats.

- `[P2]` **Permanent badge slot on seat bottom-left.** Small icon slot under the avatar, separate from the turn-indicator ring. Content TBD: tier badge, single-achievement-pin glyph, "founding member" mark. **Needs:** designer call on which shape ships first. A worker can prep the layout slot in [`PlayerArea.kt`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayerArea.kt) and stub the content as a no-op until the call lands — flag the directional uncertainty in the in-flight block.

- `[P2]` **Emote / blast-pack unlocks.** Extra emoji blasts in the in-game tray, earned. **Needs:** designer call on which packs and what unlocks them.

- `[P2]` **League-tier rewards (blocked on league mechanic).** One cosmetic per league tier granted at season end. Re-pick once the league system has a real surface.

- `[P2]` **RFT (rare-from-the-floor) drops (blocked on server-side roll plumbing).** Low-probability cosmetic drops at hand-end. Re-pick once the server can roll the dice.

- `[P2]` **League season-end grant wiring (blocked on league mechanic).** Wire the league season-end tally to call `recordEarnedGrant` for any tier-tied cosmetic. League-finish has no client-trigger seam — fires from the server-side close routine when leagues exist.

### Strings — centralize everything in `:libraries:resources`

- `[P0]` **Sweep every inline user-facing string into `:libraries:resources` Compose Multiplatform resources.** Most UI copy is hardcoded at the callsite ("OWNED", "Claim your account", "Long-press to copy", section titles, error snackbars, etc.). That's load-bearing-by-accident: blocks future localization, makes voice-and-copy edits a repo-wide find-and-replace, lets two screens drift on the same idea. **Acceptance:** every user-facing string in `:features/*/impl` and `:libraries:ui` is keyed through `Res.string.*` from `:libraries:resources/src/commonMain/composeResources/`. New strings always go straight to the resource — no inline. Migration is gradual; start with shop snackbars and owned-state surfaces (shared copy across screens) so consolidation surfaces drift immediately. **Files / hints:** `:libraries:resources` already wires `composeResources/` — reuse, don't create a new module. **Out of scope:** translating anything (give strings a *home*, not a second language); bot-name copy (V1.x / V2 problem per §D); preview-only / test-only strings.

### Dialogs / sheets — DS-preset overloads

- `[P0]` **Add preset overloads to `Dialog` and `BottomSheet` so callsites stop hand-rolling title + body styling.** Today many dialogs render their own `Column { Text(title); Text(body) }` and pick typography by hand, drifting on `AppTheme.typography.Heading.*` vs `Body.*` across surfaces. **Acceptance:** two new overloads per primitive — `Dialog(title: String, body: String, ...)` and `Dialog(title: @Composable () -> Unit, body: @Composable () -> Unit, ...)`, same shape for `BottomSheet`. Both wrap a `CompositionLocalProvider` setting default text typography so any nested `Text` without an explicit typography inherits the right title/body style. Existing `Dialog(content: @Composable () -> Unit, ...)` stays as the escape hatch. Migrate at least 3 existing dialogs and 2 sheets to prove the shape and surface drift. **Files / hints:** `:libraries:ui/components/dialog/`, `:libraries:ui/components/dialog/bottomsheet/`. AGENTS.md "`Base*` + opinionated DS components" section is the pattern. **Out of scope:** retiring the existing content-slot overloads (kept for genuine custom layouts).

### Screen / chrome consistency

- `[P1]` **Horizontal scrollers should be edge-to-edge with internal content padding, not page-padded.** Today horizontal carousels (Home tiles, Recently-played-with strip, anywhere `LazyRow` lives inside the standard screen page padding) align the first/last items with the page content edge — no "scroll for more" affordance. **Acceptance:** every horizontal `LazyRow` lays out edge-to-edge (the screen's horizontal page padding doesn't apply); the row uses `contentPadding = PaddingValues(horizontal = …)` matching the page padding so the first item still visually insets, and items partially clip off the right edge to advertise scrollability. Pull out a `EdgeToEdgeRow`-style DS primitive if 3+ usages share the same shape. **Files / hints:** survey `LazyRow` usage in `:features` and convert. **Out of scope:** vertical scrollers; fixed-N non-scrolling rows.

### Animations / table polish

- `[P1]` **XP / coin earned distribution animation.** Today the showdown dialog overlays the XP/coin badges, so the user never sees the odometer count up. Defer the XP/coin badge animation until *after* the showdown / bust dialog dismisses, then play it as a small "zip" — XP particle flying up to the XP badge, coin particle flying down to the chip badge, each landing into an odometer count-up. Pairs with the full-bleed cosmetic celebration above for the post-dismiss sequencing.

- `[P1]` **`AvatarCircle` should not animate on every Profile-screen navigation.** [`AvatarCircle`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/AvatarCircle.kt) plays a fade + scale `AnimatedContent` on emoji change — right feel for the Edit Profile picker (the user changed it), wrong on the Profile screen header (the user navigated to the tab). **Acceptance:** add an `animateOnChange: Boolean = true` parameter (or equivalent opt-out); Profile screen header passes `false`; Edit Profile picker keeps the default. **Files / hints:** the parameter goes on `AvatarCircle.kt`; the consumer that should opt out is [`ProfileScreen.kt`](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt). **Out of scope:** removing the animation from the picker (real change-event feedback).

### Gameplay & table UX

- `[P1]` **Heat-map / personality blob on the tap-an-opponent profile sheet — bot variant.** Render a small 4-axis spider/blob inside the existing "Playing style" section showing the bot's tendencies. Axes from `BotPersonality`: tightness, aggression, bluffRate, and a fourth (passivity = `1 - aggression`, or add an explicit attribute). **Acceptance:** bot seats render a small visual showing where this bot sits on the four axes; the existing label ("Tight aggressive" / "Maniac" / etc.) sits above it; the five roster bots produce visually distinct shapes. **Files / hints:** [`BotPlayingStyle.kt`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/BotPlayingStyle.kt) already maps personality to label — reuse the same data. New small visual primitive belongs in `:libraries:ui/components/`. **Out of scope:** human-seat heat-map (needs real tracking, see next item); pulling in a radar-chart library — just draw it.

- `[P2]` **Per-hand attribute tracking + batch upload for the human heat-map.** No per-hand data is captured today for the local human player. To eventually surface a heat-map on the human's own profile, we need to track decisions over time. **Direction (worker recommends, reviewer course-corrects):** capture per-hand attributes (folded / called / raised / bluffed / showdown won, with win-lose outcome) into a local Room table at hand-end; batch-upload to the server when either 50 entries accumulate or 24h passes since last upload, whichever first. Server stores rows in a `player_hand_decisions` table and exposes a derived "heat-map snapshot" endpoint the human profile sheet can render. **Acceptance:** local capture works on every resolved hand; batch policy fires on threshold or timer; server endpoint returns a snapshot. **Files / hints:** Room storage at `:libraries:storage`; new server route; existing achievement counter logic is a precedent for per-hand local capture. **Out of scope:** the actual heat-map visual on the human profile (separate item once the snapshot endpoint lands); bot tracking; historical backfill. **Worker note:** write a 1-paragraph architecture sketch in the in-flight Approach line before committing code — direction ambiguity is high here, that's the safety net.

- `[P1]` **Expand the tap-an-opponent profile sheet — remaining work.** Playing-style + difficulty-tier landed. Remaining: "playing since {createdAt}" + "{N} hands at the table" duration line (needs `SeatView.fromSeat(...)` to read `Profile` + room-membership rows in [`TableUiState.kt`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/TableUiState.kt)); human-variant "Add friend" affordance (pairs with the friend graph below) and "view full profile" tap-through once profile-of-a-stranger is a real route. **Acceptance per piece:** each lands as its own commit if natural.

### Social graph + friends — load-bearing for V1.x

Home now exposes three surfaces that need the friends / recents system to actually work: the friends strip with online presence, the "recently played with" shelf with add-friend affordances, and the friend-requests inbox on profile. All currently fake or no-op.

**Locked rule:** friendship is gated on having played together. The only path to friending someone is the "recently played with" shelf — no search-by-handle, no friend-suggestions. The empty-state copy on the social surfaces has to communicate this clearly.

- `[P0]` **Friend graph — server schema + endpoints.** New tables: `friend_relations(user_a, user_b, state, created_at)` with `state ∈ {requested, accepted, blocked}` and `user_a` always the lexicographically smaller id (so the row is unique regardless of direction). Endpoints: `POST /v1/friends/requests` (target user id), `POST /v1/friends/requests/{id}/accept|decline|block`, `GET /v1/friends` (accepted), `GET /v1/friends/requests` (inbound, pending). Anti-abuse: rate-limit outbound requests per user/day; block-relations dominate accept/decline. **Hard dep:** only ids surfaced through the recently-played-with shelf can be friended — see the next bullet.

- `[P0]` **Recently-played-with tracking.** Server records the human seats present at every multiplayer hand a user finishes; on the client, `RecentOpponentsRepository.observeRecent(limit = 10)` returns deduped most-recent first. Bots are excluded server-side (can't friend the house). [`RecentlyPlayedWithStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/RecentlyPlayedWithStrip.kt) renders the list; tile flips to "Sent" when `RecentOpponent.requestSent` flips true. **Acceptance:** the shelf renders real data and add-friend works end-to-end.

- `[P0]` **Communicate the "friend via play" rule on the social empty states.** Zero-friend users need to know how to get there. **Acceptance:** the Recently-played-with shelf's empty state explains the rule in plain copy ("Make a friend by playing together — start a friend game or sit down at a public table") with CTAs into the relevant flows. Friends-strip empty state echoes briefly. Profile screen's social section (if it exists) carries the same explanation. **Files / hints:** [`FriendsStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt), [`RecentlyPlayedWithStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/RecentlyPlayedWithStrip.kt), Profile social section. **Out of scope:** in-app invite-via-share-link (Phase 2+); friend suggestions (locked-out by design).

- `[P1]` **Online-presence signal.** Cheapest path: server emits a presence event when a user's WS connects/disconnects and stores last-seen + current-room (if any). Client subscribes once per session to a presence stream filtered to friend ids. [`FriendsStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) already takes `List<FriendOnline>` shaped for this — drop the real list in and the surface lights up.

- `[P1]` **Friend requests inbox — section on Profile, badge on Home.** Inbox is a section on [`ProfileScreen.kt`](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt) — list of pending inbound requests with accept/decline buttons. Home doesn't get its own inbox; [`FriendsStrip.kt`](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/FriendsStrip.kt) renders a "N friend requests" badge when `pendingRequests > 0`, tap routes into Profile's inbox section. Strip survives even with zero friends online so long as there are pending requests.

- `[P1]` **Voice + safety pass on every friend-system copy string.** Audit every string (request prompts, request-sent confirmations, accept/decline banners, empty states) against the spec voice rules: no urgency, no "X people are waiting!", no begging. Block-relation behavior defaults: blocking a user removes any existing accepted-friend row and prevents matchmaking into the same public room. Implement the defaults; adjust later if product flags otherwise.

- **Out of scope for V1.x:** friend suggestions ("people you might know"), in-app invite-via-share-link, push notifications for requests, group chat. All Phase 2+ design questions.

---

## B. Multiplayer hardening

**MP is parked behind the architecture revisit.** The bullet below produces the sub-items that unlock the rest of §B — don't pick the parked items below until the revisit lands.

- `[P0]` **Revisit the multiplayer system design before §B expands.** A step-back eval lives at [`multiplayer-architecture-eval.md`](./multiplayer-architecture-eval.md): current REST + WS shape is correct in its bones; biggest gaps are *in-memory-only state* and *no event log*. Recommended target is event-sourced game state persisted to Postgres, sequence-numbered events over the existing WS, snapshot-on-hand-end compaction. **Acceptance:** decide adoption (in whole or in part) and produce concrete engineering items for each accepted piece — each item lands in §B as its own bullet, replacing the parked work below. The decision + the resulting item list is the deliverable. Treat this as a worker-pickable item where the worker reads the eval, picks a direction, writes the resulting sub-item list back into this file under §B, and the reviewer validates the direction. **Out of scope:** managed realtime layers for the game itself (the eval rejects those for game traffic but leaves Supabase Realtime open for ambient social channels later).

### Parked until the architecture revisit emits sub-items

The items below describe shape-of-eventual-work, not bot-pickable tasks. They re-enter §B as concrete bullets once the revisit decides their shape. **Don't pick these directly.**

- **Buy-in / stack / re-buy mechanic.** Server table-reservations (buy-in moves wallet → table-held on sit, reverses on stand / sweep-evict); client renders stack (not wallet) on the play screen; re-buy dialog on stack = 0; sit-out toggle in seat menu; anti-smurf gate ([§5.3](./product/product-spec.md#53-public-rooms)) rejects sit-down if buy-in > 25% of wallet. Spec at [§4.1](./product/product-spec.md#wallet-stack--buy-ins). Bot tables already map difficulty → `StakeTier` via `SoloBotsPokerSessionFactory.toStakeTier()`. Re-pick once the architecture revisit decides whether buy-in moves live in the event log or as a separate reservations subsystem.

- **MP credit by table composition** ([§5.4](./product/product-spec.md#mp-credit-by-table-composition)). The "≥2 humans AND humans ≥ bots" rule for granting MP XP / league credit / achievements is documented but not enforced. Without it, two friends + four bots is a chip-farm exploit. Client also needs the visible "Practice tier · bots present" label. Re-pick once the revisit decides where post-hand progression hooks fire so the gating sits at the right seam.

- **Orphaned-room policy.** Last-human-leaves → kill the room; user taps back → leave the room (verify WS teardown); app dies / disconnect → keep the seat warm via the existing `disconnectedAt` grace + reaper; on launch, `GET /v1/me/active-rooms` drives the Rejoin / Forfeit banner. **Resolved direction:** post-eviction transition is **forfeit-then-spectator** — auto-fold for the rest of the session, subscription remains read-only, reconnect comes back as a spectator (decided 2026-05-27, replaces the prior "sit out vs remove" open question). Re-pick the wiring once the revisit decides whether orphan handling lives in the event log (compensating events for forfeit) or as a separate sweep.

---

## C. Engineering / structural

Quality issues flagged across the codebase. None are blockers; they compound.

### Caching + config plumbing

- `[P2]` **Replace `FeatureConfig`'s `by featureValue(...)` with DI-bound `ConfiguredValue<T>` singletons.** Today new tunable values land as another delegate on a growing `FeatureConfig` object — fine, but the QA menu has to know about each new field by name to render an override row. **Acceptance:** existing `FeatureConfig` callsites read from injected `ConfiguredValue<T>` singletons (each `@Inject` + `@ContributesBinding` into a multibinding `Set<ConfiguredValue<*>>`); QA menu enumerates the multibinding set and renders the right override widget per type (Boolean / Int / String / Long); old `featureValue` delegate is deleted; tests cover the override path. **Files / hints:** `:libraries:config` owns the current `FeatureConfig` + override repo; QA menu lives in `features/profile/impl/.../QaMenuScreen.kt`. **Out of scope:** `AppConfig` (the server-driven `GET /v1/app-config` channel — different cascade, different consumers) and any per-feature flag that isn't user-tunable.

### Module sprawl: `libraries/cards`, `gameplay`, `game`

- `[P2]` **Audit and split `libraries/cards`.** Originally the "highly shared" dumping ground; now too big and overlaps with `libraries/gameplay` (engine types) and `libraries/game` (session abstraction). Audit what's *truly* cross-feature primitive vs what landed there for lack of a better home. Likely splits: progression (XP / achievements / ranks) into `libraries/progression` or stays — but if it stays, cosmetics + chips + identity etc. need their own homes. Capture as a deliberate refactor pass. Do not entangle with feature work.

---

## D. Already on the books elsewhere

For completeness; don't re-derive these here when the link below tracks them.

- **Known sharp edges** — [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_known_sharp_edges.md) (auto-loaded).
- **Phase 4.2 server-authoritative gameplay** — out of scope until we choose to start it. See [docs/decisions.md](./decisions.md) and the `:libraries:gameplay` JVM-target blocker noted in memory.
- **Real platform billing impls (Play Billing v6+ / StoreKit 2)** — billing scaffold + `FakeBillingClient` is in place; provisioning store listings is the gate, not engineering. `DevBillingClient` bridges the gap so debug builds render chip-pack tiles end-to-end; once the real platform bindings land, both `DevBillingClient` and `NoOpBillingClient` become candidates for removal.
- **OAuth UI gated by `IdentityFeatureConfig`** — Apple/Google buttons are wired but flagged off until dashboard credentials exist.
- **Username localization, bot name localization** — V1.x / V2 problems.
