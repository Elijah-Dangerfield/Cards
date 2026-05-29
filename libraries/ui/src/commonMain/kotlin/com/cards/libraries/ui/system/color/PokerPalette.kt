package com.dangerfield.cards.libraries.ui.system.color

import androidx.compose.ui.graphics.Color

/**
 * Game-specific colors for poker UI artifacts (chips, cards, dealer/blind
 * markers, seat states).
 *
 * These are *not* semantic theme tokens — they're tied to physical poker
 * objects (a chip is gold, a card back is blue, the big-blind chip is red)
 * and should look the same under any future light/dark theme.
 *
 * Prefer `AppTheme.colors.*` for anything semantic (background, surface,
 * accent, text). Reach for these only when rendering an explicit game element.
 */
object PokerPalette {
    /** Casino-chip gold — bet pills, pot icon, winner glow, dealer button gold variants. */
    val ChipGold: Color = Color(0xFFE0B863)
    val ChipGoldOutline: Color = Color(0xFF765F2D)


    /** Card-face background. Slightly off-white so it doesn't blow out against dark surfaces. */
    val CardWhite: Color = Color(0xFFF4F1E8)

    /** Card-back blue for face-down community/hole cards and the deck stack. */
    val CardBackBlue: Color = Color(0xFF2E4A9E)

    /** Active-seat halo, also doubles for "Your turn" emphasis. */
    val SeatActive: Color = Color(0xFFFFD66E)

    /** Dealer-button color. Alias of [CardWhite] but kept distinct so the role is clear at the call site. */
    val DealerWhite: Color = CardWhite

    /** Big-blind chip color. Small blind reuses [ChipGold]. */
    val BlindRed: Color = Color(0xFFC42E2E)

    /** Fill for an empty card slot — reads as a card-shaped "well" the next card will land in. */
    val CardSlot: Color = Color(0x14F4F1E8)

    /** 1.5dp outline color for the community-board "well" — sits on top of the
     *  felt, intentionally subtle so dealt cards visually fill the outline. */
    val CardSlotOutline: Color = Color(0x1AFFFFFF)

    /** XP-progression cyan — start of the `XpBadge` inner gradient and the
     *  hue of its surrounding ring. The ring + gradient share a colour
     *  family so the badge reads as one object. */
    val ProgressionCyan: Color = Color(0xFF4FC3F7)

    /** XP-progression green — end of the `XpBadge` inner gradient. Pairs
     *  with [ProgressionCyan]. */
    val ProgressionGreen: Color = Color(0xFF66BB6A)

    /** Celebratory / "ready" amber — sparkle glyph + radial glow on the
     *  tutorial ready screen. Distinct from [ChipGold] (used for bet pills
     *  and the dealer button) so the celebration reads brighter than a
     *  routine chip stack. */
    val SparkleGold: Color = Color(0xFFE5B946)

    /** Coin face — light end of the radial gradient that gives the disc
     *  its "lit from above" highlight. Pairs with [CoinGradientEnd]. */
    val CoinGradientStart: Color = Color(0xFFFFD66B)

    /** Coin face — dark end of the radial gradient. Pairs with
     *  [CoinGradientStart]. */
    val CoinGradientEnd: Color = Color(0xFFD9A933)

    /** Coin-edge ring — darker than the disc face so the metal rim reads
     *  as 3D against the lit gradient. */
    val CoinOutline: Color = Color(0xFFB68721)

    /** Coin-face glyph color — deep amber-brown chosen for contrast against
     *  the gold disc, not pure black so it reads as warm-on-metal rather
     *  than ink-on-coin. */
    val CoinGlyph: Color = Color(0xFF3D2A0A)

    /** Rank-badge purple — cool end of the rank disc's linear gradient. Paired
     *  with [RankBadgePink] to give the multiplayer Elo badge its signature
     *  two-tone identity. */
    val RankBadgePurple: Color = Color(0xFF8E7CC3)

    /** Rank-badge pink — warm end of the rank disc's linear gradient. Pairs
     *  with [RankBadgePurple]. */
    val RankBadgePink: Color = Color(0xFFE07AB1)

    /** Card-felt green — muted dark green tied to the poker-felt iconography.
     *  Used as the tile background behind a 🎴 card emoji on the onboarding
     *  "How it works" card. Distinct from the equipped-felt colors in
     *  [com.dangerfield.cards.libraries.ui.components.poker.feltSurfaceColor]
     *  (those are per-user equipped table themes); this one is a fixed
     *  brand swatch representing the felt as an *icon*. */
    val FeltGreen: Color = Color(0xFF3F5B45)
}
