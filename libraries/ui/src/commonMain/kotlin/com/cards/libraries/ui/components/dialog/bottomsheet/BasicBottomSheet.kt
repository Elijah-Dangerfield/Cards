package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.dialog.ModalContent
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.ui.tooling.preview.Preview

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
    backgroundColor: ColorResource = AppTheme.colors.background,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
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
                        icon = Icons.Check("CLose"),
                        onClick = state::dismiss
                    )
                }
            } else {
                VerticalSpacerD800()
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