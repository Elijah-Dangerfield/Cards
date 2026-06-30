package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_screenshot_add_a11y
import cards.libraries.resources.generated.resources.ui_screenshot_attach_label
import cards.libraries.resources.generated.resources.ui_screenshot_count
import cards.libraries.resources.generated.resources.ui_screenshot_remove_a11y
import com.dangerfield.cards.libraries.ui.MAX_FEEDBACK_SCREENSHOTS
import com.dangerfield.cards.libraries.ui.PickedImage
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.rememberImagePicker
import com.dangerfield.cards.libraries.ui.toImageBitmap
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD300
import org.jetbrains.compose.resources.stringResource

private val TileSize = 76.dp

/**
 * DS field that lets the user attach up to [maxImages] screenshots to a
 * feedback / bug report. Shows a labelled "x/max" header, a row of square
 * thumbnails (each with a remove badge), and an add tile that opens the system
 * photo picker until the cap is reached. Picked images arrive already
 * downscaled + JPEG-compressed (see [rememberImagePicker]).
 *
 * Stateless: the caller owns the [screenshots] list and reacts to [onPicked]
 * (newly chosen images, to be appended + capped) and [onRemove] (index to drop).
 */
@Composable
fun ScreenshotAttachmentField(
    screenshots: List<PickedImage>,
    onPicked: (List<PickedImage>) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxImages: Int = MAX_FEEDBACK_SCREENSHOTS,
) {
    val picker = rememberImagePicker(maxImages = maxImages) { picked ->
        if (picked.isNotEmpty()) onPicked(picked)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.ui_screenshot_attach_label),
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.content,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.ui_screenshot_count, screenshots.size, maxImages),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }

        VerticalSpacerD300()

        Row(horizontalArrangement = Arrangement.spacedBy(Dimension.D400)) {
            screenshots.forEachIndexed { index, image ->
                ScreenshotThumbnail(image = image, onRemove = { onRemove(index) })
            }
            if (screenshots.size < maxImages) {
                AddScreenshotTile(onClick = picker::launch)
            }
        }
    }
}

@Composable
private fun ScreenshotThumbnail(
    image: PickedImage,
    onRemove: () -> Unit,
) {
    val bitmap = remember(image) { image.bytes.toImageBitmap() }

    Box(
        modifier = Modifier
            .size(TileSize)
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surfaceRaised.color),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                icon = Icons.PhotoLibrary(null),
                size = IconSize.Medium,
                color = AppTheme.colors.contentTertiary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        IconButton(
            icon = Icons.Close(stringResource(Res.string.ui_screenshot_remove_a11y)),
            onClick = onRemove,
            size = IconButton.Size.Smallest,
            backgroundColor = AppTheme.colors.surfaceInverse,
            iconColor = AppTheme.colors.onSurfaceInverse,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Dimension.D200),
        )
    }
}

@Composable
private fun AddScreenshotTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(TileSize)
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surfaceRaised.color)
            .bounceClick(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon = Icons.PhotoLibrary(stringResource(Res.string.ui_screenshot_add_a11y)),
            size = IconSize.Medium,
            color = AppTheme.colors.contentSecondary,
        )
    }
}
