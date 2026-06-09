package com.cards.integration.setup

import com.dangerfield.cards.libraries.gameplay.PlayerIntent
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

    /** Encode a client frame, decode it as the server's frame type. */
    private fun roundTripToServer(frame: ClientFrame): RoomClientFrame =
        wire.decodeFromString(RoomClientFrame.serializer(), wire.encodeToString(ClientFrame.serializer(), frame))
}
