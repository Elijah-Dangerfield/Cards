package com.dangerfield.cards.libraries.ui.components.header

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun TopBar(
    title: String? = null,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    typographyToken: TypographyResource = AppTheme.typography.Display.D900,
    backgroundColor: Color = AppTheme.colors.background.color,
    actions: @Composable () -> Unit = {},
    scrollState: ScrollState? = null,
    liftOnScroll: Boolean = scrollState != null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
            )
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
                    size = IconButton.Size.Large,
                    icon = Icons.ChevronLeft("Navigate back"),
                    onClick = onNavigateBack
                )
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
    scrollState: ScrollState?,
): Modifier {

    checkNotNull(scrollState) {
        "ScrollState should not be null when liftOnScroll is true"
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

