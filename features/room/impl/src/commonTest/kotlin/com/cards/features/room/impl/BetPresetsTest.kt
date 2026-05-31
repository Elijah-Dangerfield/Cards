package com.dangerfield.cards.features.room.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [BetPresets.from] against the short-stack edge case that crashed the
 * raise sheet (Sentry CARDS-2): when a seat is too short to make a full
 * min-raise, `maxRaiseTotal` sits *below* `minRaiseTotal`, and the preset
 * builder used to feed that inverted range into `coerceIn`, throwing
 * `IllegalArgumentException: Cannot coerce value to an empty range`.
 *
 * `from` must return no presets in that state rather than throw — the raise
 * UI is gated on `canRaise` anyway, so there is nothing to offer.
 */
class BetPresetsTest {

    @Test
    fun `from returns empty when max is below min (short stack)`() {
        // Mirrors the crash: min legal raise 8200, but the seat can only put
        // in 4750 (all-in) — an empty range.
        val legal = legalActions(minRaiseTotal = 8200, maxRaiseTotal = 4750)

        val presets = BetPresets.from(legal)

        assertTrue(presets.isEmpty(), "Expected no presets for an un-raiseable short stack")
    }

    @Test
    fun `from builds presets when a raise is legal`() {
        val legal = legalActions(minRaiseTotal = 40, maxRaiseTotal = 980, potIfYouCall = 120)

        val presets = BetPresets.from(legal)

        assertTrue(presets.isNotEmpty(), "Expected presets when min <= max")
        // Every preset amount must sit within the legal [min, max] range.
        assertTrue(presets.all { it.amount in 40..980 })
        // The Min preset is always present and equals minRaiseTotal.
        assertEquals(40L, presets.first().amount)
    }

    @Test
    fun `from is safe at the boundary where min equals max`() {
        val legal = legalActions(minRaiseTotal = 500, maxRaiseTotal = 500)

        val presets = BetPresets.from(legal)

        assertTrue(presets.all { it.amount == 500L })
    }

    private fun legalActions(
        minRaiseTotal: Long,
        maxRaiseTotal: Long,
        potIfYouCall: Long = maxRaiseTotal,
    ) = LegalActions(
        canCheck = false,
        canCall = true,
        callAmount = 0,
        canRaise = maxRaiseTotal >= minRaiseTotal,
        isOpenBet = false,
        minRaiseTotal = minRaiseTotal,
        maxRaiseTotal = maxRaiseTotal,
        canAllIn = true,
        allInAmount = maxRaiseTotal,
        potIfYouCall = potIfYouCall,
    )
}
