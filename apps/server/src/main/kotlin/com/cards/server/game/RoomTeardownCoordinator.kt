package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.HandParticipation
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
        val session = gameSessions.peek(room.code)

        // Resolve any still-live hand BEFORE reading stacks so committed chips have
        // settled into the seats. Folding out the non-all-in seats lets the hand
        // complete: an all-in seat keeps its showdown right and wins the run-out, so
        // its committed chips land in its stack rather than being read as a mid-hand
        // 0 and lost. This is the both-clients-vanished case (MP-17) — normally the
        // last leaver's forfeit already completed the hand, so this is a no-op.
        resolveLiveHand(session)

        // Read live stacks BEFORE ending the session (end() drops it).
        val state = session?.state?.value

        // Cash out everyone still holding escrow for this room — seated members AND
        // any player who left mid-hand all-in and is awaiting deferred settlement
        // (their session stays open until now). `activeUsersInRoom` is the complete
        // funded set; never-funded members simply aren't in it. Bots hold no wallet,
        // so they never have a session here.
        for (userId in tableSessions.activeUsersInRoom(room.code)) {
            // Live seat stack, else the stack they settled with last hand (0 for a
            // busted-and-dropped player). Falling through to a full-escrow refund
            // would mint a busted player's lost stake (MP-13); null only when no
            // hand was ever dealt.
            val stack = state?.stackFor(userId)
                ?: session?.lastKnownStack(userId.value.toString())
            Catching { tableSessions.cashOut(userId, stack) }
                .onFailure { log.warn("Teardown cash-out failed for ${userId.value} in room ${room.code}", it) }
        }
        Catching { gameSessions.end(room.code) }
            .onFailure { log.warn("Failed to end session for closed room ${room.code}", it) }
    }

    /**
     * Drive a still-live hand to completion by folding out each seat that's still
     * in the hand but not all-in. Each forfeit may complete the hand (a sole
     * remaining contender wins), so re-check after every one. All-in seats are left
     * untouched — they're entitled to the showdown and win the run-out, so their
     * committed chips settle into their stack. Idempotent + best-effort.
     */
    private suspend fun resolveLiveHand(session: GameSession?) {
        session ?: return
        val live = session.state.value ?: return
        if (live.street == BettingRound.Complete) return
        for (seat in live.seats) {
            if (session.state.value?.street == BettingRound.Complete) break
            if (seat.handParticipation == HandParticipation.InHand && seat.playerId != null) {
                Catching { session.forfeitSeat(seat.playerId!!) }
                    .onFailure { log.warn("Teardown forfeit failed for seat ${seat.index} in room ${session.id}", it) }
            }
        }
    }
}
