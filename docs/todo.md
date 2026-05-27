# TODO

**Last reviewed:** 2026-05-27 · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md), [backlog.md](./backlog.md)

The live punch list of actionable engineering work. Append, check off, and **delete** done items — they don't need to live here as history. Add a [decisions.md](./decisions.md) entry **only** when an item resolved a non-trivial architectural call worth not re-litigating (see decisions.md header for what qualifies). Most items just get crossed off and removed.

When an item points at a file path or system, the assumption is that path/system already exists — the work is the gap, not a greenfield build.

**Priority tags** (every item carries one — workers should bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking; pick once your area's P0s are claimed or you can't confidently progress on them.
- `[P2]` — Lower urgency than P1, but **still worker-pickable**. Many of these need a directional call (which shape, which API, which content seed). Make a recommendation, ship a slice, and let the reviewer course-correct — that's the safety net. Don't skip a P2 because it's ambiguous; skip only if it's marked `(blocked on X)` and the blocker is real (waiting on another system, not waiting on judgment).

**Item template** (the more of these you fill in, the safer it is for an automated worker to pick up):

> **Problem:** what's wrong / missing.
> **Acceptance:** how we know it's done.
> **Files / hints:** where to start looking.
> **Out of scope:** what NOT to drag in.

Everything in this file is worker-pickable. Items that need a human action — device QA, dashboard config, content writing, product decisions — live in [`docs/developer-todo.md`](./developer-todo.md) instead. Per-cycle follow-ups tied to a specific PR's diff live in the PR's "Heads up" section.

---

## A. UX gaps observed in the build

These are bugs / polish items found playing the app or scanning the code. Cheap individually; collectively the V1 quality bar.

### Achievements — bot-vs-human split

**Locked design:** achievement grants follow the gameplay surface they fire from. **Bot games** run the engine client-side and always will — the client posts achievement unlocks to `POST /v1/me/grants/achievement/{id}` and that's the permanent shape (not a security gap to harden, just how bot achievements work). **MP achievements** wait for Phase 4.2 server-authoritative gameplay, where the server witnesses the hand and grants directly — no client POST. The registry needs `AchievementMode.{BOTS_ONLY, MP_ONLY, ANY}` to keep these wired correctly so an MP-only achievement can't be self-granted via the client endpoint once the server takes over.

### Catalog gating — unlock-only vs purchasable

- `[P1]` **Achievement-unlock celebration: full-bleed for bots, inline for MP.** Today the showdown / bust dialog's `AchievementUnlockedCallout` shows a single inline "🎁 Also unlocked · {label}" row, same shape in both modes. **Acceptance:** branch on the gameplay surface — **bot mode** gets a full-bleed sheet sequenced *after* the hand-result dialog dismisses, with cosmetic art (felt / card-back / title pill preview), name, source ("from the Comeback Kid achievement"), and celebration animations; when a single hand unlocks multiple achievements (common on a new user's first game), group them into one celebration sheet with a stacked list rather than a sequence of N modals. **MP mode** keeps the inline row exactly as today — fast, no full-screen takeover, doesn't disrupt the game. **Files / hints:** existing inline row in [`HandResultDialogs.kt`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/HandResultDialogs.kt); branch on whether the active session is `SoloBots*` or MP. Sequences with the XP/coin animation below if both fire same hand. **Out of scope:** net-new cosmetic art.

- `[P2]` **Permanent badge slot on the seat — mirror of the SB / BB badge placement.** New small badge slot on the player's seat, laid out as the visual counterpart to the existing small-blind / big-blind badges on the opposite side. **Acceptance:** seat layout in [`PlayerArea.kt`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayerArea.kt) renders a badge slot mirroring SB/BB placement on the opposite side; slot content is sourced from inventory (initially via the new founding-member item below — that's the natural first content); the slot stays empty when the player has nothing to show. **Files / hints:** look at how SB/BB badges are positioned today and mirror to the opposite side; the seat already has a turn-indicator ring around the avatar — this is distinct from that. **Out of scope:** what else can populate the badge over time (designer call later — the slot is the deliverable, not the content catalog).

- `[P2]` **Founding member badge for the first 1000 users.** A permanent recognition for early adopters — surfaces on their profile and in the new permanent-badge slot above. **Direction (worker recommends, reviewer course-corrects):** (a) **detection** — server-side trigger on user creation; if the user's account id falls within the first 1000 confirmed accounts, grant a founding-member cosmetic. (b) **shape** — pick one of badge (fits the new permanent slot) or title (fits the existing title pill); recommend the badge form since the slot work is shipping alongside, and ship that end-to-end. (c) **notification** — *not* on app entry (don't spam a new user with a celebration the moment they open the app). Recommend surfacing via the existing notifications inbox the first time they open the Profile tab after the grant lands, with a one-time celebratory dialog when they tap the notification. **Acceptance:** the first 1000 accounts receive the founding-member cosmetic on creation; existing users within the first 1000 are backfilled by a one-time migration; the cosmetic renders on the permanent badge slot; users are notified non-intrusively. **Files / hints:** server-side cosmetic grant lives near `recordEarnedGrant`; migration via the `V*__products.sql` pattern; notification surface via `UserMessageRepository`. **Out of scope:** anything for users beyond the first 1000.

- `[P2]` **Emote / blast-pack unlocks.** Extra emoji blasts in the in-game tray, earned through achievements or progression. **Direction:** recommend one pack shape (e.g., "Comeback Kid emote pack" granted with the matching achievement), ship it end-to-end so the unlock pathway is proven, leave the catalog expansion to later iterations.

- `[P2]` **League-tier rewards (blocked on league mechanic).** One cosmetic per league tier granted at season end. Genuinely blocked — re-pick once the league system has a real surface.

- `[P2]` **RFT (rare-from-the-floor) drops (blocked on server-side roll plumbing).** Low-probability cosmetic drops at hand-end. Genuinely blocked — re-pick once the server can roll the dice.

### Strings — centralize everything in `:libraries:resources`

- `[P0]` **Sweep every inline user-facing string into `:libraries:resources` Compose Multiplatform resources.** Most UI copy is hardcoded at the callsite ("OWNED", "Claim your account", "Long-press to copy", section titles, error snackbars, etc.). That's load-bearing-by-accident: blocks future localization, makes voice-and-copy edits a repo-wide find-and-replace, lets two screens drift on the same idea. **Acceptance:** every user-facing string in `:features/*/impl` and `:libraries:ui` is keyed through `Res.string.*` from `:libraries:resources/src/commonMain/composeResources/`. New strings always go straight to the resource — no inline. Migration is gradual; start with shop snackbars and owned-state surfaces (shared copy across screens) so consolidation surfaces drift immediately. **Files / hints:** `:libraries:resources` already wires `composeResources/` — reuse, don't create a new module. **Out of scope:** translating anything (give strings a *home*, not a second language); bot-name copy (V1.x / V2 problem per §D); preview-only / test-only strings.

### Screen / chrome consistency

### Animations / table polish

- `[P1]` **XP / coin earned distribution animation.** Today the showdown dialog overlays the XP/coin badges, so the user never sees the odometer count up. Defer the XP/coin badge animation until *after* the showdown / bust dialog dismisses, then play it as a small "zip" — XP particle flying up to the XP badge, coin particle flying down to the chip badge, each landing into an odometer count-up. Pairs with the full-bleed cosmetic celebration above for the post-dismiss sequencing.

### Gameplay & table UX

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
