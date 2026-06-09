package com.cards.integration.setup

import com.cards.integration.helpers.GameplaySession
import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.TestClient
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In-hand play through the REAL wire: two real clients, the real server gameplay
 * engine, real sockets. This is the heart of the mission — once players are seated,
 * a hand actually plays, the contract holds, and one client can't act for another.
 *
 * Driven at the [com.dangerfield.cards.libraries.rooms.RoomConnectionHandle] level
 * (via [GameplaySession]) rather than the full play view model — the wire + engine
 * is what's under test here.
 */
class InHandPlayTest : IntegrationTest() {

    @Test
    fun twoClients_playHeadsUpHand_toCompletion_viaFold() = integration {
        val host = client()
        val joiner = client()
        val code = createAndJoin(host, joiner)
        val hostGame = gameplay(host.connect(code))
        val joinerGame = gameplay(joiner.connect(code))
        hostGame.awaitConnected()
        joinerGame.awaitConnected()

        hostGame.startHand()

        // Both clients see the hand dealt with someone to act.
        val dealt = hostGame.nextSnapshot { it.actingSeatIndex != null }
        joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // The player to act folds; heads-up that ends the hand.
        val actingSeat = dealt.actingSeatIndex!!
        val actingGame = gameFor(dealt.seatAt(actingSeat).playerId, host, hostGame, joiner, joinerGame)
        val ack = actingGame.submit(PlayerIntent.Fold(seatIndex = actingSeat))
        assertTrue(ack.accepted, "fold should be accepted, error=${ack.error}")

        // Both clients converge on a completed hand.
        hostGame.nextSnapshot { it.street == BettingRound.Complete }
        joinerGame.nextSnapshot { it.street == BettingRound.Complete }
    }

    @Test
    fun holeCards_areScrubbedPerRecipient() = integration {
        val host = client()
        val joiner = client()
        val code = createAndJoin(host, joiner)
        val hostGame = gameplay(host.connect(code))
        val joinerGame = gameplay(joiner.connect(code))
        hostGame.awaitConnected()
        joinerGame.awaitConnected()

        hostGame.startHand()

        val hostView = hostGame.nextSnapshot { it.actingSeatIndex != null }
        val joinerView = joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // Each client sees its own hole cards but never the opponent's.
        assertTrue(hostView.seatFor(host).holeCards.isNotEmpty(), "host should see its own cards")
        assertTrue(hostView.seatFor(joiner).holeCards.isEmpty(), "host must NOT see joiner's cards")
        assertTrue(joinerView.seatFor(joiner).holeCards.isNotEmpty(), "joiner should see its own cards")
        assertTrue(joinerView.seatFor(host).holeCards.isEmpty(), "joiner must NOT see host's cards")
    }

    @Test
    fun outOfTurnIntent_isRejected() = integration {
        val host = client()
        val joiner = client()
        val code = createAndJoin(host, joiner)
        val hostGame = gameplay(host.connect(code))
        val joinerGame = gameplay(joiner.connect(code))
        hostGame.awaitConnected()
        joinerGame.awaitConnected()

        hostGame.startHand()
        val dealt = hostGame.nextSnapshot { it.actingSeatIndex != null }

        // The player who is NOT to act tries to fold their own seat — out of turn.
        val actingUserId = dealt.seatAt(dealt.actingSeatIndex!!).playerId
        val idle = if (actingUserId == host.userId) joiner else host
        val idleGame = if (actingUserId == host.userId) joinerGame else hostGame
        val ack = idleGame.submit(PlayerIntent.Fold(seatIndex = dealt.seatFor(idle).index))
        assertFalse(ack.accepted, "an out-of-turn intent must be rejected")
    }

    // ---- helpers ----

    private suspend fun createAndJoin(host: TestClient, joiner: TestClient): String {
        val created = host.repository.createRoom()
        check(created is CreateRoomOutcome.Success) { "createRoom failed: $created" }
        joiner.repository.joinRoom(created.room.code)
        return created.room.code
    }

    private fun GameState.seatFor(client: TestClient): Seat =
        seats.first { it.playerId == client.userId }

    private fun gameFor(
        playerId: String?,
        host: TestClient,
        hostGame: GameplaySession,
        joiner: TestClient,
        joinerGame: GameplaySession,
    ): GameplaySession = when (playerId) {
        host.userId -> hostGame
        joiner.userId -> joinerGame
        else -> error("acting seat $playerId is neither host nor joiner")
    }
}
