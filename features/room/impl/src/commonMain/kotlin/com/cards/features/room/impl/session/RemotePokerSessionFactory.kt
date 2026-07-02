package com.dangerfield.cards.features.room.impl.session

import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.features.room.impl.usecase.MultiplayerCredit

import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.cards.GameSpeed
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.game.Personality
import com.dangerfield.cards.libraries.game.PlayStyle
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * Production [PokerSessionFactory] for multiplayer sessions. Opens
 * a [RoomConnectionHandle] via
 * [RoomRepository.connect] and hands it to a [RemotePokerSession] that
 * routes server-side gameplay frames into the [PokerSession] surface
 * the VM already consumes.
 *
 * Assisted-injected on [roomCode] + [localUserId] — both arrive on
 * the multiplayer route and stay fixed for the session's lifetime.
 *
 * Personalities are intentionally empty: real humans don't get a bot
 * personality (the V1 server fills empty seats with bots only as a
 * future extension, and that route would carry its own personality
 * metadata). Until then, [occupantsFor] derives [SeatOccupant.Human]
 * for every filled seat.
 *
 * The local human's seat is derived from each [GameState] by matching
 * [localUserId] against `seat.playerId` rather than baked in at
 * construction. That keeps [tableFor] correct if the user re-seats
 * (V1 forbids it, but the dynamic lookup is no more code than the
 * static one and removes a hidden invariant).
 */
