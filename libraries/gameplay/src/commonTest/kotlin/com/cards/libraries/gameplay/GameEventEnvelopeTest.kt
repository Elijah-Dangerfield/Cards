package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the v1 [GameEventEnvelope] layout — the on-disk shape that
 * future `game_events.event_jsonb` rows will carry. Every event variant
 * round-trips through the envelope, and a representative variant has
 * its exact JSON shape asserted so a future field rename or
 * `@SerialName` change fails loudly here before it lands in a
 * persisted row.
 */
class GameEventEnvelopeTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun envelopeUsesV1PlusTypePlusPayloadShape() {
        val event = GameEvent.HandStarted(sequence = 7, handNumber = 3, buttonSeatIndex = 2)
        val envelope = event.toEnvelope()

        assertEquals(1, envelope.version)
        assertEquals("HandStarted", envelope.type)
        // Payload carries the event's fields — *no* embedded "type" key,
        // that lives on the outer envelope.
        assertEquals(7L, envelope.payload["sequence"]?.jsonPrimitive?.long)
        assertEquals(3, envelope.payload["handNumber"]?.jsonPrimitive?.int)
        assertEquals(2, envelope.payload["buttonSeatIndex"]?.jsonPrimitive?.int)
        assertNull(envelope.payload["type"], "discriminator must not leak into payload")
    }

    @Test
    fun envelopeJsonShapeIsExactlyVTypePayload() {
        val event = GameEvent.BlindPosted(
            sequence = 1,
            seatIndex = 0,
            amount = 25,
            isSmall = true,
        )

        val encoded = json.encodeToString(GameEventEnvelope.serializer(), event.toEnvelope())
            .let { json.parseToJsonElement(it).jsonObject }

        assertEquals(setOf("v", "type", "payload"), encoded.keys)
        assertEquals(1, encoded["v"]?.jsonPrimitive?.int)
        assertEquals("BlindPosted", encoded["type"]?.jsonPrimitive?.contentOrNull)

        val payload = encoded["payload"]?.jsonObject ?: error("payload missing")
        assertEquals(1L, payload["sequence"]?.jsonPrimitive?.long)
        assertEquals(0, payload["seatIndex"]?.jsonPrimitive?.int)
        assertEquals(25L, payload["amount"]?.jsonPrimitive?.long)
        assertEquals(true, payload["isSmall"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun currentVersionConstantIsOne() {
        // Pin the constant — a future bump means a new envelope shape,
        // which means a new schema-evolution branch in the reader. This
        // test exists so the bump is loud at PR-review time.
        assertEquals(1, GameEventEnvelope.CURRENT_VERSION)
    }

    @Test
    fun roundTripPreservesEveryVariant() {
        val cards = listOf(Card(Rank.Ace, Suit.Spades), Card(Rank.King, Suit.Hearts))
        val variants: List<GameEvent> = listOf(
            GameEvent.HandStarted(sequence = 1, handNumber = 1, buttonSeatIndex = 0),
            GameEvent.BlindPosted(sequence = 2, seatIndex = 0, amount = 25, isSmall = true),
            GameEvent.BlindPosted(sequence = 3, seatIndex = 1, amount = 50, isSmall = false),
            GameEvent.HoleCardsDealt(sequence = 4, seatIndex = 0, cards = cards),
            GameEvent.StreetAdvanced(
                sequence = 5,
                street = BettingRound.Flop,
                communityCards = listOf(
                    Card(Rank.Two, Suit.Clubs),
                    Card(Rank.Three, Suit.Diamonds),
                    Card(Rank.Four, Suit.Hearts),
                ),
            ),
            GameEvent.ActionTaken(
                sequence = 6,
                seatIndex = 0,
                action = PlayerAction.Fold,
                resultingStreetContribution = 0,
            ),
            GameEvent.ActionTaken(
                sequence = 7,
                seatIndex = 1,
                action = PlayerAction.Check,
                resultingStreetContribution = 0,
            ),
            GameEvent.ActionTaken(
                sequence = 8,
                seatIndex = 1,
                action = PlayerAction.Call(amount = 100),
                resultingStreetContribution = 100,
            ),
            GameEvent.ActionTaken(
                sequence = 9,
                seatIndex = 1,
                action = PlayerAction.Bet(amount = 200),
                resultingStreetContribution = 200,
            ),
            GameEvent.ActionTaken(
                sequence = 10,
                seatIndex = 1,
                action = PlayerAction.Raise(totalStreetContribution = 400, raiseAmount = 200),
                resultingStreetContribution = 400,
            ),
            GameEvent.ActionTaken(
                sequence = 11,
                seatIndex = 1,
                action = PlayerAction.AllIn(amount = 800),
                resultingStreetContribution = 800,
            ),
            GameEvent.PotAwarded(
                sequence = 12,
                seatIndex = 0,
                amount = 1_500,
                potIndex = 0,
                withCategory = HandCategory.Flush,
            ),
            GameEvent.PotAwarded(
                sequence = 13,
                seatIndex = 0,
                amount = 1_500,
                potIndex = 0,
                withCategory = null,
            ),
            GameEvent.HandEnded(
                sequence = 14,
                winners = listOf(
                    HandWinner(seatIndex = 0, amount = 1_500, handRank = null, byFold = true),
                ),
                board = cards,
                revealedHoleCards = mapOf(0 to cards),
            ),
        )

        variants.forEach { event ->
            val envelope = event.toEnvelope()
            val decoded = envelope.toGameEvent()
            assertEquals(event, decoded, "round-trip mismatch for ${event::class.simpleName}")
        }
    }

    @Test
    fun roundTripThroughSerializedString() {
        val event = GameEvent.PotAwarded(
            sequence = 99,
            seatIndex = 2,
            amount = 9_999,
            potIndex = 1,
            withCategory = HandCategory.StraightFlush,
        )

        val asString = json.encodeToString(GameEventEnvelope.serializer(), event.toEnvelope())
        val envelope = json.decodeFromString(GameEventEnvelope.serializer(), asString)
        val decoded = envelope.toGameEvent()

        assertEquals(event, decoded)
    }

    @Test
    fun decodeFailsOnUnknownEnvelopeVersion() {
        val future = GameEventEnvelope(
            version = 99,
            type = "HandStarted",
            payload = JsonObject(emptyMap()),
        )
        val failure = assertFailsWith<IllegalStateException> { future.toGameEvent() }
        assertNotNull(failure.message)
        // Don't pin exact wording — pin that the version surfaces.
        assertEquals(true, failure.message!!.contains("99"))
    }

    @Test
    fun playerActionPayloadCarriesTypeDiscriminator() {
        // Nested polymorphism inside ActionTaken.action still flows through
        // kotlinx — the inner discriminator stays in the payload because
        // only the outer event's discriminator is lifted to the envelope.
        val event = GameEvent.ActionTaken(
            sequence = 1,
            seatIndex = 0,
            action = PlayerAction.Raise(totalStreetContribution = 200, raiseAmount = 100),
            resultingStreetContribution = 200,
        )

        val envelope = event.toEnvelope()
        val action = envelope.payload["action"]?.jsonObject ?: error("action payload missing")
        assertEquals("Raise", (action["type"] as? JsonPrimitive)?.content)
        assertEquals(200L, action["totalStreetContribution"]?.jsonPrimitive?.long)
        assertEquals(100L, action["raiseAmount"]?.jsonPrimitive?.long)
    }
}
