# Auth/Profile Rework — handoff doc

In-flight as of 2026-05-22. Pick up wherever this leaves off.

---

## Why this exists

The trigger was a `401 Unauthorized` on every cold boot's `InventorySync.sync()` call. Investigation revealed:

1. A **discrete bug**: `InventorySyncServiceImpl` used `networkClient.client` (unauthenticated) instead of `networkClient.authenticatedClient`. Every other authed call site was correct.
2. The **identity architecture was conflated**: a single `IdentityRepository` owned Supabase auth state, server profile state, the access token, and the local cache. The state machine had an `Unknown` sentinel that callers had to handle. The cache was hydrated eagerly on init and emitted as `SignedIn(cached)` before `/v1/me` resolved — producing brief stale-state flashes.
3. **Sync was over-layered**: each of Chips/Inventory/Equipment/UserMessage had a `*SyncService` interface + `*SyncBootstrapper` class on top of the repository — extra plumbing that just forwarded `AppEvent.ColdBoot` / `OnForeground` into a `sync()` call.
4. The user's mental model:
   > Have an `AuthRepository` and a `ProfileRepository`. The profile repo waits for auth to be completed before it makes its request to get the profile. The auth is more auth focused, it would be combined with the auth token stuff. On init it does the get-or-create from supabase.
   >
   > We might wanna nix the Identity data model and start calling it profile.
   >
   > One way or another we either expose a real identity or a fallback one. Idk if we needed it to be a state flow that always has a value to give callers. It's okay to make them wait.

---

## Architecture (target shape)

### Two repos, one direction of dependency

- **`AuthRepository`** owns the Supabase user lifecycle + access token. On init, it runs get-or-create via `supabase.auth.awaitInitialization()` + `sessionStatus.value`. It IS the producer of the access token — there's no separate `AuthTokenProvider`. Operations: `current()`, `observe()`, `accessToken()`, `refreshAccessToken()`, `retry()`, sign-in/up flows, OAuth, link, delete account, sign out.
- **`ProfileRepository`** owns `/v1/me` + the local profile cache. Collects `authRepository.observe()` and resolves on every change. Exposes `current()`, `observe()`, `update(...)`, `fetchAvatarPack()`.

`ProfileRepository` depends on `AuthRepository`, not vice versa. `NetworkClient` depends on `AuthRepository` for the bearer token. The cycle between identity and networking (`AuthRepository → ProfileApi → NetworkClient → AuthRepository`) is broken by a lazy provider in `NetworkClientImpl` — same pattern the old code used.

### No in-flight sentinel state

`current()` suspends until the answer is real. `observe()` is a `SharedFlow(replay = 1)` that only emits resolved values — no `Unknown` / `Resolving` / `Initializing` variant for callers to handle.

UI that wants a spinner shows it while waiting on the first emission, then renders the resolved value.

### Sealed `Profile`: Authenticated vs Fallback

```kotlin
sealed interface Profile {
    val id: String
    data class Authenticated(
        override val id: String,             // Supabase auth.users.id
        val displayName: String,
        val avatarEmoji: String,
        val avatarBackgroundColor: String?,
        val email: String?,
        val isAnonymous: Boolean,
    ) : Profile
    data class Fallback(override val id: String) : Profile  // client-only UUID
}
```

`AuthState` is similarly two-variant:

```kotlin
sealed interface AuthState {
    data class Authenticated(val userId: String, val isAnonymous: Boolean, val email: String?) : AuthState
    data class Unauthenticated(val cause: Throwable? = null) : AuthState
}
```

The `Fallback` case fires only when auth couldn't resolve AND there's no cached real profile. Features that need a real Supabase user hard-gate on `is Profile.Authenticated`; read-only browse surfaces accept either.

### Cache as fallback, not first-frame

On resolve, `ProfileRepository`:

