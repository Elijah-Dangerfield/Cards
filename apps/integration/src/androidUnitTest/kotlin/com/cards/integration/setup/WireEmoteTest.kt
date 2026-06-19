package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatFor
import com.cards.integration.helpers.seatTwoAndConnect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Table emotes over the REAL wire. An emote a player blasts rides a
 * `ClientFrame.SendEmoji` to the server, which fans it out to every
 * socket in the room as a seat-attributed `GameplayFrame.EmojiBlast`.
 * The gameplay flows are state-only, so this exercises the dedicated
 * ephemeral broadcast channel end-to-end: two real clients, the real
 * server, real sockets.
 */
class WireEmoteTest : IntegrationTest() {

    @Test
    fun emote_ridesTheWire_andReachesTheOpponentAttributedToTheSenderSeat() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        // A live hand seats both players — the session exists, so the
        // emote channel is wired. Read one dealt snapshot to learn the
        // host's seat index.
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val hostSeat = dealt.seatFor(table.host).index

        table.hostGame.sendEmote("🎉")

        val received = table.joinerGame.nextEmote()
        assertEquals(hostSeat, received.seatIndex, "the opponent must see the emote attributed to the sender's seat")
        assertEquals("🎉", received.emoji)
    }
}
