package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.system.color.ProvideContentColor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Low-level bottom-sheet wrapper around Material3's [ModalBottomSheet].
 * Most callers should prefer [BasicBottomSheet] which adds the standard
 * gutter/padding shell on top of this.
 *
 * Drag-handle selection happens via the typed [dragHandle] parameter
 * (see [BottomSheetDragHandle]). When the handle is
 * [BottomSheetDragHandle.Icon], the container's shape is replaced with a
 * [NotchedSheetShape] sized to match the bubble — so the bubble can sit
 * half-above the sheet's top edge. The clip uses the shape's outline,
 * which includes the bulge, so the bubble's upper half is visible.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun BottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: ColorResource = AppTheme.colors.background,
    contentColor: ColorResource = LocalContentColor.current,
    state: BottomSheetState = rememberBottomSheetState(),
    sheetGesturesEnabled: Boolean = true,
    shouldDismissOnBackPress: Boolean = true,
    shouldDismissOnClickOutside: Boolean = true,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    dragHandle: BottomSheetDragHandle = BottomSheetDragHandle.Basic,
    content: @Composable ColumnScope.() -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val dismissComplete by rememberUpdatedState(onDismissRequest)

    DisposableEffect(state, coroutineScope) {
        state.attachDismissController(coroutineScope) { dismissComplete() }
        onDispose { state.detachDismissController(coroutineScope) }
    }

    BackHandler(enabled = shouldDismissOnBackPress) {
        if (shouldDismissOnBackPress) {
            state.dismiss()
        }
    }

    val sheetShape: Shape = when (dragHandle) {
        is BottomSheetDragHandle.Icon -> NotchedSheetShape(
            cornerRadius = SheetCornerRadius,
            notchRadius = dragHandle.bubbleSize / 2,
        )
        else -> RoundedTopSheetShape
    }

    ModalBottomSheet(
        onDismissRequest = { state.dismiss() },
        sheetState = state.materialSheetStateDelegate,
        containerColor = backgroundColor.color,
        sheetGesturesEnabled = sheetGesturesEnabled,
        scrimColor = AppTheme.colors.backgroundOverlay.color,
        shape = sheetShape,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = shouldDismissOnBackPress,
            shouldDismissOnClickOutside = shouldDismissOnClickOutside
        ),
        tonalElevation = 0.dp,
        dragHandle = {
            when (val handle = dragHandle) {
                BottomSheetDragHandle.None -> Unit
                BottomSheetDragHandle.Basic -> DragHandle(
                    modifier = Modifier.fillMaxWidth(0.2f),
                    color = contentColor.color,
                )
                is BottomSheetDragHandle.Custom -> handle.render()
                is BottomSheetDragHandle.Icon -> IconBubbleSlot(
                    spec = handle,
                    sheetBackground = backgroundColor,
                    sheetContentColor = contentColor,
                )
            }
        },
    ) {
        ProvideContentColor(contentColor) {
            Column(
                modifier = modifier,
                horizontalAlignment = contentAlignment,
                content = content,
            )
        }
    }
}

/**
 * Renders the overhanging icon bubble inside Material3's drag-handle slot.
 *
 * Coordinate system inside the slot (slot-y, where slot-y=0 is the top of
 * the slot — which is also the top of the sheet's measured bounds and the
 * top of the bulge carved by [NotchedSheetShape]):
 *
 * ```
 *  slot-y=0          ← top of bulge (visible sheet edge curves up to here)
 *      ___
 *     /   \          ← bulge area, slot-y ∈ [0, bubbleSize/2]
 *  ──┘     └──       ← slot-y = bubbleSize/2 — visual "regular" sheet top
 *    │     │
 *    │     │         ← bubble's bottom half, lives inside sheet body
 *    └─────┘         ← slot-y = bubbleSize — bottom of slot
 * ```
 *
 * The slot's measured height = `bubbleSize`. The bubble is sized
 * `bubbleSize × bubbleSize` and aligned to the TOP of the slot, so its
 * top edge aligns with slot-y=0 (the bulge top) and its bottom edge
 * aligns with slot-y=bubbleSize (just inside the sheet body). Visually,
 * the bubble's vertical center lands exactly on the sheet's regular top
 * edge → half-on / half-off look.
 *
 * No `Modifier.offset` is needed — the math works out by sizing alone,
 * which keeps the bubble inside the slot's measured bounds and avoids
 * any GraphicsLayer clipping surprises.
 *
 * Bubble fill defaults to the sheet's [sheetBackground]. The intentional
 * effect is "the sheet's top edge flows into the bubble" — they merge.
 * Override [BottomSheetDragHandle.Icon.backgroundColor] for a contrasting
 * bubble.
 */
@Composable
private fun IconBubbleSlot(
    spec: BottomSheetDragHandle.Icon,
    sheetBackground: ColorResource,
    sheetContentColor: ColorResource,
) {
    val bubbleSize = spec.bubbleSize
    val bubbleColor = spec.backgroundColor ?: sheetBackground
    val borderColor = spec.borderColor

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(bubbleSize),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .size(bubbleSize)
                .clip(CircleShape)
                .background(bubbleColor.color)
                .let {
                    if (borderColor != null) {
                        it.border(width = 1.dp, color = borderColor.color, shape = CircleShape)
                    } else {
                        it
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            ProvideContentColor(sheetContentColor) {
                spec.content()
            }
        }
    }
}

/** Standard rounded-top-corner sheet shape (no notch). Used when the drag
 *  handle is anything other than [BottomSheetDragHandle.Icon]. */
private val RoundedTopSheetShape: Shape =
    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

private val SheetCornerRadius = 16.dp

@Preview(heightDp = 500)
@Composable
private fun PreviewBottomSheet() {
    PreviewContent {
        BottomSheet(
            onDismissRequest = {},
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
        ) {
            Text(
                text = "Content",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        vertical = Dimension.D1400,
                        horizontal = Dimension.D400,
                    ),
                textAlign = TextAlign.Center,
            )
        }
    }
}
