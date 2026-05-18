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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * Standard bottom-sheet shell with a drag handle (or close button),
 * horizontal gutters, and a [ModalContent] body.
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
 *   - [BottomSheetDragHandle.Icon] — chunky icon bubble that
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
@Composable
fun BasicBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    state: BottomSheetState = rememberBottomSheetState(),
    showCloseButton: Boolean = false,
    sheetGesturesEnabled: Boolean = true,
    shouldDismissOnBackPress: Boolean = true,
    shouldDismissOnClickOutside: Boolean = true,
    dragHandle: BottomSheetDragHandle = BottomSheetDragHandle.Basic,
    backgroundColor: ColorResource = AppTheme.colors.background,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    topPadding: Dp = defaultTopPaddingFor(dragHandle),
    stickyTopContent: @Composable () -> Unit = {},
    stickyBottomContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val effectiveHandle = if (showCloseButton) BottomSheetDragHandle.None else dragHandle

    BottomSheet(
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
                        icon = Icons.Close("Close"),
                        onClick = state::dismiss,
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
 * Picks the right default top padding for the chosen handle. Icon sheets
 * already have plenty of vertical real estate from the bubble + grabber,
 * so they start the body content immediately. Plain / no handle sheets
 * need a small breath between the handle and the headline.
 */
private fun defaultTopPaddingFor(handle: BottomSheetDragHandle): Dp = when (handle) {
    is BottomSheetDragHandle.Icon -> 0.dp
    else -> Dimension.D400
}

// ---------------------------------------------------------------------------
// Previews — one per BottomSheetDragHandle variant, plus the close-button
// path. Lets us eyeball every shape the DS offers without spinning the app.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun PreviewBasicBottomSheet_HandleBasic() {
    PreviewContent {
        BasicBottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            backgroundColor = AppTheme.colors.surfacePrimary,
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
private fun PreviewBasicBottomSheet_HandleNone() {
    PreviewContent {
        BasicBottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            dragHandle = BottomSheetDragHandle.None,
            backgroundColor = AppTheme.colors.surfacePrimary,
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
private fun PreviewBasicBottomSheet_HandleIconCoin() {
    PreviewContent {
        BasicBottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            backgroundColor = AppTheme.colors.surfacePrimary,
            dragHandle = BottomSheetDragHandle.Icon(
                content = {
                    com.dangerfield.cards.libraries.ui.components.ChipCoin(
                        size = 36.dp,
                        textTypography = AppTheme.typography.Heading.H700,
                    )
                },
            ),
            stickyTopContent = {
                Text(
                    text = "Spend chips",
                    typography = AppTheme.typography.Heading.H700,
                )
            },
            content = {
                Text(
                    text = "The bubble's TOP half sits above the sheet edge — the sheet container shape has a circular notch carved out at the top so the overhang renders correctly.",
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
private fun PreviewBasicBottomSheet_HandleIconEmoji() {
    PreviewContent {
        BasicBottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            backgroundColor = AppTheme.colors.surfacePrimary,
            dragHandle = BottomSheetDragHandle.Icon(
                bubbleSize = 64.dp,
                content = {
                    Text(
                        text = "🎉",
                        typography = AppTheme.typography.Heading.H800,
                    )
                },
            ),
            stickyTopContent = {
                Text(
                    text = "Achievement unlocked",
                    typography = AppTheme.typography.Heading.H700,
                )
            },
            content = {
                Text(
                    text = "Bigger bubble (64dp) for hero moments.",
                    typography = AppTheme.typography.Body.B500,
                    textAlign = TextAlign.Center,
                )
            },
        )
    }
}

@Preview
@Composable
private fun PreviewBasicBottomSheet_HandleIconWithBorder() {
    PreviewContent {
        BasicBottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            backgroundColor = AppTheme.colors.surfacePrimary,
            dragHandle = BottomSheetDragHandle.Icon(
                backgroundColor = AppTheme.colors.accentPrimary,
                borderColor = AppTheme.colors.onAccentPrimary,
                content = {
                    Text(
                        text = "🃏",
                        typography = AppTheme.typography.Heading.H800,
                    )
                },
            ),
            stickyTopContent = {
                Text(
                    text = "Card back equipped",
                    typography = AppTheme.typography.Heading.H700,
                )
            },
            content = {
                Text(
                    text = "Bubble can stand out from the sheet via a custom fill + border.",
                    typography = AppTheme.typography.Body.B500,
                    textAlign = TextAlign.Center,
                )
            },
        )
    }
}

@Preview
@Composable
private fun PreviewBasicBottomSheet_CloseButton() {
    PreviewContent {
        BasicBottomSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
            showCloseButton = true,
            backgroundColor = AppTheme.colors.surfacePrimary,
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
