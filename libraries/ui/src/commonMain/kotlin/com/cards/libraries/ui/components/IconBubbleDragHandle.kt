package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.color.ProvideContentColor
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Rich, "vibe-setting" drag handle for [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BasicBottomSheet]
 * (and any sibling Dialog primitives we add later).
 *
 * Instead of the plain horizontal pill, a chunky circular icon bubble sits
 * at the top of the sheet — so by the time the user finishes parsing the
 * sheet's title, they already know roughly what kind of thing this is
 * ("oh, a chip purchase," "oh, an emote browser"). Designed to be the
 * Cards-app equivalent of Duolingo's owl head poking out of a dialog:
 * it's recognisable before the words register.
 *
 * Pass [content] = a composable for the bubble's interior. Common choices:
 *  - [ChipCoin] for chip-related sheets
 *  - A 2-character emoji string wrapped in [Text]
 *  - A bespoke icon
 *
 * **Overhang note:** Material3's `ModalBottomSheet` clips its container,
 * so the bubble currently renders inside the sheet bounds rather than the
 * "half-on / half-off" treatment we eventually want. The slot lives in a
 * stable place; once we move to a custom sheet container that disables
 * clipping (or hosts the handle in scrim space), the overhang flips on for
 * every call site at once.
 *
 * @param content Bubble interior — sized for [bubbleSize].
 * @param modifier Outer modifier (applied to the column that wraps the
 *   bubble and the bottom spacing).
 * @param bubbleSize Diameter of the bubble. Tweak per sheet; bigger reads
 *   as more "celebrate this surface."
 * @param backgroundColor Bubble fill. Defaults to surfacePrimary so the
 *   bubble reads as "lifted off the sheet."
 * @param borderColor Optional thin ring around the bubble. Subtle by
 *   default — `null` to drop it.
 */
@Composable
fun IconBubbleDragHandle(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bubbleSize: Dp = 56.dp,
    backgroundColor: ColorResource = AppTheme.colors.surfacePrimary,
    borderColor: ColorResource? = AppTheme.colors.borderDisabled,
    bottomSpacing: Dp = 8.dp,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 4dp grabber strip above the bubble — gives the user something to
        // grab without the bubble taking on dual duty as a button. Keeps
        // affordance crystal-clear at a glance.
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.borderDisabled.color),
        )
        Spacer(modifier = Modifier.height(bottomSpacing))
        Box(
            modifier = Modifier
                .size(bubbleSize)
                .clip(CircleShape)
                .background(backgroundColor.color)
                .let {
                    if (borderColor != null) {
                        it.border(width = 1.dp, color = borderColor.color, shape = CircleShape)
                    } else {
                        it
                    }
                }
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProvideContentColor(color = AppTheme.colors.onSurfacePrimary) {
                content()
            }
        }
        Spacer(modifier = Modifier.height(bottomSpacing))
    }
}

@Preview
@Composable
private fun IconBubbleDragHandlePreview_Chips() {
    PreviewContent {
        Box(
            modifier = Modifier
                .background(AppTheme.colors.surfacePrimary.color)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconBubbleDragHandle(
                content = {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(PokerPalette.ChipGold),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$",
                            typography = AppTheme.typography.Heading.H700,
                            color = AppTheme.colors.background,
                        )
                    }
                },
            )
        }
    }
}

@Preview
@Composable
private fun IconBubbleDragHandlePreview_Emoji() {
    PreviewContent {
        Box(
            modifier = Modifier
                .background(AppTheme.colors.surfacePrimary.color)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconBubbleDragHandle(
                content = {
                    Text(
                        text = "🎉",
                        typography = AppTheme.typography.Heading.H800,
                        color = AppTheme.colors.text,
                    )
                },
            )
        }
    }
}
