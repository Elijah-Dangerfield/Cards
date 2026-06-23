package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomClosedListener
import com.dangerfield.cards.server.domain.TableSessionService
import me.tatarka.inject.annotations.Inject
import org.slf4j.LoggerFactory
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The one [RoomClosedListener]: when a room is torn down, settle every seated
 * human's escrow and then end the game session so nothing tied to the room
 * outlives it.
 *
 *  1. **Cash out each human** at their live table stack (the room's last seated
 *     member who triggered teardown, plus anyone still sitting). Bots are
 *     skipped — they hold no wallet, only an engine stack. A human who was never
 *     funded, or already cashed out on the way down, resolves to no active
 *     session and is a no-op; the credit is keyed, so this can't double-pay. The
 *     stack is read from the live session before it's ended; a null stack (no
 *     hand was ever dealt) refunds the full funded buy-in.
 *  2. **End the session** ([GameSessionRegistry.end]) — cancels the per-session
 *     bot + turn-timer coroutines, drops the in-memory session, deletes the
 *     durable snapshot. Without this every table that dealt a hand leaked a live
 *     session (and a recycled room code could rehydrate a stranger's hand).
 *
 * Best-effort throughout: a failure is logged, never thrown — the room is
 * already gone from the registry by the time we run, and the boot recovery sweep
 * is the backstop that re-settles any session we couldn't close here.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class RoomTeardownCoordinator(
    private val gameSessions: GameSessionRegistry,
    private val tableSessions: TableSessionService,
) : RoomClosedListener {

    private val log = LoggerFactory.getLogger(RoomTeardownCoordinator::class.java)

    override suspend fun onRoomClosed(room: Room) {
        // Read live stacks BEFORE ending the session (end() drops it).
        val state = gameSessions.peek(room.code)?.state?.value
        for (member in room.members) {
            if (member.bot != null) continue
            val stack = state?.stackFor(member.userId)
            Catching { tableSessions.cashOut(member.userId, stack) }
                .onFailure { log.warn("Teardown cash-out failed for ${member.userId.value} in room ${room.code}", it) }
        }
        Catching { gameSessions.end(room.code) }
            .onFailure { log.warn("Failed to end session for closed room ${room.code}", it) }
    }
}
