package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme

/**
 * The "what sits in the lip of a modal's top edge" affordance shared by
 * [Dialog] and [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet].
 *
 * The notch geometry — half-circle / squircle carve-out, ring of scrim
 * halo, body gap below the bubble — is owned by the DS (see
 * [TopAccessoryDefaults]). All a caller picks is **what fills the slot**:
 *
 *  - [Emoji] — short string rendered as a glanceable glyph. The standard
 *    path; covers achievements, info, copy, "are you sure" surfaces.
 *  - [Icon] — themed vector icon. Use when an emoji doesn't read clearly
 *    (system actions, branded glyphs).
 *  - [Custom] — escape hatch for anything else (avatar tile, stacked
 *    coins, image, gradient logo). Promote back to a typed variant when
 *    a second surface needs the same treatment.
 *
 * `style` controls the bubble outline (circle vs. squircle); `surface`
 * controls the fill (null = match the host surface for a seamless top
 * edge, explicit = the bubble pops against the surrounding sheet/dialog).
 */
@Stable
sealed interface TopAccessory {
    val style: AccessoryShape
    val surface: BubbleSurface?

    /**
     * Single emoji / short glyph (e.g., `"🎉"`, `"💃"`, `"$"`) rendered
     * centered in the bubble. Anything longer than ~2 grapheme clusters
     * will overflow — keep it tight.
     *
     * Construction is restricted to the `:libraries:ui` module; every
     * caller routes through [topAccessoryEmoji] so the theme-aware
     * default surface stays a single chokepoint.
     */
    @Immutable
    data class Emoji internal constructor(
        val emoji: String,
        override val style: AccessoryShape = AccessoryShape.Circle,
        override val surface: BubbleSurface? = null,
    ) : TopAccessory

    /**
     * Themed [IconResource] rendered centered in the bubble. The icon
     * tint defaults to the host's content color; pass an explicit
     * [iconColor] for branded / accent treatments.
     *
     * Construction is restricted to the `:libraries:ui` module; every
     * caller routes through [topAccessoryIcon].
     */
    @Immutable
    data class Icon internal constructor(
        val icon: IconResource,
        val iconSize: IconSize = IconSize.Largest,
        val iconColor: ColorResource? = null,
        override val style: AccessoryShape = AccessoryShape.Circle,
        override val surface: BubbleSurface? = null,
    ) : TopAccessory

    /**
     * Free-form composable rendered inside the bubble slot. The bubble
     * still owns its outline shape ([style]) and fill ([surface]); the
     * caller owns whatever's painted on top — an avatar, a stacked-coin
     * glyph, a logo mark, a gradient image, etc.
     *
     * When you reach for this, also consider whether the shape deserves
     * a typed variant — if a second surface ever needs the same content,
     * lift it into the sealed type.
     */
    @Immutable
    data class Custom internal constructor(
        val render: @Composable () -> Unit,
        override val style: AccessoryShape = AccessoryShape.Circle,
        override val surface: BubbleSurface? = null,
    ) : TopAccessory
}

/**
 * Outline of the bubble that sits in a modal's notch.
 *
 *  - [Circle] — pure circle. Universal default; reads as a badge / stamp.
 *  - [Squircle] — softly rounded square. Reserved for commerce / equip
 *    flows where the bubble doubles as an "app-icon-like" product callout.
 */
enum class AccessoryShape { Circle, Squircle }

/**
 * Theme-aware factory for [TopAccessory.Emoji]. Default [surface] is a light
 * inverse disc (with a dark on-content color) so the bubble reads as a bright
 * "stamp" against the dark sheet/dialog and any emoji — including monochrome
 * glyphs like suit pips — stays legible on it. Pass `null` to match the host
 * surface for a seamless top edge, or an explicit accent / gradient when the
 * bubble should carry a specific color.
 */
@Composable
fun topAccessoryEmoji(
    emoji: String,
    style: AccessoryShape = AccessoryShape.Circle,
    surface: BubbleSurface? = BubbleSurface.Solid(
        color = AppTheme.colors.surfaceInverseDeep,
        onContentColor = AppTheme.colors.onSurfaceInverse,
    ),
): TopAccessory.Emoji = TopAccessory.Emoji(emoji, style, surface)

/**
 * Theme-aware factory for [TopAccessory.Icon]. Shares the emoji bubble's light
 * inverse default so every default accessory disc reads the same.
 */
@Composable
fun topAccessoryIcon(
    icon: IconResource,
    iconSize: IconSize = IconSize.Largest,
    iconColor: ColorResource? = null,
    style: AccessoryShape = AccessoryShape.Circle,
    surface: BubbleSurface? = BubbleSurface.Solid(
        color = AppTheme.colors.surfaceInverse,
        onContentColor = AppTheme.colors.onSurfaceInverse,
    ),
): TopAccessory.Icon = TopAccessory.Icon(icon, iconSize, iconColor, style, surface)

/**
 * Factory for the chip-themed top bubble used by chip-related modals
 * (rebuy, bust, chip rewards, soft-bust grant, tip-the-dealer). Paints a
 * solid casino-gold circle with a `$` glyph in the middle.
 */
@Composable
fun topAccessoryChipBubble(): TopAccessory.Emoji = TopAccessory.Emoji(
    emoji = "$",
    style = AccessoryShape.Circle,
    surface = BubbleSurface.Solid(
        color = ColorResource.FromColor(AppTheme.colors.poker.chipGold.color, "chip-gold"),
    ),
)

/**
 * Factory for a free-form top accessory. The bubble outline + fill stay
 * DS-owned; [render] fills the inner slot.
 */
@Composable
fun topAccessoryCustom(
    style: AccessoryShape = AccessoryShape.Circle,
    surface: BubbleSurface? = null,
    render: @Composable () -> Unit,
): TopAccessory.Custom = TopAccessory.Custom(render, style, surface)
