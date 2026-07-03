package com.dangerfield.cards.features.room.impl.session

import com.dangerfield.cards.features.room.impl.TableUiState

import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.GameSpeed
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.game.Personality
import com.dangerfield.cards.libraries.game.PlayStyle
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.identity.profile.Profile

/**
 * Factory the VM depends on for session creation + occupant derivation, so the
 * VM is decoupled from concrete session construction (bot-personality/difficulty
 * for solo, room-code for MP) and the bot-loop bootstrap. Solo uses
 * `SoloBotsPokerSessionFactory`; MP uses `RemotePokerSessionFactory`; tests use
 * a fake.
 */
interface PokerSessionFactory {
    val difficultyName: String

    /**
     * Which [XpMode] this session counts for. Drives progression attribution
     * and gating for prestige signals like `ReviewTrigger.SessionEnd` — an
     * MP disconnect shouldn't masquerade as a positive moment.
     */
    val xpMode: XpMode

    /**
     * Room code for the session, or `null` when there is no shareable code
     * (solo bot games). Constant for the session's life — set at construction
     * by MP, absent for solo. The play screen surfaces it in the cheat sheet
     * so a host can still share the code after the lobby is gone.
     */
    val roomCode: String? get() = null

    fun create(
        humanSeatIndex: Int,
        gameSpeedProvider: () -> GameSpeed,
        onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit,
    ): PokerSession

    /**
     * Start the session's run loop — the bot loop for solo, the WebSocket for
     * remote. Suspends until the session is torn down.
     */
    suspend fun bootstrap(session: PokerSession)

    /** Derive the [SeatOccupant] list from engine state; [curve] sets opponent levels. */
    fun occupantsFor(state: GameState, curve: LevelCurve = DefaultLevelCurve): List<SeatOccupant>

    /**
     * The local human's seat index in [state] (MP seats vary, so this matches
     * the local user id per seat). Per-hand attribution keys off it. Returns
     * `-1` when the human isn't seated (pre-snapshot / spectator) so attribution
     * degrades to "no credit" rather than crediting another seat.
     */
    fun humanSeatIndex(state: GameState): Int

    /**
     * Project engine state into a [TableUiState]. The factory owns this because
     * inputs differ by session type (solo knows bot personalities locally; MP
     * sources them from server occupant metadata). [lastWinners] and
     * [lastActionBySeat] are per-hand transients the VM tracks from events.
     */
    fun tableFor(
        state: GameState,
        lastWinners: GameEvent.HandEnded? = null,
        lastActionBySeat: Map<Int, PlayerAction> = emptyMap(),
        humanProfile: Profile.Authenticated? = null,
        humanLevel: Int? = null,
        curve: LevelCurve = DefaultLevelCurve,
    ): TableUiState
}

/**
 * Maps an engine [Seat] to a [SeatOccupant] given an optional personality (solo
 * supplies it; MP sources it from server occupant metadata). Used by
 * [PokerSessionFactory] implementations and tests.
 */
internal fun seatToOccupant(
    seat: Seat,
    personality: Personality?,
    curve: LevelCurve = DefaultLevelCurve,
): SeatOccupant = when {
    seat.playerId == null -> SeatOccupant.Empty(seatIndex = seat.index)
    seat.isBot -> SeatOccupant.Bot(
        seatIndex = seat.index,
        displayName = seat.displayName,
        personality = personality ?: Personality(label = seat.displayName, style = PlayStyle.Unknown),
    )
    else -> SeatOccupant.Human(
        seatIndex = seat.index,
        displayName = seat.displayName,
        userId = seat.playerId ?: "",
        personality = personality,
        // Derived from server-snapshotted Seat.xp through the same curve as the
        // local human's; 0 until it resolves.
        level = seat.xp?.let { levelProgressFor(it, curve).level } ?: 0,
        leagueTier = null, // sourced from league repo (V1.1)
    )
}
