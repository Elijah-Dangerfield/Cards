package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandCategory
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandRank
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.Pot
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.Suit
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the server → client wire contract for the gameplay frames: a fully
 * populated [GameState] snapshot and every [GameEvent] / [PlayerAction]
 * variant round-trip losslessly through [RoomSocketJson] against the exact
 * [RoomSocketEventDto.serializer] the production decode path
 * ([ReconnectingRoomSocket]) uses. The gameplay types are shared between
 * client and server (`:libraries:gameplay`), so this guards the other drift
 * vector: a field that silently drops at the serialization boundary (a
 * `@Transient`, a non-serializable type, a sealed variant added without a
 * `@SerialName`) breaks the deep-equality assert here before it ships.
 *
 * The `ClientFrame` ↔ server `RoomClientFrame` direction (genuinely two
 * separate type hierarchies) is pinned by the integration
 * `WireFormatContractTest`; this file pins the shared-type frames.
 */
class RoomSocketWireFormatTest {

    @Test
    fun gameStateSnapshot_roundTripsLosslessly() {
        val original: RoomSocketEventDto = RoomSocketEventDto.GameStateSnapshot(richGameState())

        val decoded = roundTrip(original)

        assertEquals(
            original,
            decoded,
            "a fully-populated GameState snapshot must survive the wire byte-for-byte",
        )
    }

    @Test
    fun everyGameEventVariant_roundTripsLosslessly() {
        // Enumerated, not generated: a new GameEvent variant added without a
        // @SerialName (or carrying a non-serializable field) fails the moment
        // it's appended here — the same forcing function as an exhaustive `when`.
        val variants: List<GameEvent> = listOf(
            GameEvent.HandStarted(sequence = 1, handNumber = 7, buttonSeatIndex = 2),
            GameEvent.BlindPosted(sequence = 2, seatIndex = 0, amount = 25, isSmall = true),
            GameEvent.HoleCardsDealt(
                sequence = 3,
                seatIndex = 1,
                cards = listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
            ),
            GameEvent.StreetAdvanced(
                sequence = 4,
                street = BettingRound.Flop,
                communityCards = listOf(
                    Card(Rank.Two, Suit.Clubs),
                    Card(Rank.Seven, Suit.Diamonds),
                    Card(Rank.Ten, Suit.Hearts),
                ),
            ),
            GameEvent.ActionTaken(
                sequence = 5,
                seatIndex = 0,
                action = PlayerAction.Raise(totalStreetContribution = 150, raiseAmount = 100),
                resultingStreetContribution = 150,
            ),
            GameEvent.PotAwarded(
                sequence = 6,
                seatIndex = 1,
                amount = 300,
                potIndex = 0,
                withCategory = HandCategory.FullHouse,
            ),
            GameEvent.HandEnded(
                sequence = 7,
                winners = listOf(
                    HandWinner(
                        seatIndex = 1,
                        amount = 300,
                        handRank = HandRank(
                            category = HandCategory.FullHouse,
                            tiebreakers = listOf(14, 13),
                            bestFive = listOf(
                                Card(Rank.Ace, Suit.Spades),
                                Card(Rank.Ace, Suit.Hearts),
                                Card(Rank.Ace, Suit.Clubs),
                                Card(Rank.King, Suit.Spades),
                                Card(Rank.King, Suit.Hearts),
                            ),
                        ),
                        byFold = false,
                    ),
                ),
                board = listOf(
                    Card(Rank.Ace, Suit.Clubs),
                    Card(Rank.King, Suit.Hearts),
                    Card(Rank.Two, Suit.Diamonds),
                    Card(Rank.Five, Suit.Spades),
                    Card(Rank.Nine, Suit.Clubs),
                ),
                revealedHoleCards = mapOf(
                    0 to listOf(Card(Rank.Queen, Suit.Clubs), Card(Rank.Jack, Suit.Diamonds)),
                    1 to listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
                ),
            ),
        )

        variants.forEach { event ->
            val original: RoomSocketEventDto =
                RoomSocketEventDto.GameEventOccurred(seq = event.sequence, event = event)

            val decoded = roundTrip(original)

            assertEquals(
                original,
                decoded,
                "${event::class.simpleName} must survive the wire byte-for-byte",
            )
        }
    }

