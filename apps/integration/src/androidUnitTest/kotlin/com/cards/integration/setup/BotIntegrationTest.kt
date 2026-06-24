package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.driveToCompletion
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.rooms.AddBotOutcome
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **Backend bots over the wire.** The host can seat a bot while the room is in
 * the lobby (it shows up on every socket), can't once a hand is live, and once
 * seated the server drives the bot's turns with no human acting for it.
 */
class BotIntegrationTest : IntegrationTest() {

    @Test
    fun host_addsBotInLobby_botSeatAppearsOnTheSocket() = integration {
        val host = client()
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom())
        val code = created.room.code
        val hostGame = gameplay(host.connect(code)).also { it.awaitConnected() }

        val outcome = host.repository.addBot(code)
        val success = assertIs<AddBotOutcome.Success>(outcome)
        assertTrue(success.room.members.any { it.isBot }, "the add-bot response carries the bot seat")

        // ...and it propagates to the live socket.
        hostGame.awaitRoom { it.members.any { m -> m.isBot } }
    }

    @Test
    fun addBot_onceTheHandIsLive_isRejected() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        table.hostGame.nextSnapshot { it.actingSeatIndex != null } // room is Playing now

        val outcome = table.host.repository.addBot(table.code)
        assertIs<AddBotOutcome.NotJoinable>(outcome)
    }

    @Test
    fun seatedBot_isDrivenByTheServer_withNoHumanActingForIt() = integration {
        val host = client()
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom())
        val code = created.room.code
        assertIs<AddBotOutcome.Success>(host.repository.addBot(code))
        val hostGame = gameplay(host.connect(code)).also { it.awaitConnected() }
        hostGame.awaitRoom { it.members.size == 2 && it.members.any { m -> m.isBot } }

        hostGame.startHand()
        // Drive ONLY the host's turns; the bot's turns are the server's job. The
        // hand can only reach completion if the bot acts on its own — so a
        // completed hand proves the bot driver ran.
        val completed = driveToCompletion(hostGame, mapOf(host.userId to hostGame))
        assertEquals(
            BettingRound.Complete,
            completed.street,
            "the server drove the bot's turns to completion with no human acting for it",
        )
    }
}
