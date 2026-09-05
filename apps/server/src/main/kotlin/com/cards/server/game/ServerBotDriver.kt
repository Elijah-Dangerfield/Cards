package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.bots.BotDecision
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.bots.OpponentTracker
import com.dangerfield.cards.libraries.bots.buildHandContextFromState
import com.dangerfield.cards.libraries.bots.toBotDifficulty
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.StakeTier
import com.dangerfield.cards.server.domain.BotSeat
import com.dangerfield.cards.server.plugins.SpanAttrs
import com.dangerfield.cards.server.plugins.withRootSpan
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
 *
 * The driver also owns the **universal between-hands beat**: when a hand completes
 * and the table can deal another, it holds the deal for [nextHandBeatMs], announces
 * the deadline on [GameSession.nextHandEvents] so clients render an honest
 * countdown, then deals exactly when the beat elapses. Every table with at least
 * one human and two chip-holders auto-advances this way (private host-run tables
 * included — they lost manual pacing in the play-screen overhaul); an all-bot
 * table still idles rather than simulate to an empty room.
 */
@OptIn(ExperimentalTime::class)
class ServerBotDriver(
    private val session: GameSession,
    private val scope: CoroutineScope,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
    private val equityIterations: Int = 200,
    private val thinkDelay: (BotPersonality, BotThought, Random) -> Long = ::serverThinkDelayMs,
    // Wall clock, used to stamp the next-hand deadline broadcast to clients. The
    // client ticks its countdown against the same instant the driver deals at, so
    // the countdown can never be a lie the server deals under.
    private val clock: Clock = Clock.System,
    // The between-hands beat: how long the table holds before dealing the next
    // hand, so the human sees the showdown / result first and can leave with their
    // winnings before being dealt back in. Config (8s, tune). Tests shrink it so a
    // multi-hand run isn't gated on real seconds per boundary.
    private val nextHandBeatMs: Long = 8_000,
    // One opponent read per session, fed the engine event stream and passed into
    // every bot decision so MP bots adapt to the humans they actually face — the
    // same adaptive layer solo already had (MP-32). Injectable so a test can assert
    // the read the driver built up.
    private val opponentTracker: OpponentTracker = OpponentTracker(),
) {
    // playerId -> bot truth. Mutated only from the single collector coroutine
    // (drive loop) and from updateRoster; updateRoster runs before/around
    // startHand on the registry path, the drive loop reads it — a plain map
    // guarded by @Volatile-style single-writer discipline is enough since
    // assignments are whole-map merges, not in-place edits.
    @Volatile
    private var roster: Map<String, BotSeat> = emptyMap()

    // True while a next-hand countdown has been announced to clients and not yet
    // cancelled. Mutated only from the single collector coroutine (drive loop), so
    // a plain field is enough. Guards the Cleared emit so we only announce a
    // cancellation for a countdown we actually opened.
    private var nextHandArmed: Boolean = false

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
            // Feed the opponent read from the event stream on its own child job.
            // A one-decision lag between an action and the read reflecting it is
            // fine — opponent modeling is inherently historical.
            launch { session.events.collect { opponentTracker.observe(it.event) } }
            session.state.collectLatest { state -> state?.let { drive(it) } }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun drive(state: GameState) {
        if (state.street == BettingRound.Complete) {
            maybeScheduleNextHand(state)
            return
        }
        // A live hand is in progress — any countdown we'd announced is moot (the
        // next hand already dealt, or the table un-completed). Clear it so a stale
        // "Next hand in 0:0X" never lingers on the felt.
        clearNextHandCountdownIfArmed()
        val acting = state.actingSeatIndex ?: return
        val seat = state.seats.firstOrNull { it.index == acting } ?: return
        if (!seat.isBot || !seat.canAct) return
        val playerId = seat.playerId ?: return
        if (seat.holeCards.size != 2) return // pre-deal / odd state — let the engine settle.

        val botSeat = roster[playerId] ?: fallbackBotSeat(playerId, acting, state).also {
            roster = roster + (playerId to it)
        }

        val handContext = buildHandContextFromState(state, acting)
        val decision = withContext(cpuDispatcher) {
            BotDecision.choose(
                state = state,
                seatIndex = acting,
                personality = botSeat.personality,
                difficulty = botSeat.difficulty,
                opponentTracker = opponentTracker,
                random = random,
                equityIterations = equityIterations,
                handContext = handContext,
            )
        }

        // Humanlike pause. collectLatest cancels this the moment the table state
        // moves on, so a stale timer never fires an outdated action.
        delay(thinkDelay(botSeat.personality, decision.thought, random))

        val nonce = "bot:${session.id}:${state.handNumber}:$acting:${state.street}"
        // Rooted: a bot's turn is its own unit of work, exactly like a human's
        // submit_intent. Without this the stages underneath it inherit whatever
        // context happens to be on the shared driver dispatcher, or none at all,
        // and surface in Tempo as parentless orphans.
        val result = withRootSpan(
            name = "bot_action",
            configure = {
                setAttribute(SpanAttrs.IntentType, decision.intent::class.simpleName ?: "Unknown")
                setAttribute(SpanAttrs.SessionId, session.id.toString())
            },
        ) {
            session.applyIntent(playerId, decision.intent, nonce)
        }
        if (result is IntentResult.Rejected) {
            // Expected only on a benign race (state already advanced). Log at
            // debug so a genuinely wedged bot is still discoverable.
            log.debug("bot intent rejected for seat {} in hand {}: {}", acting, state.handNumber, result.reason)
        }
    }

    /**
     * Universal between-hands beat. When a hand completes and the table can deal
     * another, hold the deal for [nextHandBeatMs] and announce the deadline on
     * [GameSession.nextHandEvents] so clients render an honest "Next hand in 0:0X"
     * countdown (and, on a real-chip table, can leave with their winnings the whole
     * window) — then deal exactly when the beat elapses. The lowest-seat chip-holder
     * stands in as the advancer; `requestNextHand` is server-internal and
     * userId-agnostic, so a bot or a human is equally valid here.
     *
     * `collectLatest` cancels the pending [delay] the instant a newer state arrives
     * (a rebuy, a leave, a manual tap on a practice table), and `requestNextHand`
     * re-checks `street == Complete` + dedupes by nonce, so a benign race is a no-op.
     *
     * Every table with a human and two chip-holders auto-advances — public, open,
     * and **private host-run** alike (private tables traded manual pacing for one
     * simple lifecycle in the play-screen overhaul). Counted toward the two-with-chips
     * gate is any mid-hand joiner waiting in the session queue (CARDS-24), so a table
     * that drops to a lone survivor still deals when a searcher is waiting to fill it.
     *
     * Still deliberately NOT advanced:
     *  - **All-bot** — a table with no human left (the lone human quit a bot
     *    fallback table, or every human dropped from a private bot room) goes
     *    idle instead of simulating hands forever to an empty room.
     *  - **Single survivor, no joiner** — a heads-up bust leaves one chip-holder;
     *    that's the rebuy-grace window's job ([MatchOverGraceDriver]), not ours.
     */
    private suspend fun maybeScheduleNextHand(state: GameState) {
        val seated = state.seats.filter { it.playerId != null }
        if (seated.none { !it.isBot }) { clearNextHandCountdownIfArmed(); return } // all-bot: idle
        val seatsWithChips = seated.filter { it.stack > 0 }
        // A mid-hand joiner sits in the session's pending queue, not in [state.seats],
        // and is folded into the deal by [requestNextHand]. Count them toward the
        // "enough players to deal" gate: a table that drops to a single seated player
        // with chips must still advance when a joiner is waiting to fill it (CARDS-24).
        val pendingJoiners = session.pendingJoinerIds.count { it !in seatsWithChips.mapNotNull { seat -> seat.playerId } }
        if (seatsWithChips.size + pendingJoiners < 2) { clearNextHandCountdownIfArmed(); return }
        val advancer = seatsWithChips.minByOrNull { it.index } ?: run { clearNextHandCountdownIfArmed(); return }

        nextHandArmed = true
        session.emitNextHandEvent(
            NextHandEvent.Pending(deadlineEpochMs = clock.now().toEpochMilliseconds() + nextHandBeatMs),
        )
        delay(nextHandBeatMs)

        nextHandArmed = false
        val result = withRootSpan(
            name = "bot_next_hand",
            configure = { setAttribute(SpanAttrs.SessionId, session.id.toString()) },
        ) {
            session.requestNextHand(advancer.playerId!!, "next:${session.id}:${state.handNumber}")
        }
        // A rejection here means the deal didn't happen and no fresh hand snapshot
        // will arrive to clear the client countdown (e.g. a race emptied the table).
        // Announce the cancellation so the felt countdown doesn't hang at 0:00.
        if (result is IntentResult.Rejected) session.emitNextHandEvent(NextHandEvent.Cleared)
    }

    private fun clearNextHandCountdownIfArmed() {
        if (!nextHandArmed) return
        nextHandArmed = false
        session.emitNextHandEvent(NextHandEvent.Cleared)
    }

    /**
     * Personality for a bot seat the roster doesn't know about — only happens
     * after a server restart hydrates a session whose room (and its personality
     * assignments) is gone. Deterministic by seat so the same revived hand reads
     * consistently for its remaining life. Difficulty is recovered from the
     * table's stakes (the buy-in is the starting stack) so a revived High/Premium
     * bot stays sharp rather than dropping to Standard (MP-33).
     */
    private fun fallbackBotSeat(playerId: String, seatIndex: Int, state: GameState): BotSeat = BotSeat(
        personality = BotPersonality.Roster[seatIndex % BotPersonality.Roster.size],
        difficulty = StakeTier.fromBuyIn(state.settings.startingStack).toBotDifficulty(),
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
 * avoid pulling the client's `GameSpeed` plumbing onto the server. (Mirroring the
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