```
val profile = Catching {
    /v1/me get-or-create → Profile.Authenticated
}.fold(
    onSuccess = { it.also { cache.write(it) } },
    onFailure = { cause ->
        cache.read() ?: Profile.Fallback(ensureLocalId())
    }
)
```

The cache is read only in the failure path. Returning users on a healthy network see splash → home with fresh data (no stale flash). Offline returning users fall back to the cached profile.

---

## What's done

All on `dev`, ahead of `origin/main` by 6 commits (the auth rework) + the user's other recent work (achievements/profile/typography).

### `fce2bd2 fix(auth): inventory sync uses authenticated client`
One-line swap in `InventorySyncServiceImpl` (later folded into `InventoryRepositoryImpl`). Resolves the 401-on-cold-boot symptom.

### `2350b98 refactor(cards): fold *SyncService + *Bootstrapper into their repos`
Each of `Chips`/`Inventory`/`Equipment`/`UserMessage` now owns `suspend fun sync(): Result<Unit>` directly. Repos implement `AppEventListener` via a second `@ContributesBinding` multibinding. Deleted the 4 service interfaces + 3 bootstrapper classes + corresponding test files (replaced with `*RepositoryImplSyncTest`s).

Net: -1100 lines, fewer moving parts to reason about.

### `775aa11 feat(networking): real AppState.isOffline + offline banner`
- `ConnectivityObserver` interface in `:libraries:networking:impl/commonMain`.
- iOS impl: `NWPathMonitor` wrapped in `callbackFlow`.
- Android impl: `ConnectivityManager.NetworkCallback`.
- `AppStateImpl` exposes `isOffline: StateFlow<Boolean>` via `connectivityObserver.observe().map { !it }.stateIn(appScope, Eagerly, false)`.
- `OfflineBanner` composable in `apps/compose`, rendered in the scaffold's `topBar` slot next to `AppGuardBanner`. Slide-down anim, warning-tinted, "Offline — some features unavailable."

### `b89f9e3 feat(identity): introduce AuthRepository + ProfileRepository interfaces`
Interface scaffold only — no impls, no `@ContributesBinding` annotations. Files:
- `libraries/identity/src/.../auth/AuthRepository.kt`
- `libraries/identity/src/.../auth/AuthOutcomes.kt` (SignInOutcome, SignUpOutcome, RefreshOutcome, ResendOutcome, DeleteAccountOutcome, LinkIdentityOutcome, LinkEmailIdentityOutcome, OAuthProvider). Note: outcome `Success` variants carry no payload — the new auth state lands on `observe()`.
- `libraries/identity/src/.../profile/ProfileRepository.kt` (interface + Profile sealed type + UpdateProfileOutcome + AvatarPack + AvatarPackOutcome).

### `1c471e9 feat(identity): impl AuthRepository + ProfileRepository`
Impls live alongside the old `SupabaseIdentityRepository`. Both are bound; nothing conflicts because they implement different interfaces. Files:

- `libraries/identity/impl/src/.../auth/SupabaseAuthRepositoryImpl.kt` — bootstrap is recursive via `supabase.auth.awaitInitialization() + sessionStatus.value` (per the user's `SupabaseIdentityRepository2.kt` sketch — that sketch is now deleted since the pattern's absorbed). Cap at 5 attempts; on exhaust emits `Unauthenticated(cause)`. Uses `SharedFlow(replay = 1)` for state.
- `libraries/identity/impl/src/.../profile/ProfileCache.kt` — new file-backed cache, separate from `IdentityCache`. Stores both the last-known Authenticated profile AND the fallback `localId` in one record. Versioned JSON serializer.
- `libraries/identity/impl/src/.../profile/SupabaseProfileRepositoryImpl.kt` — collects `authRepository.observe()`; on every emission, resolves to `Profile.Authenticated` (`/v1/me`) or `Profile.Fallback` (cache, then localId). `update()` is optimistic with rollback.

