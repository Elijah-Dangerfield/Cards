package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.libraries.ui.components.dialog.BubbleSurface
import com.dangerfield.cards.libraries.ui.components.dialog.EmojiBubble
import com.dangerfield.cards.libraries.ui.components.dialog.EmojiBubbleDefaults
import com.dangerfield.cards.libraries.ui.components.dialog.EmojiBubbleNotchRadius
import com.dangerfield.cards.libraries.ui.system.LowLevelDSComponent
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.system.color.ProvideContentColor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Low-level bottom-sheet primitive — wraps Material3's [ModalBottomSheet]
 * but **does not** apply the DS gutter/padding shell or any opinionated
 * content scaffolding.
 *
 * For 99% of sheets use [BottomSheet] — it wraps [BaseBottomSheet] with
 * the standard horizontal gutter, top spacing, and [ModalContent]-style
 * scaffolding. Reach for [BaseBottomSheet] only when the caller is
 * deliberately escaping the defaults (custom inner padding,
 * full-bleed surface, one-off layout). The [LowLevelDSComponent] opt-in is
 * the discoverable signal that this is the escape hatch, not the standard
 * path.
 *
 * Drag-handle selection happens via the typed [dragHandle] parameter
 * (see [BottomSheetDragHandle]). When the handle is
 * [BottomSheetDragHandle.Emoji], the container's shape is replaced with
 * a [NotchedSheetShape] sized to match the emoji bubble so the bubble
 * sits half-above the sheet's top edge. Look-and-feel for the bubble
 * lives in [EmojiBubble] / [com.dangerfield.cards.libraries.ui.components.dialog.EmojiBubbleDefaults]
 * — sheets don't tune it.
 */
@LowLevelDSComponent
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun BaseBottomSheet(
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
        is BottomSheetDragHandle.Emoji -> NotchedSheetShape(
            cornerRadius = SheetCornerRadius,
            notchRadius = EmojiBubbleNotchRadius,
            notchCornerRadius = EmojiBubbleDefaults.notchCornerRadiusFor(dragHandle.style),
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
            when (dragHandle) {
                BottomSheetDragHandle.None -> Unit
                BottomSheetDragHandle.Basic -> DragHandle(
                    modifier = Modifier.fillMaxWidth(0.2f),
                    color = contentColor.color,
                )
                is BottomSheetDragHandle.Custom -> dragHandle.render()
                is BottomSheetDragHandle.Emoji -> EmojiBubble(
                    emoji = dragHandle.emoji,
                    style = dragHandle.style,
                    surface = dragHandle.surface
                        ?: BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
                    contentColor = contentColor,
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

/** Standard rounded-top-corner sheet shape (no notch). Used when the drag
 *  handle is anything other than [BottomSheetDragHandle.Emoji]. */
private val RoundedTopSheetShape: Shape =
    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

private val SheetCornerRadius = 16.dp

@OptIn(LowLevelDSComponent::class)
@Preview(heightDp = 500)
@Composable
private fun PreviewBaseBottomSheet() {
    PreviewContent {
        BaseBottomSheet(
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

@OptIn(LowLevelDSComponent::class)
@Preview(heightDp = 500)
@Composable
private fun PreviewBaseBottomSheet_EmojiHandle_DefaultSurface() {
    PreviewContent {
        BaseBottomSheet(
            onDismissRequest = {},
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            backgroundColor = AppTheme.colors.surfacePrimary,
            dragHandle = BottomSheetDragHandle.Emoji(emoji = "🎉"),
        ) {
            Text(
                text = "Emoji handle, default surface — bubble matches the sheet background.",
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

@OptIn(LowLevelDSComponent::class)
@Preview(heightDp = 500)
@Composable
private fun PreviewBaseBottomSheet_EmojiHandle_AccentSurface() {
    PreviewContent {
        BaseBottomSheet(
            onDismissRequest = {},
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            backgroundColor = AppTheme.colors.surfacePrimary,
            dragHandle = BottomSheetDragHandle.Emoji(
                emoji = "$",
                style = EmojiHandleStyle.Squircle,
                surface = BubbleSurface.Solid(AppTheme.colors.accentPrimary),
            ),
        ) {
            Text(
                text = "Emoji handle, explicit surfaceColor — bubble pops against the sheet.",
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
