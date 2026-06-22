package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.bots.BotDecision
import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.bots.buildHandContextFromState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.server.domain.BotSeat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Drives every bot seat in one [GameSession] entirely server-side. Nothing on
 * the client is involved: the driver observes [GameSession.state] and, whenever
 * the acting seat is a bot, computes a move with the shared [BotDecision] engine
 * and submits it via the session's own (userId-agnostic) [GameSession.applyIntent].
 *
 * One instance per session, owned by [DefaultGameSessionRegistry] (created with
 * the session, cancelled in `end`). The registry seeds the per-bot personality
 * roster from the [SeatOccupant]s at each `startHand` via [updateRoster]; the
 * roster is keyed by the stable bot userId and persists across hands (a
 * `requestNextHand` rebuilds occupants from the engine seats, which carry
 * `isBot` but not personality).
 *
 * Correctness:
 *  - **No double-acting.** `collectLatest` cancels an in-flight decision the
 *    instant a newer state arrives, and [GameSession.applyIntent] independently
 *    re-checks `actingSeatIndex` and dedupes by nonce — so a stale apply is a
 *    no-op even in the unlikely race.
 *  - **Deterministic nonces** (no clock / RNG): `bot:<session>:<hand>:<seat>:<street>`.
 *    A re-emission of the same decision point produces the same nonce, which the
 *    session's nonce ring swallows.
 *  - **Restart survival.** After a hydrate the roster is empty (personalities
 *    aren't part of [GameState]); the driver lazily assigns a deterministic
 *    fallback personality to any bot seat it doesn't recognize, so bots keep
 *    playing. The exact personality may differ from before the restart — an
 *    accepted trade vs. a schema change for a rare mid-hand restart.
 */
class ServerBotDriver(
    private val session: GameSession,
    private val scope: CoroutineScope,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
    private val equityIterations: Int = 200,
    private val thinkDelay: (BotPersonality, BotThought, Random) -> Long = ::serverThinkDelayMs,
    private val nextHandDelayMs: Long = 1_200,
) {
    // playerId -> bot truth. Mutated only from the single collector coroutine
    // (drive loop) and from updateRoster; updateRoster runs before/around
    // startHand on the registry path, the drive loop reads it — a plain map
    // guarded by @Volatile-style single-writer discipline is enough since
    // assignments are whole-map merges, not in-place edits.
    @Volatile
    private var roster: Map<String, BotSeat> = emptyMap()

    private var job: Job? = null

    /** Merge the bot occupants of a fresh hand into the roster (idempotent). */
    fun updateRoster(occupants: List<SeatOccupant>) {
        val additions = occupants.mapNotNull { occ -> occ.bot?.let { occ.userId to it } }
        if (additions.isEmpty()) return
        roster = roster + additions
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            session.state.collectLatest { state -> state?.let { drive(it) } }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun drive(state: GameState) {
        if (state.street == BettingRound.Complete) {
            maybeAdvanceAllBotTable(state)
            return
        }
        val acting = state.actingSeatIndex ?: return
        val seat = state.seats.firstOrNull { it.index == acting } ?: return
        if (!seat.isBot || !seat.canAct) return
        val playerId = seat.playerId ?: return
        if (seat.holeCards.size != 2) return // pre-deal / odd state — let the engine settle.

        val botSeat = roster[playerId] ?: fallbackBotSeat(playerId, acting).also {
            roster = roster + (playerId to it)
        }

        val handContext = buildHandContextFromState(state, acting)
        val decision = withContext(cpuDispatcher) {
            BotDecision.choose(
                state = state,
                seatIndex = acting,
                personality = botSeat.personality,
                difficulty = botSeat.difficulty,
                random = random,
                equityIterations = equityIterations,
                handContext = handContext,
            )
        }

        // Humanlike pause. collectLatest cancels this the moment the table state
        // moves on, so a stale timer never fires an outdated action.
        delay(thinkDelay(botSeat.personality, decision.thought, random))

        val nonce = "bot:${session.id}:${state.handNumber}:$acting:${state.street}"
        val result = session.applyIntent(playerId, decision.intent, nonce)
        if (result is IntentResult.Rejected) {
            // Expected only on a benign race (state already advanced). Log at
            // debug so a genuinely wedged bot is still discoverable.
            log.debug("bot intent rejected for seat {} in hand {}: {}", acting, state.handNumber, result.reason)
        }
    }

    /**
     * When a hand completes at a table with NO human seated, no client will tap
     * "next hand" — so the lowest-seat bot with chips advances it. A mixed table
     * leaves the advance to the human (preserves the normal UX). Terminates
     * naturally: once fewer than two bots have chips the session won't start
     * another hand.
     */
    private suspend fun maybeAdvanceAllBotTable(state: GameState) {
        val seated = state.seats.filter { it.playerId != null }
        if (seated.any { !it.isBot }) return
        val withChips = seated.filter { it.isBot && it.stack > 0 }
        if (withChips.size < 2) return
        val advancer = withChips.minByOrNull { it.index } ?: return
        delay(nextHandDelayMs)
        session.requestNextHand(advancer.playerId!!, "bot-next:${session.id}:${state.handNumber}")
    }

    /**
     * Personality for a bot seat the roster doesn't know about — only happens
     * after a server restart hydrates a session whose room (and its personality
     * assignments) is gone. Deterministic by seat so the same revived hand reads
     * consistently for its remaining life.
     */
    private fun fallbackBotSeat(playerId: String, seatIndex: Int): BotSeat = BotSeat(
        personality = BotPersonality.Roster[seatIndex % BotPersonality.Roster.size],
        difficulty = BotDifficulty.Standard,
        revealed = true,
    ).also {
        log.info("Assigned fallback personality {} to unrostered bot {}", it.personality.name, playerId)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ServerBotDriver::class.java)
    }
}

/**
 * Humanlike think time for a server bot. Real players don't act on a fixed
 * cadence, so neither do bots: the base scales with the decision's difficulty
 * and the personality, then every pause gets per-decision jitter plus a chance
 * of a quick **snap** (obvious spot, reacted instantly) or a long **tank**
 * (weighted toward genuinely close spots). The spread matters most for stealth
 * bots a human reads as a real opponent — a metronome gives them away.
 *
 * A deliberately simpler cousin of the client's `BotTiming`; kept standalone to
 * avoid pulling the client's `BotSpeed` plumbing onto the server. (Mirroring the
 * table's actual pace — speeding up when humans are snappy — is a future lift.)
 */
internal fun serverThinkDelayMs(
    personality: BotPersonality,
    thought: BotThought,
    random: Random,
): Long {
    // Closeness of the call decision (strength ≈ pot odds) stretches the pause.
    val closeness = 1.0 - kotlin.math.abs(thought.handStrength - thought.potOdds).coerceIn(0.0, 1.0)
    // Base 500..1600ms by difficulty; tight bots dwell longer, aggressive snap.
    val base = 500.0 + closeness * 1_100.0
    val personalityFactor = 0.85 + personality.tightness * 0.4 - personality.aggression * 0.2
    var ms = base * personalityFactor

    when {
        // ~15% snap: an obvious decision answered without deliberation.
        random.nextDouble() < 0.15 -> ms *= 0.35
        // ~8% tank: a long think, amplified on genuinely close spots.
        random.nextDouble() > 0.92 -> ms *= 2.2 + closeness * 1.8
    }
    // Per-decision jitter so two identical spots never time the same.
    ms *= 0.8 + random.nextDouble() * 0.5

    return ms.toLong().coerceIn(300, 6_000)
}