    @Test
    fun everyPlayerActionVariant_roundTripsLosslessly() {
        // PlayerAction rides inside ActionTaken; pin the whole sealed hierarchy so
        // a new action kind can't slip across the wire untested.
        val actions: List<PlayerAction> = listOf(
            PlayerAction.Fold,
            PlayerAction.Check,
            PlayerAction.Call(amount = 50),
            PlayerAction.Bet(amount = 100),
            PlayerAction.Raise(totalStreetContribution = 200, raiseAmount = 100),
            PlayerAction.AllIn(amount = 980),
        )

        actions.forEach { action ->
            val original: RoomSocketEventDto = RoomSocketEventDto.GameEventOccurred(
                seq = 1,
                event = GameEvent.ActionTaken(
                    sequence = 1,
                    seatIndex = 0,
                    action = action,
                    resultingStreetContribution = 200,
                ),
            )

            val decoded = roundTrip(original)

            assertEquals(
                original,
                decoded,
                "${action::class.simpleName} must survive the wire byte-for-byte",
            )
        }
    }

    @Test
    fun snapshot_withUnknownEnumValue_coercesToDefault_inReleaseConfig() {
        // Tests run in debug, where RoomSocketJson stays strict, so we mirror the
        // release config (coerceInputValues on) here to pin the forward-compat
        // contract: a server status/visibility value this client doesn't know
        // coerces to the property default instead of dropping the whole frame.
        val releaseJson = Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val frame = """
            {
              "type": "snapshot",
              "room": {
                "code": "ABCDEF",
                "hostUserId": "11111111-1111-1111-1111-111111111111",
                "createdAtEpochMs": 1700000000000,
                "maxSeats": 6,
                "status": "Brand_New_Status",
                "members": [],
                "visibility": "Brand_New_Visibility"
              }
            }
        """.trimIndent()

        val decoded = releaseJson.decodeFromString(RoomSocketEventDto.serializer(), frame)

        val room = (decoded as RoomSocketEventDto.Snapshot).room
        assertEquals(RoomStatusDto.Unknown, room.status, "an unknown status coerces to the default")
        assertEquals(
            RoomVisibilityDto.Private,
            room.visibility,
            "an unknown visibility coerces to the pessimistic default",
        )
    }

    private fun roundTrip(original: RoomSocketEventDto): RoomSocketEventDto {
        val encoded = RoomSocketJson.encodeToString(RoomSocketEventDto.serializer(), original)
        return RoomSocketJson.decodeFromString(RoomSocketEventDto.serializer(), encoded)
    }

    private fun richGameState(): GameState = GameState(
        settings = RoomSettings.Default,
        handNumber = 12,
        buttonSeatIndex = 1,
        seats = listOf(
            Seat(
                index = 0,
                playerId = "11111111-1111-1111-1111-111111111111",
                displayName = "Ada",
                stack = 1_480,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.InHand,
                isBot = false,
                contributedThisStreet = 100,
                contributedThisHand = 120,
                holeCards = listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Spades)),
                hasActedThisStreet = true,
                badgeProductIds = listOf("badge_shark", "title_grinder"),
                avatarEmoji = "🐢",
                avatarBackgroundColor = "#1B5E20",
                xp = 4_200,
            ),
            Seat(
                index = 1,
                playerId = "22222222-2222-2222-2222-222222222222",
                displayName = "Babbage",
                stack = 0,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.AllIn,
                isBot = false,
                contributedThisStreet = 100,
                contributedThisHand = 1_500,
                holeCards = listOf(Card(Rank.Queen, Suit.Hearts), Card(Rank.Queen, Suit.Diamonds)),
                hasActedThisStreet = true,
                badgeProductIds = emptyList(),
                avatarEmoji = null,
                avatarBackgroundColor = null,
                xp = null,
            ),
            Seat(
                index = 2,
                playerId = "bot-greedy",
                displayName = "Greedy",
                stack = 740,
                seatStatus = SeatStatus.Active,
                handParticipation = HandParticipation.Folded,
                isBot = true,
                contributedThisStreet = 0,
                contributedThisHand = 25,
                holeCards = emptyList(),
                hasActedThisStreet = true,
            ),
        ),
        community = listOf(
            Card(Rank.Ten, Suit.Clubs),
            Card(Rank.Five, Suit.Hearts),
            Card(Rank.Two, Suit.Spades),
        ),
        street = BettingRound.Flop,
        currentBetThisStreet = 100,
        lastFullRaiseSize = 100,
        actingSeatIndex = 0,
        deckRemaining = listOf(Card(Rank.Three, Suit.Diamonds), Card(Rank.Eight, Suit.Clubs)),
        pots = listOf(
            Pot(amount = 1_545, eligibleSeatIndexes = listOf(0, 1)),
            Pot(amount = 200, eligibleSeatIndexes = listOf(0)),
        ),
        lastSequence = 42,
    )
}
