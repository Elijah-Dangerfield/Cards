package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Enforces the per-turn time limit for every **human** seat in one [GameSession].
 * A player who sits on their action past [com.dangerfield.cards.libraries.gameplay.RoomSettings.turnTimerSeconds]
 * is auto-acted so the table never stalls: auto-check when checking is legal
 * (the seat faces no outstanding bet), otherwise auto-fold.
 *
 * One instance per session, owned by [DefaultGameSessionRegistry] (created with
 * the session, cancelled in `end`). Mirrors [ServerBotDriver]'s shape — observe
 * [GameSession.state], react when a seat is on the clock — but the two never
 * collide: the bot driver acts only on bot seats, this driver only on human
 * seats. Bots already act within a couple of seconds via the bot driver, so the
 * timer would only ever fire on a genuinely wedged bot; leaving them out keeps
 * the two paths from racing for the same nonce.
 *
 * Correctness:
 *  - **No stale fire.** `collectLatest` cancels the pending [delay] the instant a
 *    newer state arrives (the player acted, the street advanced, anyone joined),
 *    so a timer armed for a turn that's already over never submits.
 *  - **Deterministic nonce** (no clock / RNG): `timeout:<session>:<hand>:<seat>:<street>`.
 *    A re-arm of the same decision point produces the same nonce, which the
 *    session's nonce ring swallows — and [GameSession.applyIntent] independently
 *    re-checks `actingSeatIndex`, so a stale apply is a no-op even in a race.
 *  - **Authoritative source.** Reads the unscrubbed [GameState] the session holds,
 *    so hole cards and contributions are always present (scrubbing only happens
 *    at the socket layer).
 */
class TurnTimerDriver(
    private val session: GameSession,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            session.state.collectLatest { state -> state?.let { armFor(it) } }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun armFor(state: GameState) {
        if (state.street == BettingRound.Complete) return
        val acting = state.actingSeatIndex ?: return
        val seat = state.seats.firstOrNull { it.index == acting } ?: return
        // Humans only — bots are the bot driver's job; racing them for the same
        // turn would buy nothing but a duplicate (deduped) apply.
        if (seat.isBot || !seat.canAct) return
        if (seat.holeCards.size != 2) return // pre-deal / odd state — let the engine settle.

        val timeoutMs = state.settings.turnTimerSeconds.toLong() * 1_000L
        if (timeoutMs <= 0) return
        delay(timeoutMs)

        // Auto-check when the seat owes nothing to the current bet, else fold.
        // Mirrors the engine's own check rule (contributedThisStreet == currentBet).
        val canCheck = seat.contributedThisStreet >= state.currentBetThisStreet
        val intent: PlayerIntent =
            if (canCheck) PlayerIntent.Check(acting) else PlayerIntent.Fold(acting)
        val playerId = seat.playerId ?: return
        val nonce = "timeout:${session.id}:${state.handNumber}:$acting:${state.street}"

        val result = session.applyIntent(playerId, intent, nonce)
        if (result is IntentResult.Rejected) {
            // Expected only on a benign race (the player acted just as the timer
            // fired). Debug so a genuinely wedged seat is still discoverable.
            log.debug(
                "turn-timeout intent rejected for seat {} in hand {}: {}",
                acting,
                state.handNumber,
                result.reason,
            )
        } else {
            log.info("auto-acted seat {} ({}) on turn-timeout in hand {}", acting, intent::class.simpleName, state.handNumber)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(TurnTimerDriver::class.java)
    }
}
