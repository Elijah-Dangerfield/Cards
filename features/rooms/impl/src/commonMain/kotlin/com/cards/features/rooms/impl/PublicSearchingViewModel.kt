package com.dangerfield.cards.features.rooms.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.FindTableOutcome
import com.dangerfield.cards.libraries.rooms.MatchmakingRepository
import com.dangerfield.cards.libraries.rooms.PlayBotsOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the public "Find a table" search — honesty-first matchmaking.
 *
 * Lifecycle:
 *  1. On [PublicSearchingAction.Start] it asks the matchmaker to seat the user
 *     (real humans only — never a bot). The server either drops them into a
 *     table that already has players, or opens a fresh one and we genuinely wait.
 *  2. It opens the room socket and watches who's seated. [PublicSearchingState.realPlayersFound]
 *     counts *other connected humans*, so the screen can honestly say "still
 *     looking" vs "someone joined". The instant the server deals the first hand
 *     (room flips to Playing) we hand off to the live table.
 *  3. If the long search window ([SEARCH_WINDOW]) elapses with nobody else here,
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

    /** Set once auth resolves so we can exclude ourselves from the found-count. */
    private var localUserId: String? = null

    /** The table we're currently seated in (for leave + play-bots). */
    private var currentRoomCode: String? = null

    /** Guards the one-shot hand-off so a re-emitted Playing snapshot can't double-navigate. */
    private var hasNavigated = false

    init {
        takeAction(PublicSearchingAction.Start)
    }

    override suspend fun handleAction(action: PublicSearchingAction) {
        when (action) {
            PublicSearchingAction.Start,
            PublicSearchingAction.Retry,
                -> action.run {
                updateState {
                    it.copy(phase = SearchPhase.Searching, error = null, realPlayersFound = 0)
                }
                beginSearch()
            }

            is PublicSearchingAction.ConnectionUpdated -> action.run {
                when (val conn = action.connection) {
                    RoomConnection.Connecting -> Unit
                    is RoomConnection.Connected -> {
                        val others = countOtherHumans(conn.room)
                        updateState { it.copy(realPlayersFound = others) }
                        // The server deals a public table itself the moment two
                        // humans are present — that flip to Playing is our cue to
                        // hand off to the live table (also covers a mid-hand join).
                        if (conn.room.status == RoomStatus.Playing && !hasNavigated) {
                            hasNavigated = true
                            sendEvent(PublicSearchingEvent.NavigateToTable(conn.room.code))
                        }
                    }
                    is RoomConnection.Reconnecting -> Unit // transient blip — keep searching
                    is RoomConnection.Closed -> when (conn.reason) {
                        // Table vanished under us (GC / server restart). The code is
                        // dead, so re-find a fresh table rather than chase a ghost.
                        ClosedReason.RoomDeleted -> beginSearch()
                        ClosedReason.Rejected ->
                            updateState { it.copy(error = SearchError.Connection) }
                        ClosedReason.Cancelled -> Unit // we tore it down ourselves
                    }
                }
            }

            PublicSearchingAction.SearchTimedOut -> action.run {
                // Only offer bots if we're genuinely alone. If a human is here the
                // table is dealing (or about to) and the offer would be wrong.
                if (state.phase == SearchPhase.Searching && state.realPlayersFound == 0) {
                    updateState { it.copy(phase = SearchPhase.BotFallbackOffer) }
                }
            }

            is PublicSearchingAction.FindFailed -> action.updateState {
                it.copy(error = action.error)
            }

            PublicSearchingAction.PlayBots -> action.run {
                val code = currentRoomCode ?: return@run
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
                updateState { it.copy(phase = SearchPhase.Searching, error = null) }
                armTimeout()
            }

            PublicSearchingAction.TryAgainLater,
            PublicSearchingAction.Cancel,
                -> leaveAndExit()
        }
    }

    /** Cancel any in-flight search, then find a fresh table and watch it. */
    private fun beginSearch() {
        searchJob?.cancel()
        timeoutJob?.cancel()
        hasNavigated = false
        searchJob = viewModelScope.launch {
            if (localUserId == null) {
                (auth.current() as? AuthState.Authenticated)?.let { localUserId = it.userId }
            }
            when (val outcome = matchmaking.findTable(minBuyIn, maxBuyIn)) {
                is FindTableOutcome.Success -> {
                    currentRoomCode = outcome.room.code
                    armTimeout()
                    // Collect forever (until this job is cancelled). Each snapshot
                    // routes back through an action so all state mutation stays in
                    // the unidirectional loop.
                    rooms.connect(outcome.room.code).connection.collect {
                        takeAction(PublicSearchingAction.ConnectionUpdated(it))
                    }
                }
                is FindTableOutcome.InvalidRange ->
                    takeAction(PublicSearchingAction.FindFailed(SearchError.InvalidRange))
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

    private fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(SEARCH_WINDOW)
            takeAction(PublicSearchingAction.SearchTimedOut)
        }
    }

    private fun leaveAndExit() {
        val code = currentRoomCode
        searchJob?.cancel()
        timeoutJob?.cancel()
        // Fire-and-forget on the app scope so the leave reaches the server even
        // though we navigate away immediately and tear this VM down.
        if (code != null) {
            appScope.launch { Catching { rooms.leaveRoom(code) } }
        }
        sendEvent(PublicSearchingEvent.NavigateBack)
    }

    private fun countOtherHumans(room: Room): Int =
        room.members.count { !it.isBot && it.isConnected && it.userId != localUserId }

    private companion object {
        /**
         * How long we hunt for real humans before honestly offering disclosed
         * bots. Deliberately generous — real-human play is the whole point, so
         * we'd rather wait than fall back early. Tunable launch knob.
         */
        val SEARCH_WINDOW = 60.seconds
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
)

sealed interface SearchPhase {
    /** Hunting for real players (or waiting in a fresh table). */
    data object Searching : SearchPhase

    /** The window elapsed with nobody else here — offering disclosed bots. */
    data object BotFallbackOffer : SearchPhase

    /** The user accepted bots; they're seating + the table is dealing. */
    data object JoiningBots : SearchPhase
}

sealed interface SearchError {
    data object InvalidRange : SearchError
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
    data class ConnectionUpdated(val connection: RoomConnection) : PublicSearchingAction
    data class FindFailed(val error: SearchError) : PublicSearchingAction
    data object SearchTimedOut : PublicSearchingAction
    data class PlayBotsResult(val outcome: PlayBotsOutcome) : PublicSearchingAction
}
