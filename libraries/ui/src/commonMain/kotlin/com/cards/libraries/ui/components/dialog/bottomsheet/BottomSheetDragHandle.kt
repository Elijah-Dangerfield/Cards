package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.dangerfield.cards.libraries.ui.components.dialog.TopAccessory

/**
 * Typed drag-handle slot for [BottomSheet] / [BaseBottomSheet].
 *
 * Standardised vocabulary so every sheet picks from the same set instead
 * of reinventing geometry and colors per feature. The DS owns the look
 * and feel — variants accept the **content** (a [TopAccessory] payload,
 * a custom composable for the escape hatch) but never visual tuning.
 * Size, background, border, padding, and typography live inside the
 * renderer. To change the look app-wide, change one place.
 *
 *  - [None] — no handle. For sheets that pair with their own top bar
 *    or aren't user-dismissable.
 *
 *  - [Basic] — plain Material-3 horizontal pill. Default for utility
 *    sheets.
 *
 *  - [Accessory] — bubble that **half-overhangs** the top edge of the
 *    sheet. Wraps a [TopAccessory] (emoji / icon / custom). The
 *    container shape becomes a [NotchedSheetShape] so the bubble's top
 *    half lives in the carved-out notch. Single source of truth for
 *    "sheet with a top affordance" — purchase sheets, achievement
 *    unlock, item-equip, anywhere a glanceable cue should precede
 *    reading.
 *
 *  - [Custom] — escape hatch. Free-form composable that renders INSIDE
 *    the sheet's clip (no overhang). Use only when none of the above
 *    shapes fit; for overhang treatments use [Accessory] with a
 *    [TopAccessory.Custom] inside.
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
     * Top-edge bubble that half-overhangs the sheet. Holds the
     * [TopAccessory] payload (emoji, icon, or custom render) — the
     * sheet itself only knows about the notch geometry, not what fills
     * the slot.
     */
    @Immutable
    data class Accessory(val accessory: TopAccessory) : BottomSheetDragHandle

    /**
     * Free-form drag-handle composable. The sheet keeps its default
     * top-corner-rounded shape, so [render]ed content sits INSIDE the
     * sheet's clip — no overhang. If you want overhang, use [Accessory]
     * with [TopAccessory.Custom] inside.
     */
    @Immutable
    data class Custom(val render: @Composable () -> Unit) : BottomSheetDragHandle
}

/**
 * Convenience: lift a [TopAccessory] into a [BottomSheetDragHandle.Accessory].
 * Keeps callsites tight (`dragHandle = topAccessoryEmoji(...).asDragHandle()`)
 * while preserving the typed wrapper at the sheet API boundary.
 */
fun TopAccessory.asDragHandle(): BottomSheetDragHandle.Accessory =
    BottomSheetDragHandle.Accessory(this)
