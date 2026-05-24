package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Owns the "ensure a Supabase session exists" policy: waits for supabase-kt's
 * persisted-session hydration, signs in anonymously when no session was found,
 * and loops on transient states (mid-init, mid-refresh).
 *
 * Extracted from [SupabaseAuthRepositoryImpl] so the network layer can wait
 * for a token via [GatewayAuthTokenProvider] without dragging in the rest of
 * the auth repository — which would form a `NetworkClient → AuthRepository
 * → ProfileApi → NetworkClient` construction cycle. AuthBootstrap depends
 * only on [SupabaseAuthGateway], so the cycle is broken at the type level.
 *
 * Memoizes the resolved outcome: concurrent callers share the in-flight
 * resolve; subsequent callers get the memoized result. [invalidate] clears
 * the memo — call it after signOut / deleteAccount so the next consumer's
 * resolve runs a fresh anon sign-in.
 */
@SingleIn(AppScope::class)
@Inject
class AuthBootstrap(
    private val gateway: SupabaseAuthGateway,
) {
    private val logger = KLog.withTag("AuthBootstrap")
    private val mutex = Mutex()

    private var memoized: BootstrapOutcome? = null

    /**
     * Run the resolve loop and return its outcome. Memoized — concurrent
     * callers share, subsequent callers get the cached result until
     * [invalidate] is called.
     */
    suspend fun awaitResolved(): BootstrapOutcome = mutex.withLock {
        memoized ?: resolveLocked().also { memoized = it }
    }

    /**
     * Clear the memoized outcome. The next [awaitResolved] re-runs the loop.
     * Use after signOut / deleteAccount so the resolve runs fresh anon
     * sign-in for the next consumer (token request, retry button, etc.).
     */
    suspend fun invalidate(): Unit = mutex.withLock {
        logger.d { "invalidate: clearing memoized outcome" }
        memoized = null
    }

    private suspend fun resolveLocked(): BootstrapOutcome {
        repeat(MaxResolveAttempts) { attempt ->
            val step = Catching { resolveOnce(attempt) }
                .fold(onSuccess = { it }, onFailure = { ResolveStep.Failed(it) })
            when (step) {
                is ResolveStep.Settled -> return step.outcome
                ResolveStep.Advanced, ResolveStep.Transient -> Unit
                is ResolveStep.Failed -> {
                    logger.w(step.cause) { "Resolve failed at attempt ${attempt + 1}" }
                    return BootstrapOutcome.Failed(step.cause)
                }
            }
        }
        logger.w { "Resolve exhausted $MaxResolveAttempts attempts without settling" }
        return BootstrapOutcome.Failed(cause = null)
    }

    private suspend fun resolveOnce(attempt: Int): ResolveStep {
        gateway.awaitInitialization()
        val status = gateway.currentStatus()
        logger.d { "Resolve attempt ${attempt + 1}/$MaxResolveAttempts; status=${status::class.simpleName}" }
        return when (status) {
            AuthGatewayStatus.Authenticated -> {
                val session = gateway.currentSession()
                    ?: error("Authenticated status but no session in gateway")
                ResolveStep.Settled(
                    BootstrapOutcome.Authenticated(
                        userId = session.userId,
                        isAnonymous = session.isAnonymous,
                        email = session.email,
                    ),
                )
            }
            AuthGatewayStatus.NotAuthenticated -> {
                gateway.signInAnonymously()
                ResolveStep.Advanced
            }
            AuthGatewayStatus.Initializing,
            is AuthGatewayStatus.RefreshFailure -> ResolveStep.Transient
        }
    }

    private sealed interface ResolveStep {
        data class Settled(val outcome: BootstrapOutcome.Authenticated) : ResolveStep
        data object Advanced : ResolveStep
        data object Transient : ResolveStep
        data class Failed(val cause: Throwable) : ResolveStep
    }
}

/**
 * What [AuthBootstrap.awaitResolved] produced. [Authenticated] carries the
 * fields the auth repo projects onto its public `AuthState`; [Failed]
 * carries the cause (network, anon-disabled-at-project-level, exhausted
 * retries) so the repo can render a real diagnostic.
 */
sealed interface BootstrapOutcome {
    data class Authenticated(
        val userId: String,
        val isAnonymous: Boolean,
        val email: String?,
    ) : BootstrapOutcome

    data class Failed(val cause: Throwable?) : BootstrapOutcome
}

private const val MaxResolveAttempts: Int = 5
