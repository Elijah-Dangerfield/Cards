package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.system.AppTheme

/**
 * The handful of felt / table-theme styles V1 ships. Enum-shaped (not
 * `Color`) so the VM can hold "which felt is equipped" without pulling
 * `androidx.compose.ui.graphics` into the engine layer — the actual
 * Color resolves at render time through [feltSurfaceColor].
 *
 * Catalog productId → style mapping lives in [feltForProductId] (single
 * source of truth, kept tight to the catalog seed in the server's
 * `V5__products.sql` migration). Anything unrecognized falls back to
 * [Default], so a new server-side felt before the client knows it
 * renders sanely instead of crashing or going black.
 */
enum class EquippedFelt { Default, RoyalRed, MidnightBlue, Charcoal, Sunset, Neon, PineGreen }

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
    "felt_pine_green" -> EquippedFelt.PineGreen
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
 *
 * **Pine green** is deliberately a dark slate-green, not casino-green —
 * product-spec §3.1 explicitly calls out "slate green used sparingly,
 * never the saturated casino-green felt." The hex sits in the same
 * luminance band as the other felts (≈8–11% lightness) so cards + chips
 * stay readable.
 */
@Composable
fun feltSurfaceColor(felt: EquippedFelt): Color = when (felt) {
    EquippedFelt.Default -> AppTheme.colors.background.color
    EquippedFelt.RoyalRed -> Color(0xFF4A1418)
    EquippedFelt.MidnightBlue -> Color(0xFF0E1B3D)
    EquippedFelt.Charcoal -> Color(0xFF15171A)
    EquippedFelt.Sunset -> Color(0xFF3B1F12)
    EquippedFelt.Neon -> Color(0xFF1A0D2E)
    EquippedFelt.PineGreen -> Color(0xFF14322B)
}

/**
 * Surface accent that sits *on top of* the felt — icon-button backgrounds,
 * subtle dividers, anything that would normally come from
 * `AppTheme.colors.surfaceSecondary` but needs to stay legible against
 * the equipped felt instead of the default app background.
 *
 * Rule of thumb: same hue as the felt, ~12–15% lighter. Reads as "raised
 * felt" rather than "alien element pasted on a colored background."
 * Default felt falls through to the standard secondary surface so the
 * non-equipped case looks identical to before.
 */
@Composable
fun feltAccentSurface(felt: EquippedFelt): Color = when (felt) {
    EquippedFelt.Default -> AppTheme.colors.surfaceSecondary.color
    EquippedFelt.RoyalRed -> Color(0xFF6A2429)
    EquippedFelt.MidnightBlue -> Color(0xFF1E3061)
    EquippedFelt.Charcoal -> Color(0xFF24272C)
    EquippedFelt.Sunset -> Color(0xFF5A331F)
    EquippedFelt.Neon -> Color(0xFF2D1A4A)
    EquippedFelt.PineGreen -> Color(0xFF1F4A40)
}

/**
 * The active felt's accent-surface color, scoped to the play surface. PlayPokerScreen
 * provides this at the top of its content tree based on the equipped felt; any
 * descendant element that needs a "raised felt" tone — icon-button backgrounds, subtle
 * dividers, anything currently using `AppTheme.colors.surfaceSecondary` while sitting
 * on the felt — should read this instead so it stays legible across all felt choices.
 *
 * Default fallback is the standard secondary surface, so reading this outside the play
 * screen is harmless.
 */
val LocalFeltAccentSurface: ProvidableCompositionLocal<Color?> = compositionLocalOf { null }

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
    "cardback_comeback_kid" -> CardBackStyle.ComebackKid
    "cardback_skulls" -> CardBackStyle.Skulls
    "cardback_galaxy" -> CardBackStyle.Galaxy
    "cardback_holographic" -> CardBackStyle.Holographic
    "cardback_fire" -> CardBackStyle.Fire
    "cardback_avatar" -> CardBackStyle.Avatar
    "cardback_lattice" -> CardBackStyle.Lattice
    "cardback_hatching" -> CardBackStyle.Hatching
    "cardback_crosshatch" -> CardBackStyle.Crosshatch
    "cardback_dots" -> CardBackStyle.Dots
    "cardback_pinstripes" -> CardBackStyle.Pinstripes
    else -> CardBackStyle.Default
}

/**
 * Catalog productId → display title string. Null for unknown / non-title
 * ids. The title shows under the player's name at the table when
 * equipped — pure vanity flex, no gameplay effect.
 *
 * V1 ships English-only; localization can swap this lookup for a
 * server-provided `titleLabel` field on the product when the catalog
 * grows multi-locale.
 */
fun titleForProductId(productId: String?): String? = when (productId) {
    "title_bluff_master" -> "Bluff Master"
    "title_shark" -> "The Shark"
    "title_high_roller" -> "High Roller"
    "title_pot_magnet" -> "Pot Magnet"
    "title_short_stack_hero" -> "Short Stack Hero"
    "title_bot_whisperer" -> "Bot Whisperer"
    "title_felt_veteran" -> "Felt Veteran"
    "title_royalty" -> "Royalty"
    "title_suited_run" -> "Suited Run"
    else -> null
}

/**
 * Catalog productId → permanent badge emoji. Same shape as
 * [titleForProductId] — single switchboard, null fallback so an unknown
 * server-side badge id renders nothing instead of crashing.
 *
 * Badges live in the permanent seat slot (mirrored placement opposite
 * the SB/BB chip). Granted server-side during profile creation when the
 * user falls inside the matching cohort window — see
 * `apps/server/.../V43__founding_member_badge.sql` for the catalog
 * entry and `PostgresProfileRepository.grantFoundingMemberBadge` for
 * the issuance pathway.
 */
fun badgeEmojiForProductId(productId: String?): String? = badgeRegistry[productId]

private val badgeRegistry: Map<String, String> = mapOf(
    "badge_founding_member_1000" to "🏛",
)
