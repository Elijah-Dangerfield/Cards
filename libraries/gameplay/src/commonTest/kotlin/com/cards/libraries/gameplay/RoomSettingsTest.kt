package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [RoomSettings.forBuyIn] — the buy-in → stakes derivation a host's slider
 * drives. Every result must satisfy [RoomSettings]'s own invariants (the init
 * block), so the table is always playable whatever buy-in is chosen.
 */
class RoomSettingsTest {

    @Test
    fun forBuyIn_scalesBlindsAtAHundredBigBlinds() {
        val settings = RoomSettings.forBuyIn(buyIn = 5_000, maxSeats = 6)
        assertEquals(5_000, settings.startingStack)
        assertEquals(50, settings.bigBlind, "bigBlind = buyIn / 100")
        assertEquals(25, settings.smallBlind, "smallBlind = bigBlind / 2")
    }

    @Test
    fun forBuyIn_clampsToTheValidRange() {
        val tooSmall = RoomSettings.forBuyIn(buyIn = 1, maxSeats = 2)
        assertEquals(RoomSettings.MIN_BUY_IN, tooSmall.startingStack)

        val tooBig = RoomSettings.forBuyIn(buyIn = Long.MAX_VALUE, maxSeats = 9)
        assertEquals(RoomSettings.MAX_BUY_IN, tooBig.startingStack)
    }

    @Test
    fun forBuyIn_floorsBlindsSoSmallTablesStayValid() {
        // At the minimum buy-in the naive bigBlind (buyIn/100 = 1) would be
        // below the 2/1 floor; forBuyIn must floor it so RoomSettings' init
        // (startingStack >= 10 big blinds, bigBlind >= smallBlind) holds.
        val settings = RoomSettings.forBuyIn(buyIn = RoomSettings.MIN_BUY_IN, maxSeats = 2)
        assertEquals(2, settings.bigBlind)
        assertEquals(1, settings.smallBlind)
        assertTrue(settings.startingStack >= settings.bigBlind * 10)
    }

    @Test
    fun defaultHostBuyIn_isASensibleFractionOfTheStarterGrant() {
        // ROOM-13: the create-table screen opens on this. The 10,000-chip starter
        // grant is the reference bankroll; a first-time host should commit a slice,
        // not half of it, so they can rebuy and sit a second table. Pin it within
        // 5..25% of the grant and on a tidy slider step.
        val starterGrant = 10_000L
        assertTrue(
            RoomSettings.DEFAULT_HOST_BUY_IN in (starterGrant / 20)..(starterGrant / 4),
            "host default ${RoomSettings.DEFAULT_HOST_BUY_IN} should be 5..25% of the $starterGrant grant",
        )
        assertEquals(0L, RoomSettings.DEFAULT_HOST_BUY_IN % 50, "must land on the 50-chip slider step")
        assertTrue(RoomSettings.DEFAULT_HOST_BUY_IN >= RoomSettings.MIN_BUY_IN)

        // It must also derive a fully valid table (init invariants hold).
        val settings = RoomSettings.forBuyIn(RoomSettings.DEFAULT_HOST_BUY_IN, maxSeats = 6)
        assertEquals(RoomSettings.DEFAULT_HOST_BUY_IN, settings.startingStack)
        assertTrue(settings.startingStack >= settings.bigBlind * 10)
    }

    @Test
    fun forBuyIn_isAlwaysConstructible_acrossTheRange() {
        // forBuyIn returning means RoomSettings.init didn't throw — assert it
        // holds across a sweep of buy-ins and seat counts.
        val buyIns = listOf(100L, 137L, 250L, 999L, 5_000L, 100_000L, 1_000_000L)
        for (buyIn in buyIns) {
            for (seats in 2..9) {
                val s = RoomSettings.forBuyIn(buyIn, seats)
                assertTrue(s.smallBlind > 0)
                assertTrue(s.bigBlind >= s.smallBlind)
                assertTrue(s.startingStack >= s.bigBlind * 10)
                assertEquals(seats, s.maxSeats)
            }
        }
    }
}