class RemotePokerSessionFactory @Inject constructor(
    @Assisted override val roomCode: String,
    @Assisted private val localUserId: String,
    // True for a public (matchmade) table. A public bots-only table is the
    // disclosed-bot subsidy — real chips ARE at stake (you keep what you win, up
    // to a daily limit) — so it must not show the private "practice only" copy.
    @Assisted private val isPublicTable: Boolean,
    private val roomRepository: RoomRepository,
    private val telemetry: Telemetry,
) : PokerSessionFactory {

    override val difficultyName: String = "Multiplayer"

    override val xpMode: XpMode = XpMode.MULTIPLAYER

    /**
     * The handle is opened lazily on [create] and reused for the
     * session's lifetime. Two screens (lobby + play) calling
     * `roomRepository.connect(roomCode)` share one underlying WS via
     * the per-code cache on the socket — this handle is just our
     * view of it.
     */
    private lateinit var handle: RoomConnectionHandle

    override fun create(
        humanSeatIndex: Int,
        gameSpeedProvider: () -> GameSpeed,
        onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit,
    ): PokerSession {
        // gameSpeedProvider + humanSeatIndex are bot-mode hints; remote
        // sessions don't act on them. onHandEnded is wired through to the
        // session so a server HandEnded runs the VM's progression path (XP,
        // player-stats, hand-end celebration) — the VM's GameEvent collector
        // only projects the table, it never calls handleHandEnded (PROG-4).
        handle = roomRepository.connect(roomCode)
        return RemotePokerSession(
            handle = handle,
            localUserId = localUserId,
            onLeave = { (roomRepository.leaveRoom(roomCode) as? LeaveRoomOutcome.Success)?.settledBalance },
            onHandEnded = onHandEnded,
        )
    }

    override suspend fun bootstrap(session: PokerSession) {
        // Tag the crash-reporting scope with the room for the whole time the
        // play session is live — run() suspends until the VM scope tears down,
        // so the finally clears it on every exit (clean leave, room closed, or
        // VM cleared). Feedback filed mid-game then carries room_code, the
        // pivot to this room's server traces/logs. Same lifecycle owns the
        // other MP scope tags (seat / hand / opponents) and the snapshot
        // provider that attaches the live GameState to a feedback report.
        val mpSession = session as RemotePokerSession
        telemetry.setRoom(roomCode)
        telemetry.setMpStateProvider {
            // Read-and-serialize lives inside the provider so the snapshot
            // reflects the moment feedback is filed, not bootstrap time.
            // Sentinel pre-first-snapshot state (empty seats) is treated as
            // no-snapshot — there's nothing useful to attach yet.
            Catching {
                val state = mpSession.gameStateFlow.value
                if (state.seats.isEmpty()) null
                else mpStateJson.encodeToString(GameState.serializer(), state)
            }.getOrNull()
        }
        try {
            coroutineScope {
                launch { mirrorMpScope(mpSession) }
                mpSession.run()
            }
        } finally {
            telemetry.setRoom(null)
            telemetry.setSeat(null)
            telemetry.setHand(null)
            telemetry.setOpponents(null)
            telemetry.setMpStateProvider(null)
        }
    }

    /**
     * Push the local player's seat, the current hand number, and the other
     * humans' user ids onto the Sentry scope whenever the table changes.
     * `distinctUntilChanged` keeps tag writes proportional to real changes,
     * not snapshot churn — most snapshots carry the same seat assignment.
     */
    private suspend fun mirrorMpScope(session: RemotePokerSession) {
        session.gameStateFlow
            .map { state ->
                if (state.seats.isEmpty()) {
                    MpScopeContext(seatIndex = null, handNumber = null, opponents = emptyList())
                } else {
                    val mySeat = state.seats.firstOrNull { it.playerId == localUserId }?.index
                    val opponents = state.seats
                        .mapNotNull { it.playerId }
                        .filter { it != localUserId }
                    MpScopeContext(
                        seatIndex = mySeat,
                        handNumber = state.handNumber.takeIf { it > 0 },
                        opponents = opponents,
                    )
                }
            }
            .distinctUntilChanged()
            .collect { ctx ->
                telemetry.setSeat(ctx.seatIndex)
                telemetry.setHand(ctx.handNumber)
                telemetry.setOpponents(ctx.opponents.takeIf { it.isNotEmpty() })
            }
    }

    private data class MpScopeContext(
        val seatIndex: Int?,
        val handNumber: Int?,
        val opponents: List<String>,
    )

    private companion object {
        // Default config is fine — GameState's serializers handle every nested
        // type. `encodeDefaults` left off so the attachment stays compact.
        private val mpStateJson = Json { ignoreUnknownKeys = true }
    }

    override fun humanSeatIndex(state: GameState): Int =
        state.seats.firstOrNull { it.playerId == localUserId }?.index ?: -1

    override fun occupantsFor(state: GameState, curve: LevelCurve): List<SeatOccupant> {
        if (state.seats.isEmpty()) return emptyList()
        return state.seats.map { seat ->
            when {
                seat.playerId == null -> SeatOccupant.Empty(seatIndex = seat.index)
                seat.isBot -> SeatOccupant.Bot(
                    seatIndex = seat.index,
                    displayName = seat.displayName,
                    personality = Personality(
                        label = seat.displayName,
                        style = PlayStyle.Unknown,
                    ),
                )
                else -> SeatOccupant.Human(
                    seatIndex = seat.index,
                    displayName = seat.displayName,
                    userId = seat.playerId!!,
                    personality = null,
                    // The server snapshots each player's lifetime XP onto the
                    // Seat at hand-start; derive their level the same way the
                    // local human's is derived, through the same server-tunable
                    // [curve]. 0 when XP hasn't resolved yet.
                    level = seat.xp?.let { levelProgressFor(it, curve).level } ?: 0,
                    leagueTier = null,
                )
            }
        }
    }

    override fun tableFor(
        state: GameState,
        lastWinners: GameEvent.HandEnded?,
        lastActionBySeat: Map<Int, PlayerAction>,
        humanProfile: Profile.Authenticated?,
        humanLevel: Int?,
        curve: LevelCurve,
    ): TableUiState {
        // Pre-first-snapshot: render Loading. The session emits a
        // sentinel state with empty seats until the server's first
        // GameStateSnapshot lands.
        if (state.seats.isEmpty()) return TableUiState.Loading
        val humanSeatIndex = state.seats.firstOrNull { it.playerId == localUserId }?.index ?: -1
        // Revealed backend bots carry their personality name on the wire
        // (`Seat.botStyleKey`); map it to the local roster so the opponent
        // sheet can render the playing-style radar. Hidden bots carry no key
        // (and arrive scrubbed to isBot=false), so they never appear here.
        val personalitiesBySeat = state.seats.mapNotNull { seat ->
            seat.botStyleKey
                ?.let { key -> BotPersonality.Roster.firstOrNull { it.name == key } }
                ?.let { seat.index to it }
        }.toMap()
        return TableUiState.fromGameState(
            gameState = state,
            humanSeatIndex = humanSeatIndex,
            personalitiesBySeat = personalitiesBySeat,
            lastWinners = lastWinners,
            lastActionBySeat = lastActionBySeat,
            humanProfile = humanProfile,
            humanLevel = humanLevel,
            botDifficultyLabel = difficultyName,
            practiceTierBotsPresent = MultiplayerCredit.showsPracticeTierLabel(state),
            practiceTierBotsOnly = MultiplayerCredit.isBotsOnly(state),
            // A public bots-only table is the disclosed-bot subsidy: real chips at
            // stake, house-funded. Private bots-only stays practice.
            subsidizedBotTable = isPublicTable && MultiplayerCredit.isBotsOnly(state),
            turnTimerEnforced = true,
            curve = curve,
            // A seatless local member is a mid-game joiner spectating until the
            // next hand boundary seats them — surface the "dealt in next hand"
            // notice rather than leaving them staring at a table with no cards.
            waitingToBeDealtIn = humanSeatIndex < 0,
        )
    }
}
