package com.dangerfield.cards.features.rooms.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.rooms.CandidatesOutcome
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.FindTableOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.MatchmakingRepository
import com.dangerfield.cards.libraries.rooms.PlayBotsOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.SubsidyBudgetOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Drives the public "Find a table" search — honesty-first matchmaking.
 *
 * Lifecycle:
 *  1. On [PublicSearchingAction.Start] it asks the matchmaker to seat the user
 *     via the server's atomic find-or-create ([beginSearch]) — no manual table
 *     chooser (MP-35): two people searching at nearly the same time must end up
 *     playing without either of them tapping anything. The server picks the
 *     best eligible table (most humans, oldest, affordable, one-tier rescue) or
 *     opens a fresh one; real humans only — a find never seats a bot.
 *  2. Seated with players already there ([FindTableOutcome.Success.created]
 *     false) → the pre-deal lobby ([SearchPhase.Joined]) showing the seat grid.
 *     Seated alone in a fresh table → the genuine waiting radar, plus the
 *     ROOM-12 wait-time candidates poll so two simultaneous creates still
 *     consolidate onto one table.
 *  3. It opens the room socket and watches who's seated. [PublicSearchingState.realPlayersFound]
 *     counts *other connected humans*, so the screen can honestly say "still
 *     looking" vs "someone joined". The instant the server deals the first hand
 *     (room flips to Playing) we hand off to the live table.
 *  4. If the long search window ([SEARCH_WINDOW]) elapses with nobody else here,
 *     we surface the honest disclosed-bot offer rather than force it: "we
 *     couldn't find anyone — play bots for real, and they'll step aside the
 *     moment a real player shows up."
 *
 * Resilience: if the table is GC'd mid-search (e.g. a server restart), the old
 * room code is dead, so we silently re-find rather than trying to reconnect to a
 * ghost. Cancelling / giving up leaves the seat via the [AppCoroutineScope] so
 * the server is notified even as we navigate away.
 */
