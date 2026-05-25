package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.cards.SessionTracker
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.impl.PatchMeRequest
import com.dangerfield.cards.libraries.identity.impl.ProfileApi
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Backs [ProfileRepository] on top of [AuthRepository] + `/v1/me`.
 *
 * Lifecycle:
 *
 *  - On init, [appScope] launches a collector on [AuthRepository.observe].
 *    Every auth state change triggers a resolve. The first resolve
 *    completes the initial resolve; subsequent ones cover sign-in,
 *    sign-out, account delete, etc.
 *  - Resolve:
 *      - [AuthState.Authenticated] → `/v1/me` get-or-create →
 *        [Profile.Authenticated], cached.
 *      - [AuthState.Unauthenticated] → cache fallback. If cached
 *        profile exists, emit it (the supabase-kt session may still
 *        be valid for some calls). Otherwise emit
 *        [Profile.Fallback] keyed on a stable client UUID from cache.
 *      - Network error during the `/v1/me` call → same cache fallback
 *        path. Cache as fallback, not first-frame.
 *
 * Profile flow has no in-flight sentinel. [current] suspends until the
 * first resolved emission; [observe] only emits resolved values.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ProfileRepository::class)
@Inject
class ProfileRepositoryImpl(
    private val authRepository: AuthRepository,
    private val profileApi: ProfileApi,
    private val profileCache: ProfileCache,
    private val avatarPackCache: AvatarPackCache,
    private val sessionTracker: SessionTracker,
    private val clock: Clock,
    private val appEventBus: AppEventBus,
    appScope: AppCoroutineScope,
) : ProfileRepository {

    private val logger = KLog.withTag("ProfileRepository")
    private val mutex = Mutex()
    private val _state = MutableSharedFlow<Profile>(replay = 1)
    private val sharedState: Flow<Profile> = _state.asSharedFlow()

    /**
     * Serializes avatar-pack fetches so a race (two screens opening
     * simultaneously) shares one network call. Kept separate from
     * [mutex] (which guards profile resolution) so a slow `/v1/me`
     * never blocks a `/v1/avatars` and vice versa.
     */
    private val avatarPackMutex = Mutex()

    /**
     * Memoized result of the last successful avatar fetch — either
     * from disk hydration at first call or from the most recent
     * network success. Returned for any same-session refetch without
     * touching disk or network again. Null until the first call (or
     * after [avatarPackCache] is cleared).
     */
    private var memoizedAvatarPack: AvatarPackOutcome.Success? = null

    /**
     * Session id under which [memoizedAvatarPack] was captured.
     * Compared against the current [SessionTracker] id on each call —
     * mismatch means "session rolled, refetch."
     */
    private var memoizedSessionId: Long? = null

    init {
        logger.d { "init: subscribing to AuthRepository.observe()" }
        // Watch auth state. Every change re-resolves the profile —
        // sign-in flips Fallback → Authenticated; sign-out flips the
        // other way; refresh-after-claim swaps the Authenticated
        // payload to the non-anonymous one.
        appScope.launch {
            authRepository.observe().collect { auth ->
                logger.d { "Auth changed → resolve (${auth::class.simpleName})" }
                Catching { resolve(auth) }
                    .logOnFailure { "Profile resolve from auth change failed" }
            }
        }
    }

    override suspend fun current(): Profile = sharedState.first()

    override fun observe(): Flow<Profile> = sharedState

    private suspend fun resolve(auth: AuthState): Profile = mutex.withLock {
        val resolved = when (auth) {
            is AuthState.Authenticated -> resolveAuthenticatedLocked(auth)
            is AuthState.Unauthenticated -> resolveFallbackLocked()
        }
        _state.emit(resolved)
        // Info-level — profile emissions are load-bearing observability,
        // same reason as the auth-state emit logs.
        when (resolved) {
            is Profile.Authenticated -> logger.i {
                "Emitted Profile.Authenticated(id=${resolved.id}, isAnonymous=${resolved.isAnonymous}, hasEmail=${resolved.email != null})"
            }
            is Profile.Fallback -> logger.i {
                "Emitted Profile.Fallback(localId=${resolved.id}) — no auth + no cached profile"
            }
        }
        resolved
    }

    private suspend fun resolveAuthenticatedLocked(auth: AuthState.Authenticated): Profile =
        Catching {
            logger.d { "GET /v1/me for ${auth.userId}" }
            val me = profileApi.me()
            val profile = Profile.Authenticated(
                id = me.userId,
                displayName = me.displayName,
                avatarEmoji = me.avatarEmoji,
                avatarBackgroundColor = me.avatarBackgroundColor,
                email = auth.email,
                isAnonymous = me.isAnonymous,
                createdAt = Instant.fromEpochMilliseconds(me.createdAtEpochMs),
            )
            profileCache.writeAuthenticated(profile)
            // Real session resolved — local fallback no longer relevant.
            profileCache.writeLocalId(null)
            profile
        }.fold(
            onSuccess = { it },
            onFailure = { cause ->
                logger.w(cause) { "/v1/me failed; falling back to cache" }
                // Cache as fallback: an old real profile is better than
                // nothing; the supabase-kt session may still work for
                // individual calls.
                val cached = Catching { profileCache.readAuthenticated() }
                    .logOnFailure { "Profile cache read failed" }
                    .getOrNull()
                if (cached != null) {
                    logger.i { "Cache fallback: using cached profile ${cached.id}" }
                    cached
                } else {
                    logger.i { "Cache empty: emitting Profile.Fallback with localId" }
                    Profile.Fallback(id = ensureLocalIdLocked())
                }
            },
        )

    private suspend fun resolveFallbackLocked(): Profile {
        // No auth → no real profile to fetch. But we may have one cached
        // from a previous session. If so, that's the best we have to
        // show until auth comes back. Otherwise the fallback UUID.
        val cached = Catching { profileCache.readAuthenticated() }
            .logOnFailure { "Profile cache read failed" }
            .getOrNull()
        if (cached != null) {
            logger.d { "Unauthenticated but cached profile ${cached.id} exists; surfacing it" }
            return cached
        }
        logger.d { "Unauthenticated + no cache; emitting Profile.Fallback" }
        return Profile.Fallback(id = ensureLocalIdLocked())
    }

    private suspend fun ensureLocalIdLocked(): String {
        val existing = Catching { profileCache.readLocalId() }.getOrNull()
        if (existing != null) return existing
        val fresh = Uuid.random().toString()
        Catching { profileCache.writeLocalId(fresh) }
            .logOnFailure { "Failed to persist new localId" }
        return fresh
    }

    // ---------- update + avatar pack ----------

    override suspend fun update(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = mutex.withLock {
        // Don't log the new values themselves — display names are
        // mildly user-identifying. Just record which fields are
        // changing.
        logger.d {
            "update: fields=[" +
                listOfNotNull(
                    "displayName".takeIf { displayName != null },
                    "avatarEmoji".takeIf { avatarEmoji != null },
                    "avatarBackgroundColor".takeIf { avatarBackgroundColor != null },
                    "clearAvatarBackgroundColor".takeIf { clearAvatarBackgroundColor },
                ).joinToString() +
                "]"
        }
        // The auth check here is structural: PATCH /v1/me requires a
        // session, and the request will 401 cleanly if not. Catching
        // that here avoids a network round-trip in the obvious case.
        val auth = authRepository.current()
        if (auth !is AuthState.Authenticated) {
            logger.w { "update: NotSignedIn (auth is ${auth::class.simpleName})" }
            return@withLock UpdateProfileOutcome.NotSignedIn
        }

        // Optimistic: write the prospective profile to cache + state
        // immediately so the UI updates without a round-trip. On
        // failure, roll back.
        val priorProfile = lastEmittedAuthenticatedOrNull()
        if (priorProfile != null) {
            val optimistic = priorProfile.copy(
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: priorProfile.displayName,
                avatarEmoji = avatarEmoji ?: priorProfile.avatarEmoji,
                avatarBackgroundColor = when {
                    clearAvatarBackgroundColor -> null
                    avatarBackgroundColor != null -> avatarBackgroundColor
                    else -> priorProfile.avatarBackgroundColor
                },
            )
            profileCache.writeAuthenticated(optimistic)
            _state.emit(optimistic)
            logger.d { "update: optimistic write applied; awaiting server confirm" }
        }

        Catching {
            profileApi.patchMe(
                PatchMeRequest(
                    displayName = displayName,
                    avatarEmoji = avatarEmoji,
                    avatarBackgroundColor = avatarBackgroundColor,
                    clearAvatarBackgroundColor = clearAvatarBackgroundColor,
                ),
            )
        }.fold(
            onSuccess = { updated ->
                val profile = Profile.Authenticated(
                    id = updated.userId,
                    displayName = updated.displayName,
                    avatarEmoji = updated.avatarEmoji,
                    avatarBackgroundColor = updated.avatarBackgroundColor,
                    isAnonymous = updated.isAnonymous,
                    email = auth.email,
                    createdAt = Instant.fromEpochMilliseconds(updated.createdAtEpochMs),
                )
                profileCache.writeAuthenticated(profile)
                _state.emit(profile)
                logger.i { "update: Success for ${profile.id}" }
                UpdateProfileOutcome.Success(profile)
            },
            onFailure = { e ->
                if (priorProfile != null) {
                    profileCache.writeAuthenticated(priorProfile)
                    _state.emit(priorProfile)
                    logger.d { "update: rolled back optimistic write" }
                }
                val outcome = when (e) {
                    is ClientRequestException -> when (e.response.status.value) {
                        409 -> UpdateProfileOutcome.DisplayNameTaken
                        401 -> UpdateProfileOutcome.NotSignedIn
                        400 -> when {
                            displayName != null -> UpdateProfileOutcome.InvalidDisplayName
                            avatarEmoji != null -> UpdateProfileOutcome.InvalidAvatarEmoji
                            else -> UpdateProfileOutcome.InvalidAvatarBackgroundColor
                        }
                        else -> UpdateProfileOutcome.Unknown(e)
                    }
                    is ServerResponseException -> UpdateProfileOutcome.Unknown(e)
                    else -> UpdateProfileOutcome.NetworkError(e)
                }
                logger.w(e) { "update: ${outcome::class.simpleName}" }
                outcome
            },
        )
    }

    override suspend fun fetchAvatarPack(): AvatarPackOutcome = avatarPackMutex.withLock {
        val currentSessionId = sessionTracker.current.id

        // Hot path: already fetched this session. Skip disk + network.
        memoizedAvatarPack?.let { memoized ->
            if (memoizedSessionId == currentSessionId) {
                logger.d { "fetchAvatarPack: in-memory hit (session=$currentSessionId)" }
                return@withLock memoized
            }
        }

        // Cold path: hydrate from disk if we haven't yet. Returns
        // the disk snapshot if it's from the same session, so two
        // tabs opening Edit Profile back-to-back across a cold start
        // still share one network call.
        val cached = Catching { avatarPackCache.read() }
            .logOnFailure { "Avatar pack cache read failed" }
            .getOrNull()
        if (cached != null) {
            val ageMs = clock.now().toEpochMilliseconds() - cached.fetchedAtEpochMs
            val tooStale = ageMs > MAX_AVATAR_SNAPSHOT_AGE_MS
            when {
                tooStale -> {
                    logger.i { "Dropping persisted avatar pack: ${ageMs / 1000}s old > $MAX_AVATAR_SNAPSHOT_AGE_MS ms" }
                    Catching { avatarPackCache.clear() }
                        .logOnFailure { "Failed to clear stale avatar pack snapshot" }
                }
                cached.lastFetchSessionId == currentSessionId -> {
                    val hydrated = cached.toSuccess()
                    memoizedAvatarPack = hydrated
                    memoizedSessionId = currentSessionId
                    logger.d { "fetchAvatarPack: disk hit (session=$currentSessionId)" }
                    return@withLock hydrated
                }
                else -> {
                    // Old session's snapshot — usable as a fallback
                    // if the network fails, but we still try to
                    // refresh below.
                    memoizedAvatarPack = cached.toSuccess()
                }
            }
        }

        logger.d { "fetchAvatarPack: GET /v1/avatars (session=$currentSessionId)" }
        val networkOutcome = Catching { profileApi.avatars() }.fold(
            onSuccess = { response ->
                logger.d {
                    "fetchAvatarPack: Success (${response.packs.size} packs, ${response.backgroundPalette.size} colors)"
                }
                AvatarPackOutcome.Success(
                    packs = response.packs.map { dto ->
                        AvatarPack(
                            id = dto.id,
                            name = dto.name,
                            emojis = dto.emojis,
                            unlockProductId = dto.unlockProductId,
                        )
                    },
                    palette = response.backgroundPalette,
                )
            },
            onFailure = { e ->
                val classifier = when (e) {
                    is ClientRequestException, is ServerResponseException -> "Unknown"
                    else -> "NetworkError"
                }
                logger.w(e) { "fetchAvatarPack: $classifier — falling back" }
                null
            },
        )

        if (networkOutcome != null) {
            memoizedAvatarPack = networkOutcome
            memoizedSessionId = currentSessionId
            Catching {
                avatarPackCache.write(
                    outcome = networkOutcome,
                    sessionId = currentSessionId,
                    fetchedAtEpochMs = clock.now().toEpochMilliseconds(),
                )
            }.logOnFailure { "Failed to persist avatar pack" }
            return@withLock networkOutcome
        }

        // Network failed. Prefer whatever we already had cached
        // (in-memory or freshly-loaded-from-disk above) over the
        // hardcoded fallback so the user keeps seeing a richer
        // catalog if one was ever fetched.
        memoizedAvatarPack?.let { return@withLock it }
        logger.i { "fetchAvatarPack: no cache available, returning hardcoded fallback" }
        FALLBACK_AVATAR_PACK
    }

    private companion object {
        /**
         * Maximum age of a persisted avatar-pack snapshot before we
         * drop it on read. The catalog of emoji packs turns over very
         * rarely (new pack ships = release-worthy event), so a week
         * is the outer edge of "still mostly accurate" — same bound
         * the shop catalog uses for consistency.
         */
        val MAX_AVATAR_SNAPSHOT_AGE_MS: Long = 7.days.inWholeMilliseconds

        /**
         * Last-resort pack used when the network fails and nothing's
         * been cached yet — first install, never online before. Keeps
         * the avatar picker functional on bad-network fresh installs.
         * Once any real fetch succeeds it overwrites this; we never
         * fall back to this list while a real snapshot is available.
         */
        val FALLBACK_AVATAR_PACK: AvatarPackOutcome.Success = AvatarPackOutcome.Success(
            packs = listOf(
                AvatarPack(
                    id = "starter_fallback",
                    name = "Starter",
                    emojis = listOf("🦊", "😀", "🐼", "🐯", "🦄", "🐸", "🦁", "🌶️"),
                    unlockProductId = null,
                ),
            ),
            palette = listOf(
                "#E48A58", "#E4C658", "#7D8794", "#C68A3D",
                "#C658E4", "#5DA15D", "#E4A258", "#5DAE5D",
            ),
        )
    }

    private fun lastEmittedAuthenticatedOrNull(): Profile.Authenticated? =
        _state.replayCache.firstOrNull() as? Profile.Authenticated

    @Suppress("unused")
    private val signedOutTrigger: Unit = run {
        // Future hook: clear the profile cache when AppEvent.SignedOut
        // fires from AuthRepository. Wiring is mute for now — the auth
        // observer above already re-resolves and emits Unauthenticated →
        // Fallback, which is the user-visible part.
        appEventBus.let { /* keep param referenced */ }
        Unit
    }
}
