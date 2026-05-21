package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.system.AppTheme

/**
 * Typed drag-handle slot for [BottomSheet] / [BaseBottomSheet].
 *
 * Standardised vocabulary so every sheet picks from the same set instead
 * of reinventing geometry and colors per feature. The DS owns the look
 * and feel — variants accept the **content** (an emoji char, a custom
 * composable for the escape hatch) but never visual tuning. Size,
 * background, border, padding, and typography live inside the renderer.
 * To change the look app-wide, change one place.
 *
 *  - [None] — no handle. For sheets that pair with their own top bar
 *    or aren't user-dismissable.
 *
 *  - [Basic] — plain Material-3 horizontal pill. Default for utility
 *    sheets.
 *
 *  - [Emoji] — chunky emoji bubble that **half-overhangs** the top edge
 *    of the sheet. The container shape becomes a [NotchedSheetShape] so
 *    the bubble's top half lives in the carved-out notch. Single source
 *    of truth for "sheet with a top icon" — purchase sheets, achievement
 *    unlock, item-equip, anywhere a glanceable cue should precede
 *    reading.
 *
 *  - [Custom] — escape hatch. Free-form composable. Use only when none
 *    of the above shapes fit; promoting back to a typed variant is
 *    preferred so other surfaces can reuse the treatment.
 */
@Stable
sealed interface BottomSheetDragHandle {

    /** No handle. Sheet dismissed via gesture / scrim / back press. */
    @Immutable
    object None : BottomSheetDragHandle

    /** Plain Material-3 horizontal pill. */
    @Immutable
    object Basic : BottomSheetDragHandle

    /**
     * Emoji bubble that half-overhangs the sheet's top edge.
     *
     * Caller picks the emoji (e.g., `"💃"`, `"🎰"`, `"$"`) and a [style]
     * (default [EmojiHandleStyle.Circle]; pass [EmojiHandleStyle.Squircle]
     * for purchase / commerce sheets where a softer, more "icon-app" look
     * is preferred). Everything else — bubble size, fill, typography,
     * border — is locked by the DS so sheets stay visually consistent
     * across the app.
     *
     * Construction is restricted to the `:libraries:ui` module — every
     * caller routes through [bottomSheetEmojiHandle], the composable
     * factory whose defaults can read [AppTheme]. Mirrors the
     * [com.dangerfield.cards.libraries.ui.components.dialog.DialogEmoji] /
     * [com.dangerfield.cards.libraries.ui.components.dialog.dialogEmoji]
     * split.
     *
     * @param emoji A short string rendered centered in the bubble. Single
     *   emoji or `$`-style char. Anything longer than ~2 grapheme clusters
     *   will overflow the bubble — keep it short.
     * @param style Bubble shape. [EmojiHandleStyle.Circle] is the
     *   universal default; [EmojiHandleStyle.Squircle] is reserved for
     *   commerce / purchase sheets.
     * @param surface Fill for the bubble — a [BubbleSurface.Solid] color
     *   or a [BubbleSurface.Gradient] brush. `null` means "match
     *   the sheet's surfacePrimary" so the bubble reads as a continuation
     *   of the top edge. Pass an explicit value to make the bubble pop
     *   against the sheet (brand accent, product-specific gradient that
     *   mirrors the card the user tapped, etc.).
     */
    @Immutable
    data class Emoji internal constructor(
        val emoji: String,
        val style: EmojiHandleStyle = EmojiHandleStyle.Circle,
        val surface: BubbleSurface? = null,
    ) : BottomSheetDragHandle

    /**
     * Free-form drag-handle composable. The sheet keeps its default
     * top-corner-rounded shape, so [render]ed content sits INSIDE the
     * sheet's clip — no overhang. If you want overhang, use [Emoji].
     */
    @Immutable
    data class Custom(val render: @Composable () -> Unit) : BottomSheetDragHandle
}

/**
 * Outline of the emoji bubble that sits in a sheet's / dialog's notch.
 *
 *  - [Circle] — pure circle. Universal default; reads as a "badge" or
 *    "stamp" affordance.
 *  - [Squircle] — softly rounded square. Reads as an "app icon"; reserved
 *    for commerce / purchase / equip flows where the bubble doubles as a
 *    product callout.
 */
enum class EmojiHandleStyle { Circle, Squircle }

/**
 * Theme-aware factory for [BottomSheetDragHandle.Emoji]. Mirrors
 * [com.dangerfield.cards.libraries.ui.components.dialog.dialogEmoji] so
 * sheets and dialogs share one DS chokepoint for the bubble fill — when
 * the default surface token gets pinned, both layers move together.
 */
@Composable
fun bottomSheetEmojiHandle(
    emoji: String,
    style: EmojiHandleStyle = EmojiHandleStyle.Circle,
    surface: BubbleSurface? = BubbleSurface.Solid(AppTheme.colors.surfaceTertiary),
): BottomSheetDragHandle = BottomSheetDragHandle.Emoji(emoji, style, surface)
