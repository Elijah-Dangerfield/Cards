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
