package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Resolves the heads-up dead-end: when a hand finishes with one player busted and
 * unable (or unwilling) to rebuy, the table can't deal a next hand, and without a
 * terminal path both players idle on "waiting for opponent to rebuy or leave"
 * forever (CARDS-3S / MP-14).
 *
 * One instance per session, owned by [DefaultGameSessionRegistry] (created with
 * the session, cancelled in `end`). Mirrors [TurnTimerDriver]: observe
 * [GameSession.state], react when the table sits in the terminal condition, and
 * use `collectLatest` so the pending [delay] is cancelled the instant a newer
 * state arrives (a rebuy, a leave, the next hand) — a window armed for a table
 * that recovered never fires.
 *
 * The driver only *detects* and *announces* via [GameSession.matchOverEvents];
 * flipping the room to finished, cashing the winner out, and routing clients off
 * is the socket layer's job, which owns the room + the fan-out.
 */
@OptIn(ExperimentalTime::class)
class MatchOverGraceDriver(
    private val session: GameSession,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
) {
    private var job: Job? = null
    private var graceArmed = false

    fun start() {
        if (job != null) return
        job = scope.launch {
            session.state.collectLatest { state -> state?.let { evaluate(it) } }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun evaluate(state: GameState) {
        val survivor = soleChipHolder(state)
        if (survivor == null) {
            // Table can continue (or a hand is live) — clear any countdown we'd announced.
            if (graceArmed) {
                graceArmed = false
                session.emitMatchOverEvent(MatchOverEvent.GraceCancelled)
            }
            return
        }

        val terminal = terminalBust(state)
        if (terminal == null) {
            // One chip-holder left but nobody who could rebuy: every busted seat
            // is a bot (never offered a rebuy window). Unless a mid-hand joiner is
            // queued to refill the table (the bot driver deals them in, CARDS-24),
            // there is nothing to wait for — resolve outright. Without this the
            // table sat in a dead zone: no rebuy countdown, no next hand, the
            // survivor wedged (MP-37).
            if (session.pendingJoinerIds.isNotEmpty()) return
            graceArmed = false
            session.emitMatchOverEvent(MatchOverEvent.Resolved(winnerUserId = survivor.playerId!!))
            log.info("match over for session {} — sole chip-holder {} (bot bust)", session.id, survivor.playerId)
            return
        }

        graceArmed = true
        session.emitMatchOverEvent(
            MatchOverEvent.GraceStarted(
                deadlineEpochMs = clock.now().toEpochMilliseconds() + graceMillis,
                bustedSeatIndex = terminal.bustedSeatIndex,
                winnerSeatIndex = terminal.winnerSeatIndex,
            ),
        )
        delay(graceMillis)

        // `collectLatest` cancels this block the moment a newer state arrives, so
        // reaching here means the table sat terminal for the whole window. Re-read
        // the live state and re-check rather than trusting the captured snapshot.
        val live = session.state.value ?: return
        val stillTerminal = terminalBust(live) ?: return
        graceArmed = false
        session.emitMatchOverEvent(MatchOverEvent.Resolved(winnerUserId = stillTerminal.winnerUserId))
        log.info("match over for session {} — winner {}", session.id, stillTerminal.winnerUserId)
    }

    /**
     * The seat left standing when the table can't continue: a finished hand with
     * exactly one seated player holding chips. Null while a hand is live or two
     * or more seats still have chips (the table deals on).
     */
    private fun soleChipHolder(state: GameState) =
        if (state.street != BettingRound.Complete) {
            null
        } else {
            state.seats.filter { it.playerId != null && it.stack > 0 }.singleOrNull()
        }

    /**
     * The graceful terminal condition: a [soleChipHolder] plus at least one busted
     * seat belonging to a human who could rebuy — that human gets the rebuy-grace
     * window. Null when the only busted seats are bots: a busted bot never rebuys,
     * so [evaluate] resolves that shape outright instead of counting down.
     */
    private fun terminalBust(state: GameState): Terminal? {
        val winner = soleChipHolder(state) ?: return null
        val bustedHuman = state.seats
            .firstOrNull { it.playerId != null && !it.isBot && it.stack <= 0 }
            ?: return null
        return Terminal(
            winnerUserId = winner.playerId!!,
            winnerSeatIndex = winner.index,
            bustedSeatIndex = bustedHuman.index,
        )
    }

    private data class Terminal(
        val winnerUserId: String,
        val winnerSeatIndex: Int,
        val bustedSeatIndex: Int,
    )

    companion object {
        const val DEFAULT_GRACE_MILLIS = 60_000L
        private val log = LoggerFactory.getLogger(MatchOverGraceDriver::class.java)
    }
}
