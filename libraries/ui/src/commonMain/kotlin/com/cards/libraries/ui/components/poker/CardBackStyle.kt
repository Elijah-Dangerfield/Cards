package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette

/**
 * The visual styles a face-down card can render as. Decoupled from
 * product IDs on purpose — the rendering layer just knows "the user has
 * equipped this style"; mapping FROM product id (`cardback_marble` →
 * [Marble]) lives in the feature module that owns the equipment-state
 * subscription.
 *
 * New styles need:
 *  - An enum value here.
 *  - A render branch in [PlayingCardBack].
 *  - A mapper entry where the feature derives the style from the
 *    equipped productId.
 *
 * Adding a value WITHOUT updating [PlayingCardBack] is a compile error
 * (`when` exhaustiveness), so the renderer can't silently fall through.
 */
enum class CardBackStyle { Default, Marble, Gold, Neon, Diamond, ComebackKid }

/**
 * Ambient style for every [PlayingCardBack] in the composition. Set
 * once at the play-screen root via [CompositionLocalProvider] so the
 * style flows through every face-down card (opponent hole cards, deck
 * stack, dealt-but-not-revealed community cards) without threading a
 * parameter through every composable.
 */
val LocalCardBackStyle = compositionLocalOf { CardBackStyle.Default }

/** Solid + accent colors per style, exposed for the renderer below. */
internal data class CardBackPalette(
    val baseBrush: Brush,
    val borderColor: Color,
)

@Composable
internal fun paletteFor(style: CardBackStyle): CardBackPalette = when (style) {
    CardBackStyle.Default -> CardBackPalette(
        baseBrush = Brush.linearGradient(listOf(PokerPalette.CardBackBlue, PokerPalette.CardBackBlue)),
        borderColor = Color.White.copy(alpha = 0.18f),
    )
    CardBackStyle.Marble -> CardBackPalette(
        // Cool grey-on-grey gradient — reads as veined stone without
        // needing a custom drawable.
        baseBrush = Brush.linearGradient(
            listOf(Color(0xFF8E8E96), Color(0xFFCFCFD6), Color(0xFF8E8E96)),
        ),
        borderColor = Color.White.copy(alpha = 0.3f),
    )
    CardBackStyle.Gold -> CardBackPalette(
        // Goldsmith two-tone — slightly redder warm gold at the top
        // fading into [PokerPalette.ChipGold].
        baseBrush = Brush.linearGradient(
            listOf(Color(0xFFB67E2C), PokerPalette.ChipGold),
        ),
        borderColor = Color(0xFFFFE7A0),
    )
    CardBackStyle.Neon -> CardBackPalette(
        baseBrush = Brush.linearGradient(
            listOf(Color(0xFF6F1AB6), Color(0xFFE21FB6), Color(0xFF1ABFE2)),
        ),
        borderColor = Color(0xFFFF4DD9),
    )
    CardBackStyle.Diamond -> CardBackPalette(
        baseBrush = Brush.linearGradient(
            listOf(Color(0xFF1F3A8A), Color(0xFF60A5FA), Color(0xFF1F3A8A)),
        ),
        borderColor = Color(0xFFA9C2FF),
    )
    CardBackStyle.ComebackKid -> CardBackPalette(
        baseBrush = Brush.linearGradient(
            listOf(Color(0xFF6B1E0E), Color(0xFFE85D1B), Color(0xFFE8BC4D)),
        ),
        borderColor = Color(0xFFFFC766),
    )
}
