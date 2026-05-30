package com.dangerfield.cards.libraries.ui.components.icon

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.icon.IconButton.Size
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.ui.tooling.preview.Preview

@NonRestartableComposable
@Composable
fun IconButton(
    icon: IconResource,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    backgroundColor: ColorResource? = AppTheme.colors.surface,
    iconColor: ColorResource = LocalContentColor.current,
    size: Size = Size.Medium,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    backgroundOverride: androidx.compose.ui.graphics.Color? = null,
) {
    val padding = size.padding
    val iconSize = size.iconSize

    @Composable
    fun Button(modifier: Modifier) {
        Surface(
            modifier = modifier,
            contentPadding = PaddingValues(padding),
            // Caller-supplied raw color (e.g. the per-felt accent on the
            // play screen) wins over the DS-token `backgroundColor`. The
            // primary path stays type-safe; the override is for surfaces
            // whose background isn't expressible as a DS token.
            color = backgroundColor,
            colorOverride = backgroundOverride,
            contentColor = iconColor,
            radius = Radii.IconButton,
            onClick = onClick,
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactionSource
        ) {
            Icon(
                icon = icon,
                size = iconSize
            )
        }
    }

    Button(modifier = modifier)

}

object IconButton {
    enum class Size {
        Smallest,
        Small,
        Medium,
        Large,
        Largest,
    }
}

val Size.padding: Dp
    get() = when (this) {
        Size.Smallest -> Dimension.D100
        Size.Small -> Dimension.D200
        Size.Medium -> Dimension.D300
        Size.Large -> Dimension.D400
        Size.Largest -> Dimension.D400
    }

val Size.iconSize: IconSize
    get() = when (this) {
        Size.Smallest -> IconSize.Smallest
        Size.Small -> IconSize.Small
        Size.Medium -> IconSize.Medium
        Size.Large -> IconSize.Large
        Size.Largest -> IconSize.Largest
    }

private val iconButtons = listOf(
    Icons.Check(""),
    Icons.Check(""),
    Icons.Check(""),
    Icons.Check(""),
    Icons.Check(""),
    Icons.Settings(""),
    Icons.Check(""),
)

@Suppress("MagicNumber")
@Preview
@Composable
private fun PreviewIconButtons() {
    PreviewContent() {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(com.dangerfield.cards.libraries.ui.components.icon.iconButtons) { icon ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(8.dp)
                ) {
                    com.dangerfield.cards.libraries.ui.components.icon.IconButton(
                        icon = icon,
                        modifier = Modifier.size(48.dp),
                        backgroundColor = null,
                        size = Size.Medium,
                        onClick = {}
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = icon::class.simpleName ?: "Err")
                }
            }
        }
    }
}

@Suppress("MagicNumber")
@Preview
@Composable
private fun PreviewIconButtonsBackground() {
    PreviewContent() {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(com.dangerfield.cards.libraries.ui.components.icon.iconButtons) { icon ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(8.dp)
                ) {
                    com.dangerfield.cards.libraries.ui.components.icon.IconButton(
                        icon = icon,
                        modifier = Modifier.size(48.dp),
                        backgroundColor = AppTheme.colors.content,
                        iconColor = AppTheme.colors.background,
                        size = Size.Medium,
                        onClick = {}
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = icon::class.simpleName ?: "Err")
                }
            }
        }
    }
}