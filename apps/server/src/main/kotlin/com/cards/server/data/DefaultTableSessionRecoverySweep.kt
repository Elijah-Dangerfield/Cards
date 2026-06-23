package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.TableSessionRecoverySweep
import com.dangerfield.cards.server.domain.TableSessionRepository
import com.dangerfield.cards.server.domain.TableSessionService
import com.dangerfield.cards.server.game.SessionSnapshotStore
import com.dangerfield.cards.server.game.stackFor
import me.tatarka.inject.annotations.Inject
import org.slf4j.LoggerFactory
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Cash out every still-open [com.cards.server.domain.TableSession] from
 * its room's durable snapshot. Run once at boot (see `Application.module`):
 * after a restart all in-memory rooms are gone, so an open session is
 * abandoned and its player's chips must be refunded.
 *
 * Each settlement is wrapped in [Catching] so one bad row can't abort the
 * whole sweep, and the underlying cash-out is keyed + status-gated, so a
 * crash partway through (or a second sweep) never double-credits.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class DefaultTableSessionRecoverySweep(
    private val tableSessions: TableSessionRepository,
    private val tableSessionService: TableSessionService,
    private val snapshots: SessionSnapshotStore,
) : TableSessionRecoverySweep {

    override suspend fun sweepAbandonedSessions(): Int {
        val active = tableSessions.listActive()
        if (active.isEmpty()) return 0
        log.info("Recovering {} abandoned table session(s)", active.size)
        var settled = 0
        for (session in active) {
            Catching {
                // No snapshot seat (never played a hand, or no snapshot) →
                // null lets cashOut refund the full funded amount.
                val finalStack = snapshots.readByCode(session.roomCode)?.state?.stackFor(session.userId)
                tableSessionService.cashOut(session.userId, finalStack)
                settled++
            }.onFailure {
                log.warn(
                    "Recovery cash-out failed for table session {} (user {})",
                    session.sessionId,
                    session.userId.value,
                    it,
                )
            }
        }
        return settled
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DefaultTableSessionRecoverySweep::class.java)
    }
}
