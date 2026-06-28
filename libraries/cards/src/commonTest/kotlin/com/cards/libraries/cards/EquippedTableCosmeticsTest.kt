package com.dangerfield.cards.libraries.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the felt + card-back resolution that SHOP-3 runs at room-create time to
 * pin the host's table look. The list is equipped-only and newest-first (as the
 * DAO returns), so the first match per slot is the active one; a non-cosmetic
 * equip is ignored, and an empty slot resolves to null (no host override → each
 * player keeps their own equipped cosmetic).
 */
class EquippedTableCosmeticsTest {

    @Test
    fun picksFeltAndCardBack_ignoringOtherSlots() {
        val result = equippedTableCosmetics(
            listOf(
                entry("cardback_gold"),
                entry("tool_win_odds"),
                entry("felt_royal_red"),
                entry("title_shark"),
            ),
        )
        assertEquals("felt_royal_red", result.feltProductId)
        assertEquals("cardback_gold", result.cardBackProductId)
    }

    @Test
    fun newestEquippedWins_perSlot() {
        // Newest-first ordering: the first felt in the list is the active one.
        val result = equippedTableCosmetics(
            listOf(
                entry("felt_midnight_blue"),
                entry("felt_charcoal"),
            ),
        )
        assertEquals("felt_midnight_blue", result.feltProductId)
        assertNull(result.cardBackProductId)
    }

    @Test
    fun nothingEquipped_resolvesToNullPerSlot() {
        val result = equippedTableCosmetics(emptyList())
        assertNull(result.feltProductId)
        assertNull(result.cardBackProductId)
    }

    @Test
    fun unequippedRows_areIgnored() {
        val result = equippedTableCosmetics(
            listOf(
                entry("felt_royal_red", isEquipped = false),
                entry("cardback_marble"),
            ),
        )
        assertNull(result.feltProductId)
        assertEquals("cardback_marble", result.cardBackProductId)
    }

    private fun entry(productId: String, isEquipped: Boolean = true) = EquipmentEntry(
        productId = productId,
        isEquipped = isEquipped,
        syncState = EquipmentSyncState.Synced,
        updatedAtEpochMs = 0L,
    )
}