@Inject
class PublicSearchingViewModel(
    @Assisted private val minBuyIn: Long,
    @Assisted private val maxBuyIn: Long,
    private val matchmaking: MatchmakingRepository,
    private val rooms: RoomRepository,
    private val auth: AuthRepository,
    private val appScope: AppCoroutineScope,
) : SEAViewModel<PublicSearchingState, PublicSearchingEvent, PublicSearchingAction>(
    initialStateArg = PublicSearchingState(minBuyIn = minBuyIn, maxBuyIn = maxBuyIn),
) {

    private val logger = KLog.withTag("PublicSearchingVM")

    /** Holds the find + socket-observe chain. Cancelled on re-find, leave, navigate. */
    private var searchJob: Job? = null

    /** The countdown to the bot-fallback offer. Re-armed on "keep waiting". */
    private var timeoutJob: Job? = null

    /** The ROOM-12 wait-time candidates re-poll. Cancelled on migrate / leave. */
    private var candidatesPollJob: Job? = null

    /** Set once auth resolves so we can exclude ourselves from the found-count. */
    private var localUserId: String? = null

    /** The table we're currently seated in (for leave + play-bots). */
    private var currentRoomCode: String? = null

    /**
     * The fresh waiting table we opened via [beginSearch], kept so the wait-time
     * candidates poll (ROOM-12) can tiebreak a discovered table against it (older
     * table wins, so two mutual searchers converge on one and never swap seats).
     * Null when the find matched us onto a table with players already there.
     */
    private var ownWaitingRoom: Room? = null

    /** Guards the one-shot hand-off so a re-emitted Playing snapshot can't double-navigate. */
    private var hasNavigated = false

    /** When this search episode began — the `wait_ms` origin for the matchmaking funnel events. */
    private var searchStartedAt = TimeSource.Monotonic.markNow()

    /** One `matchmaking.real_player_arrived` per search episode, not per snapshot. */
    private var realPlayerArrivalLogged = false

    /**
     * Consecutive auto re-finds (table vanished under us) with no healthy
     * connection in between. Capped so a server flapping mid-search can't spin us
     * into a tight re-find loop on the app's busiest screen.
     */
    private var reFindAttempts = 0

    init {
        takeAction(PublicSearchingAction.Start)
    }

    override suspend fun handleAction(action: PublicSearchingAction) {
        when (action) {
            PublicSearchingAction.Start,
            PublicSearchingAction.Retry,
                -> action.run {
                reFindAttempts = 0
                searchStartedAt = TimeSource.Monotonic.markNow()
                realPlayerArrivalLogged = false
                logger.logEvent("matchmaking.search_started", "entry" to "public")
                updateState {
                    it.copy(phase = SearchPhase.Searching, error = null, realPlayersFound = 0)
                }
                beginSearch()
            }

            is PublicSearchingAction.WaitingCandidatesRefreshed -> action.run {
                // Only migrate while we're genuinely waiting alone. Once a human is
                // here (or we left the wait phase) the table is dealing and moving
                // would be wrong; a transient poll failure just keeps us waiting.
                if (state.phase != SearchPhase.Searching || state.realPlayersFound > 0) return@run
                val candidates = (action.outcome as? CandidatesOutcome.Success)?.rooms ?: return@run
                // Only consolidate into a table we could actually be seated at — an
                // unaffordable one would just bounce us back off the entry bar.
                val target = migrationTarget(candidates.filter { it.affordable }.map { it.room }) ?: return@run
                updateState { it.copy(error = null) }
                migrateTo(target)
            }

            is PublicSearchingAction.Seated -> action.run {
                if (action.intoJoinedLobby) {
                    logger.logEvent("matchmaking.matched", "wait_ms" to waitMs())
                    updateState {
                        it.copy(
                            phase = SearchPhase.Joined,
                            joinedRoom = action.room,
                            realPlayersFound = countOtherHumans(action.room),
                        )
                    }
                } else {
                    updateState { it.copy(phase = SearchPhase.Searching) }
                }
            }

            is PublicSearchingAction.LocalUserResolved -> action.updateState {
                it.copy(localUserId = action.userId)
            }

            is PublicSearchingAction.ConnectionUpdated -> action.run {
                when (val conn = action.connection) {
                    RoomConnection.Connecting -> Unit
                    is RoomConnection.Connected -> {
                        // A healthy snapshot means the re-find loop converged.
                        reFindAttempts = 0
                        val others = countOtherHumans(conn.room)
                        if (others > 0 && !realPlayerArrivalLogged &&
                            (state.phase == SearchPhase.Searching || state.phase == SearchPhase.BotFallbackOffer)
                        ) {
                            realPlayerArrivalLogged = true
                            logger.logEvent(
                                "matchmaking.real_player_arrived",
                                "during" to if (state.phase == SearchPhase.BotFallbackOffer) "bot_offer" else "wait",
                                "wait_ms" to waitMs(),
                            )
                        }
                        // Once the user has explicitly taken a seat (ROOM-11) the
                        // screen shows a pre-deal lobby off this snapshot, so keep
                        // it fresh as members come and go while we wait for the deal.
                        updateState {
                            it.copy(
                                realPlayersFound = others,
                                joinedRoom = if (it.phase == SearchPhase.Joined) conn.room else it.joinedRoom,
                            )
                        }
                        // The server deals a public table itself the moment two
                        // humans are present — that flip to Playing is our cue to
                        // hand off to the live table (also covers a mid-hand join).
                        if (conn.room.status == RoomStatus.Playing && !hasNavigated) {
                            hasNavigated = true
                            sendEvent(PublicSearchingEvent.NavigateToTable(conn.room.code))
                        }
                    }
                    is RoomConnection.Reconnecting -> Unit // transient blip — keep searching
                    is RoomConnection.Closed -> when (val reason = conn.reason) {
                        // Table vanished under us (GC / server restart). The code is
                        // dead, so re-find a fresh table rather than chase a ghost —
                        // capped + backed off so a flapping server can't spin a tight
                        // re-find loop on the app's busiest screen.
                        // MatchOver (MP-14) is a play-screen terminal; if it reaches
                        // the search socket the table's gone, so re-find like a GC.
                        ClosedReason.RoomDeleted, is ClosedReason.MatchOver ->
                            if (reFindAttempts >= MAX_REFINDS) {
                                updateState { it.copy(error = SearchError.Network) }
                            } else {
                                reFindAttempts++
                                updateState { it.copy(phase = SearchPhase.Searching, error = null, joinedRoom = null) }
                                beginSearch(backoff = true)
                            }
                        // IncompatibleVersion (ENG-7): the table sent a frame this
                        // build can't parse — surface a connection error rather than
                        // re-finding into the same unparseable table.
                        ClosedReason.Rejected,
                        ClosedReason.ReconnectFailed,
                        ClosedReason.IncompatibleVersion,
                            -> updateState { it.copy(error = SearchError.Connection) }
                        // The server dealt without us because our balance fell under
                        // the entry bar between find and deal — terminal. Show a real
                        // "you need X chips" state with a route back, never an infinite
                        // "dealing you in".
                        is ClosedReason.SeatUnaffordable ->
                            updateState {
                                it.copy(error = SearchError.CannotBeSeated(reason.minBalanceToSit))
                            }
                        ClosedReason.Cancelled -> Unit // we tore it down ourselves
                    }
                }
            }

            PublicSearchingAction.SearchTimedOut -> action.run {
                // Only offer bots if we're genuinely alone. If a human is here the
                // table is dealing (or about to) and the offer would be wrong.
                if (state.phase == SearchPhase.Searching && state.realPlayersFound == 0) {
                    logger.logEvent("matchmaking.bot_offer_shown", "wait_ms" to waitMs())
                    updateState { it.copy(phase = SearchPhase.BotFallbackOffer) }
                    // Read the disclosed-bot subsidy headroom so a near-cap player
                    // learns the limit before sitting rather than from a surprising
                    // balance afterward (MP-6). Best-effort: a failed read just omits
                    // the disclosure, the offer still stands.
                    viewModelScope.launch {
                        takeAction(PublicSearchingAction.SubsidyBudgetLoaded(matchmaking.subsidyBudget()))
                    }
                }
            }

            is PublicSearchingAction.SubsidyBudgetLoaded -> action.run {
                val budget = (action.outcome as? SubsidyBudgetOutcome.Success) ?: return@run
                if (budget.remaining < budget.cap) {
                    updateState {
                        it.copy(subsidyNotice = SubsidyNotice(remaining = budget.remaining, cap = budget.cap))
                    }
                }
            }

            is PublicSearchingAction.FindFailed -> action.updateState {
                it.copy(error = action.error)
            }

            PublicSearchingAction.PlayBots -> action.run {
                val code = currentRoomCode ?: return@run
                logger.logEvent("matchmaking.bot_offer_accepted", "wait_ms" to waitMs())
                updateState { it.copy(phase = SearchPhase.JoiningBots, error = null) }
                viewModelScope.launch {
                    takeAction(PublicSearchingAction.PlayBotsResult(matchmaking.playBots(code)))
                }
            }

            is PublicSearchingAction.PlayBotsResult -> action.run {
                when (val outcome = action.outcome) {
                    // Bots are seating + the server is dealing; the next Playing
                    // snapshot hands us off. Stay on the "dealing you in" state.
                    is PlayBotsOutcome.Success -> Unit
                    // The happy surprise: a real human arrived during the search,
                    // so the server kept the real game. Drop the bot path; the
                    // human table will deal and navigate us in.
                    PlayBotsOutcome.RealPlayerJoined ->
                        updateState { it.copy(phase = SearchPhase.Searching) }
                    // Our table was reclaimed before we could fill it — re-find.
                    PlayBotsOutcome.RoomNotFound -> beginSearch()
                    is PlayBotsOutcome.NotSignedIn ->
                        updateState { it.copy(phase = SearchPhase.BotFallbackOffer, error = SearchError.NotSignedIn) }
                    is PlayBotsOutcome.NetworkError ->
                        updateState { it.copy(phase = SearchPhase.BotFallbackOffer, error = SearchError.Network) }
                    is PlayBotsOutcome.Unknown -> {
                        logger.w(outcome.cause) { "play-bots failed" }
                        updateState { it.copy(phase = SearchPhase.BotFallbackOffer, error = SearchError.Unknown) }
                    }
                }
            }

            PublicSearchingAction.KeepWaiting -> action.run {
                logger.logEvent("matchmaking.bot_offer_declined", "next" to "keep_waiting")
                updateState { it.copy(phase = SearchPhase.Searching, error = null, subsidyNotice = null) }
                armTimeout()
            }

            PublicSearchingAction.TryAgainLater -> {
                logger.logEvent("matchmaking.bot_offer_declined", "next" to "leave")
                leaveAndExit()
            }

            PublicSearchingAction.Cancel -> leaveAndExit()
        }
    }

    /**
     * Re-poll candidates while we genuinely wait in our own fresh table so a table
     * that appears afterward is still discovered (ROOM-12). Reuses [candidatesPollJob]
     * so a re-find always replaces any stale poll.
     */
    private fun armWaitingCandidatesPoll() {
        candidatesPollJob?.cancel()
        candidatesPollJob = viewModelScope.launch {
            // Self-terminates once we're no longer genuinely waiting alone (a human
            // arrived, the window elapsed into the bot offer, or we left the phase),
            // so the poll never outlives the state it exists to serve.
            while (state.phase == SearchPhase.Searching && state.realPlayersFound == 0) {
                delay(CANDIDATES_POLL_INTERVAL)
                if (state.phase != SearchPhase.Searching || state.realPlayersFound > 0) break
                takeAction(PublicSearchingAction.WaitingCandidatesRefreshed(matchmaking.findCandidates(minBuyIn, maxBuyIn)))
            }
        }
    }

    /**
     * Pick a table worth migrating to from a wait-time candidates poll, or null to
     * stay put. Skips our own waiting table and only moves to one that's strictly
     * preferable — more other humans, or (tie) an older table (code as the final
     * tiebreak). The age tiebreak makes two mutual searchers converge on the older
     * table instead of swapping seats and both ending up alone.
     */
    private fun migrationTarget(candidates: List<Room>): Room? {
        val own = ownWaitingRoom ?: return null
        val ownHumans = own.members.count { !it.isBot }
        return candidates
            .filter { it.code != own.code && !it.isFull && it.status != RoomStatus.Finished }
            .filter { candidate ->
                val candidateHumans = candidate.members.count { !it.isBot }
                when {
                    candidateHumans != ownHumans -> candidateHumans > ownHumans
                    candidate.createdAtEpochMs != own.createdAtEpochMs ->
                        candidate.createdAtEpochMs < own.createdAtEpochMs
                    else -> candidate.code < own.code
                }
            }
            .minWithOrNull(
                compareByDescending<Room> { it.members.count { m -> !m.isBot } }
                    .thenBy { it.createdAtEpochMs }
                    .thenBy { it.code },
            )
    }

    /** Leave our own waiting table and consolidate into the discovered one (ROOM-12). */
    private fun migrateTo(target: Room) {
        val leaving = currentRoomCode
        searchJob?.cancel()
        timeoutJob?.cancel()
        candidatesPollJob?.cancel()
        if (leaving != null && leaving != target.code) {
            appScope.launch { Catching { rooms.leaveRoom(leaving) } }
        }
        joinAndWatch(target.code)
    }

    /**
     * Join a table by code, then watch its socket — the ROOM-12 wait-time
     * consolidation path. A migration is still genuine waiting, so it stays on
     * the still-hunting radar rather than the pre-deal lobby.
     */
    private fun joinAndWatch(code: String) {
        searchJob?.cancel()
        timeoutJob?.cancel()
        candidatesPollJob?.cancel()
        hasNavigated = false
        searchJob = viewModelScope.launch {
            ensureLocalUserId()
            when (val outcome = rooms.joinRoom(code)) {
                is JoinRoomOutcome.Success -> {
                    currentRoomCode = outcome.room.code
                    // We're at a discovered table now, not waiting in our own.
                    ownWaitingRoom = null
                    takeAction(PublicSearchingAction.Seated(outcome.room, intoJoinedLobby = false))
                    armTimeout()
                    watchRoom(outcome.room.code)
                }
                // The discovered table filled, vanished, or stopped accepting
                // joins between the poll and the migrate — re-find so the search
                // keeps going rather than dead-ending.
                is JoinRoomOutcome.Full,
                is JoinRoomOutcome.NotFound,
                is JoinRoomOutcome.NotJoinable,
                    -> beginSearch()
                is JoinRoomOutcome.OverBalance ->
                    takeAction(PublicSearchingAction.FindFailed(SearchError.InsufficientBalance))
                is JoinRoomOutcome.NotSignedIn ->
                    takeAction(PublicSearchingAction.FindFailed(SearchError.NotSignedIn))
                is JoinRoomOutcome.NetworkError ->
                    takeAction(PublicSearchingAction.FindFailed(SearchError.Network))
                is JoinRoomOutcome.Unknown -> {
                    logger.w(outcome.cause) { "join candidate failed" }
                    takeAction(PublicSearchingAction.FindFailed(SearchError.Unknown))
                }
            }
        }
    }

    /**
     * Cancel any in-flight search, then ask the server's atomic find-or-create
     * to seat us (MP-35) — onto the best existing table when one fits, else a
     * fresh one to genuinely wait in.
     */
    private fun beginSearch(backoff: Boolean = false) {
        searchJob?.cancel()
        timeoutJob?.cancel()
        candidatesPollJob?.cancel()
        hasNavigated = false
        searchJob = viewModelScope.launch {
            if (backoff) delay(REFIND_BACKOFF)
            ensureLocalUserId()
            when (val outcome = matchmaking.findTable(minBuyIn, maxBuyIn)) {
                is FindTableOutcome.Success -> {
                    currentRoomCode = outcome.room.code
                    if (outcome.created) {
                        ownWaitingRoom = outcome.room
                        logger.logEvent("matchmaking.wait_started")
                        // ROOM-12: keep browsing /candidates while we genuinely
                        // wait, so a table created moments after ours is still
                        // discovered and we consolidate into it.
                        armWaitingCandidatesPoll()
                    } else {
                        // Matched with players already there — straight into the
                        // pre-deal lobby; the server deals the moment enough
                        // sockets are up.
                        ownWaitingRoom = null
                        takeAction(PublicSearchingAction.Seated(outcome.room, intoJoinedLobby = true))
                    }
                    armTimeout()
                    watchRoom(outcome.room.code)
                }
                is FindTableOutcome.InvalidRange ->
                    takeAction(PublicSearchingAction.FindFailed(SearchError.InvalidRange))
                is FindTableOutcome.InsufficientBalance ->
                    takeAction(PublicSearchingAction.FindFailed(SearchError.InsufficientBalance))
                is FindTableOutcome.NotSignedIn ->
                    takeAction(PublicSearchingAction.FindFailed(SearchError.NotSignedIn))
                is FindTableOutcome.RateLimited ->
                    takeAction(PublicSearchingAction.FindFailed(SearchError.RateLimited))
                is FindTableOutcome.NetworkError ->
                    takeAction(PublicSearchingAction.FindFailed(SearchError.Network))
                is FindTableOutcome.Unknown -> {
                    logger.w(outcome.cause) { "find table failed" }
                    takeAction(PublicSearchingAction.FindFailed(SearchError.Unknown))
                }
            }
        }
    }

    /**
     * Collect the room socket forever (until [searchJob] is cancelled). Each
     * snapshot routes back through an action so all state mutation stays in the
     * unidirectional loop. Shared by the find-and-wait, matched, migration, and
     * re-find paths.
     */
    private suspend fun watchRoom(code: String) {
        rooms.connect(code).connection.collect {
            takeAction(PublicSearchingAction.ConnectionUpdated(it))
        }
    }

    private suspend fun ensureLocalUserId() {
        if (localUserId == null) {
            (auth.current() as? AuthState.Authenticated)?.let {
                localUserId = it.userId
                takeAction(PublicSearchingAction.LocalUserResolved(it.userId))
            }
        }
    }

    private fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(SEARCH_WINDOW)
            takeAction(PublicSearchingAction.SearchTimedOut)
        }
    }

    private fun leaveAndExit() {
        logger.logEvent(
            "matchmaking.abandoned",
            "phase" to state.phase.eventName(),
            "wait_ms" to waitMs(),
        )
        val code = currentRoomCode
        searchJob?.cancel()
        timeoutJob?.cancel()
        candidatesPollJob?.cancel()
        // Fire-and-forget on the app scope so the leave reaches the server even
        // though we navigate away immediately and tear this VM down.
        if (code != null) {
            appScope.launch { Catching { rooms.leaveRoom(code) } }
        }
        sendEvent(PublicSearchingEvent.NavigateBack)
    }

    private fun countOtherHumans(room: Room): Int =
        room.members.count { !it.isBot && it.isConnected && it.userId != localUserId }

    private fun waitMs(): Long = searchStartedAt.elapsedNow().inWholeMilliseconds

    private fun SearchPhase.eventName(): String = when (this) {
        SearchPhase.Searching -> "searching"
        SearchPhase.Joined -> "joined"
        SearchPhase.BotFallbackOffer -> "bot_offer"
        SearchPhase.JoiningBots -> "joining_bots"
    }

    private companion object {
        /**
         * How long we hunt for real humans before honestly offering disclosed
         * bots. Deliberately generous — real-human play is the whole point, so
         * we'd rather wait than fall back early. Tunable launch knob.
         */
        val SEARCH_WINDOW = 60.seconds

        /** Backoff before an auto re-find when the table vanished, so a flapping server can't spin a tight loop. */
        val REFIND_BACKOFF = 2.seconds

        /** How often the ROOM-12 wait-time poll re-browses candidates while we wait alone. */
        val CANDIDATES_POLL_INTERVAL = 5.seconds

        /** Cap on consecutive auto re-finds before we stop and show a retry instead of looping forever. */
        const val MAX_REFINDS = 3
    }
}

