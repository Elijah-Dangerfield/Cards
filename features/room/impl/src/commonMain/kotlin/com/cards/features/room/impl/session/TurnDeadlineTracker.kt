package com.dangerfield.cards.features.room.impl.session

import com.dangerfield.cards.libraries.gameplay.GameState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Derives a stable absolute deadline (epoch ms) for the seat currently on the
 * clock, so the on-table countdown ring can render "time left" rather than
 * restarting a fixed-duration animation every time the play screen re-enters
 * composition (MP-33 — tapping stats and coming back used to reset the ring).
 *
 * One instance per MP session, held by [RemotePokerSessionFactory] so it
 * outlives any single composition. The deadline is stamped once, the first time
 * a given turn (`handNumber to lastSequence`) is seen, and reused for every
 * later projection of that same turn — so a snapshot that doesn't move the
 * action (an emote, a stack refresh) never nudges the deadline. It re-stamps the
 * moment the action moves, exactly like the server re-arms its own timeout.
 *
 * Client-derived on purpose: the server stays authoritative on the real auto-act
 * timeout ([com.dangerfield.cards.libraries.gameplay.RoomSettings.turnTimerSeconds]
 * enforced by `TurnTimerDriver`); this only anchors the *visual* ring so it
 * survives navigation. Only a human seat on a timer-enforced table gets a
 * deadline — bots aren't timed, and no acting seat (between hands / complete)
 * clears it.
 */
@OptIn(ExperimentalTime::class)
class TurnDeadlineTracker(private val clock: Clock) {

    private var armedTurn: Pair<Int, Long>? = null
    private var armedDeadlineEpochMs: Long? = null

    fun deadlineFor(state: GameState): Long? {
        val acting = state.actingSeatIndex ?: return clear()
        val timerSeconds = state.settings.turnTimerSeconds
        if (timerSeconds <= 0) return clear()
        val seat = state.seats.firstOrNull { it.index == acting } ?: return clear()
        if (seat.isBot) return clear()

        val turn = state.handNumber to state.lastSequence
        if (turn != armedTurn) {
            armedTurn = turn
            armedDeadlineEpochMs = clock.now().toEpochMilliseconds() + timerSeconds * 1_000L
        }
        return armedDeadlineEpochMs
    }

    private fun clear(): Long? {
        armedTurn = null
        armedDeadlineEpochMs = null
        return null
    }
}
