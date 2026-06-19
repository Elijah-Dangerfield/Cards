package com.cards.integration.setup

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.gameplay.scrubbedFor
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.server.routes.RoomClientFrame
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client and the server define the outbound socket frames as **separate**
 * sealed types ([ClientFrame] vs [RoomClientFrame]) that must agree on the wire.
 * Every other test mocks one side; only the same-repo setup can prove the two
 * serialize to the same bytes. These cross-decode tests pin that contract: a
 * `@SerialName` or field rename on either side fails here immediately, before it
 * can ship as a runtime "unknown frame" on a real socket.
 *
 * The wire `Json` mirrors production on both sides (`classDiscriminator = "type"`,
 * `ignoreUnknownKeys = true` — see `RoomSocketJson` / `RoomSocketRoutes`).
 */
class WireFormatContractTest {

    private val wire = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    @Test
    fun startHand_clientFrame_decodesAsServerFrame() {
        val decoded = roundTripToServer(ClientFrame.StartHand(clientNonce = "n-start"))
        assertTrue(decoded is RoomClientFrame.StartHand, "got $decoded")
        assertEquals("n-start", decoded.clientNonce)
    }

    @Test
    fun requestNextHand_clientFrame_decodesAsServerFrame() {
        val decoded = roundTripToServer(ClientFrame.RequestNextHand(clientNonce = "n-next"))
        assertTrue(decoded is RoomClientFrame.RequestNextHand, "got $decoded")
        assertEquals("n-next", decoded.clientNonce)
    }

    @Test
    fun submitIntent_clientFrame_decodesAsServerFrame_withIntentIntact() {
        val decoded = roundTripToServer(
            ClientFrame.SubmitIntent(
                intent = PlayerIntent.Raise(seatIndex = 3, totalAmountThisStreet = 120, clientNonce = "i-1"),
                clientNonce = "n-intent",
            ),
        )
        assertTrue(decoded is RoomClientFrame.SubmitIntent, "got $decoded")
        assertEquals("n-intent", decoded.clientNonce)
        val intent = decoded.intent
        assertTrue(intent is PlayerIntent.Raise, "got $intent")
        assertEquals(3, intent.seatIndex)
        assertEquals(120, intent.totalAmountThisStreet)
    }

    @Test
    fun serverFrame_decodesBackAsClientFrame() {
        // The reverse direction — a server-authored frame the client must read.
        val json = wire.encodeToString(RoomClientFrame.serializer(), RoomClientFrame.StartHand("rev"))
        val decoded = wire.decodeFromString(ClientFrame.serializer(), json)
        assertTrue(decoded is ClientFrame.StartHand && decoded.clientNonce == "rev", "got $decoded")
    }

    /**
     * The server broadcasts each hand as a `GameState` (wrapped in
     * `GameStateSnapshot`), scrubbed per-recipient via [scrubbedFor]. Every
     * *public* `Seat` field is meant to reach an opponent's view — only
     * private hole cards are scrubbed. This pins that contract field-by-field:
     * serialize the scrubbed state through the shared `GameState` serializer
     * (same bytes both sides decode), and assert the opponent's seat survives
     * intact. A new public seat field that doesn't ride the wire — or one the
     * scrub accidentally drops — fails here instead of shipping as a silently
     * missing avatar / badge / level on a real opponent's screen.
     */
    @Test
    fun publicSeatFields_reachAnOpponentsView_overTheWire() {
        val opponent = Seat(
            index = 1,
            playerId = "opponent",
            displayName = "Rival",
            stack = 1_500,
            seatStatus = SeatStatus.Active,
            handParticipation = HandParticipation.InHand,
            isBot = false,
            contributedThisStreet = 50,
            contributedThisHand = 120,
            holeCards = listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Hearts)),
            hasActedThisStreet = true,
            badgeProductIds = listOf("badge_legend", "title_shark"),
            avatarEmoji = "🦈",
            avatarBackgroundColor = "#FF8800",
            xp = 2_500,
        )
        val viewerSeat = opponent.copy(index = 0, playerId = "viewer", displayName = "Me")
        val state = GameState(
            settings = RoomSettings.Default,
            handNumber = 3,
            buttonSeatIndex = 0,
            seats = listOf(viewerSeat, opponent),
            community = emptyList(),
            street = BettingRound.Flop,
            currentBetThisStreet = 50,
            lastFullRaiseSize = 50,
            actingSeatIndex = 0,
            deckRemaining = emptyList(),
        )

        // Scrub for the viewer (seat 0), serialize, decode — exactly the
        // round trip the socket performs before a snapshot reaches a client.
        val scrubbed = state.scrubbedFor(viewerSeatIndex = 0)
        val onWire = wire.decodeFromString(
            GameState.serializer(),
            wire.encodeToString(GameState.serializer(), scrubbed),
        )

        val decodedOpponent = onWire.seats.single { it.index == 1 }
        assertEquals("opponent", decodedOpponent.playerId)
        assertEquals("Rival", decodedOpponent.displayName)
        assertEquals(1_500, decodedOpponent.stack)
        assertEquals(SeatStatus.Active, decodedOpponent.seatStatus)
        assertEquals(HandParticipation.InHand, decodedOpponent.handParticipation)
        assertEquals(false, decodedOpponent.isBot)
        assertEquals(50, decodedOpponent.contributedThisStreet)
        assertEquals(120, decodedOpponent.contributedThisHand)
        assertEquals(true, decodedOpponent.hasActedThisStreet)
        assertEquals(listOf("badge_legend", "title_shark"), decodedOpponent.badgeProductIds)
        assertEquals("🦈", decodedOpponent.avatarEmoji)
        assertEquals("#FF8800", decodedOpponent.avatarBackgroundColor)
        assertEquals(2_500, decodedOpponent.xp)
        // The one field that must NOT reach an opponent: pre-showdown hole cards.
        assertTrue(decodedOpponent.holeCards.isEmpty(), "opponent hole cards must be scrubbed")
    }

    /** Encode a client frame, decode it as the server's frame type. */
    private fun roundTripToServer(frame: ClientFrame): RoomClientFrame =
        wire.decodeFromString(RoomClientFrame.serializer(), wire.encodeToString(ClientFrame.serializer(), frame))
}
