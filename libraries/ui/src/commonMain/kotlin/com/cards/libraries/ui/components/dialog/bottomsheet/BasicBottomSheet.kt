package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.dialog.ModalContent
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Standard bottom-sheet shell with a drag handle (or close button), horizontal
 * gutters, and a [ModalContent] body.
 *
 * Padding model:
 *  - Horizontal: [Dimension.D800] (32dp) on each side. Matches the rest of
 *    the modal family so a sheet and a Dialog feel like the same surface.
 *  - Bottom: [Dimension.D800] for safe-area / button breathing room.
 *  - Top: [topPadding]. Defaults to [Dimension.D400] (16dp) — tight enough
 *    that the sheet doesn't waste an inch on empty space, but leaves a beat
 *    between the drag handle and the first headline. Callers with custom
 *    drag handles that already include their own top space can set this to
 *    `0.dp`. Older call sites that want the previous airy feel can pass
 *    `Dimension.D800`.
 *
 * Custom drag handles — [dragHandle] is a slot:
 *  - When `null` (the default), the underlying [BottomSheet] renders the
 *    plain horizontal pill from Material3.
 *  - Pass a composable to get richer treatments. The
 *    [com.dangerfield.cards.libraries.ui.components.IconBubbleDragHandle]
 *    primitive is the prescribed DS option — a chunky icon bubble that
 *    sets the "this kind of sheet" vibe before the user reads a word.
 *
 * Drag handle slot ignored when [showCloseButton] is true, since the close
 * button replaces it anchor-wise.
 */
@Composable
fun BasicBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    state: BottomSheetState = rememberBottomSheetState(),
    showCloseButton: Boolean = false,
    sheetGesturesEnabled: Boolean = true,
    shouldDismissOnBackPress: Boolean = true,
    shouldDismissOnClickOutside: Boolean = true,
    showDragHandle: Boolean = true,
    dragHandle: @Composable (() -> Unit)? = null,
    backgroundColor: ColorResource = AppTheme.colors.background,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    topPadding: Dp = Dimension.D400,
    stickyTopContent: @Composable () -> Unit = {},
    stickyBottomContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    BottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier,
        state = state,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shouldDismissOnBackPress = shouldDismissOnBackPress,
        shouldDismissOnClickOutside = shouldDismissOnClickOutside,
        backgroundColor = backgroundColor,
        showDragHandle = showDragHandle && !showCloseButton,
        dragHandle = dragHandle,
        contentAlignment = contentAlignment,
    ) {
        Column(
            modifier = Modifier.padding(
                start = Dimension.D800,
                end = Dimension.D800,
                bottom = Dimension.D800
            )
        ) {
            if (showCloseButton) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Dimension.D800),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        icon = Icons.Close("CLose"),
                        onClick = state::dismiss
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(topPadding))
            }

            ModalContent(
                modifier = modifier,
                topContent = stickyTopContent,
                content = content,
                backgroundColor = if (backgroundColor.color.alpha < 1f) backgroundColor.withAlpha(0f) else backgroundColor,
                bottomContent = stickyBottomContent,
            )
        }
    }
}

@Composable
@Preview
private fun PreviewBasicBottomSheetCloseButton() {
    PreviewContent() {
        BasicBottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = { -> },
            modifier = Modifier,
            showCloseButton = true,
            stickyTopContent = { Text(text = "Top Content") },
            content = {
                Column {
                    Text(text = "content".repeat(100))
                    Text(text = "is good".repeat(100))
                }
            },
            stickyBottomContent = {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { }
                ) {
                    Text(text = "Bottom Content")
                }
            },
        )
    }
}

@Composable
@Preview
private fun PreviewBasicBottomSheet() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BasicBottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = { -> },
            modifier = Modifier,
            stickyTopContent = { Text(text = "Top Content") },
            content = {
                Column {
                    Text(text = "content".repeat(10))
                    Text(text = "is good".repeat(10))
                }
            },
            stickyBottomContent = {
                Button(onClick = { }) {
                    Text(text = "Bottom Content")
                }
            },
        )
    }
}
