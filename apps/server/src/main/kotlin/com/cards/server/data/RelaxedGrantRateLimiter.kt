package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A best-effort throttle on grant-on-replay: the relaxed grants that credit a
 * StoreKit-replayed, account-mismatched receipt to the current caller (see
 * `docs/wiki/purchases.md`). It exists to make abuse visible and bounded, NOT
 * to guarantee correctness — idempotency on the store transaction id is what
 * actually prevents a receipt from ever granting twice. A legitimate user
 * recovering a handful of stuck purchases stays well under the cap; a caller
 * relaxing far more receipts than that in a day is the anomaly worth refusing.
 *
 * In-memory and per-user. The server is single-writer (one instance holds the
 * Postgres advisory lock), so a process-local window is consistent for the live
 * fleet; it resets on redeploy, which is acceptable for a throttle whose backstop
 * is idempotency and whose durable audit trail is the billing-events record.
 */
@SingleIn(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class RelaxedGrantRateLimiter(private val clock: Clock) {

    private val recentByUser = HashMap<UserId, ArrayDeque<Instant>>()

    /**
     * Record a relaxed grant for [userId] and report whether it is within the
     * cap. Returns false (and records nothing) once [MAX_PER_WINDOW] relaxed
     * grants have landed inside the trailing [WINDOW].
     */
    fun tryAcquire(userId: UserId): Boolean = synchronized(recentByUser) {
        val now = clock.now()
        val cutoff = now - WINDOW
        val stamps = recentByUser.getOrPut(userId) { ArrayDeque() }
        while (stamps.isNotEmpty() && stamps.first() < cutoff) stamps.removeFirst()
        if (stamps.size >= MAX_PER_WINDOW) return false
        stamps.addLast(now)
        true
    }

    internal companion object {
        val WINDOW = 24.hours
        const val MAX_PER_WINDOW = 10
    }
}
