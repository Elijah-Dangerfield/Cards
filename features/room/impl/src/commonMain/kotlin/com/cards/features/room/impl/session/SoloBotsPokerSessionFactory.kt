package com.dangerfield.cards.features.room.impl.session

import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.features.room.impl.ui.label

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.game.Personality
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.StakeTier
import com.dangerfield.cards.libraries.identity.profile.Profile
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * Production [PokerSessionFactory] for solo (bot-only) sessions.
 *
 * Owns the bot-personality assignment, knows how to construct a
 * [LocalBotsSession], and projects [TableUiState] using the personalities it
 * picked. When MP lands (Phase 4), a sibling `RemotePokerSessionFactory`
 * will satisfy the same interface using server-supplied occupant metadata.
 *
 * Assisted-injected on the [BotDifficulty] + seat count parameters — the
 * `FeatureEntryPoint` constructs an instance per route. Stakes are derived
 * from the difficulty so the three home-screen entry points (Casual /
 * Standard / Challenging) each map to a fixed [StakeTier].
 */
class SoloBotsPokerSessionFactory @Inject constructor(
    @Assisted private val difficulty: BotDifficulty,
    @Assisted private val seatCount: Int,
    private val dispatchers: DispatcherProvider,
) : PokerSessionFactory {

    private val botPersonalities: List<BotPersonality> =
        BotPersonality.forDifficulty(difficulty, seatCount - 1)

    private val stakeTier: StakeTier = difficulty.toStakeTier()

    override val difficultyName: String = difficulty.name

    override val xpMode: XpMode = XpMode.BOTS

    /**
     * Personalities indexed by seat. Computed once per session-factory
     * instance so the [tableFor] projection and [occupantsFor] mapping see
     * the same personality at each seat across emissions.
     */
    private var personalitiesBySeat: Map<Int, BotPersonality> = emptyMap()
    private var humanSeatIndex: Int = 0

    override fun create(
        humanSeatIndex: Int,
        botSpeedProvider: () -> BotSpeed,
        onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit,
    ): PokerSession {
        this.humanSeatIndex = humanSeatIndex
        this.personalitiesBySeat = buildMap {
            var iter = 0
            for (i in 0 until botPersonalities.size + 1) {
                if (i == humanSeatIndex) continue
                put(i, botPersonalities[iter % botPersonalities.size])
                iter += 1
            }
        }
        return LocalBotsSession(
            difficulty = difficulty,
            humanSeatIndex = humanSeatIndex,
            botPersonalities = botPersonalities,
            settings = stakeTier.toRoomSettings(),
            botSpeedProvider = botSpeedProvider,
            onHandEnded = onHandEnded,
            dispatchers = dispatchers,
        )
    }

    override suspend fun bootstrap(session: PokerSession) {
        (session as LocalBotsSession).runUntilHumansTurnOrComplete()
    }

    override fun humanSeatIndex(state: GameState): Int = humanSeatIndex

    override fun occupantsFor(state: GameState, curve: LevelCurve): List<SeatOccupant> = state.seats.map { seat ->
        val personality = personalitiesBySeat[seat.index]?.let { p ->
            // Map :libraries:bots BotPersonality → :libraries:game Personality
            // primitives. The two domains overlap conceptually but live in
            // separate libraries so we don't drag bot-decision code into the
            // shared game-data module.
            Personality(
                label = p.name,
                style = com.dangerfield.cards.libraries.game.PlayStyle.Unknown,
            )
        }
        seatToOccupant(seat, personality, curve)
    }

    override fun tableFor(
        state: GameState,
        lastWinners: GameEvent.HandEnded?,
        lastActionBySeat: Map<Int, PlayerAction>,
        humanProfile: Profile.Authenticated?,
        humanLevel: Int?,
        curve: LevelCurve,
    ): TableUiState = TableUiState.fromGameState(
        gameState = state,
        humanSeatIndex = humanSeatIndex,
        personalitiesBySeat = personalitiesBySeat,
        lastWinners = lastWinners,
        lastActionBySeat = lastActionBySeat,
        humanProfile = humanProfile,
        humanLevel = humanLevel,
        botDifficultyLabel = difficultyName,
        curve = curve,
    )
}

private fun BotDifficulty.toStakeTier(): StakeTier = when (this) {
    BotDifficulty.Casual -> StakeTier.Casual
    BotDifficulty.Standard -> StakeTier.Standard
    BotDifficulty.Challenging -> StakeTier.High
}
