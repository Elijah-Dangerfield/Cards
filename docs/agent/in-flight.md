## feat(review): fire SessionEnd prompt on bot-table clean exit

**Problem:** The third V1 review-prompt trigger (`SessionEnd`) was un-wired — `PlayPokerEvent.NavigatedBack` existed as a typed signal but was never emitted, so even with the launcher binding in place the OS would never see a "clean exit" moment from a bot session. Without it, the V1-must-have review-prompt feature only covers two of three triggers.

**Approach:** Two coupled changes in `PlayPokerViewModel` and `PlayPokerScreen`. (1) `PokerSessionFactory` grows an `xpMode: XpMode` property so the VM no longer has to hardcode `XpMode.BOTS` for hand-summary attribution and can gate prestige-bearing signals cleanly — `SoloBotsPokerSessionFactory` returns `XpMode.BOTS`; the `FakePokerSessionFactory` and the test-local `RealLocalSoloFactory` got matching defaults. (2) `PlayPokerAction.LeaveTable` is a new no-op-state action that the screen dispatches just before `onBack()` from each clean-exit path: `BackHandler`, top-bar back, and the confirmed-leave dialog (routed through a shared `leaveTable` lambda so the three call sites stay in sync). The VM handles `LeaveTable` by calling `reviewPromptCoordinator.requestPrompt(SessionEnd)` only when `sessionFactory.xpMode == XpMode.BOTS` — wrapped in `Catching {}` so a launcher failure can't punch through navigation. Two new tests in `PlayPokerViewModelTest`: bot-mode LeaveTable fires SessionEnd; MULTIPLAYER-mode LeaveTable suppresses it.

**Reviewer notes:** The `viewModelScope`-cancellation race is worth a second look. `LeaveTable` is `takeAction`'d before `onBack()` runs, so the VM is being torn down on essentially the same frame the coordinator suspends on its mutex / launcher call. The coordinator's eligibility-gate writes to `AppCache` *before* the `launcher.requestReview()` call, so the timestamp is durable even if the actual platform call gets cancelled mid-flight (the OS will just see the next eligible trigger as a fresh prompt). I considered emitting on a longer-lived scope to avoid this entirely, but adding an app-scope coroutine for a single fire-and-forget felt heavier than the race itself warrants. If the prompt-actually-shown rate looks anemic on real-device testing, this is the first place to revisit. Also: I generalized the hardcoded `XpMode.BOTS` in `handleHandEnded` to `sessionFactory.xpMode` while I was there — same value today but lays the groundwork for MP to wire its own factory without touching the VM.

**Deferred:**
- Real-device verification on Android + iOS — flagged in updated `docs/todo.md`. The whole V1 review-prompt path needs a smoke test before TestFlight, and unit tests can't simulate `SKStoreReviewController` / Play Core's throttling.
- Backwards-compat for future remote MP factory — when `RemotePokerSessionFactory` lands (Phase 4.2) it'll need to declare `xpMode = XpMode.MULTIPLAYER` to participate in the gate. Mentioned implicitly in the existing MP hardening todo.

## feat(review): wire ReviewLauncher to existing platform ReviewPrompter

**Problem:** The 2026-05-21 scaffold of `:libraries:review` shipped `ReviewLauncher` + `NoOpReviewLauncher` with no platform binding, and the listed remaining work prescribed dropping in `AndroidReviewLauncher` (Play Core) and `IosReviewLauncher` (StoreKit). But `:libraries:cards` already exposed `ReviewPrompter` with an `AndroidReviewPrompter` impl that wraps `ReviewManager.launchReviewFlow` (including `ActivityProvider` plumbing) and an iOS Swift `SKStoreReviewController` impl passed in via `IosAppComponentFactory.create(...)`. Writing parallel platform bindings would have duplicated working infrastructure.

