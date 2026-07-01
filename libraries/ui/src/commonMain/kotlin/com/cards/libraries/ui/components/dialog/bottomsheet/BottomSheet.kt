package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.dialog.AccessoryShape
import com.dangerfield.cards.libraries.ui.components.dialog.TopAccessory
import com.dangerfield.cards.libraries.ui.system.LowLevelDSComponent
import com.dangerfield.cards.libraries.ui.components.dialog.ModalContent
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.ProvideTextConfig
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.typography.TypographyResource
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_close_a11y
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Standard bottom-sheet shell with a drag handle (or close button),
 * horizontal gutters, and a [ModalContent] body. This is the DS-opinionated
 * default — 99% of sheets should use this. For the raw escape hatch see
 * [BaseBottomSheet] (gated by [LowLevelDSComponent]).
 *
 * Padding model:
 *  - Horizontal: [Dimension.D800] on each side, matching the rest of the
 *    modal family.
 *  - Bottom: [Dimension.D800] for safe-area / button breathing room.
 *  - Top: [topPadding]. Default depends on [dragHandle] — see below.
 *
 * Drag handle:
 *  The [dragHandle] param picks from the typed [BottomSheetDragHandle]
 *  vocabulary. The big choices:
 *
 *   - [BottomSheetDragHandle.Accessory] — chunky bubble that
 *     **half-overhangs** the sheet's top edge (via [NotchedSheetShape]).
 *     The default [topPadding] for this variant is `0.dp` because the
 *     bubble already provides plenty of vertical real estate.
 *
 *   - [BottomSheetDragHandle.Basic] — plain Material-3 pill. Default
 *     [topPadding] is `Dimension.D400` so the first headline sits a beat
 *     below the pill.
 *
 *   - [BottomSheetDragHandle.None] — no handle. Default [topPadding] is
 *     `Dimension.D400` so content doesn't crash into the sheet top.
 *
 *   - [BottomSheetDragHandle.Custom] — escape hatch. Default [topPadding]
 *     `Dimension.D400`; caller can override.
 *
 *  When [showCloseButton] is true, the handle is overridden to a close
 *  button anchored top-right; the [dragHandle] choice is ignored.
 */
@OptIn(LowLevelDSComponent::class)
@Composable
fun BottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    state: BottomSheetState = rememberBottomSheetState(),
    showCloseButton: Boolean = false,
    sheetGesturesEnabled: Boolean = true,
    shouldDismissOnBackPress: Boolean = true,
    shouldDismissOnClickOutside: Boolean = true,
    dragHandle: BottomSheetDragHandle = BottomSheetDragHandle.Basic,
    backgroundColor: ColorResource = AppTheme.colors.surface,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    topPadding: Dp = defaultTopPaddingFor(dragHandle),
    stickyTopContent: @Composable () -> Unit = {},
    stickyBottomContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val effectiveHandle = if (showCloseButton) BottomSheetDragHandle.None else dragHandle

    BaseBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier,
        state = state,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shouldDismissOnBackPress = shouldDismissOnBackPress,
        shouldDismissOnClickOutside = shouldDismissOnClickOutside,
        backgroundColor = backgroundColor,
        dragHandle = effectiveHandle,
        contentAlignment = contentAlignment,
    ) {
        Column(
            modifier = Modifier.padding(
                start = Dimension.D800,
                end = Dimension.D800,
                bottom = Dimension.D800,
            )
        ) {
            if (showCloseButton) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Dimension.D800),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        icon = Icons.Close(stringResource(Res.string.ui_close_a11y)),
                        onClick = state::dismiss,
                        // Raised a level above the sheet surface so the button
                        // reads as a real affordance (like the top-bar back
                        // button does against the page), not a flat glyph.
                        backgroundColor = AppTheme.colors.surfaceRaised,
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

/**
 * Title + freeform body preset. For sheets that share the
 * centered-title-then-rows shape (informational sheets, coming-soon
 * stubs, settings shells). Bakes the standard title typography +
 * trailing breathing room so callsites don't each re-pick them. Body
 * is freeform; the caller renders its own rows + spacers in the
 * [ColumnScope].
 */
@Composable
fun BottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    state: BottomSheetState = rememberBottomSheetState(),
    showCloseButton: Boolean = false,
    sheetGesturesEnabled: Boolean = true,
    shouldDismissOnBackPress: Boolean = true,
    shouldDismissOnClickOutside: Boolean = true,
    dragHandle: BottomSheetDragHandle = BottomSheetDragHandle.Basic,
    backgroundColor: ColorResource = AppTheme.colors.surface,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    topPadding: Dp = defaultTopPaddingFor(dragHandle),
    body: @Composable ColumnScope.() -> Unit,
) {
    BottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        state = state,
        showCloseButton = showCloseButton,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shouldDismissOnBackPress = shouldDismissOnBackPress,
        shouldDismissOnClickOutside = shouldDismissOnClickOutside,
        dragHandle = dragHandle,
        backgroundColor = backgroundColor,
        contentAlignment = contentAlignment,
        topPadding = topPadding,
        title = {
            Text(
                text = title,
                typography = SheetTitleTypography,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        body = body,
    )
}

/**
 * Composite-title variant of the title preset. Same paddings + body
 * shape as the string-title overload, but takes a `title: @Composable`
 * slot for cases where the title isn't a single line of text — e.g.
 * the tap-an-opponent profile sheet's displayName + sub-badge stack.
 *
 * Like every opinionated DS component, the sheet **owns the typography of
 * its title slot**: anything in [title] renders in the serif felt-signature
 * headline ([SheetTitleTypography]) in [content][Colors.content]. Body rows
 * inherit [Body.B500][BodyTypography.B500] from [ModalContent]. So a bare
 * `Text("…")` in [title] is correct by default; pass an explicit
 * `typography`/`color` on a child to override.
 */
@Composable
fun BottomSheet(
    title: @Composable ColumnScope.() -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    state: BottomSheetState = rememberBottomSheetState(),
    showCloseButton: Boolean = false,
    sheetGesturesEnabled: Boolean = true,
    shouldDismissOnBackPress: Boolean = true,
    shouldDismissOnClickOutside: Boolean = true,
    dragHandle: BottomSheetDragHandle = BottomSheetDragHandle.Basic,
    backgroundColor: ColorResource = AppTheme.colors.surface,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    topPadding: Dp = defaultTopPaddingFor(dragHandle),
    body: @Composable ColumnScope.() -> Unit,
) {
    BottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        state = state,
        showCloseButton = showCloseButton,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shouldDismissOnBackPress = shouldDismissOnBackPress,
        shouldDismissOnClickOutside = shouldDismissOnClickOutside,
        dragHandle = dragHandle,
        backgroundColor = backgroundColor,
        contentAlignment = contentAlignment,
        topPadding = topPadding,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ProvideTextConfig(
                typography = SheetTitleTypography,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            ) { title() }
            Spacer(modifier = Modifier.height(Dimension.D500))
            body()
            Spacer(modifier = Modifier.height(Dimension.D800))
        }
    }
}

