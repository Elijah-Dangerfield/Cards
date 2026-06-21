package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.cards.SessionTracker
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.PendingIdentity
import com.dangerfield.cards.libraries.identity.impl.auth.PendingGuestAccountStore
import com.dangerfield.cards.libraries.identity.impl.MeDto
import com.dangerfield.cards.libraries.identity.impl.PatchMeRequest
import com.dangerfield.cards.libraries.identity.impl.ProfileApi
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.ProfileEditRejection
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
 * Backs [ProfileRepository] on top of [AuthRepository] + `/v1/me`
 * (the per-user profile) and `/v1/avatars` (the global emoji pack
 * catalog). Despite the historical "Supabase" prefix on prior
 * iterations, this class never talks to Supabase directly — all data
 * comes from our own backend. [AuthRepository] / [SupabaseAuthGateway]
 * own the supabase-kt session and only feed [AuthState] in here.
 *
 * **Profile resolve** (the user-specific bit):
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
 *  - Profile flow has no in-flight sentinel. [current] suspends until
 *    the first resolved emission; [observe] only emits resolved values.
 *
 * **Avatar pack fetch** ([fetchAvatarPack]) follows the
 * **session-aware cache pattern** documented in `AGENTS.md`: persist
 * the last successful `/v1/avatars` response to disk, hydrate on first
 * call, dedupe in-memory across same-session refetches, only re-fetch
 * when [SessionTracker] reports a new session. On a true cold-install
 * + no network the repo returns a hardcoded 8-emoji fallback as
 * Success so the picker is never empty. See the method docstring for
 * the full state machine.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ProfileRepository::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class ProfileRepositoryImpl(
    private val authRepository: AuthRepository,
    private val profileApi: ProfileApi,
    private val profileCache: ProfileCache,
    private val avatarPackCache: AvatarPackCache,
    private val sessionTracker: SessionTracker,
    private val pendingGuestAccountStore: PendingGuestAccountStore,
    private val pendingProfileEditStore: PendingProfileEditStore,
    private val clock: Clock,
    private val appScope: AppCoroutineScope,
) : ProfileRepository, AutoInit {

    private val logger = KLog.withTag("ProfileRepository")
    private val mutex = Mutex()
    private val _state = MutableSharedFlow<Profile>(replay = 1)
    private val sharedState: Flow<Profile> = _state.asSharedFlow()

    /**
     * One-shot rejections from flushing a queued offline edit the server then
     * refused. `extraBufferCapacity` so an emit from inside the resolve mutex
     * never suspends waiting on a slow collector.
     */
    private val _editRejections = MutableSharedFlow<ProfileEditRejection>(extraBufferCapacity = 4)

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
        // Warm the avatar pack on the same trigger we'd otherwise warm
        // it from (onboarding picker, EditProfile open). With this in
        // place, returning users — who skip onboarding — also land on
        // EditProfile with a hot cache instead of a fallback list.
        // [fetchAvatarPack] dedupes against the session-aware cache so
        // a screen that also calls it later still only hits the network
        // once per session.
        appScope.launch {
            Catching { fetchAvatarPack() }
                .logOnFailure { "Avatar pack boot warm failed" }
        }
    }

    override suspend fun current(): Profile = sharedState.first()

    override fun observe(): Flow<Profile> = sharedState

    override fun observeEditRejections(): Flow<ProfileEditRejection> = _editRejections

    override suspend fun flushPendingEdits() {
        // Cheap pre-check off the lock: nothing to do unless a session is live
        // and an edit is actually queued. Otherwise re-resolve, which fetches
        // server truth and flushes the queued edit on top (see resolve).
        val auth = authRepository.current()
        if (auth !is AuthState.Authenticated) return
        val hasPending = Catching { pendingProfileEditStore.read() }.getOrNull() != null
        if (!hasPending) return
        logger.d { "flushPendingEdits: re-resolving to flush a queued edit" }
        Catching { resolve(auth) }.logOnFailure { "flushPendingEdits resolve failed" }
    }

    private suspend fun resolve(auth: AuthState): Profile = mutex.withLock {
        val resolved = when (auth) {
            is AuthState.Authenticated -> resolveAuthenticatedLocked(auth)
            is AuthState.Unauthenticated -> resolveFallbackLocked(auth)
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

    private suspend fun resolveAuthenticatedLocked(auth: AuthState.Authenticated): Profile {
        val base = fetchServerProfileLocked(auth)
        // A session is live again — flush any edit queued while offline, on top
        // of the freshly-fetched server truth, before emitting. Folding the
        // flush into the resolve means the user never sees the un-patched server
        // value flash in (no "new name → old name → new name" churn).
        return if (base is Profile.Authenticated) flushQueuedEditLocked(base, auth.email) else base
    }

    private suspend fun fetchServerProfileLocked(auth: AuthState.Authenticated): Profile =
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
                    buildFallbackLocked()
                }
            },
        )

    /**
     * If an offline edit is queued, PATCH it on top of [base] (the freshly
     * resolved server profile). Returns the profile to emit:
     *  - **success** → server-confirmed profile; the queue is cleared.
     *  - **validation rejection** (name taken / invalid) → the queue is cleared
     *    (it can never succeed as-is), the optimistic value reverts to [base]
     *    (server truth), and a [ProfileEditRejection] is surfaced.
     *  - **transient failure** (network / 5xx) → the queue is kept, and the
     *    optimistic overlay (base + the edit) is emitted so the user keeps
     *    seeing their pending change; the next trigger retries.
     */
    private suspend fun flushQueuedEditLocked(
        base: Profile.Authenticated,
        email: String?,
    ): Profile.Authenticated {
        val pending = Catching { pendingProfileEditStore.read() }.getOrNull() ?: return base
        logger.i { "Flushing queued offline profile edit" }
        return Catching {
            profileApi.patchMe(
                PatchMeRequest(
                    displayName = pending.displayName,
                    avatarEmoji = pending.avatarEmoji,
                    avatarBackgroundColor = pending.avatarBackgroundColor,
                    clearAvatarBackgroundColor = pending.clearAvatarBackgroundColor,
                ),
            )
        }.fold(
            onSuccess = { updated ->
                Catching { pendingProfileEditStore.clear() }
                    .logOnFailure { "Clearing flushed profile edit failed" }
                val profile = updated.toAuthenticated(email)
                profileCache.writeAuthenticated(profile)
                logger.i { "Queued edit flushed: Success for ${profile.id}" }
                profile
            },
            onFailure = { e ->
                val rejection = e.toValidationRejectionOrNull(pending)
                if (rejection != null) {
                    Catching { pendingProfileEditStore.clear() }
                        .logOnFailure { "Clearing rejected profile edit failed" }
                    profileCache.writeAuthenticated(base)
                    _editRejections.tryEmit(rejection)
                    logger.w(e) { "Queued edit rejected ($rejection) — reverted to server truth" }
                    base
                } else {
                    // Transient — keep the queue, keep showing the optimistic value.
                    val optimistic = base.applyingEdit(pending)
                    profileCache.writeAuthenticated(optimistic)
                    logger.w(e) { "Queued edit flush failed transiently — keeping it queued" }
                    optimistic
                }
            },
        )
    }

    private suspend fun resolveFallbackLocked(auth: AuthState.Unauthenticated): Profile {
        // A server-confirmed dead session (the auth server rejected our token):
        // the cached profile is a ghost. Surfacing it as Authenticated is exactly
        // what makes the app keep firing authed calls that all 401. Clear it and
        // drop to Fallback so the app knows it has no working account — routing to
        // re-auth happens off the SessionExpired auth state, not from here.
        if (auth.reason == AuthState.Unauthenticated.Reason.SessionExpired) {
            val cached = Catching { profileCache.readAuthenticated() }
                .logOnFailure { "Profile cache read failed" }
                .getOrNull()
            if (cached != null) {
                logger.i { "SessionExpired — clearing stale cached profile ${cached.id}" }
                Catching { profileCache.clear() }
                    .logOnFailure { "Failed to clear stale cached profile after session expiry" }
            }
            return buildFallbackLocked()
        }

        // Benign unauthenticated (no session yet / clean sign-out / offline): we
        // may have a profile cached from a previous session. If so, that's the
        // best we have to show until auth comes back. Otherwise the fallback UUID.
        val cached = Catching { profileCache.readAuthenticated() }
            .logOnFailure { "Profile cache read failed" }
            .getOrNull()
        if (cached != null) {
            logger.d { "Unauthenticated but cached profile ${cached.id} exists; surfacing it" }
            return cached
        }
        logger.d { "Unauthenticated + no cache; emitting Profile.Fallback" }
        return buildFallbackLocked()
    }

    /**
     * Build a [Profile.Fallback], enriching it with the user's locally-chosen
     * identity when one is owed but unsynced — the name/avatar picked during an
     * offline onboarding (held in [PendingGuestAccountStore]). Surfacing it lets
     * the app show the user's choice instead of a generic "You" while we're
     * session-less; it syncs to the server once a session is minted. When nothing
     * is owed (no offline onboarding pending), the fields stay null.
     */
    private suspend fun buildFallbackLocked(): Profile.Fallback {
        val pending = Catching { pendingGuestAccountStore.read() }
            .logOnFailure { "Reading pending identity for Fallback failed" }
            .getOrNull()
        return Profile.Fallback(
            id = ensureLocalIdLocked(),
            displayName = pending?.displayName,
            avatarEmoji = pending?.avatarEmoji,
            avatarBackgroundColor = pending?.avatarBackgroundColor,
        )
    }

    /**
     * Apply an offline profile edit for a session-less (Fallback) user by
     * merging it into the owed guest-account record. The guest-mint path applies
     * that identity when a session is established, so the single mint is the
     * sync. Emits an enriched [Profile.Fallback] immediately so the UI reflects
     * the change without waiting on the network. Assumes [mutex] is held.
     */
    private suspend fun queueGuestIdentityEditLocked(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome {
        val existing = Catching { pendingGuestAccountStore.read() }.getOrNull()
        val merged = PendingIdentity(
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.displayName,
            avatarEmoji = avatarEmoji ?: existing?.avatarEmoji,
            avatarBackgroundColor = when {
                clearAvatarBackgroundColor -> null
                avatarBackgroundColor != null -> avatarBackgroundColor
                else -> existing?.avatarBackgroundColor
            },
        )
        Catching { pendingGuestAccountStore.set(merged) }
            .logOnFailure { "Queuing offline profile edit failed" }
        _state.emit(
            Profile.Fallback(
                id = ensureLocalIdLocked(),
                displayName = merged.displayName,
                avatarEmoji = merged.avatarEmoji,
                avatarBackgroundColor = merged.avatarBackgroundColor,
            ),
        )
        logger.i { "update: Queued offline identity edit (will sync on session mint)" }
        return UpdateProfileOutcome.Queued
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
        // PATCH /v1/me requires a session. When we don't have one, branch on
        // whether this is a real account that's merely offline vs. a guest who
        // hasn't reached the server yet:
        val auth = authRepository.current()
        if (auth !is AuthState.Authenticated) {
            val cachedAuthed = Catching { profileCache.readAuthenticated() }
                .logOnFailure { "Profile cache read during offline update failed" }
                .getOrNull()
            if (cachedAuthed != null) {
                // A real (claimed/anon) account, just offline. Apply the edit
                // optimistically and queue it to PATCH when a session returns —
                // offline-first: the user's change sticks and syncs on reconnect.
                val base = lastEmittedAuthenticatedOrNull() ?: cachedAuthed
                val optimistic = base.applyingEdit(displayName, avatarEmoji, avatarBackgroundColor, clearAvatarBackgroundColor)
                profileCache.writeAuthenticated(optimistic)
                _state.emit(optimistic)
                Catching {
                    pendingProfileEditStore.enqueue(displayName, avatarEmoji, avatarBackgroundColor, clearAvatarBackgroundColor)
                }.logOnFailure { "Queuing offline profile edit failed" }
                logger.i { "update: Queued offline edit for cached account (will sync when online)" }
                return@withLock UpdateProfileOutcome.Queued
            }
            // True Fallback — onboarded but session-less (e.g. onboarded
            // offline). Record the chosen identity into the owed guest-account
            // record so it (a) surfaces on the Fallback now and (b) is applied
            // server-side when the session is minted. Optimistic local emit.
            return@withLock queueGuestIdentityEditLocked(
                displayName = displayName,
                avatarEmoji = avatarEmoji,
                avatarBackgroundColor = avatarBackgroundColor,
                clearAvatarBackgroundColor = clearAvatarBackgroundColor,
            )
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
                // Validation failures are terminal — the user must fix the input,
                // so roll the optimistic write back and surface the typed error.
                val validation = when (e) {
                    is ClientRequestException -> when (e.response.status.value) {
                        409 -> UpdateProfileOutcome.DisplayNameTaken
                        400 -> when {
                            displayName != null -> UpdateProfileOutcome.InvalidDisplayName
                            avatarEmoji != null -> UpdateProfileOutcome.InvalidAvatarEmoji
                            else -> UpdateProfileOutcome.InvalidAvatarBackgroundColor
                        }
                        else -> null
                    }
                    else -> null
                }
                if (validation != null) {
                    if (priorProfile != null) {
                        profileCache.writeAuthenticated(priorProfile)
                        _state.emit(priorProfile)
                        logger.d { "update: rolled back optimistic write (validation)" }
                    }
                    logger.w(e) { "update: ${validation::class.simpleName}" }
                    validation
                } else {
                    // Transient (network / 401 mid-edit / 5xx) — keep the
                    // optimistic value and queue the PATCH to flush on reconnect
                    // instead of losing the edit. Offline-first.
                    Catching {
                        pendingProfileEditStore.enqueue(displayName, avatarEmoji, avatarBackgroundColor, clearAvatarBackgroundColor)
                    }.logOnFailure { "Queuing edit after transient failure failed" }
                    logger.w(e) { "update: Queued after transient failure" }
                    UpdateProfileOutcome.Queued
                }
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
         *
         * Must stay in sync with `OnboardingViewModel.STARTER_PACK`
         * and remain a subset of the server's starter pack — server
         * contract is append-only, so any emoji in this list at APK
         * build time is guaranteed to still be accepted by patchMe
         * forever. That's what keeps the fallback safe across drift
         * between an old APK and a newer server.
         */
        val FALLBACK_AVATAR_PACK: AvatarPackOutcome.Success = AvatarPackOutcome.Success(
            packs = listOf(
                AvatarPack(
                    id = "starter_fallback",
                    name = "Starter",
                    emojis = listOf("🦊", "🐱", "🐼", "🐯", "🐸", "🦁", "🃏", "🎲"),
                    unlockProductId = null,
                ),
            ),
            palette = listOf(
                "#5bc79b", "#7555ff", "#ff6b35", "#ffc857",
                "#52a2ff", "#ff5da2", "#a18bff", "#37d5c2",
            ),
        )
    }

    private fun lastEmittedAuthenticatedOrNull(): Profile.Authenticated? =
        _state.replayCache.firstOrNull() as? Profile.Authenticated

    private fun MeDto.toAuthenticated(email: String?): Profile.Authenticated = Profile.Authenticated(
        id = userId,
        displayName = displayName,
        avatarEmoji = avatarEmoji,
        avatarBackgroundColor = avatarBackgroundColor,
        email = email,
        isAnonymous = isAnonymous,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
    )

    /** Overlay a raw edit onto a profile — the optimistic-write shape. */
    private fun Profile.Authenticated.applyingEdit(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): Profile.Authenticated = copy(
        displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: this.displayName,
        avatarEmoji = avatarEmoji ?: this.avatarEmoji,
        avatarBackgroundColor = when {
            clearAvatarBackgroundColor -> null
            avatarBackgroundColor != null -> avatarBackgroundColor
            else -> this.avatarBackgroundColor
        },
    )

    private fun Profile.Authenticated.applyingEdit(edit: PendingProfileEdit): Profile.Authenticated =
        applyingEdit(
            displayName = edit.displayName,
            avatarEmoji = edit.avatarEmoji,
            avatarBackgroundColor = edit.avatarBackgroundColor,
            clearAvatarBackgroundColor = edit.clearAvatarBackgroundColor,
        )

    /**
     * Map a flush failure to a terminal [ProfileEditRejection] (the server
     * refused the edit on its merits), or null when it's transient (network /
     * 5xx) and worth keeping queued.
     */
    private fun Throwable.toValidationRejectionOrNull(edit: PendingProfileEdit): ProfileEditRejection? =
        when (this) {
            is ClientRequestException -> when (response.status.value) {
                409 -> ProfileEditRejection.DisplayNameTaken
                400 -> when {
                    edit.displayName != null -> ProfileEditRejection.InvalidDisplayName
                    edit.avatarEmoji != null -> ProfileEditRejection.InvalidAvatarEmoji
                    else -> ProfileEditRejection.InvalidAvatarBackgroundColor
                }
                else -> null
            }
            else -> null
        }
}