**Approach:** Made `ReviewLauncher` an adapter. New `AdaptedReviewLauncher` in `:libraries:review:impl` (commonMain) injects `ReviewPrompter` and delegates `requestReview()` straight through. `@ContributesBinding(replaces = [NoOpReviewLauncher::class])` swaps it in at DI graph merge time, so the production binding now wraps the existing platform impls on both Android and iOS with zero new platform code. `NoOpReviewLauncher` stays around as a test fallback. One commonTest pin verifies the delegation: a counting `ReviewPrompter` fake observes both `launcher.requestReview()` calls. Also updated the `ReviewLauncher` and `NoOpReviewLauncher` doc comments to reflect the new wiring so the next reader doesn't go looking for the missing platform classes.

**Reviewer notes:** This is an executive decision call about the shape of the abstraction. The original scaffold's path (separate Android/iOS launcher classes inside `:libraries:review:impl`) keeps the new module self-contained but requires duplicating the Play Core / `ActivityProvider` plumbing. The adapter path here leans on the existing `:libraries:cards` infrastructure and keeps the layering thin — `:libraries:review` owns the eligibility gate, `:libraries:cards` owns the OS call. The two interfaces (`ReviewLauncher`, `ReviewPrompter`) end up doing the same thing — `suspend fun requestReview()` — which is a real consolidation question worth raising. My read: the eligibility-gate-vs-OS-call layering is a useful seam; keep both, with the adapter making the relationship explicit. If the reviewer disagrees, the cheap collapse is to delete `ReviewLauncher` entirely and have `RealReviewPromptCoordinator` take a `ReviewPrompter` directly. Not doing that tonight — the worker who scaffolded `:libraries:review` made the explicit call to keep them separate.

**Deferred:**
- The collapse-or-keep decision on `ReviewLauncher` vs `ReviewPrompter` — captured in the reviewer notes above. Worth a second pair of eyes before V1 ships; if we keep both, the doc-comment cross-refs are accurate; if we collapse, it's a half-day refactor.

## feat(server): add ProductCatalogSource.readById for unlock-only lookup

**Problem:** Earlier tonight's `unlock_only` slice added the schema + shop-filter side, but the same todo entry called out the missing read-by-id path: the shop filter (`read()`) hides unlock-only rows, but Trophy Case rendering and the inventory-grant path need to resolve a known product id back to its `Product` regardless of the flag. Without it, the moment we ship an unlock-only cosmetic the rendering code has nowhere to look it up.

**Approach:** Added `suspend fun readById(id: String, context: ClientContext): Product?` to `ProductCatalogSource`. The Postgres impl does the same row → `Product` mapping as `read()` (reuses `toChipPack` / `toChipOffer` / `readPlatforms`), but the `where` clause is `id eq id` with no `unlock_only` predicate. Returns null on unknown id rather than throwing — Trophy Case rendering wants a "skip this row" outcome, not an exception. `ClientContext` parameter accepted for the same forward-compat reason as `read()` (future platform / locale variance) but not used for filtering today. The `FakeCatalogSource` in `ProductsRoutesTest` got a `readById = null` impl so the existing route tests stay unchanged. Three new tests on `PostgresProductCatalogSourceTest`: known-seeded-product round-trips, the unlock-only fixture (synthesized + cleaned up) is hidden from `read()` but surfaces through `readById()`, and an unknown id returns null.

**Reviewer notes:** `readById` returns `Product?` (nullable), not a sealed `ReadByIdOutcome`. Considered the outcome type for parity with the client-side patterns, but the caller's question is binary — "does this row exist or not?" — and there's no auth / not-signed-in dimension on a catalog read. If a richer error story becomes load-bearing (e.g. "deleted product still in someone's inventory"), the right move is a wrapper outcome over `Product?`, not threading exceptions through.

**Deferred:**
- The actual Trophy Case render / inventory-grant code paths — they consume `readById` but aren't wired yet. Same todo entry tracks them.

## feat(server): add unlock_only flag and filter shop catalog

**Problem:** `docs/todo.md` §B "Catalog gating" called out a V1-blocker: legendary / league / achievement-chain cosmetics are spec'd to never appear in the shop, but the `products` table had no `unlock_only` column and the catalog query had no filter. The moment any prestige cosmetic ships it would have leaked into the shop and broken the no-pay-to-win principle.

