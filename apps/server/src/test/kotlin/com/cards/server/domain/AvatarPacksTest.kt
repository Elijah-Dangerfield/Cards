package com.dangerfield.cards.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarPacksTest {

    @Test
    fun paidPacks_haveNoEmojiOverlapWithStarter() {
        val starter = AvatarPacks.Starter.emojis.toSet()
        val paidPacks = AvatarPacks.all.filter { it.unlockProductId != null }
        assertTrue(paidPacks.isNotEmpty(), "No paid packs declared")

        paidPacks.forEach { pack ->
            val overlap = pack.emojis.toSet().intersect(starter)
            assertTrue(
                overlap.isEmpty(),
                "Paid pack \"${pack.name}\" overlaps with Starter: $overlap. " +
                    "Paid packs must be net-new vs Starter so buyers feel they got something.",
            )
        }
    }

    @Test
    fun starter_isAlwaysAvailable() {
        val available = AvatarPacks.availableFor(ownedProductIds = emptySet())
        assertEquals(listOf(AvatarPacks.Starter), available)
    }

    @Test
    fun availableFor_includesPaidPackOnceOwned() {
        val available = AvatarPacks.availableFor(
            ownedProductIds = setOf("avatars_animals"),
        )
        assertTrue(AvatarPacks.Animals in available)
        assertTrue(AvatarPacks.Starter in available)
        assertTrue(AvatarPacks.Food !in available)
    }
}
