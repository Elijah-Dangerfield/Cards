package com.dangerfield.cards.features.shop.impl

import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.StoreSku
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the pure classifiers on [ShopState]: the grid-card classifier
 * ([ShopState.classify]), the sheet-mode classifier
 * ([ShopState.sheetModeFor]), and the expiry / affordability / unlock
 * guards they're built from. These drive both what the user sees and
 * what the VM lets them buy, so the priority order (owned > locked >
 * insufficient > available) is contract, not styling.
 */
class ShopStateTest {

    private val offer = Product.ChipOffer(
        id = "felt_royal_red",
        title = "Royal Red",
        subtitle = "Felt",
        iconEmoji = "🟥",
        costChips = 5_000,
        grantsKey = "felt.royal_red",
        unlockLevel = 3,
    )

    private val pack = Product.ChipPack(
        id = "chip_pack_small",
        title = "Pocket Stack",
        subtitle = "5,000 chips",
        iconEmoji = "🪙",
        grantsChips = 5_000,
        store = StoreSku("chips_small", "$0.99"),
    )

    // ---------- classify ----------

    @Test
    fun classify_availableWhenUnlockedAndAffordable() {
        val state = ShopState(chipBalance = 10_000, playerLevel = 5)
        assertEquals(ChipOfferCardState.Available(costChips = 5_000), state.classify(offer))
    }

    @Test
    fun classify_insufficientCarriesDeficit() {
        val state = ShopState(chipBalance = 3_000, playerLevel = 5)
        assertEquals(
            ChipOfferCardState.Insufficient(costChips = 5_000, shortBy = 2_000),
            state.classify(offer),
        )
    }

    @Test
    fun classify_lockedWinsOverInsufficient() {
        val state = ShopState(chipBalance = 0, playerLevel = 1)
        assertEquals(ChipOfferCardState.Locked(requiredLevel = 3), state.classify(offer))
    }

    @Test
    fun classify_ownedWinsOverEverything() {
        val state = ShopState(
            chipBalance = 0,
            playerLevel = 1,
            ownedProductIds = setOf(offer.id),
        )
        assertEquals(ChipOfferCardState.Owned, state.classify(offer))
    }

    @Test
    fun classify_nullBalanceReadsAsCannotAfford() {
        // Balance hasn't hydrated yet — the buy affordance must stay off.
        val state = ShopState(chipBalance = null, playerLevel = 5)
        assertEquals(
            ChipOfferCardState.Insufficient(costChips = 5_000, shortBy = 5_000),
            state.classify(offer),
        )
    }

    // ---------- sheetModeFor ----------

    @Test
    fun sheetModeFor_mirrorsClassifyPriorities() {
        val available = ShopState(chipBalance = 10_000, playerLevel = 5)
        assertIs<PurchaseSheetMode.Available>(available.sheetModeFor(offer))

        val insufficient = ShopState(chipBalance = 3_000, playerLevel = 5)
        val insufficientMode = insufficient.sheetModeFor(offer)
        assertIs<PurchaseSheetMode.Insufficient>(insufficientMode)
        assertEquals(2_000, insufficientMode.shortBy)

        val locked = ShopState(chipBalance = 10_000, playerLevel = 1)
        val lockedMode = locked.sheetModeFor(offer)
        assertIs<PurchaseSheetMode.Locked>(lockedMode)
        assertEquals(3, lockedMode.requiredLevel)

        val owned = ShopState(chipBalance = 0, playerLevel = 1, ownedProductIds = setOf(offer.id))
        assertIs<PurchaseSheetMode.Owned>(owned.sheetModeFor(offer))
    }

    @Test
    fun sheetModeFor_iapPackIsAlwaysAvailable_whenNotOwned() {
        // No level gate and no chip cost on real-money packs in V1.
        val state = ShopState(chipBalance = null, playerLevel = 1)
        assertIs<PurchaseSheetMode.Available>(state.sheetModeFor(pack))
    }

    // ---------- isExpired ----------

    @Test
    fun isExpired_falseWithoutSaleWindow() {
        val state = ShopState(timeAnchor = CatalogTimeAnchor.capture(serverNowEpochMs = 1_000_000))
        assertFalse(state.isExpired(offer))
    }

    @Test
    fun isExpired_falseWithoutAnchor() {
        // Haven't fetched yet — assume good rather than blocking the tap.
        val withWindow = offer.copy(availableUntilEpochMs = 1L)
        val state = ShopState(timeAnchor = null)
        assertFalse(state.isExpired(withWindow))
    }

    @Test
    fun isExpired_trueWhenWindowIsPastServerTime() {
        val anchor = CatalogTimeAnchor.capture(serverNowEpochMs = 1_000_000)
        val state = ShopState(timeAnchor = anchor)
        val expired = offer.copy(availableUntilEpochMs = 999_999)
        assertTrue(state.isExpired(expired))
    }

    @Test
    fun isExpired_falseWhenWindowIsInTheFuture() {
        val anchor = CatalogTimeAnchor.capture(serverNowEpochMs = 1_000_000)
        val state = ShopState(timeAnchor = anchor)
        val live = offer.copy(availableUntilEpochMs = 1_000_000 + 60_000)
        assertFalse(state.isExpired(live))
    }
}