**Approach:** Three-line slice. New `V10__unlock_only_products.sql` adds `unlock_only BOOLEAN NOT NULL DEFAULT FALSE` to `products` (default-false keeps every existing row shop-eligible). `ProductsTable` got an `unlockOnly` Exposed column matching the new schema. `PostgresProductCatalogSource.read` now adds `.where { ProductsTable.unlockOnly eq false }` — the shop catalog never sees unlock-only rows. Two new tests on `PostgresProductCatalogSourceTest`: a one-shot insert of a synthetic unlock-only product proves it's filtered out of the catalog, and a coexistence test confirms shop products still appear when an unlock-only row exists. Tests use raw SQL via `TransactionManager.current().exec` because `title_by_locale` is JSONB and Exposed's parameterized insert won't auto-cast a String to JSONB.

**Reviewer notes:** The catalog filter handles the *prevention* side (no leak into the shop), but the *grant* side — actually inserting an unlock-only product id into a user's inventory when they unlock an achievement / league finish — isn't wired. Same for the read-by-id path the Trophy Case will need (the current catalog filter would hide unlock-only rows from any code that tries to render them). Both are next-slice work and are flagged in the updated `docs/todo.md` entry. V1 can ship with zero unlock-only rows and the shop is unaffected, so this slice is enough to land the structural piece without forcing a Trophy Case shipping decision tonight.

**Deferred:**
- Inventory-grant path that writes unlock_only product ids into `inventory` on achievement / league reward. Flagged in updated `docs/todo.md`.
- `ProductCatalogSource.readById(id)` (or equivalent) that bypasses the filter for Trophy Case rendering. Same todo entry.

## feat(rooms): add RoomRepository.getActiveRooms() client surface

**Problem:** Server `GET /v1/me/active-rooms` landed earlier tonight, but `RoomRepository` had no client method to call it. Without a typed outcome surface, anything wired on cold launch later would have to hand-roll its own HTTP + status mapping.

**Approach:** Mirrored the existing pattern from `createRoom` / `joinRoom`. Added `RoomApi.listActive(): HttpResponse` (`HttpRoomApi` does `authenticatedClient.get("/v1/me/active-rooms")`), `ActiveRoomsResponseDto` matching the server envelope (`schemaVersion` + `rooms: List<RoomDto>`), `RoomRepository.getActiveRooms(): GetActiveRoomsOutcome` with `Success` / `NotSignedIn` / `NetworkError` / `Unknown` variants, and `RoomRepositoryImpl.getActiveRooms` doing the standard `ClientRequestException` / `HttpRequestTimeoutException` / `ServerResponseException` / `Throwable` ladder. Four new MockEngine-driven tests pin: 200 with rooms → Success, 200 empty → Success-with-empty-list, 401 → NotSignedIn, transport throw → NetworkError. `FakeRoomRepository` in `LobbyViewModelTest` got the new method with a defaults-to-empty outcome so existing lobby tests stay unchanged.

**Reviewer notes:** The repo method is exposed but nothing calls it yet — the cold-launch consumer (Home / AppLaunchGate) is the next slice. Choosing not to wire it inside `LobbyViewModel` because the "you have an ongoing game" surface belongs in the launch flow, not behind the lobby's join CTA — by the time a user reaches the lobby they're already past the point we want to intercept. Worth a second look on whether `RoomRepository` is even the right home long-term (vs. a smaller "session resume" service) once a second consumer materializes; punting the abstraction question until that consumer arrives.

**Deferred:**
- Cold-launch wiring + rejoin/forfeit UI — flagged in updated `docs/todo.md` (under §C MP hardening, orphan-room policy). Needs a design call on the surface (banner on Home? dedicated launch-gate screen?).

## feat(review): wire achievement-unlock + level-up callers in PlayPokerViewModel