### `ef01a8e feat(networking): wire NetworkClient to AuthRepository.accessToken`
The bearer plugin's `loadTokens` / `refreshTokens` now call `AuthRepository.accessToken()` / `refreshAccessToken()` instead of the old `AuthTokenProvider`. Added `:libraries:identity` as a dependency of `:libraries:networking:impl`. The 5s `LoadTokensTimeout` is gone — `AuthRepository`'s own bootstrap is the backstop.

`SupabaseAuthTokenProvider` is now dead code — kept bound until the consumer migration deletes it along with `IdentityRepository`.

---

## What's still in flight (slice B)

Migrate all `IdentityRepository` consumers to `AuthRepository` / `ProfileRepository`, then delete the old types.

### Files to migrate

**Production code** (each grep `IdentityRepository\|IdentityState\|: Identity`):
- `features/onboarding/impl/.../OnboardingViewModel.kt` — replace `identityRepository.retry()` (currently `ensureInitialized()` in the original) with `authRepository.retry()`; branch on `AuthState.Authenticated` vs `Unauthenticated.cause` for the dev-friendly error messages.
- `features/onboarding/impl/.../signin/SignInViewModel.kt` — `signInWithEmail` moves to `AuthRepository`; `SignInOutcome.Success` no longer carries an identity payload.
- `features/onboarding/impl/.../signup/SignUpViewModel.kt`, `VerifyEmailViewModel.kt` — same shape.
- `features/lobby/impl/.../LobbyViewModel.kt` — replaces `identity.state.filterIsInstance<SignedIn>().first()` with `profileRepository.observe().filterIsInstance<Profile.Authenticated>().first()` or `profileRepository.current() as? Profile.Authenticated`.
- `features/shop/impl/.../ShopViewModel.kt` — uses identity for the userId in IAP purchase calls; route via `authRepository.current() as? AuthState.Authenticated`.
- `features/room/impl/.../PlayPokerViewModel.kt` — collects `identityRepository.state` for the human player's display info; map to `profileRepository.observe().filterIsInstance<Profile.Authenticated>()`.
- `features/room/impl/.../TableUiState.kt` + `SoloBotsPokerSessionFactory.kt` — replace `Identity` parameter type with `Profile.Authenticated`.
- `features/profile/impl/.../edit/EditProfileViewModel.kt` — `updateProfile` moves to `ProfileRepository`. `UpdateProfileOutcome.Success` now carries `Profile.Authenticated` (not `Identity`).
- `features/profile/impl/.../account/DeleteAccountViewModel.kt`, `ClaimAccountViewModel.kt` — `deleteAccount` / `linkOAuthIdentity` / `linkEmailIdentity` on `AuthRepository`.
- `features/profile/impl/.../feedback/FeedbackViewModel.kt`, `bugreport/BugReportViewModel.kt` — reads the user's email; switch to `(profileRepository.current() as? Profile.Authenticated)?.email`.
- `features/profile/impl/.../ProfileFeatureEntryPoint.kt` — composable that reads identity state for the profile screen header. Replace `identityState as? IdentityState.SignedIn)?.identity` with `(profile as? Profile.Authenticated)`.
- `features/home/impl/.../HomeViewModel.kt` — probably reads identity for the home avatar; map to ProfileRepository.
- `libraries/cards/impl/.../InAppMessageManagerImpl.kt` — calls `identityRepository.awaitIdentity()` before the foreground dialog gate. Switch to `authRepository.current()` — we just need to know auth landed, the manager doesn't care about profile details.
- `libraries/cards/impl/.../TelemetryUserBinder.kt` — collects identity for Sentry user-id tagging. `profileRepository.observe()` → tag the userId.
- `apps/compose/src/commonMain/.../AppViewModel.kt` — splash gating. Wait for `profileRepository.observe().first()` (which is post-first-resolve) before opening `HomeRoute`.

