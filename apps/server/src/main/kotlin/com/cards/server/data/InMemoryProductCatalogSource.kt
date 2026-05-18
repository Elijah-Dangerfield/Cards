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
                subtitleByLocale = mapOf("en" to "1,000 chips", "es" to "1.000 fichas"),
                iconEmoji = "🪙",
                grantsChips = 1_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.small", "$0.99"),
                    android = PlatformStore.StoreSku("chips_small", "$0.99"),
                ),
            ),
            Product.ChipPack(
                id = "chip_pack_medium",
                titleByLocale = mapOf("en" to "Tall Stack", "es" to "Pila alta"),
                subtitleByLocale = mapOf("en" to "15,000 chips", "es" to "15.000 fichas"),
                iconEmoji = "💰",
                featured = true,
                badgeByLocale = mapOf("en" to "BEST VALUE", "es" to "MEJOR VALOR"),
                grantsChips = 15_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.medium", "$4.99"),
                    android = PlatformStore.StoreSku("chips_medium", "$4.99"),
                ),
            ),
            Product.ChipPack(
                id = "chip_pack_large",
                titleByLocale = mapOf("en" to "Whale Stack", "es" to "Pila ballena"),
                subtitleByLocale = mapOf("en" to "50,000 chips", "es" to "50.000 fichas"),
                iconEmoji = "🐋",
                badgeByLocale = mapOf("en" to "+20% BONUS", "es" to "+20% EXTRA"),
                grantsChips = 50_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.large", "$9.99"),
                    android = PlatformStore.StoreSku("chips_large", "$9.99"),
                ),
            ),
            Product.ChipPack(
                id = "chip_pack_mega",
                titleByLocale = mapOf("en" to "High Roller", "es" to "Gran apostador"),
                subtitleByLocale = mapOf("en" to "150,000 chips", "es" to "150.000 fichas"),
                iconEmoji = "👑",
                grantsChips = 150_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.mega", "$19.99"),
                    android = PlatformStore.StoreSku("chips_mega", "$19.99"),
                ),
            ),
        ).filter { context.platform in it.platforms }

        // Chip-purchasable items live in the same catalog as IAP packs
        // (one API, one cache, one rendering pipeline). `iconEmoji` is
        // the V1 visual; `grantsKey` is the opaque effect/inventory id
        // the client maps to behavior.
        //
        // V1 is cosmetic + math-utility, NO P2W. Utility items
        // (win-% viewer, opponent-style viewer) only surface info the
        // player can already derive from public-to-them data — they
        // just do the math fast. No new info ever crosses the
        // information-asymmetry line.
        // Catalog organisation:
        //   - Categories: felts (table colors, visible to you only in V1) →
        //     card backs → emote / avatar emoji packs → player titles.
        //   - Level gating: cheaper / friendlier items unlock first
        //     (1 → 3 → 5), premium tier at 10, vanity-flex items at 15+.
        //   - Each "pack" purchase grants a SET of related items (e.g.,
        //     Animal Pack unlocks 8 avatar emojis). Single-cosmetic items
        //     keep their grantsKey leaf-named.
        //   - V1 is strictly cosmetic — no items that reveal opponent
        //     hands, betting odds, or other gameplay info. Anything
        //     adjacent to that is filed for the MP / training-mode pass.
        val chipOffers = listOf(
            // --- Felts (cheap, gateway purchase, level 1) ---
            Product.ChipOffer(
                id = "felt_royal_red",
                titleByLocale = mapOf("en" to "Royal Red Felt", "es" to "Fieltro rojo real"),
                subtitleByLocale = mapOf("en" to "Table felt", "es" to "Fieltro de mesa"),
                descriptionByLocale = mapOf(
                    "en" to "Deep red felt for the playing surface. Equip from your items — visible to you only in solo games.",
                ),
                iconEmoji = "🟥",
                costChips = 1_500,
                grantsKey = "felt.royal_red",
                unlockLevel = 1,
            ),
            Product.ChipOffer(
                id = "felt_midnight_blue",
                titleByLocale = mapOf("en" to "Midnight Blue Felt", "es" to "Fieltro azul medianoche"),
                subtitleByLocale = mapOf("en" to "Table felt", "es" to "Fieltro de mesa"),
                descriptionByLocale = mapOf(
                    "en" to "Deep blue felt — easy on the eyes during long sessions. Equip from your items.",
                ),
                iconEmoji = "🟦",
                costChips = 1_500,
                grantsKey = "felt.midnight_blue",
                unlockLevel = 1,
            ),
            Product.ChipOffer(
                id = "felt_charcoal",
                titleByLocale = mapOf("en" to "Charcoal Felt", "es" to "Fieltro carbón"),
                subtitleByLocale = mapOf("en" to "Table felt", "es" to "Fieltro de mesa"),
                descriptionByLocale = mapOf(
                    "en" to "Moody black felt. Equip from your items.",
                ),
                iconEmoji = "⬛",
                costChips = 2_000,
                grantsKey = "felt.charcoal",
                unlockLevel = 3,
            ),

            // --- Emote reaction packs (cheap, level 1-3, multiple emotes per pack) ---
            Product.ChipOffer(
                id = "emotes_drama",
                titleByLocale = mapOf("en" to "Drama Emote Pack", "es" to "Paquete drama"),
                subtitleByLocale = mapOf("en" to "Emotes · 4 reactions", "es" to "Emotes · 4 reacciones"),
                descriptionByLocale = mapOf(
                    "en" to "Unlocks 💃 🧂 🎭 🤦 — send big, screen-filling reactions to the table. Equip individually from your items.",
                ),
                iconEmoji = "💃",
                costChips = 3_500,
                grantsKey = "emotes.drama",
                unlockLevel = 1,
            ),
            Product.ChipOffer(
                id = "emotes_cute",
                titleByLocale = mapOf("en" to "Cute Emote Pack", "es" to "Paquete tierno"),
                subtitleByLocale = mapOf("en" to "Emotes · 4 reactions", "es" to "Emotes · 4 reacciones"),
                descriptionByLocale = mapOf(
                    "en" to "Unlocks 🥺 🥰 😇 🤗 — soft-pawed reactions for friendly tables. Equip from your items.",
                ),
                iconEmoji = "🥺",
                costChips = 3_500,
                grantsKey = "emotes.cute",
                unlockLevel = 1,
            ),
            Product.ChipOffer(
                id = "emotes_fierce",
                titleByLocale = mapOf("en" to "Fierce Emote Pack", "es" to "Paquete feroz"),
                subtitleByLocale = mapOf("en" to "Emotes · 4 reactions", "es" to "Emotes · 4 reacciones"),
                descriptionByLocale = mapOf(
                    "en" to "Unlocks 😤 🔥 💀 😎 — heat for the bluffers. Equip from your items.",
                ),
                iconEmoji = "🔥",
                costChips = 4_500,
                grantsKey = "emotes.fierce",
                unlockLevel = 5,
            ),
            Product.ChipOffer(
                id = "emotes_royal",
                titleByLocale = mapOf("en" to "Royal Emote Pack", "es" to "Paquete real"),
                subtitleByLocale = mapOf("en" to "Emotes · 4 reactions", "es" to "Emotes · 4 reacciones"),
                descriptionByLocale = mapOf(
                    "en" to "Unlocks 👑 🃏 ♠️ ♥️ — high-roller-coded reactions. Equip from your items.",
                ),
                iconEmoji = "👑",
                costChips = 6_000,
                grantsKey = "emotes.royal",
                unlockLevel = 10,
            ),

            // --- Avatar emoji packs (replaces the temporary letter avatar) ---
            // Concept: every player gets a single random starter emoji + the
            // "Basic" pack at signup. Buying a pack unlocks its emojis as
            // avatar choices in profile. V1 ships the packs; the avatar-
            // picker UI lands when auth lands.
            Product.ChipOffer(
                id = "avatars_animals",
                titleByLocale = mapOf("en" to "Animal Avatars", "es" to "Avatares animales"),
                subtitleByLocale = mapOf("en" to "Avatar pack · 8 emojis", "es" to "Paquete · 8 emojis"),
                descriptionByLocale = mapOf(
                    "en" to "Unlocks 🐱 🐶 🐯 🐼 🦊 🐻 🦁 🐸 as avatar choices in your profile. Pick the one you wear at the table.",
                ),
                iconEmoji = "🦊",
                costChips = 4_000,
                grantsKey = "avatars.animals",
                unlockLevel = 1,
            ),
            Product.ChipOffer(
                id = "avatars_food",
                titleByLocale = mapOf("en" to "Foodie Avatars", "es" to "Avatares foodie"),
                subtitleByLocale = mapOf("en" to "Avatar pack · 8 emojis", "es" to "Paquete · 8 emojis"),
                descriptionByLocale = mapOf(
                    "en" to "Unlocks 🍕 🍔 🌮 🍣 🍰 🥑 🍩 ☕ as avatar choices. Become The Taco Player.",
                ),
                iconEmoji = "🍕",
                costChips = 4_000,
                grantsKey = "avatars.food",
                unlockLevel = 1,
            ),
            Product.ChipOffer(
                id = "avatars_sports",
                titleByLocale = mapOf("en" to "Sports Avatars", "es" to "Avatares deportes"),
                subtitleByLocale = mapOf("en" to "Avatar pack · 8 emojis", "es" to "Paquete · 8 emojis"),
                descriptionByLocale = mapOf(
                    "en" to "Unlocks ⚽ 🏀 🏈 ⚾ 🎾 🎯 🎳 🥊 as avatar choices.",
                ),
                iconEmoji = "🏀",
                costChips = 4_500,
                grantsKey = "avatars.sports",
                unlockLevel = 3,
            ),
            Product.ChipOffer(
                id = "avatars_fantasy",
                titleByLocale = mapOf("en" to "Fantasy Avatars", "es" to "Avatares fantasía"),
                subtitleByLocale = mapOf("en" to "Avatar pack · 8 emojis", "es" to "Paquete · 8 emojis"),
                descriptionByLocale = mapOf(
                    "en" to "Unlocks 🧙 🧚 🧛 🧜 🦄 🐉 🧞 🐲 as avatar choices.",
                ),
                iconEmoji = "🧙",
                badgeByLocale = mapOf("en" to "POPULAR", "es" to "POPULAR"),
                costChips = 6_000,
                grantsKey = "avatars.fantasy",
                unlockLevel = 5,
            ),
            Product.ChipOffer(
                id = "avatars_mythical",
                titleByLocale = mapOf("en" to "Mythical Avatars", "es" to "Avatares míticos"),
                subtitleByLocale = mapOf("en" to "Avatar pack · 8 emojis", "es" to "Paquete · 8 emojis"),
                descriptionByLocale = mapOf(
                    "en" to "Unlocks 🦖 🐙 🦕 🦑 🦞 🦀 🐡 🦈 as avatar choices.",
                ),
                iconEmoji = "🦖",
                costChips = 9_000,
                grantsKey = "avatars.mythical",
                unlockLevel = 15,
            ),

            // --- Card backs (mid-tier) ---
            Product.ChipOffer(
                id = "cardback_marble",
                titleByLocale = mapOf("en" to "Marble Card Back", "es" to "Reverso mármol"),
                subtitleByLocale = mapOf("en" to "Card back", "es" to "Reverso de carta"),
                descriptionByLocale = mapOf(
                    "en" to "Marble-pattern back for your hole cards. Equip from your items.",
                ),
                iconEmoji = "🂠",
                badgeByLocale = mapOf("en" to "POPULAR", "es" to "POPULAR"),
                costChips = 4_000,
                grantsKey = "cardback.marble",
                unlockLevel = 3,
            ),
            Product.ChipOffer(
                id = "cardback_gold",
                titleByLocale = mapOf("en" to "Gold Foil Card Back", "es" to "Reverso oro"),
                subtitleByLocale = mapOf("en" to "Card back", "es" to "Reverso de carta"),
                descriptionByLocale = mapOf(
                    "en" to "Glinting gold foil pattern. Equip from your items.",
                ),
                iconEmoji = "🂠",
                costChips = 5_000,
                grantsKey = "cardback.gold",
                unlockLevel = 5,
            ),
            Product.ChipOffer(
                id = "cardback_neon",
                titleByLocale = mapOf("en" to "Neon Lines Card Back", "es" to "Reverso neón"),
                subtitleByLocale = mapOf("en" to "Card back", "es" to "Reverso de carta"),
                descriptionByLocale = mapOf(
                    "en" to "Glowing neon-line pattern. Equip from your items.",
                ),
                iconEmoji = "🂠",
                costChips = 5_000,
                grantsKey = "cardback.neon",
                unlockLevel = 5,
            ),
            Product.ChipOffer(
                id = "cardback_diamond",
                titleByLocale = mapOf("en" to "Diamond Lattice", "es" to "Rejilla diamante"),
                subtitleByLocale = mapOf("en" to "Card back · Premium", "es" to "Reverso · Premium"),
                descriptionByLocale = mapOf(
                    "en" to "Crystalline diamond pattern — for high-roller decks. Equip from your items.",
                ),
                iconEmoji = "💎",
                costChips = 12_000,
                grantsKey = "cardback.diamond",
                unlockLevel = 15,
            ),

            // --- Premium table themes (high tier) ---
            Product.ChipOffer(
                id = "table_neon",
                titleByLocale = mapOf("en" to "Neon Table", "es" to "Mesa de neón"),
                subtitleByLocale = mapOf("en" to "Table theme", "es" to "Tema de mesa"),
                descriptionByLocale = mapOf(
                    "en" to "Replaces the felt AND rail with a full neon table theme. Higher tier than a felt-only swap.",
                ),
                iconEmoji = "🎰",
                featured = true,
                badgeByLocale = mapOf("en" to "NEW", "es" to "NUEVO"),
                costChips = 8_000,
                grantsKey = "table.neon",
                unlockLevel = 8,
            ),
            Product.ChipOffer(
                id = "table_sunset",
                titleByLocale = mapOf("en" to "Sunset Table", "es" to "Mesa atardecer"),
                subtitleByLocale = mapOf("en" to "Table theme", "es" to "Tema de mesa"),
                descriptionByLocale = mapOf(
                    "en" to "Warm sunset-orange theme. Felt + rail. Equip from your items.",
                ),
                iconEmoji = "🌅",
                costChips = 8_000,
                grantsKey = "table.sunset",
                unlockLevel = 8,
            ),

            // --- Player titles (top tier — pure vanity flex) ---
            Product.ChipOffer(
                id = "title_bluff_master",
                titleByLocale = mapOf("en" to "Bluff Master", "es" to "Maestro del farol"),
                subtitleByLocale = mapOf("en" to "Player title", "es" to "Título de jugador"),
                descriptionByLocale = mapOf(
                    "en" to "Shows under your name at the table for everyone to see. Pure flex.",
                ),
                iconEmoji = "🎭",
                costChips = 12_000,
                grantsKey = "title.bluff_master",
                unlockLevel = 10,
            ),
            Product.ChipOffer(
                id = "title_shark",
                titleByLocale = mapOf("en" to "The Shark", "es" to "El tiburón"),
                subtitleByLocale = mapOf("en" to "Player title", "es" to "Título de jugador"),
                descriptionByLocale = mapOf(
                    "en" to "For the player who reads the table. Shows under your name.",
                ),
                iconEmoji = "🦈",
                costChips = 18_000,
                grantsKey = "title.shark",
                unlockLevel = 15,
            ),
            Product.ChipOffer(
                id = "title_high_roller",
                titleByLocale = mapOf("en" to "High Roller", "es" to "Apostador grande"),
                subtitleByLocale = mapOf("en" to "Player title", "es" to "Título de jugador"),
                descriptionByLocale = mapOf(
                    "en" to "Rare title for the dedicated. Shows under your name.",
                ),
                iconEmoji = "🏆",
                badgeByLocale = mapOf("en" to "RARE", "es" to "RARO"),
                costChips = 25_000,
                grantsKey = "title.high_roller",
                unlockLevel = 20,
            ),

            // --- Math / utility unlocks ---
            // These ARE NOT P2W. They surface information the player
            // already has (cards in their hand + on the board) or that
            // is public (an opponent's prior public play history) — we
            // just do the math / aggregation fast. Same principle as
            // showing the player their hand rank: we save them the
            // mental arithmetic.
            Product.ChipOffer(
                id = "tool_win_odds",
                titleByLocale = mapOf("en" to "Win Odds Display", "es" to "Probabilidades"),
                subtitleByLocale = mapOf("en" to "Utility", "es" to "Utilidad"),
                descriptionByLocale = mapOf(
                    "en" to "Shows your live win percentage during a hand, computed from YOUR hole cards + the visible board. Same info you can work out yourself — we just do the math.",
                ),
                iconEmoji = "📊",
                costChips = 10_000,
                grantsKey = "tool.win_odds",
                unlockLevel = 10,
            ),
            Product.ChipOffer(
                id = "tool_opponent_style",
                titleByLocale = mapOf("en" to "Opponent Style Reader", "es" to "Estilo de oponente"),
                subtitleByLocale = mapOf("en" to "Utility", "es" to "Utilidad"),
                descriptionByLocale = mapOf(
                    "en" to "Heat-map summary of each opponent's prior public play (raise / call / fold tendencies). Public info — we just keep the receipts.",
                ),
                iconEmoji = "🔍",
                badgeByLocale = mapOf("en" to "RARE", "es" to "RARO"),
                costChips = 20_000,
                grantsKey = "tool.opponent_style",
                unlockLevel = 15,
            ),
        ).filter { context.platform in it.platforms }

        return ProductCatalog(
            chipPacks = chipPacks,
            chipOffers = chipOffers,
        )
    }
}