// ---------- MVI types ----------

data class PublicSearchingState(
    val phase: SearchPhase = SearchPhase.Searching,
    /** Other *connected humans* at the table — 0 while we're still alone. */
    val realPlayersFound: Int = 0,
    val minBuyIn: Long,
    val maxBuyIn: Long,
    val error: SearchError? = null,
    /**
     * The table the user was matched onto, staged once the find seats them and
     * kept fresh from each snapshot while [SearchPhase.Joined] renders the
     * pre-deal lobby (ROOM-11). Null on every other phase.
     */
    val joinedRoom: Room? = null,
    /** This device's user id, so the joined-lobby seat grid can mark "you". */
    val localUserId: String? = null,
    /**
     * Set on the bot-fallback offer when the player has already drawn down some of
     * their daily disclosed-bot subsidy, so the offer can disclose the limit up
     * front. Null when full headroom remains (no need to caveat) or unread.
     */
    val subsidyNotice: SubsidyNotice? = null,
)

/** The near-cap disclosure shown alongside the disclosed-bot offer. */
data class SubsidyNotice(
    /** House-funded bonus chips still available today (clamped >= 0). */
    val remaining: Long,
    /** The daily subsidy cap, for "X of Y left" framing. */
    val cap: Long,
)

sealed interface SearchPhase {
    /** Hunting for real players (or waiting in a fresh table). */
    data object Searching : SearchPhase

