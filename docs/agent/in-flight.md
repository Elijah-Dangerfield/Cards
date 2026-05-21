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
