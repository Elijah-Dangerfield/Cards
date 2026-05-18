package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.PlatformStore
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalog
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.http.ClientContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Hardcoded catalog for the dev server. Edit, restart, the change is live on
 * the next client refresh.
 *
 * Production swaps this out for a Postgres-backed source bound under the same
 * [@ContributesBinding] — the endpoint stays unchanged.
 *
 * V1 catalog: four chip packs covering the standard "small / medium / large
 * / mega" price ladder used by social-casino apps. Real price authority sits
 * with the platform store; the `fallbackPriceDisplay` here is the placeholder
 * the UI shows while the store fetch is in-flight or unavailable.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class InMemoryProductCatalogSource : ProductCatalogSource {

    override suspend fun read(context: ClientContext): ProductCatalog {
        val chipPacks = listOf(
            Product.ChipPack(
                id = "chip_pack_small",
                titleByLocale = mapOf("en" to "Pocket Stack", "es" to "Pila de bolsillo"),
                subtitleByLocale = mapOf("en" to "5,000 chips", "es" to "5.000 fichas"),
                iconKey = "chips_small",
                grantsChips = 5_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.small", "$0.99"),
                    android = PlatformStore.StoreSku("chips_small", "$0.99"),
                ),
            ),
            Product.ChipPack(
                id = "chip_pack_medium",
                titleByLocale = mapOf("en" to "Tall Stack", "es" to "Pila alta"),
                subtitleByLocale = mapOf("en" to "30,000 chips", "es" to "30.000 fichas"),
                iconKey = "chips_medium",
                featured = true,
                badgeByLocale = mapOf("en" to "BEST VALUE", "es" to "MEJOR VALOR"),
                grantsChips = 30_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.medium", "$4.99"),
                    android = PlatformStore.StoreSku("chips_medium", "$4.99"),
                ),
            ),
            Product.ChipPack(
                id = "chip_pack_large",
                titleByLocale = mapOf("en" to "Whale Stack", "es" to "Pila ballena"),
                subtitleByLocale = mapOf("en" to "80,000 chips", "es" to "80.000 fichas"),
                iconKey = "chips_large",
                badgeByLocale = mapOf("en" to "+20% BONUS", "es" to "+20% EXTRA"),
                grantsChips = 80_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.large", "$9.99"),
                    android = PlatformStore.StoreSku("chips_large", "$9.99"),
                ),
            ),
            Product.ChipPack(
                id = "chip_pack_mega",
                titleByLocale = mapOf("en" to "High Roller", "es" to "Gran apostador"),
                subtitleByLocale = mapOf("en" to "250,000 chips", "es" to "250.000 fichas"),
                iconKey = "chips_mega",
                grantsChips = 250_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.mega", "$19.99"),
                    android = PlatformStore.StoreSku("chips_mega", "$19.99"),
                ),
            ),
        ).filter { context.platform in it.platforms }

        // Chip-purchasable items live in the same catalog as IAP packs (one
        // API, one cache, one rendering pipeline). Each joins to a
        // client-bundled asset via `iconKey` (visual) and `grantsKey`
        // (effect/inventory entry).
        //
        // V1 selection is cosmetic-only — no gameplay-affecting items, per
        // the no-P2W product spec. Mixed across price tiers + categories
        // (emotes / table themes / card backs / player titles) so the shop
        // grid feels alive even before real art assets are wired in.
        val chipOffers = listOf(
            // --- Emotes (cheapest, gateway purchases) ---
            Product.ChipOffer(
                id = "emote_dance",
                titleByLocale = mapOf("en" to "Victory Dance", "es" to "Baile de victoria"),
                subtitleByLocale = mapOf("en" to "Emote", "es" to "Emote"),
                iconKey = "emote_dance",
                costChips = 2_500,
                grantsKey = "emote.dance",
            ),
            Product.ChipOffer(
                id = "emote_tilt",
                titleByLocale = mapOf("en" to "Salty Shake", "es" to "Sacudida salada"),
                subtitleByLocale = mapOf("en" to "Emote", "es" to "Emote"),
                iconKey = "emote_tilt",
                costChips = 2_500,
                grantsKey = "emote.tilt",
            ),
            Product.ChipOffer(
                id = "emote_think",
                titleByLocale = mapOf("en" to "Deep Think", "es" to "Pensamiento profundo"),
                subtitleByLocale = mapOf("en" to "Emote", "es" to "Emote"),
                iconKey = "emote_think",
                costChips = 2_500,
                grantsKey = "emote.think",
            ),
            Product.ChipOffer(
                id = "emote_facepalm",
                titleByLocale = mapOf("en" to "Facepalm", "es" to "Palma en la cara"),
                subtitleByLocale = mapOf("en" to "Emote", "es" to "Emote"),
                iconKey = "emote_facepalm",
                costChips = 2_500,
                grantsKey = "emote.facepalm",
            ),
            // --- Card backs (mid-tier) ---
            Product.ChipOffer(
                id = "cardback_gold",
                titleByLocale = mapOf("en" to "Gold Foil", "es" to "Lámina dorada"),
                subtitleByLocale = mapOf("en" to "Card back", "es" to "Reverso de carta"),
                iconKey = "cardback_gold",
                costChips = 6_000,
                grantsKey = "cardback.gold",
            ),
            Product.ChipOffer(
                id = "cardback_marble",
                titleByLocale = mapOf("en" to "Marble", "es" to "Mármol"),
                subtitleByLocale = mapOf("en" to "Card back", "es" to "Reverso de carta"),
                iconKey = "cardback_marble",
                badgeByLocale = mapOf("en" to "POPULAR", "es" to "POPULAR"),
                costChips = 6_000,
                grantsKey = "cardback.marble",
            ),
            Product.ChipOffer(
                id = "cardback_neon",
                titleByLocale = mapOf("en" to "Neon Lines", "es" to "Líneas de neón"),
                subtitleByLocale = mapOf("en" to "Card back", "es" to "Reverso de carta"),
                iconKey = "cardback_neon",
                costChips = 6_000,
                grantsKey = "cardback.neon",
            ),
            // --- Table themes (premium) ---
            Product.ChipOffer(
                id = "table_neon",
                titleByLocale = mapOf("en" to "Neon Table", "es" to "Mesa de neón"),
                subtitleByLocale = mapOf("en" to "Table theme", "es" to "Tema de mesa"),
                iconKey = "table_neon",
                featured = true,
                badgeByLocale = mapOf("en" to "NEW", "es" to "NUEVO"),
                costChips = 12_000,
                grantsKey = "table.neon",
            ),
            Product.ChipOffer(
                id = "table_sunset",
                titleByLocale = mapOf("en" to "Sunset Felt", "es" to "Fieltro atardecer"),
                subtitleByLocale = mapOf("en" to "Table theme", "es" to "Tema de mesa"),
                iconKey = "table_sunset",
                costChips = 12_000,
                grantsKey = "table.sunset",
            ),
            // --- Player titles (top tier — vanity flex) ---
            Product.ChipOffer(
                id = "title_bluff_master",
                titleByLocale = mapOf("en" to "Bluff Master", "es" to "Maestro del farol"),
                subtitleByLocale = mapOf("en" to "Player title", "es" to "Título de jugador"),
                iconKey = "title_bluff_master",
                costChips = 15_000,
                grantsKey = "title.bluff_master",
            ),
            Product.ChipOffer(
                id = "title_high_roller",
                titleByLocale = mapOf("en" to "High Roller", "es" to "Apostador grande"),
                subtitleByLocale = mapOf("en" to "Player title", "es" to "Título de jugador"),
                iconKey = "title_high_roller",
                badgeByLocale = mapOf("en" to "RARE", "es" to "RARO"),
                costChips = 25_000,
                grantsKey = "title.high_roller",
            ),
        ).filter { context.platform in it.platforms }

        return ProductCatalog(
            chipPacks = chipPacks,
            chipOffers = chipOffers,
        )
    }
}