**Test fakes** (each module's test directory):
- `features/onboarding/impl/.../AuthViewModelFakes.kt`, `OnboardingViewModelTest.kt` (`FinishIdentityRepository`)
- `features/lobby/impl/.../LobbyViewModelTest.kt` (`AlwaysSignedInIdentity`)
- `features/profile/impl/.../account/AccountViewModelFakes.kt`, `feedback/FeedbackViewModelTest.kt`, `bugreport/BugReportViewModelTest.kt`, `edit/EditProfileViewModelTest.kt`, `account/AccountActionsViewModelTest.kt`, `account/DeleteAccountViewModelTest.kt`
- `features/room/impl/.../Fakes.kt` (`FakeIdentityRepository`), `PlayPokerViewModelIntegrationTest.kt`
- `features/shop/impl/.../ShopViewModelTest.kt` (`FakeIdentityRepository`)
- `libraries/cards/impl/.../InAppMessageManagerImplTest.kt` (`FakeIdentityRepo`), `TelemetryUserBinderTest.kt`

Each fake gets two new versions: `FakeAuthRepository` + `FakeProfileRepository`. ViewModels that touch both inject both fakes.

### Mechanical conversion table

| Old | New |
|---|---|
| `IdentityRepository.state` | `AuthRepository.observe()` (auth) or `ProfileRepository.observe()` (profile) |
| `IdentityState.SignedIn` | `AuthState.Authenticated` or `Profile.Authenticated` |
| `IdentityState.Unknown` | (gone) callers suspend instead |
| `Identity` (data class) | `Profile.Authenticated` |
| `identity.userId` | `profile.id` |
| `identity.displayName` etc. | unchanged on `Profile.Authenticated` |
| `awaitIdentity()` extension | `profileRepository.observe().filterIsInstance<Profile.Authenticated>().first()` or `authRepository.current() as? AuthState.Authenticated` |
| `IdentityRepository.ensureInitialized()` (throwing) | `authRepository.retry()` (non-throwing, returns `AuthState`) |
| `identityRepository.signInWithEmail(...)` | `authRepository.signInWithEmail(...)` — `SignInOutcome.Success` no longer carries identity |
| `identityRepository.updateProfile(...)` | `profileRepository.update(...)` — `UpdateProfileOutcome.Success` carries `Profile.Authenticated` |
| `identityRepository.deleteAccount()` | `authRepository.deleteAccount()` |
| `IdentityCache` | `ProfileCache` (new; both cached profile + localId) |

### Files to delete after migration

- `libraries/identity/src/.../Identity.kt` (the `Identity` data class + `IdentityState` sealed type)
- `libraries/identity/src/.../IdentityRepository.kt` (interface + outcome types — the new outcomes already exist in `auth/AuthOutcomes.kt` and `profile/ProfileRepository.kt`)
- `libraries/identity/impl/src/.../SupabaseIdentityRepository.kt`
- `libraries/identity/impl/src/.../SupabaseAuthTokenProvider.kt`
- `libraries/identity/impl/src/.../IdentityCache.kt` (its role moves to `ProfileCache`)
- `libraries/networking/src/.../AuthTokenProvider.kt` + `NoOpAuthTokenProvider.kt` (no longer referenced anywhere)

### Coexistence note (current state)

Both `SupabaseIdentityRepository` (old) and `SupabaseAuthRepositoryImpl` (new) are bound and both run a bootstrap on init. Each calls `supabase.auth.signInAnonymously()` if no session exists. supabase-kt has internal locks so this is functionally safe, but it's wasted work + an extra anon user could in theory be created if there's a race I'm not seeing. Goes away when `SupabaseIdentityRepository` is deleted.

---

## Decisions made along the way

| Decision | Why |
|---|---|
| Two repos (Auth + Profile), not one rebranded `IdentityRepository`. | The user's framing: "it's really just a profile right?" — auth concerns and profile concerns have different lifecycles, different consumers, different failure modes. |
| No in-flight sentinel state. | Per the user: "we either expose a real identity or a fallback one. It's okay to make them wait." `SharedFlow(replay = 1)` + `current() = .first()` is the cleanest version. |
| Sealed `Profile`: Authenticated vs Fallback. | Compiler enforces the distinction at call sites. Shop hard-gates on `is Profile.Authenticated`; offline-browsable surfaces accept either. |
| Cache is read on `onFailure` only, not eagerly. | Reading `Catching { server } .fold(success → it, failure → cache)` matches the mental model. Avoids the stale-flash UX of the old eager-hydrate path. |
| Bootstrap via `awaitInitialization() + sessionStatus`. | From the user's `SupabaseIdentityRepository2.kt` sketch. Leverages supabase-kt's own readiness primitive instead of polling. |
| `AuthRepository.accessToken()` replaces `AuthTokenProvider`. | The user: "auth would be combined with the auth token stuff." Auth is the producer; one place to own it. |
| Outcomes' `Success` variants carry no profile payload. | The new auth state will be visible on `observe()`; profile updates land on `ProfileRepository.observe()` automatically. No need to thread the new value through the return. |
| Cherry-picked the architecture-independent fixes (InventorySync 401, sync fold, connectivity) instead of recovering all 8 dropped commits. | Per user: "Cherry-pick only the keepers." The identity-related commits are superseded by the new design. |

---

## Tonight's debugging notes (relevant for next session)

### Server cold-start friction
The dev Fly server (`cards-server-dev`) has `auto_stop_machines = 'stop'` + `min_machines_running = 0`. Machines auto-stop when idle. Normal cold-start when a request arrives is ~4s and works fine, but there's a known Fly bad-state where the proxy returns "machine was recently stopped and is unavailable to service request" instead of triggering auto-start. When this hits, requests hang until the client times out (30s) or a GitHub Actions cron eventually wakes the machine.

Two safe mitigations (user dismissed both for now, but worth knowing):
- `min_machines_running = 1` — keeps one machine warm 24/7. Bulletproof. ~$2-5/month.
- `auto_stop_machines = 'suspend'` — preserves in-memory state; resume is ~50ms instead of seconds. Keeps scale-to-zero behavior.

### Temporary diagnostic logs
`SupabaseIdentityRepository.bootstrapProfileLocked()` had `[IdentityDiag]` log lines for verifying the three UIDs match. Those went away with the reset to `origin/main`. The new `SupabaseAuthRepositoryImpl` doesn't have them — they were tied to the old code path. If you need to confirm UIDs again after the consumer migration, add equivalent logs in `SupabaseProfileRepositoryImpl.resolveAuthenticatedLocked` (the `/v1/me` call site).

### The UID mystery (RESOLVED)
The original "I see only 1 user in Supabase and the UID doesn't match" was the **Supabase Auth dashboard hiding anonymous users by default** — a filter setting, not a real orphan-profile situation. Server `call.userId()` reads JWT `sub` directly with no transformation; `profiles.user_id = auth.users.id` by construction. No FK enforces this yet (see OOS list).

---

## Out-of-scope follow-ups (spawned as separate sessions)

| Task | Status |
|---|---|
| **Server-authoritative starting chip grant.** Move `ChipsRepository.STARTING_GRANT = 10_000L` from client constant to a server-side wallet seed in the `findOrCreate` transaction. Drop the client-side `ensureSeeded()` so `observeBalance()` can emit `null` while loading instead of flashing 10K → real value. Touches `apps/server/.../ProfileRepository.kt` + a new migration, plus `:libraries:cards` for the client constant. | Spawned chip |
| **FK + RLS on Supabase profiles/wallet/etc.** Add `profiles.user_id REFERENCES auth.users(id) ON DELETE CASCADE` (and same for chips, inventory, equipment, user_messages). Seed a minimal `auth.users` in Testcontainers so the migration applies in tests. Enable RLS policies. Defense in depth — diagnostic confirmed the orphan situation isn't actually happening today, but RLS is the right shape before any public launch. | Spawned chip |
| **Audit features for `Profile.Fallback` behavior.** Now that there's a defined offline-with-no-auth state, decide per-feature: cached browse works (inventory/equipment list), multiplayer hard-gates, profile edit hard-gates. The global offline banner from commit `775aa11` sets expectations; this audit is per-surface polish. | Spawned chip |
| **Repo-pattern polish: Flow&lt;X?&gt; + named verbs.** `ChipsRepository.observeBalance(): Flow<Long?>` (null=loading; drops `?: STARTING_GRANT`); replace `applyDelta` with `addChips`/`subtractChips`. UI consumers handle null. Coordinated with the starting-grant move. | Spawned chip |
| **Investigate UID mismatch from IdentityDiag log.** Closed — was the dashboard filter. Spawn chip can be dismissed. | Resolved |

---

## How to verify the new auth pipeline works (before migrating consumers)

The new auth + bearer pipeline is live RIGHT NOW even though no consumer has migrated. Existing `IdentityRepository`-based code still works; underneath, every authed request goes through `AuthRepository.accessToken()`.

1. Make sure the dev server is warm: `curl -m 5 https://cards-server-dev.fly.dev/_health` should return `{"ok":true}` quickly.
2. Fresh-install + launch the app.
3. Logs should show — in order — Supabase loading session, signInAnonymously (if no session), then any of the authed sync calls succeeding (no 401s, no 30s timeouts). The legacy `IdentityRepository` will run its own bootstrap too — that double-bootstrap is the coexistence wrinkle noted above.
4. Tap "Get Started" → should land on home without hanging. Existing `IdentityState` machinery still drives onboarding nav.

If something's broken: most likely culprit is the lazy provider cycle. `AuthRepository` injects `ProfileApi` which uses `NetworkClient.authenticatedClient`. The lazy in `NetworkClientImpl` defers resolving the `AuthRepository` reference until first use, breaking the construction-time cycle. If that lazy is somehow eager, you'd get a stack overflow on cold boot.

---

## Picking it back up

When you (or a future Claude session) resume:

1. **Smoke-test on device** — confirm the auth pipeline + cherry-picked fixes work end-to-end against the live server.
2. **Decide on slice-B pacing**: one big commit (mechanical, many files, one PR) vs. feature-by-feature (smaller PRs, slower).
3. **Slice B steps**, in this order:
   a. Migrate `OnboardingViewModel` first — it's the user-visible entry point; if this works the rest is mechanical. Update `AuthViewModelFakes.kt` + `OnboardingViewModelTest.kt`.
   b. Migrate `InAppMessageManagerImpl` + `TelemetryUserBinder` (libraries/cards) — non-UI, easier to verify with unit tests.
   c. Migrate `AppViewModel` splash gate to wait on `profileRepository.observe().first()`.
   d. Migrate auth-feature VMs (SignIn, SignUp, VerifyEmail).
   e. Migrate profile-feature VMs (Edit, Account, ClaimAccount, DeleteAccount, Feedback, BugReport).
   f. Migrate shop / room / lobby / home.
   g. Delete `IdentityRepository`, `Identity`, `IdentityState`, `IdentityCache`, `SupabaseIdentityRepository`, `SupabaseAuthTokenProvider`, `AuthTokenProvider` interface, `NoOpAuthTokenProvider`.
   h. Test suite runs green; manual smoke test on device.

4. **After slice B**, consider: do we need a temporary debug log in `SupabaseAuthRepositoryImpl` and `SupabaseProfileRepositoryImpl` mirroring the old `[IdentityDiag]` lines? Useful if any subtle behavior issue shows up post-migration.

---

## Plan file reference

The original planning conversation that started this rework lives at:
`/Users/elijahdangerfield/.claude/plans/lets-work-on-our-replicated-dragonfly.md`

That doc reflects the *first* design (in-place rework of `IdentityRepository`). The current direction (Auth/Profile split) supersedes it and is captured fully here.

---

## Plan file location

This doc lives at `docs/auth-rework.md`. Update it as you go; delete when slice B is done.
