package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.system.color.ColorResource

/**
 * Typed drag-handle slot for [BasicBottomSheet] / [BottomSheet].
 *
 * Standardised so every sheet in the app picks from the same vocabulary
 * — no one is reinventing geometry or background colors per feature, and
 * the sheet's behaviour (whether to use a custom shape with a top-edge
 * bulge, where to position content, etc.) is driven by the type of handle
 * rather than callers wiring composables blind.
 *
 * Pick the variant that fits the sheet's role:
 *
 *  - [None] — no handle. Use for sheets that aren't user-dismissable, or
 *    sheets that pair with a [BasicBottomSheet.showCloseButton] style top
 *    bar.
 *
 *  - [Basic] — the plain Material-3 horizontal pill. Use for low-stakes
 *    sheets where the handle is a grabber, not a vibe.
 *
 *  - [Icon] — a chunky icon bubble that **half-overhangs the top edge** of
 *    the sheet. The sheet's container shape gets a circular notch at top-
 *    center so the bubble's top half sits above the sheet edge while the
 *    bottom half sits inside. Use for any sheet where "what kind of thing
 *    is this" should register before the user reads a word — purchase
 *    sheets, achievement unlock, item-equip, etc. This is the prescribed
 *    DS choice for "vibe" sheets.
 *
 *  - [Custom] — escape hatch. Pass a free-form composable. Use only when
 *    none of the above shapes fit; pulling it back into a typed variant
 *    is preferred so other surfaces can reuse the treatment.
 *
 * Marked [Stable] so callers can hoist a constant `BottomSheetDragHandle`
 * value into composition without triggering recompositions.
 */
@Stable
sealed interface BottomSheetDragHandle {

    /** No handle. Sheet is dismissed via gesture / scrim / back press. */
    @Immutable
    object None : BottomSheetDragHandle

    /** Plain Material-3 horizontal pill. */
    @Immutable
    object Basic : BottomSheetDragHandle

    /**
     * Chunky icon bubble that half-overhangs the sheet's top edge via a
     * notched container shape.
     *
     * @param content What sits inside the bubble. Sized for [bubbleSize].
     *   Pass `ChipCoin(...)`, an emoji `Text`, or a bespoke icon.
     * @param bubbleSize Diameter of the bubble (which is also the diameter
     *   of the notch in the sheet edge). Default 56dp reads as "small but
     *   important." Bump up to 64–72dp for hero sheets, down to 40dp for
     *   utility sheets.
     * @param backgroundColor Bubble fill. Default `null` means the bubble
     *   shares the sheet's `backgroundColor` so it reads as a continuation
     *   of the sheet's top edge. Override only if the bubble should
     *   visually pop with a different tone.
     * @param borderColor Optional thin ring around the bubble. Subtle by
     *   default so the bubble doesn't look like a button.
     */
    @Immutable
    data class Icon(
        val content: @Composable () -> Unit,
        val bubbleSize: Dp = 56.dp,
        val backgroundColor: ColorResource? = null,
        val borderColor: ColorResource? = null,
    ) : BottomSheetDragHandle

    /**
     * Free-form drag-handle composable. The sheet's container shape stays
     * default (rounded top corners, no notch), so [render]ed content sits
     * INSIDE the sheet's clip — it cannot overhang. If you want overhang,
     * use [Icon].
     */
    @Immutable
    data class Custom(val render: @Composable () -> Unit) : BottomSheetDragHandle
}
