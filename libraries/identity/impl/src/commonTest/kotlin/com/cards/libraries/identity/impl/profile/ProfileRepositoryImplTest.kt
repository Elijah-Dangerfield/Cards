package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.cards.Session
import com.dangerfield.cards.libraries.cards.SessionStartReason
import com.dangerfield.cards.libraries.cards.SessionTracker
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import com.dangerfield.cards.libraries.identity.impl.AvatarPackDto
import com.dangerfield.cards.libraries.identity.impl.AvatarPackResponseDto
import com.dangerfield.cards.libraries.identity.impl.MeDto
import com.dangerfield.cards.libraries.identity.impl.PatchMeRequest
import com.dangerfield.cards.libraries.identity.impl.ProfileApi
import com.dangerfield.cards.libraries.identity.impl.auth.PendingGuestAccountStore
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.CacheJsonSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the resolve / update / fetchAvatarPack contracts for
 * [ProfileRepositoryImpl]. The interesting edges:
 *
 *  - Authenticated → /v1/me success emits Authenticated AND writes cache;
 *    failure falls back to cached profile, then to Profile.Fallback with
 *    a generated localId.
 *  - Unauthenticated reads the cache; if no cached profile, generates +
 *    persists a localId so subsequent fallbacks are stable.
 *  - update() is optimistic: emits the prospective profile immediately,
 *    rolls back on failure, and maps HTTP status codes to outcomes.
 *  - fetchAvatarPack maps the response shape; classifies failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRepositoryImplTest : CoroutineTest() {

    // ---------- resolve from auth ----------

    @Test
    fun authenticated_andServerSucceeds_emitsAuthenticated_andCachesIt() = runUnitTest {
        val auth = FakeAuthRepository()
        val api = FakeProfileApi(meResult = Result.success(SAMPLE_ME))
        val cache = newProfileCache()
        val repo = build(auth, api, cache)

        auth.emit(AuthState.Authenticated(userId = "u1", isAnonymous = false, email = "a@b.com"))
        advanceUntilIdle()

        val profile = assertIs<Profile.Authenticated>(repo.observe().first())
        assertEquals("u1", profile.id)
        assertEquals("Alice", profile.displayName)
        assertEquals("a@b.com", profile.email, "email surfaced from AuthState, not from /v1/me")

        // Cache was written so a subsequent failure can fall back to it.
        val cached = cache.readAuthenticated()
        assertNotNull(cached)
        assertEquals("u1", cached.id)
    }

    @Test
    fun authenticated_andServerFails_fallsBackToCachedProfile() = runUnitTest {
        // Pre-seed cache with a previously-real profile.
        val cache = newProfileCache()
        cache.writeAuthenticated(SAMPLE_CACHED_PROFILE)

        val auth = FakeAuthRepository()
        val api = FakeProfileApi(meResult = Result.failure(RuntimeException("server down")))
        val repo = build(auth, api, cache)

        auth.emit(AuthState.Authenticated(userId = "u-new", isAnonymous = true, email = null))
        advanceUntilIdle()

        val profile = assertIs<Profile.Authenticated>(repo.observe().first())
        assertEquals(SAMPLE_CACHED_PROFILE.id, profile.id, "fell back to cached, not the live auth userId")
    }

    @Test
    fun authenticated_andServerFails_withNoCache_emitsFallbackWithLocalId() = runUnitTest {
        val cache = newProfileCache()
        val auth = FakeAuthRepository()
        val api = FakeProfileApi(meResult = Result.failure(RuntimeException("server down")))
        val repo = build(auth, api, cache)

        auth.emit(AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null))
        advanceUntilIdle()

        val profile = assertIs<Profile.Fallback>(repo.observe().first())
        // localId persisted so subsequent fallbacks reuse it.
        assertEquals(profile.id, cache.readLocalId())
    }

    @Test
    fun unauthenticated_withPendingOnboardingChoice_enrichesFallbackIdentity() = runUnitTest {
        // A user who onboarded offline has their chosen name/avatar held in the
        // pending guest-account store. The Fallback surfaces it so the app shows
        // their choice instead of a generic "You" while session-less.
        val pendingStore = PendingGuestAccountStore(InMemoryCacheFactory)
        pendingStore.set(
            com.dangerfield.cards.libraries.identity.auth.PendingIdentity(
                displayName = "Foxy",
                avatarEmoji = "🦊",
                avatarBackgroundColor = "#ff6b35",
            ),
        )
        val auth = FakeAuthRepository()
        val repo = build(auth, FakeProfileApi(), pendingGuestAccountStore = pendingStore)

        auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        val fallback = assertIs<Profile.Fallback>(repo.observe().first())
        assertEquals("Foxy", fallback.displayName)
        assertEquals("🦊", fallback.avatarEmoji)
        assertEquals("#ff6b35", fallback.avatarBackgroundColor)
    }

    @Test
    fun unauthenticated_withNoCache_emitsFallback_andPersistsLocalId() = runUnitTest {
        val cache = newProfileCache()
        val auth = FakeAuthRepository()
        val api = FakeProfileApi()
        val repo = build(auth, api, cache)

        auth.emit(AuthState.Unauthenticated())
        advanceUntilIdle()

        val first = assertIs<Profile.Fallback>(repo.observe().first())
        val persistedId = cache.readLocalId()
        assertEquals(first.id, persistedId)

        // Second Unauthenticated should reuse the same localId, not mint a new one.
        auth.emit(AuthState.Unauthenticated(cause = RuntimeException("offline")))
        advanceUntilIdle()
        val second = assertIs<Profile.Fallback>(repo.observe().first())
        assertEquals(first.id, second.id, "fallback id must be stable across auth re-emissions")
    }

    @Test
    fun userScopedProfileCacheCleaner_clearsCachedProfile() = runUnitTest {
        // Stale cache from the previous account must not survive a user change,
        // or the next account would briefly resolve to it before /v1/me lands.
        val cache = newProfileCache()
        cache.writeAuthenticated(SAMPLE_CACHED_PROFILE)
        assertNotNull(cache.readAuthenticated())
        val cleaner = UserScopedProfileCacheCleaner(
            profileCache = cache,
            avatarPackCache = AvatarPackCache(InMemoryCacheFactory),
        )

        cleaner.clear(previousUserId = SAMPLE_CACHED_PROFILE.id)

        assertNull(cache.readAuthenticated(), "user change must drop the cached profile")
    }

    @Test
    fun unauthenticated_butCachedProfileExists_emitsCachedAuthenticated() = runUnitTest {
        // Offline returning user: auth resolution failed but we have a
        // previously-cached real profile. The user sees their cached state
        // until auth comes back.
        val cache = newProfileCache()
        cache.writeAuthenticated(SAMPLE_CACHED_PROFILE)

        val auth = FakeAuthRepository()
        val api = FakeProfileApi()
        val repo = build(auth, api, cache)

        auth.emit(AuthState.Unauthenticated(cause = RuntimeException("offline")))
        advanceUntilIdle()

        val profile = assertIs<Profile.Authenticated>(repo.observe().first())
        assertEquals(SAMPLE_CACHED_PROFILE.id, profile.id)
    }

    @Test
    fun sessionExpired_dropsTheGhost_emitsFallback_andClearsStaleCache() = runUnitTest {
        // A server-confirmed dead session (token rejected) — unlike a benign
        // offline blip, the cached profile here is a ghost. Surfacing it as
        // Authenticated is exactly what makes the app keep firing authed calls
        // that all 401. We must drop to Fallback and clear the stale cache.
        val cache = newProfileCache()
        cache.writeAuthenticated(SAMPLE_CACHED_PROFILE)

        val auth = FakeAuthRepository()
        val repo = build(auth, FakeProfileApi(), cache)

        auth.emit(
            AuthState.Unauthenticated(
                reason = AuthState.Unauthenticated.Reason.SessionExpired,
                wasAnonymous = true,
            ),
        )
        advanceUntilIdle()

        assertIs<Profile.Fallback>(repo.observe().first())
        assertNull(cache.readAuthenticated(), "the stale cached profile must be cleared")
    }

    @Test
    fun authToAnonToClaimed_reEmitsProfile_perAuthChange() = runUnitTest {
        // The impl reads `isAnonymous` from /v1/me (the server is the
        // authoritative source for the JWT's is_anonymous claim), not
        // from the local AuthState. So the test drives both layers
        // together to exercise a real anon→claimed transition.
        val auth = FakeAuthRepository()
        val api = FakeProfileApi(meResult = Result.success(SAMPLE_ME.copy(isAnonymous = true)))
        val repo = build(auth, api)

        auth.emit(AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null))
        advanceUntilIdle()
        val first = assertIs<Profile.Authenticated>(repo.observe().first())
        assertEquals(true, first.isAnonymous)

        // Claim happened — server now returns the claimed profile shape;
        // auth re-emits Authenticated with isAnonymous=false.
        api.meResult = Result.success(SAMPLE_ME.copy(isAnonymous = false))
        auth.emit(AuthState.Authenticated(userId = "u1", isAnonymous = false, email = "a@b.com"))
        advanceUntilIdle()
        val second = assertIs<Profile.Authenticated>(repo.observe().first())
        assertEquals(false, second.isAnonymous)
        assertEquals("a@b.com", second.email)
    }

    // ---------- update ----------

    @Test
    fun update_sessionless_withCachedRealAccount_returnsNotSignedIn() = runUnitTest {
        // A real (claimed) account that's merely offline shows as cached
        // Authenticated; an offline edit can't be applied without a session and
        // must NOT be mistaken for the guest-identity queue path.
        val cache = newProfileCache()
        cache.writeAuthenticated(
            Profile.Authenticated(
                id = "u1",
                displayName = "Real",
                avatarEmoji = "🦊",
                avatarBackgroundColor = null,
                email = "a@b.com",
                isAnonymous = false,
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
            ),
        )
        val auth = FakeAuthRepository().also { it.emit(AuthState.Unauthenticated()) }
        val repo = build(auth, FakeProfileApi(), cache)
        advanceUntilIdle()

        val outcome = repo.update(displayName = "NewName")
        assertIs<UpdateProfileOutcome.NotSignedIn>(outcome)
    }

    @Test
    fun update_sessionless_fallback_queuesEditIntoPendingGuestIdentity() = runUnitTest {
        // The offline-onboarded (Fallback, no cached account) case: an edit is
        // applied locally + queued into the owed guest-account record so it
        // shows now and syncs when a session is minted.
        val pendingStore = PendingGuestAccountStore(InMemoryCacheFactory)
        val auth = FakeAuthRepository().also { it.emit(AuthState.Unauthenticated()) }
        val repo = build(auth, FakeProfileApi(), pendingGuestAccountStore = pendingStore)
        advanceUntilIdle()

        val outcome = repo.update(displayName = "Foxy", avatarEmoji = "🦊")
        assertIs<UpdateProfileOutcome.Queued>(outcome)
        // Persisted for the mint to apply.
        val pending = pendingStore.read()
        assertEquals("Foxy", pending?.displayName)
        assertEquals("🦊", pending?.avatarEmoji)
        // Surfaced optimistically on the Fallback.
        val emitted = assertIs<Profile.Fallback>(repo.observe().first())
        assertEquals("Foxy", emitted.displayName)
        assertEquals("🦊", emitted.avatarEmoji)
    }

    @Test
    fun update_success_emitsServerProfile() = runUnitTest {
        val auth = FakeAuthRepository().also {
            it.emit(AuthState.Authenticated(userId = "u1", isAnonymous = false, email = "a@b.com"))
        }
        val api = FakeProfileApi(
            meResult = Result.success(SAMPLE_ME),
            patchResult = Result.success(SAMPLE_ME.copy(displayName = "Renamed")),
        )
        val cache = newProfileCache()
        val repo = build(auth, api, cache)
        advanceUntilIdle()

        val outcome = repo.update(displayName = "Renamed")
        val success = assertIs<UpdateProfileOutcome.Success>(outcome)
        assertEquals("Renamed", success.profile.displayName)
        assertEquals("a@b.com", success.profile.email, "email kept from AuthState across the patch round-trip")

        // Cache updated to the server-confirmed profile.
        assertEquals("Renamed", cache.readAuthenticated()?.displayName)
    }

    @Test
    fun update_409_returnsDisplayNameTaken_andRollsBackCache() = runUnitTest {
        val conflict = clientResponseException(HttpStatusCode.Conflict)
        val auth = FakeAuthRepository().also {
            it.emit(AuthState.Authenticated(userId = "u1", isAnonymous = false, email = "a@b.com"))
        }
        val api = FakeProfileApi(
            meResult = Result.success(SAMPLE_ME),
            patchResult = Result.failure(conflict),
        )
        val cache = newProfileCache()
        val repo = build(auth, api, cache)
        advanceUntilIdle()

        val priorName = (repo.observe().first() as Profile.Authenticated).displayName
        val outcome = repo.update(displayName = "Taken")
        assertIs<UpdateProfileOutcome.DisplayNameTaken>(outcome)

        // Cache was rolled back to the prior profile.
        assertEquals(priorName, cache.readAuthenticated()?.displayName)
        assertEquals(priorName, (repo.observe().first() as Profile.Authenticated).displayName)
    }

    @Test
    fun update_400_withDisplayNameInPatch_returnsInvalidDisplayName() = runUnitTest {
        val badRequest = clientResponseException(HttpStatusCode.BadRequest)
        val auth = FakeAuthRepository().also {
            it.emit(AuthState.Authenticated(userId = "u1", isAnonymous = false, email = null))
        }
        val api = FakeProfileApi(
            meResult = Result.success(SAMPLE_ME),
            patchResult = Result.failure(badRequest),
        )
        val repo = build(auth, api)
        advanceUntilIdle()

        val outcome = repo.update(displayName = "!!!")
        assertIs<UpdateProfileOutcome.InvalidDisplayName>(outcome)
    }

    @Test
    fun update_genericNetworkFailure_returnsNetworkError() = runUnitTest {
        val auth = FakeAuthRepository().also {
            it.emit(AuthState.Authenticated(userId = "u1", isAnonymous = false, email = null))
        }
        val api = FakeProfileApi(
            meResult = Result.success(SAMPLE_ME),
            patchResult = Result.failure(RuntimeException("no network")),
        )
        val repo = build(auth, api)
        advanceUntilIdle()

        val outcome = repo.update(displayName = "Whatever")
        assertIs<UpdateProfileOutcome.NetworkError>(outcome)
    }

    // ---------- fetchAvatarPack ----------

    @Test
    fun init_eagerlyWarmsAvatarPack_soEditProfileOpensHot() = runUnitTest {
        // ProfileRepositoryImpl is AutoInit. Per the contract on
        // [AutoInit], construction at app boot must also kick off the
        // avatar-pack warm-up so EditProfile (and the future "new
        // items in shop"-style surfaces that read the cached pack)
        // opens with a populated picker on first tap, even for
        // returning users who skip onboarding. Session-aware cache
        // dedupes if the user later opens EditProfile in the same
        // session.
        val auth = FakeAuthRepository().also {
            it.emit(AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null))
        }
        val api = FakeProfileApi(
            meResult = Result.success(SAMPLE_ME),
            avatarPackResult = Result.success(
                AvatarPackResponseDto(
                    packs = listOf(AvatarPackDto(id = "starter", name = "Starter", emojis = listOf("🃏"))),
                    backgroundPalette = listOf("#abc"),
                ),
            ),
        )
        build(auth, api)
        advanceUntilIdle()

        assertEquals(
            1, api.avatarPackCallCount,
            "constructing ProfileRepositoryImpl must fire /v1/avatars on its own — onboarding " +
                "and EditProfile no longer kick this off explicitly",
        )
    }

    @Test
    fun fetchAvatarPack_success_mapsResponse() = runUnitTest {
        val auth = FakeAuthRepository().also {
            it.emit(AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null))
        }
        val api = FakeProfileApi(
            meResult = Result.success(SAMPLE_ME),
            avatarPackResult = Result.success(
                AvatarPackResponseDto(
                    packs = listOf(AvatarPackDto(id = "starter", name = "Starter", emojis = listOf("🃏"))),
                    backgroundPalette = listOf("#abc"),
                ),
            ),
        )
        val repo = build(auth, api)
        advanceUntilIdle()

        val outcome = repo.fetchAvatarPack()
        val success = assertIs<AvatarPackOutcome.Success>(outcome)
        assertEquals(1, success.packs.size)
        assertEquals("starter", success.packs.single().id)
        assertEquals(listOf("#abc"), success.palette)
    }

    @Test
    fun fetchAvatarPack_networkFailureWithNoCache_returnsHardcodedFallbackAsSuccess() = runUnitTest {
        // Contract change (2026-05-25): fetchAvatarPack always returns
        // Success now — the picker should never be empty just because
        // the network failed once. Without a disk snapshot, the repo
        // returns its hardcoded fallback pack. With a disk snapshot,
        // it returns the snapshot (covered by the disk-cache tests).
        //
        // The fallback emojis must match the canonical list the server's
        // append-only Starter pack guarantees forever — see
        // `AvatarPacksTest.starter_includesAllClientFallbackEmojis`.
        // If you change FALLBACK_AVATAR_PACK, mirror the change on the
        // server first and confirm the new emojis are append-additions
        // to Starter (never replacements).
        val auth = FakeAuthRepository().also {
            it.emit(AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null))
        }
        val api = FakeProfileApi(
            meResult = Result.success(SAMPLE_ME),
            avatarPackResult = Result.failure(RuntimeException("offline")),
        )
        val repo = build(auth, api)
        advanceUntilIdle()

        val success = assertIs<AvatarPackOutcome.Success>(repo.fetchAvatarPack())
        val fallbackPack = success.packs.single()
        assertEquals(
            listOf("🦊", "🐱", "🐼", "🐯", "🐸", "🦁", "🃏", "🎲"),
            fallbackPack.emojis,
            "fallback emojis must match the canonical client/server-synced starter set",
        )
        assertEquals(
            null, fallbackPack.unlockProductId,
            "fallback pack must be marked as the always-available starter (unlockProductId = null)",
        )
        // Palette colors must all be in the server's AvatarPalette form
        // (lowercase #rrggbb) so a patchMe with any of them passes
        // server-side validation. Catches the original bug where the
        // client shipped a different palette than the server validated.
        success.palette.forEach { hex ->
            assertTrue(
                hex.matches(Regex("^#[0-9a-f]{6}$")),
                "fallback palette color $hex is not lowercase #rrggbb — server validates against AvatarPalette which is lowercase only",
            )
        }
        assertEquals(8, success.palette.size, "fallback palette should match the server palette size")
    }

    @Test
    fun fetchAvatarPack_withinSameSession_doesNotRepeatNetworkCall() = runUnitTest {
        // Two screens opening Edit Profile back-to-back inside one
        // session must share the first call's result — the over-fetch
        // we just fixed.
        val auth = FakeAuthRepository().also {
            it.emit(AuthState.Authenticated(userId = "u1", isAnonymous = true, email = null))
        }
        val api = FakeProfileApi(
            meResult = Result.success(SAMPLE_ME),
            avatarPackResult = Result.success(
                AvatarPackResponseDto(
                    packs = listOf(AvatarPackDto(id = "starter", name = "Starter", emojis = listOf("🃏"))),
                    backgroundPalette = listOf("#abc"),
                ),
            ),
        )
        val repo = build(auth, api)
        advanceUntilIdle()

        repo.fetchAvatarPack()
        repo.fetchAvatarPack()
        repo.fetchAvatarPack()

        assertEquals(
            1, api.avatarPackCallCount,
            "same-session refetches must dedup",
        )
    }

    // ---------- scaffolding ----------

    private fun build(
        auth: FakeAuthRepository,
        api: FakeProfileApi,
        cache: ProfileCache = newProfileCache(),
        avatarPackCache: AvatarPackCache = AvatarPackCache(InMemoryCacheFactory),
        sessionTracker: SessionTracker = FixedSessionTracker(id = 1L),
        pendingGuestAccountStore: PendingGuestAccountStore = PendingGuestAccountStore(InMemoryCacheFactory),
        clock: kotlin.time.Clock = kotlin.time.Clock.System,
    ): ProfileRepositoryImpl = ProfileRepositoryImpl(
        authRepository = auth,
        profileApi = api,
        profileCache = cache,
        avatarPackCache = avatarPackCache,
        sessionTracker = sessionTracker,
        pendingGuestAccountStore = pendingGuestAccountStore,
        clock = clock,
        appScope = AppCoroutineScope(dispatchers),
    )

    private fun newProfileCache(): ProfileCache = ProfileCache(InMemoryCacheFactory)

    /**
     * Minimal [SessionTracker] for tests that don't exercise rollover.
     * Anything that needs to *change* the session id swaps in its own.
     */
    private class FixedSessionTracker(private val id: Long) : SessionTracker {
        override val current: Session = Session(
            id = id,
            startedAtMs = 0L,
            reason = SessionStartReason.ColdBoot,
        )
        override fun observe(): kotlinx.coroutines.flow.Flow<Session> =
            kotlinx.coroutines.flow.flowOf(current)
    }

    /**
     * Fake [CacheFactory] that backs every persistent cache with an
     * in-memory MutableStateFlow. The serializer is ignored — tests
     * don't need wire-level encoding. Sufficient for [ProfileCache]'s
     * read/write contract.
     */
    private object InMemoryCacheFactory : CacheFactory {
        override fun <T : Any> inMemory(defaultValue: () -> T): Cache<T> =
            FakeCache(defaultValue)

        override fun <T : Any> persistent(
            name: String,
            serializer: CacheJsonSerializer<T>,
            loadEagerly: Boolean,
        ): Cache<T> {
            // serializer.read(null) returns the default value per the
            // VersionedCacheJsonSerializer contract.
            return FakeCache {
                kotlinx.coroutines.runBlocking { serializer.read(null) }
            }
        }
    }

    private class FakeCache<T : Any>(private val initial: () -> T) : Cache<T> {
        private val state = MutableStateFlow(initial())
        override val updates: Flow<T> = state
        override suspend fun get(): T = state.value
        override suspend fun set(value: T) { state.value = value }
        override suspend fun clear() { state.value = initial() }
    }

    /** Stubbed [AuthRepository] that emits [AuthState] through a replay=1 shared flow. */
    private class FakeAuthRepository : AuthRepository {
        private val state = MutableSharedFlow<AuthState>(replay = 1, extraBufferCapacity = 8)

        fun emit(value: AuthState) {
            state.tryEmit(value)
        }

        override suspend fun current(): AuthState = state.first()
        override fun observe(): Flow<AuthState> = state
        override suspend fun retry(): AuthState = state.first()
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
            error("unused")
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
            error("unused")
        override suspend fun refreshSession(): RefreshOutcome = error("unused")
        override suspend fun resendVerificationEmail(email: String): ResendOutcome = error("unused")
        override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome = error("unused")
        override suspend fun signOut() = Unit
        override suspend fun deleteAccount(): DeleteAccountOutcome = error("unused")
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
            error("unused")
        override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
            error("unused")
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome =
            error("unused")
    }

    private class FakeProfileApi(
        var meResult: Result<MeDto> = Result.failure(RuntimeException("not stubbed")),
        var patchResult: Result<MeDto> = Result.failure(RuntimeException("not stubbed")),
        var avatarPackResult: Result<AvatarPackResponseDto> = Result.failure(RuntimeException("not stubbed")),
    ) : ProfileApi {
        var lastPatchRequest: PatchMeRequest? = null
            private set
        var avatarPackCallCount: Int = 0
            private set

        override suspend fun me(): MeDto = meResult.getOrThrow()

        override suspend fun patchMe(request: PatchMeRequest): MeDto {
            lastPatchRequest = request
            return patchResult.getOrThrow()
        }

        override suspend fun avatars(): AvatarPackResponseDto {
            avatarPackCallCount++
            return avatarPackResult.getOrThrow()
        }

        override suspend fun deleteMe(): HttpResponse = error("deleteMe not used by ProfileRepository")
    }

    /**
     * Build a real [ClientRequestException] for a given status. The error
     * mapper in the impl branches purely on `e.response.status.value`, so
     * we just need an exception of the right shape — we trigger it by
     * routing a request through a [MockEngine] that responds with the
     * desired status. `expectSuccess = true` makes Ktor throw on 4xx,
     * which is what production HttpClient configuration does too.
     */
    private suspend fun clientResponseException(status: HttpStatusCode): ClientRequestException {
        val mock = HttpClient(MockEngine { respond(content = ByteReadChannel("{}"), status = status) }) {
            expectSuccess = true
        }
        return try {
            mock.get("/")
            error("expected ClientRequestException for status $status")
        } catch (e: ClientRequestException) {
            e
        } finally {
            mock.close()
        }
    }

    private companion object {
        val SAMPLE_ME = MeDto(
            userId = "u1",
            displayName = "Alice",
            avatarEmoji = "🃏",
            avatarBackgroundColor = null,
            isAnonymous = false,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )

        val SAMPLE_CACHED_PROFILE = Profile.Authenticated(
            id = "cached-u",
            displayName = "Cached",
            avatarEmoji = "🐺",
            avatarBackgroundColor = "#abc",
            email = "old@b.com",
            isAnonymous = false,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        )
    }
}