**Problem:** Review-prompt scaffold landed earlier tonight but nothing called `ReviewPromptCoordinator.requestPrompt(...)`. Two of the three V1 triggers in the spec — `AchievementUnlocked` (rare/legendary unlock) and `LevelUp` — fire at hand-end and have a clean home in the play VM.

**Approach:** Constructor-inject `ReviewPromptCoordinator` into `PlayPokerViewModel` and call from `handleHandEnded` after `awardForHand` + `recordHand` resolve. Achievement trigger fires when *any* earned achievement this hand has rarity ≥ `RARE` (covers RARE/EPIC/LEGENDARY — matches the spec's "rare/legendary" copy). Level-up trigger fires when `levelProgressFor(totalXp).level` increased between the start of the callback and after both repos have settled (so achievement XP rewards counted alongside hand XP). Achievement unlock takes priority over level-up — both can land on the same hand but the stronger signal subsumes the weaker. Both are gated by the coordinator's eligibility floor (3d install age, 30d cooldown), so the call site stays naive. Six new tests pin: rare unlock fires, legendary fires, common-only suppresses, level-change fires, no-level-change suppresses, achievement-takes-priority.

**Reviewer notes:** `SessionEnd` is the third spec trigger and is *not* wired yet — `PlayPokerEvent.NavigatedBack` exists as a typed signal but isn't emitted anywhere today, and the play screen's back-handler / leave action would need to do the emission. Skipped intentionally: better to defer than to guess at "clean exit" semantics and start firing the prompt on rage-quits. Updated todo to spell out the remaining slice (suggest gating on `XpMode.BOTS` so MP-disconnects don't masquerade as positive moments). Also note the `SessionEnd` trigger could be hooked at the VM's `onCleared` / `viewModelScope` cancellation — but that fires on every screen exit including aborts, which is wrong for this purpose. Worth a human eye before the next slice picks it up.

**Deferred:**
- `SessionEnd` trigger — flagged in updated `docs/todo.md` entry. Needs a clean "user finished the session intentionally" signal first.
- Platform `ReviewLauncher` impls (Android/iOS) — same todo entry. Still pending; bindings replace `NoOpReviewLauncher` once they land.

## feat(review): scaffold :libraries:review with eligibility gate

**Problem:** V1-must-have app-store review prompts had nothing in the codebase — no abstraction, no eligibility gate, no persistence. Reviewer needed to ship a confident slice without blocking on platform bindings.

**Approach:** New `:libraries:review` (api) + `:libraries:review:impl` modules. Api defines `ReviewPromptCoordinator` + `ReviewLauncher` + `ReviewTrigger`. Impl ships `RealReviewPromptCoordinator` (Mutex-serialized eligibility gate: install age ≥3d, prompt cooldown ≥30d, both clock-driven and persisted via `AppCache.reviewInstallAt` / `lastReviewPromptAt`) and `NoOpReviewLauncher` as the default binding. End-to-end runnable today — the eligibility gate fires, timestamps persist — just nothing user-visible until the platform `ReviewLauncher` impls land. Added `apps:compose` dep so the bindings are discovered. Five-test pin on the coordinator: install-age floor, post-floor launch, cooldown suppression, cooldown release, concurrent-collapse.

**Reviewer notes:** AppData gained two `Long` fields with default `0L`; `versionedJsonSerializer` makes that non-breaking on existing persisted blobs. The coordinator stamps `reviewInstallAt` lazily on the first request that misses the age floor — for users who upgrade past this version, "install age" effectively starts from their first positive moment, not actual install. That's the honest fallback given we can't observe retroactive install dates; it just makes the first eligible prompt land 3 days later than ideal for upgraders. Worth a second look on whether to seed `reviewInstallAt` from an existing signal (e.g. `IdentityRepository` account creation) instead.

**Deferred:**
- Platform `ReviewLauncher` impls — `AndroidReviewLauncher` wrapping `ReviewManager.launchReviewFlow`, `IosReviewLauncher` wrapping `SKStoreReviewController.requestReview`. Noted in updated `docs/todo.md` entry for the next slice.
- Caller wiring — hooking `AchievementRepository` unlock / `ProgressionRepository` level-up / play-screen session-end into `ReviewPromptCoordinator.requestPrompt(...)`. Same todo entry.

## feat(server): add GET /v1/me/active-rooms endpoint

**Problem:** Orphan-room policy (`docs/todo.md` C. MP hardening) called for `GET /v1/me/active-rooms` on cold launch so the client can offer rejoin / forfeit before silently stranding a user — but the endpoint didn't exist server-side.

**Approach:** Added the route under `meRoutes` (it's a "what does this user have" query, not a room-discovery surface, so it lives with the rest of `/v1/me`). Implementation is a one-pass filter over `RoomService.snapshot()` for caller-as-member — same `snapshot()` the admin endpoint uses, so no new service method. Wired the new param through `MeRoutes` + `Application.kt`. New `ActiveRoomsResponse` DTO in `RoomDto.kt` matches the schema-versioned envelope shape used by the other room responses. Three new tests on `MeRoutesTest` — happy path (one of two rooms is the caller's), empty-when-no-membership, and 401-when-missing-auth.

**Reviewer notes:** Endpoint uses `RoomService.snapshot()` under the hood, which acquires the same global mutex as create/join/leave. With the current load (handful of rooms) that's fine. If the room map ever grows past trivial size, this could move to a dedicated `RoomService.findByMember(userId)` that doesn't lock the whole table. Not a concern today — flagging for future scale.

**Deferred:**
- Client-side wiring — calling this endpoint on cold launch and surfacing rejoin / forfeit. Updated `docs/todo.md` entry now lists this as the gap.

## feat(server): soft bust protection — auto-grant on first zero balance

**Problem:** V1-blocker on the chip-economy story (`docs/todo.md` §B Economy): a player who busted their starter grant had no path back to the table without an IAP. Spec called for a one-time 1,000-chip grant + "Welcome back to the table." dialog on first transition-to-zero.

**Approach:** Server-side, idempotency-keyed. Added `Wallet.BUST_PROTECTION_{GRANT, KEY, REASON}` constants and a private `maybeApplyBustProtection` helper in `WalletRoutes.kt`. Both `GET /v1/me/wallet` and `POST /v1/me/wallet/sync` call it after their normal work. When the balance is zero, the helper applies a +1,000 ledger event with idempotency key `bust_protection_v1` — the existing `(user_id, idempotency_key)` PK on `wallet_events` is the lifetime-once guarantee, so re-detecting zero is a no-op past the first grant. On the *first* grant (`!wasAlreadyApplied`) it also queues a Dialog `UserMessage` ("Welcome back to the table.") via the existing `UserMessageRepository`, mirroring the AdminRoutes chip-grant precedent. No new schema, no new domain service, no new client RPC — the client picks the dialog up through the existing UserMessage poll and observes the wallet delta through the existing wallet sync. Four route tests cover: GET grants and queues message on zero, GET stays no-op after the lifetime grant, sync triggers post-batch when chips drain to zero, sync doesn't trigger when balance stays positive.

**Reviewer notes:** Bust protection fires from BOTH endpoints, not just sync. That's intentional — a cold-launch user who closed the app at zero would otherwise be stranded until they happened to sync. The idempotency primitive makes the duplicate trigger cheap (one extra SELECT past the first). If the auto-pop dialog placement turns out to interrupt active play, the right fix is to gate the *client-side dialog surfacing* on session-start rather than removing the server-side grant — the chips themselves should always land. One thing worth a second look: the welcome dialog body inlines the chip count (`"…here's ${Wallet.BUST_PROTECTION_GRANT} on the house…"`) but the copy literal "Welcome back to the table." came directly from the todo entry — a copy editor might want a different voice.

**Deferred:**
- Client-side verification — actually playing a hand to zero and confirming the dialog renders. Listed in updated `docs/todo.md` entry.
- Honest copy review — the dialog body is mine, not from voice-and-copy.md. Worth a look before TestFlight.
