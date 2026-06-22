package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.rooms.RoomRepository
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * Production [PokerSessionFactory] for multiplayer sessions. Opens
 * a [com.dangerfield.cards.libraries.rooms.RoomConnectionHandle] via
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
    @Assisted private val roomCode: String,
    @Assisted private val localUserId: String,
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
    private lateinit var handle: com.dangerfield.cards.libraries.rooms.RoomConnectionHandle

    override fun create(
        humanSeatIndex: Int,
        botSpeedProvider: () -> BotSpeed,
        onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit,
    ): PokerSession {
        // botSpeedProvider + humanSeatIndex are bot-mode hints; remote
        // sessions don't act on them. onHandEnded fires through the
        // VM's normal GameEvent collector → handleHandEnded path
        // (achievement detection still works there).
        handle = roomRepository.connect(roomCode)
        return RemotePokerSession(
            handle = handle,
            onLeave = { roomRepository.leaveRoom(roomCode) },
        )
    }

    override suspend fun bootstrap(session: PokerSession) {
        // Tag the crash-reporting scope with the room for the whole time the
        // play session is live — run() suspends until the VM scope tears down,
        // so the finally clears it on every exit (clean leave, room closed, or
        // VM cleared). Feedback filed mid-game then carries room_code, the
        // pivot to this room's server traces/logs.
        telemetry.setRoom(roomCode)
        try {
            (session as RemotePokerSession).run()
        } finally {
            telemetry.setRoom(null)
        }
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
                    personality = com.dangerfield.cards.libraries.game.Personality(
                        label = seat.displayName,
                        style = com.dangerfield.cards.libraries.game.PlayStyle.Unknown,
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
            turnTimerEnforced = true,
            curve = curve,
        )
    }
}