    /**
     * The find matched the user onto a table with players already there — a
     * pre-deal lobby showing the seated players, distinct from the still-hunting
     * radar, until the server deals the first hand (ROOM-11 / MP-35).
     */
    data object Joined : SearchPhase

    /** The window elapsed with nobody else here — offering disclosed bots. */
    data object BotFallbackOffer : SearchPhase

    /** The user accepted bots; they're seating + the table is dealing. */
    data object JoiningBots : SearchPhase
}

sealed interface SearchError {
    data object InvalidRange : SearchError

    /** The buy-in ceiling exceeded the wallet — surfaced as a "lower your range" prompt. */
    data object InsufficientBalance : SearchError

    /**
     * The server dealt without seating us because our balance fell under the entry
     * bar between find and deal (the `seat_unaffordable` frame). Terminal — [neededChips]
     * is the balance required to sit at that table, shown so the user knows the gap.
     */
    data class CannotBeSeated(val neededChips: Long) : SearchError
    data object NotSignedIn : SearchError
    data object RateLimited : SearchError
    data object Network : SearchError
    /** The socket was rejected outright (not a member / unknown code). */
    data object Connection : SearchError
    data object Unknown : SearchError
}

sealed interface PublicSearchingEvent {
    /** The first hand is dealing — hand off to the live multiplayer table. */
    data class NavigateToTable(val roomCode: String) : PublicSearchingEvent

