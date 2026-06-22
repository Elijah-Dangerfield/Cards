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
