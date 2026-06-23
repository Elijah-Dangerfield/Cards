package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.GameEngine
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.deterministicDeck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the [RngBotDecider] adapter as behaviourally identical to a direct
 * [BotDecision.choose] call. This is the regression gate for the seam: as long
 * as this passes, routing [LocalBotsSession]'s bot loop through [BotDecider]
 * with the default [RngBotDecider] cannot change how bots play.
 */
class BotDeciderTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )

    @Test
    fun rngBotDecider_matchesDirectChoose_forSameSeed() {
        val state = GameEngine.startHand(
            settings = settings,
            seats = listOf(seat(0), seat(1)),
            handNumber = 1,
            buttonSeatIndex = 0,
            deck = deterministicDeck(42),
        ).state

        val acting = state.actingSeatIndex!!

        // Identical inputs, including a freshly-seeded Random for each call so
        // the RNG sequences are the same.
        val direct = BotDecision.choose(
            state = state,
            seatIndex = acting,
            personality = BotPersonality.Jane,
            difficulty = BotDifficulty.Standard,
            random = Random(123L),
            equityIterations = 200,
        )
        val viaDecider = RngBotDecider.decide(
            BotDecisionRequest(
                state = state,
                seatIndex = acting,
                personality = BotPersonality.Jane,
                difficulty = BotDifficulty.Standard,
                random = Random(123L),
                equityIterations = 200,
            ),
        )

        assertEquals(direct.intent, viaDecider.intent, "intent must match the direct call")
        assertEquals(direct.thought, viaDecider.thought, "thought must match the direct call")
    }

    private fun seat(index: Int): Seat = Seat(
        index = index,
        playerId = "p$index",
        displayName = "P$index",
        stack = settings.startingStack,
        seatStatus = SeatStatus.Active,
        handParticipation = HandParticipation.InHand,
    )
}