    /** Cancelled or gave up — return to the Find screen. */
    data object NavigateBack : PublicSearchingEvent
}

sealed interface PublicSearchingAction {
    data object Start : PublicSearchingAction
    data object Retry : PublicSearchingAction
    data object Cancel : PublicSearchingAction

    /** Accept the disclosed-bot fallback. */
    data object PlayBots : PublicSearchingAction

    /** Decline bots and leave. */
    data object TryAgainLater : PublicSearchingAction

    /** Decline bots but keep hunting for humans (re-arms the window). */
    data object KeepWaiting : PublicSearchingAction

    // ----- internal (fired by the VM's own jobs) -----
    /**
     * A seat landed. [intoJoinedLobby] true → the find matched us onto a table
     * with players already there, so show the pre-deal lobby ([SearchPhase.Joined]);
     * false → a wait-time consolidation that stays on the radar.
     */
    data class Seated(val room: Room, val intoJoinedLobby: Boolean) : PublicSearchingAction

    /** Auth resolved this device's user id (so the lobby seat grid can mark "you"). */
    data class LocalUserResolved(val userId: String) : PublicSearchingAction

    data class ConnectionUpdated(val connection: RoomConnection) : PublicSearchingAction
    data class FindFailed(val error: SearchError) : PublicSearchingAction
    data object SearchTimedOut : PublicSearchingAction
    data class PlayBotsResult(val outcome: PlayBotsOutcome) : PublicSearchingAction

    /** Result of the near-cap subsidy-budget read taken when the offer appears. */
    data class SubsidyBudgetLoaded(val outcome: SubsidyBudgetOutcome) : PublicSearchingAction

    /**
     * Result of a background candidates re-poll while *genuinely waiting* in our
     * own fresh table (ROOM-12). Lets a searcher who fell through to a waiting
     * table still discover a table that appeared afterward and consolidate into it.
     */
    data class WaitingCandidatesRefreshed(val outcome: CandidatesOutcome) : PublicSearchingAction
}