/** Felt-world signature for a sheet headline — same serif Display as the dialog title. */
private val SheetTitleTypography: TypographyResource
    @Composable get() = AppTheme.typography.Display.D900.Italic

/**
 * Picks the right default top padding for the chosen handle. Icon sheets
 * already have plenty of vertical real estate from the bubble + grabber,
 * so they start the body content immediately. Plain / no handle sheets
 * need a small breath between the handle and the headline.
 */
private fun defaultTopPaddingFor(handle: BottomSheetDragHandle): Dp = when (handle) {
    is BottomSheetDragHandle.Accessory -> 0.dp
    else -> Dimension.D400
}

// ---------------------------------------------------------------------------
// Previews — one per BottomSheetDragHandle variant, plus the close-button
// path. Lets us eyeball every shape the DS offers without spinning the app.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun PreviewBottomSheet_HandleBasic() {
    PreviewContent {
        BottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            backgroundColor = AppTheme.colors.surface,
            stickyTopContent = {
                Text(
                    text = "Hello",
                    typography = AppTheme.typography.Heading.H700,
                )
            },
            content = {
                Text(
                    text = "Default Material drag pill. Use this for utility sheets.",
                    typography = AppTheme.typography.Body.B500,
                    textAlign = TextAlign.Center,
                )
            },
            stickyBottomContent = {
                Button(modifier = Modifier.fillMaxWidth(), onClick = {}) {
                    Text(text = "Confirm")
                }
            },
        )
    }
}

@Preview
@Composable
private fun PreviewBottomSheet_HandleNone() {
    PreviewContent {
        BottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            dragHandle = BottomSheetDragHandle.None,
            backgroundColor = AppTheme.colors.background,
            stickyTopContent = {
                Text(
                    text = "No drag handle",
                    typography = AppTheme.typography.Heading.H700,
                )
            },
            content = {
                Text(
                    text = "For sheets that aren't user-dismissable or pair with their own top bar.",
                    typography = AppTheme.typography.Body.B500,
                    textAlign = TextAlign.Center,
                )
            },
        )
    }
}

@Preview
@Composable
private fun PreviewBottomSheet_EmojiCircle() {
    PreviewContent {
        BottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            backgroundColor = AppTheme.colors.background,
            dragHandle = TopAccessory.Emoji(emoji = "🎉").asDragHandle(),
            stickyTopContent = {
                Text(
                    text = "Achievement unlocked",
                    typography = AppTheme.typography.Heading.H700,
                )
            },
            content = {
                Text(
                    text = "Default circle bubble. Use for celebration, info, or any non-commerce sheet.",
                    typography = AppTheme.typography.Body.B500,
                    textAlign = TextAlign.Center,
                )
            },
        )
    }
}

@Preview
@Composable
private fun PreviewBottomSheet_EmojiSquircle() {
    PreviewContent {
        BottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            backgroundColor = AppTheme.colors.background,
            dragHandle = TopAccessory.Emoji(
                emoji = "💃",
                style = AccessoryShape.Squircle,
            ).asDragHandle(),
            stickyTopContent = {
                Text(
                    text = "Victory Dance",
                    typography = AppTheme.typography.Heading.H700,
                )
            },
            content = {
                Text(
                    text = "Squircle bubble — reserved for commerce / purchase / equip sheets so the bubble doubles as a product callout.",
                    typography = AppTheme.typography.Body.B500,
                    textAlign = TextAlign.Center,
                )
            },
            stickyBottomContent = {
                Button(modifier = Modifier.fillMaxWidth(), onClick = {}) {
                    Text(text = "Buy now")
                }
            },
        )
    }
}

@Preview
@Composable
private fun PreviewBottomSheet_CloseButton() {
    PreviewContent {
        BottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            showCloseButton = true,
            backgroundColor = AppTheme.colors.background,
            stickyTopContent = {
                Text(
                    text = "Settings",
                    typography = AppTheme.typography.Heading.H700,
                )
            },
            content = {
                Text(
                    text = "When showCloseButton is true, the drag-handle parameter is ignored and an X button anchors the top-right.",
                    typography = AppTheme.typography.Body.B500,
                    textAlign = TextAlign.Center,
                )
            },
        )
    }
}
