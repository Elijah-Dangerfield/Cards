package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.http.ClientContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests over the seeded V5 product catalog. The DB is shared
 * with the rest of the [DatabaseTest] suite; we only read here so no
 * teardown is needed.
 *
 * These tests pin the round-trip from Postgres seed → `Product` domain
 * model. They protect:
 *  - JSONB → `Map<String, String>` localization decoding,
 *  - The `kind` discriminator branching into `ChipPack` vs `ChipOffer`,
 *  - The TEXT[] platform decoding via raw SELECT (no Exposed column type),
 *  - The kind-specific fields (`grants_chips` / `store_skus` for packs,
 *    `grants_key` / `cost_chips` / `unlock_level` for offers).
 *
 * If the migration changes the seed set, these counts may need updating —
 * that's a feature, not a bug; the seed IS the contract.
 */
class PostgresProductCatalogSourceTest : DatabaseTest() {

    private fun newSource(): PostgresProductCatalogSource =
        PostgresProductCatalogSource(database = database)

    private val androidContext = ClientContext(
        platform = ClientContext.Platform.Android,
        appVersion = "1.0.0",
        buildNumber = 1,
        preferredLocales = listOf("en"),
        countryCode = null,
    )

    @Test
    fun read_returnsBothChipPacksAndChipOffers() = runTest {
        val catalog = newSource().read(androidContext)

        assertTrue(catalog.chipPacks.isNotEmpty(), "expected seeded chip packs")
        assertTrue(catalog.chipOffers.isNotEmpty(), "expected seeded chip offers")
    }

    @Test
    fun read_decodesLocalizedTitle_forEnglish() = runTest {
        val catalog = newSource().read(androidContext)

        val small = catalog.chipPacks.single { it.id == "chip_pack_small" }
        assertEquals("Pocket Stack", small.titleByLocale["en"])
    }

    @Test
    fun read_decodesLocalizedTitle_forSpanish() = runTest {
        val catalog = newSource().read(androidContext)

        val small = catalog.chipPacks.single { it.id == "chip_pack_small" }
        assertEquals("Pila de bolsillo", small.titleByLocale["es"])
    }

    @Test
    fun read_includesStoreSkus_forBothPlatforms() = runTest {
        val catalog = newSource().read(androidContext)

        val small = catalog.chipPacks.single { it.id == "chip_pack_small" }
        assertEquals("com.cards.iap.chips.small", small.store.ios.sku)
        assertEquals("chips_small", small.store.android.sku)
        assertEquals("$0.99", small.store.ios.fallbackPriceDisplay)
        assertEquals("$0.99", small.store.android.fallbackPriceDisplay)
    }

    @Test
    fun read_markedFeatured_isReturnedAsFeatured() = runTest {
        val catalog = newSource().read(androidContext)

        val medium = catalog.chipPacks.single { it.id == "chip_pack_medium" }
        assertTrue(medium.featured)
        assertEquals("BEST VALUE", medium.badgeByLocale?.get("en"))
    }

    @Test
    fun read_chipOffer_carriesGrantsKeyAndCost() = runTest {
        val catalog = newSource().read(androidContext)

        val felt = catalog.chipOffers.single { it.id == "felt_royal_red" }
        assertEquals("felt.royal_red", felt.grantsKey)
        assertEquals(1_500, felt.costChips)
        assertEquals(1, felt.unlockLevel)
    }

    @Test
    fun read_chipOffer_carriesDescription_whenPresent() = runTest {
        val catalog = newSource().read(androidContext)

        val felt = catalog.chipOffers.single { it.id == "felt_royal_red" }
        assertNotNull(felt.descriptionByLocale)
        val englishDescription = felt.descriptionByLocale?.get("en")
        assertNotNull(englishDescription)
        assertTrue(
            englishDescription.contains("Deep red felt"),
            "expected description to start with the seeded copy: $englishDescription",
        )
    }

    @Test
    fun read_chipOffer_withoutBadge_returnsNullBadge() = runTest {
        val catalog = newSource().read(androidContext)

        val felt = catalog.chipOffers.single { it.id == "felt_royal_red" }
        assertNull(felt.badgeByLocale)
    }

    @Test
    fun read_returnsAllPlatformsByDefault() = runTest {
        val catalog = newSource().read(androidContext)

        val small = catalog.chipPacks.single { it.id == "chip_pack_small" }
        assertEquals(
            setOf(
                ClientContext.Platform.Android,
                ClientContext.Platform.iOS,
                ClientContext.Platform.Other,
            ),
            small.platforms,
        )
    }

    @Test
    fun read_seededFlashSale_hasNullAvailability() = runTest {
        // The migration intentionally drops the synthetic startup-relative
        // expiry from the old in-memory source: time-limited offers are
        // admin-controlled now.
        val catalog = newSource().read(androidContext)

        val flashSale = catalog.chipPacks.single { it.id == "chip_pack_flash_sale" }
        assertNull(flashSale.availableUntilEpochMs)
    }

    @Test
    fun read_kindDiscriminator_doesNotCrossWires() = runTest {
        val catalog = newSource().read(androidContext)

        // Every chip_pack must have store + grants; no chip_offer should
        // appear in chipPacks (and vice versa).
        catalog.chipPacks.forEach { pack ->
            assertNotNull(pack.store, "chipPacks[${pack.id}] missing store")
            assertTrue(pack.grantsChips > 0, "chipPacks[${pack.id}] missing grantsChips")
        }
        catalog.chipOffers.forEach { offer ->
            assertTrue(offer.grantsKey.isNotBlank(), "chipOffers[${offer.id}] missing grantsKey")
            assertTrue(offer.costChips > 0, "chipOffers[${offer.id}] missing costChips")
        }
    }

    @Test
    fun read_returnsCatalogInSortOrder() = runTest {
        // The migration assigns sort_order ascending by category +
        // intra-category price. Chip packs come before chip offers in the
        // seed, and within each kind the first row is the cheapest /
        // smallest. We verify the ordering shape here so a future
        // sort-order rebalance breaks loudly.
        val catalog = newSource().read(androidContext)

        // First chip pack should be the small "Pocket Stack" (sort_order 100).
        assertEquals("chip_pack_small", catalog.chipPacks.first().id)
        // First chip offer should be a level-1 felt (sort_order 200).
        assertEquals("felt_royal_red", catalog.chipOffers.first().id)
    }

    @Test
    fun read_returnsKnownProductsThatClientReferencesByGrantsKey() = runTest {
        // Several `grantsKey` strings are referenced by the client by
        // string id (felts, tools, avatar packs). If the seed renames or
        // removes one of these without a coordinated client change, the
        // client will see broken purchases. This is the round-trip pin.
        val expectedGrantKeys = setOf(
            "felt.royal_red",
            "felt.midnight_blue",
            "felt.charcoal",
            "felt.sunset",
            "cardback.marble",
            "cardback.gold",
            "cardback.neon",
            "cardback.diamond",
            "tool.win_odds",
            "tool.opponent_style",
            "avatars.animals",
            "avatars.food",
            "avatars.sports",
            "avatars.fantasy",
            "avatars.mythical",
            "emotes.drama",
            "emotes.cute",
            "emotes.fierce",
            "emotes.royal",
            "table.neon",
            "table.sunset",
            "title.bluff_master",
            "title.shark",
            "title.high_roller",
        )

        val catalog = newSource().read(androidContext)
        val seededGrantKeys = catalog.chipOffers.map { it.grantsKey }.toSet()

        val missing = expectedGrantKeys - seededGrantKeys
        assertTrue(missing.isEmpty(), "missing grantsKey rows in seed: $missing")
    }
}
