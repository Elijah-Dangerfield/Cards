package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
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
class SupabaseProfileRepositoryImpl(
    private val authRepository: AuthRepository,
    private val profileApi: ProfileApi,
    private val profileCache: ProfileCache,
    private val appEventBus: AppEventBus,
    appScope: AppCoroutineScope,
) : ProfileRepository {

    private val logger = KLog.withTag("ProfileRepository")
    private val mutex = Mutex()
    private val _state = MutableSharedFlow<Profile>(replay = 1)
    private val sharedState: Flow<Profile> = _state.asSharedFlow()

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

    override suspend fun fetchAvatarPack(): AvatarPackOutcome {
        logger.d { "fetchAvatarPack: GET /v1/avatars" }
        return Catching { profileApi.avatars() }.fold(
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
                        )
                    },
                    palette = response.backgroundPalette,
                )
            },
            onFailure = { e ->
                val outcome = when (e) {
                    is ClientRequestException -> AvatarPackOutcome.Unknown(e)
                    is ServerResponseException -> AvatarPackOutcome.Unknown(e)
                    else -> AvatarPackOutcome.NetworkError(e)
                }
                logger.w(e) { "fetchAvatarPack: ${outcome::class.simpleName}" }
                outcome
            },
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
