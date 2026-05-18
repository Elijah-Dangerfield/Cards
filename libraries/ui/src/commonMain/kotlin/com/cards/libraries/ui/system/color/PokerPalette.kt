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
}
