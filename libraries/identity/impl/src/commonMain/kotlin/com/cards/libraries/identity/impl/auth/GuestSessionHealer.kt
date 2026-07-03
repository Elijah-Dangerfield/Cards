package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.identity.auth.PendingIdentity
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The *decider* for identity self-heal. Listens for the data-freshness triggers
 * — cold boot, warm boot, foreground (non-cold-boot), and connectivity-regained
 * — and, on each, asks "is this device stranded?": onboarded + online + genuinely
 * session-less (not a deliberate sign-out, not a server-killed session).
 *
 * When stranded it drives [GuestAccountCreator.ensureSession], which mints (or
 * finishes an owed) anonymous session. Success flips auth to Authenticated,
 * which fires the existing `UserChanged(null→X)` fan-out — re-syncing every
 * user-scoped repo and re-resolving `/v1/me` — so the healer doesn't have to
 * refresh anything itself. It only ever *creates the identity*; the rest rides
 * the machinery that already exists.
 *
 * This is the permanent fix for the stranding bug: even if the durable pending
 * record was ever lost, the next foreground/connectivity edge recovers the user.
 *
 * Each trigger emits one structured decision line (see [HealAction]) so the old
 * silent failure is now observable; mint success/failure is logged too.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppEventListener::class, multibinding = true)
@Inject
class GuestSessionHealer(
    // Lazy providers break the DI construction cycle: this is an AppEventListener
    // (in the dispatcher's set), and AuthRepository → AppEventBus → the dispatcher
    // → this set. GuestAccountCreator and ProfileRepository reach AuthRepository
    // transitively, so they're deferred too. Mirrors InAppMessageManagerImpl.
    authRepositoryProvider: () -> AuthRepository,
    guestAccountCreatorProvider: () -> GuestAccountCreator,
    profileRepositoryProvider: () -> ProfileRepository,
    private val appCache: AppCache,
    private val appState: AppState,
    private val appScope: AppCoroutineScope,
) : AppEventListener {

    private val logger = KLog.withTag("GuestSessionHealer")
    private val inFlight = Mutex()
    private val authRepository: AuthRepository by lazy { authRepositoryProvider() }
    private val guestAccountCreator: GuestAccountCreator by lazy { guestAccountCreatorProvider() }
    private val profileRepository: ProfileRepository by lazy { profileRepositoryProvider() }

    override fun onColdBoot(event: AppEvent.ColdBoot) = heal("coldBoot")
    override fun onWarmBoot(event: AppEvent.WarmBoot) = heal("warmBoot")

    override fun onForeground(event: AppEvent.OnForeground) {
        // Cold-boot foreground is already covered by onColdBoot; skip the
        // duplicate so we don't run the decision twice on launch.
        if (event.isColdBoot) return
        heal("foreground")
    }

    override fun onConnectivityRegained(event: AppEvent.ConnectivityRegained) = heal("connectivityRegained")

    /**
     * Single-flight: overlapping triggers skip rather than queue — every source
     * re-fires on a later edge anyway. This guards the decision run (duplicate
     * [AuthRepository.retry] hits); mint-level serialization is separately owned
     * by the creator's own mutex.
     */
    fun heal(source: String) {
        if (!inFlight.tryLock()) {
            log(source, HealAction.SKIP_ALREADY_RUNNING)
            return
        }
        appScope.launch {
            try {
                Catching { healIfStranded(source) }
                    .logOnFailure { "Identity self-heal failed (source=$source)" }
            } finally {
                inFlight.unlock()
            }
        }
    }

    private suspend fun healIfStranded(source: String) {
        // Already have a session — nothing to do.
        if (authRepository.current() is AuthState.Authenticated) {
            log(source, HealAction.SKIP_HAS_SESSION)
            return
        }

        // Cheapest recovery: re-resolve a dormant/refreshable session. Recovers a
        // claimed user whose token just needed a nudge — without minting anything.
        val afterRetry = authRepository.retry()
        if (afterRetry is AuthState.Authenticated) {
            log(source, HealAction.RETRY_RECOVERED)
            return
        }
        val unauth = afterRetry as AuthState.Unauthenticated

        when {
            unauth.reason == AuthState.Unauthenticated.Reason.SessionExpired -> {
                // The auth server killed the session; the app routes to manual
                // re-auth. Minting a guest here would paper over a real account.
                log(source, HealAction.SKIP_SESSION_EXPIRED)
            }
            unauth.reason == AuthState.Unauthenticated.Reason.SignedOut -> {
                // Deliberate sign-out — do not resurrect as a fresh guest.
                log(source, HealAction.SKIP_SIGNED_OUT)
            }
            !hasUserOnboarded() -> {
                // Pre-onboarding: the user hasn't committed to playing yet, so
                // there's no identity owed. Onboarding's own start() mints.
                log(source, HealAction.SKIP_NOT_ONBOARDED)
            }
            appState.isOffline.value -> {
                // Can't mint offline; the connectivity-regained edge will retry.
                log(source, HealAction.SKIP_OFFLINE)
            }
            else -> mint(source)
        }
    }

    private suspend fun mint(source: String) {
        val fallback = deriveIdentity()
        val action = if (fallback.displayName != null) HealAction.MINT_FROM_PENDING else HealAction.MINT_FRESH
        log(source, action)
        when (val result = guestAccountCreator.ensureSession(fallback)) {
            is AccountCreationState.Succeeded ->
                logger.i { "heal source=$source mint=success — UserChanged fan-out will re-sync everything" }
            is AccountCreationState.Failed ->
                logger.w { "heal source=$source mint=failed (${result.cause?.message}) — will retry on next trigger" }
            else ->
                logger.w { "heal source=$source mint=unexpected(${result::class.simpleName})" }
        }
    }

    /**
     * Best-effort name/avatar to seed a healed session. A cached *authenticated*
     * profile (rare for the stranding case, but possible) reuses the user's real
     * identity; otherwise null fields let the server generate a fresh one. The
     * creator additionally prefers a durably-owed pending record over this.
     */
    private suspend fun deriveIdentity(): PendingIdentity {
        val profile = Catching { profileRepository.current() }
            .logOnFailure { "Reading profile to seed heal failed; using a generated identity" }
            .getOrNull()
        return when (profile) {
            is Profile.Authenticated -> PendingIdentity(
                displayName = profile.displayName,
                avatarEmoji = profile.avatarEmoji,
                avatarBackgroundColor = profile.avatarBackgroundColor,
            )
            else -> PendingIdentity(displayName = null, avatarEmoji = null, avatarBackgroundColor = null)
        }
    }

    private suspend fun hasUserOnboarded(): Boolean =
        Catching { appCache.get().hasUserOnboarded }
            .logOnFailure { "Reading hasUserOnboarded for heal failed; assuming not onboarded" }
            .getOrNull() ?: false

    private fun log(source: String, action: HealAction) {
        logger.i { "heal source=$source action=$action" }
    }

    /** The decision the healer reached this trigger — one line per trigger. */
    private enum class HealAction {
        SKIP_ALREADY_RUNNING,
        SKIP_HAS_SESSION,
        SKIP_SESSION_EXPIRED,
        SKIP_SIGNED_OUT,
        SKIP_NOT_ONBOARDED,
        SKIP_OFFLINE,
        RETRY_RECOVERED,
        MINT_FROM_PENDING,
        MINT_FRESH,
    }
}
