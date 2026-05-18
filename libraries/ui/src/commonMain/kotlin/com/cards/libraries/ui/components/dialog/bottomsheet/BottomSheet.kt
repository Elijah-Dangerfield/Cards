package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
 * Layout strategy:
 *  1. The slot's measured height = `bubbleSize / 2 + 12dp` — that's the
 *     bottom half of the bubble plus space for the grabber pill. The TOP
 *     half of the bubble overhangs the sheet's top edge.
 *  2. The bubble Box is `size(bubbleSize)` with
 *     `Modifier.offset(y = -bubbleSize/2)` so its vertical CENTER lands
 *     at the slot's y=0 (which is the sheet's regular top edge).
 *  3. The notched sheet shape (set on the [ModalBottomSheet] container)
 *     carves out a half-circle of radius `bubbleSize/2` at top-center —
 *     so the bubble's overhanging top half is inside the clip outline
 *     and renders correctly.
 *
 * Bubble fill defaults to the sheet's [sheetBackground]. The intentional
 * effect is "the sheet's top edge IS the bubble" — they merge visually.
 * If callers want the bubble to stand out, they pass an explicit color
 * via [BottomSheetDragHandle.Icon.backgroundColor].
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
            .height(bubbleSize / 2 + 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .size(bubbleSize)
                .offset(y = -bubbleSize / 2)
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
        // Grabber pill — sits below the bubble. Reuses the sheet's content
        // color at translucent alpha so it doesn't shout louder than the
        // bubble.
        Box(
            modifier = Modifier
                .padding(top = bubbleSize / 2 + 6.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(sheetContentColor.color.copy(alpha = 0.25f)),
        )
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
