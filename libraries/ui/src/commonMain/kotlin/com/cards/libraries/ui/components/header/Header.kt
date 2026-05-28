package com.dangerfield.cards.libraries.ui.components.header

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import com.dangerfield.cards.libraries.ui.screenHorizontalInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.thenIf
import com.dangerfield.cards.system.typography.TypographyResource
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_top_bar_back_a11y
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.HorizontalSpacerD500
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun TopBar(
    title: String? = null,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    typographyToken: TypographyResource = AppTheme.typography.Heading.H800,
    backgroundColor: Color = AppTheme.colors.background.color,
    actions: @Composable () -> Unit = {},
    scrollState: ScrollableState? = null,
    liftOnScroll: Boolean = scrollState != null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(screenHorizontalInsets)
            .thenIf(liftOnScroll) { elevateOnScroll(scrollState) }
            ,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            if (onNavigateBack != null) {
                IconButton(
                    size = IconButton.Size.Medium,
                    icon = Icons.ChevronLeft(stringResource(Res.string.ui_top_bar_back_a11y)),
                    enabled = backEnabled,
                    onClick = onNavigateBack
                )
                HorizontalSpacerD500()
            }
            title?.let {
                Text(text = title, typography = typographyToken)
            }
        }
        
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions()
        }
    }
}

private fun Modifier.elevateOnScroll(
    scrollState: ScrollableState?,
): Modifier {

    checkNotNull(scrollState) {
        "scrollState should not be null when liftOnScroll is true"
    }

    return this.composed {
        val elevation by animateDpAsState(
            if (scrollState.canScrollBackward) {
                Elevation.Header.dp
            } else {
                0.dp
            }, label = ""
        )
        Modifier.shadow(elevation)
    }
}

@Preview
@Composable
private fun PreviewHeader() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.header.TopBar(
            title = "Heading Title",
        )
    }
}

@Preview
@Composable
private fun PreviewHeaderWithBack() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.header.TopBar(
            title = "Heading Title",
            onNavigateBack = {}
        )
    }
}


