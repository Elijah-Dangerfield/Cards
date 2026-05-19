package com.dangerfield.cards.features.room.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle
import com.dangerfield.cards.system.AppTheme

/**
 * The handful of felt / table-theme styles V1 ships. Enum-shaped (not
 * `Color`) so the VM can hold "which felt is equipped" without pulling
 * `androidx.compose.ui.graphics` into the engine layer — the actual
 * Color resolves at render time through [feltSurfaceColor].
 *
 * Catalog productId → style mapping lives in [feltForProductId] (single
 * source of truth, kept tight to the catalog in
 * InMemoryProductCatalogSource on the server). Anything unrecognized
 * falls back to [Default], so a new server-side felt before the client
 * knows it renders sanely instead of crashing or going black.
 */
enum class EquippedFelt { Default, RoyalRed, MidnightBlue, Charcoal, Sunset, Neon }

/**
 * Resolves a catalog product id to the felt style it equips, or
 * [EquippedFelt.Default] for unknown / non-felt ids. Centralised so
 * server catalog additions only need one client patch (or, ideally, a
 * `feltSurfaceHex` field on the product itself in a future iteration).
 */
fun feltForProductId(productId: String?): EquippedFelt = when (productId) {
    "felt_royal_red" -> EquippedFelt.RoyalRed
    "felt_midnight_blue" -> EquippedFelt.MidnightBlue
    "felt_charcoal" -> EquippedFelt.Charcoal
    // Both the weekend-sale felt and the premium table theme share the
    // sunset palette — they're priced differently but they paint the
    // same warm-orange surface.
    "felt_sunset_weekend", "table_sunset" -> EquippedFelt.Sunset
    "table_neon" -> EquippedFelt.Neon
    else -> EquippedFelt.Default
}

/**
 * Pure-color picks per felt. Dark enough across the board to keep cards +
 * chips readable; high enough chroma to feel intentional ("yes I bought
 * this") rather than a stock theme tint.
 *
 * Note these are *intentionally* not in `PokerPalette` — they're a per-
 * user choice, not a brand constant. Keeping them local means a future
 * "server ships its own hex per product" pass touches one file.
 */
@Composable
fun feltSurfaceColor(felt: EquippedFelt): Color = when (felt) {
    EquippedFelt.Default -> AppTheme.colors.background.color
    EquippedFelt.RoyalRed -> Color(0xFF4A1418)
    EquippedFelt.MidnightBlue -> Color(0xFF0E1B3D)
    EquippedFelt.Charcoal -> Color(0xFF15171A)
    EquippedFelt.Sunset -> Color(0xFF3B1F12)
    EquippedFelt.Neon -> Color(0xFF1A0D2E)
}

/**
 * Catalog productId → [CardBackStyle]. Same shape as
 * [feltForProductId] — single place to extend, fallback to
 * [CardBackStyle.Default] on unknown ids so a server-side catalog
 * addition before the client knows still renders.
 */
fun cardBackForProductId(productId: String?): CardBackStyle = when (productId) {
    "cardback_marble" -> CardBackStyle.Marble
    "cardback_gold" -> CardBackStyle.Gold
    "cardback_neon" -> CardBackStyle.Neon
    "cardback_diamond" -> CardBackStyle.Diamond
    else -> CardBackStyle.Default
}
