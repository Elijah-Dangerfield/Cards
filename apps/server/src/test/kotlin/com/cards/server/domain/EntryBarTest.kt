package com.dangerfield.cards.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the entry-bar / anchor / grant relationship that the stuck-in-Lobby bug
 * violated. The four economy knobs — starter grant, entry-bar multiple, the
 * matchmaker snap anchor, and the default search band — must agree, or a fresh
 * player is matched to a table the sit-down escrow then bounces. These tests are
 * the standing invariant that any future edit to one knob has to keep true.
 */
class EntryBarTest {

    @Test
    fun canSit_truthTable() {
        // 10k grant clears the 4× bar for a 1k table (needs 4,000) but not a 5k one
        // (needs 20,000) — exactly why the fresh player must default to the 1k tier.
        assertTrue(EntryBar.canSit(balance = 10_000, buyIn = 1_000))
        assertFalse(EntryBar.canSit(balance = 10_000, buyIn = 5_000))
        // 20k clears the 5k table (needs 20,000) — the boundary is inclusive.
        assertTrue(EntryBar.canSit(balance = 20_000, buyIn = 5_000))
    }

    @Test
    fun minBalanceToSit_isFourBuyIns() {
        assertEquals(4_000, EntryBar.minBalanceToSit(1_000))
        assertEquals(100_000, EntryBar.minBalanceToSit(25_000))
    }

    @Test
    fun maxAffordableBuyIn_isBalanceOverFour() {
        assertEquals(2_500, EntryBar.maxAffordableBuyIn(10_000))
        // Its own inverse holds at the boundary: the max affordable buy-in is sit-able.
        assertTrue(EntryBar.canSit(10_000, EntryBar.maxAffordableBuyIn(10_000)))
    }

    @Test
    fun within_snapsTowardTheAnchorNotTheWireDefault() {
        // The default search band (500..2,000) snaps to the 1,000 anchor tier, not
        // the old 5,000 wire default that a fresh grant couldn't fund.
        assertEquals(1_000, BuyInTier.within(500, 2_000))
        assertEquals(BuyInTier.ANCHOR, BuyInTier.within(500, 2_000))
    }

    @Test
    fun starterGrant_canSit_theDefaultBandsSnapTarget() {
        // The load-bearing invariant: a fresh player on the starter grant can
        // actually sit at the table the default search band maps to. If a future
        // change to the grant, the anchor, the entry-bar multiple, or the default
        // band breaks this, that's the stuck-in-Lobby bug again — fail loudly here.
        assertTrue(
            EntryBar.canSit(Wallet.STARTER_GRANT, BuyInTier.within(500, 2_000)),
            "a starter-grant player must be able to fund the default band's snap target",
        )
    }
}
