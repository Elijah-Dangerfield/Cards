package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun BottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = false,
    backgroundColor: ColorResource = AppTheme.colors.background,
    contentColor: ColorResource = LocalContentColor.current,
    state: BottomSheetState = rememberBottomSheetState(),
    sheetGesturesEnabled: Boolean = true,
    shouldDismissOnBackPress: Boolean = true,
    shouldDismissOnClickOutside: Boolean = true,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    dragHandle: @Composable (() -> Unit)? = null,
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

    ModalBottomSheet(
        onDismissRequest = { state.dismiss() },
        sheetState = state.materialSheetStateDelegate,
        containerColor = backgroundColor.color,
        sheetGesturesEnabled = sheetGesturesEnabled,
        scrimColor = AppTheme.colors.backgroundOverlay.color,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = shouldDismissOnBackPress,
            shouldDismissOnClickOutside = shouldDismissOnClickOutside
        ),
        tonalElevation = 0.dp,
        dragHandle = {
            if (showDragHandle) {
                dragHandle?.let {
                    it()
                } ?: DragHandle(
                    modifier = Modifier.fillMaxWidth(0.2f),
                    color = contentColor.color
                )
            }
        },
    ) {
        ProvideContentColor(contentColor) {
            Column(
                modifier = modifier,
                horizontalAlignment = contentAlignment,
                content = content
            )
        }
    }
}

@Preview(heightDp = 500)
@Composable
private fun PreviewBottomSheet(
) {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet(
            onDismissRequest = {},
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
        ) {
            Text(
                text = "Content",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        vertical = Dimension.D1400,
                        horizontal = Dimension.D400
                    ),
                textAlign = TextAlign.Center
            )
        }
    }
}



