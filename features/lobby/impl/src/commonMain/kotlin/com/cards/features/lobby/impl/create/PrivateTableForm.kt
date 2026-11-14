package com.dangerfield.cards.features.lobby.impl.create

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_item_sheet_locked_a11y
import cards.libraries.resources.generated.resources.private_create_buyin_label
import cards.libraries.resources.generated.resources.private_create_max_players_label
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.Slider
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.poker.BuyableCosmetic
import com.dangerfield.cards.libraries.ui.components.poker.CosmeticPreview
import com.dangerfield.cards.libraries.ui.cutout
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.color.ProvideContentColor
import org.jetbrains.compose.resources.stringResource
import com.dangerfield.cards.features.lobby.impl.CosmeticChoice

@Composable
internal fun BuyInRow(
    buyIn: Long,
    blindsCaption: String,
    maxBuyIn: Long,
    onBuyInChange: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimension.D600, vertical = Dimension.D500),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.private_create_buyin_label),
                    typography = AppTheme.typography.Label.L500,
                    color = AppTheme.colors.content,
                )
                Text(
                    text = blindsCaption,
                    typography = AppTheme.typography.Caption.C300,
                    color = AppTheme.colors.contentTertiary,
                )
            }
            ChipCoin(size = 14.dp)
            Spacer(Modifier.size(Dimension.D200))
            Text(
                text = formatThousands(buyIn),
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.content,
            )
        }
        Spacer(Modifier.height(Dimension.D300))
        ProvideContentColor(AppTheme.colors.accentPrimary) {
            Slider(
                value = buyIn.toFloat(),
                onValueChange = { onBuyInChange(roundBuyIn(it.toLong(), maxBuyIn)) },
                valueRange = RoomSettings.MIN_BUY_IN.toFloat()..maxBuyIn.coerceAtLeast(RoomSettings.MIN_BUY_IN + 1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Round a raw slider value to a tidy 50-chip step, clamped to the affordable range. */
private fun roundBuyIn(raw: Long, maxBuyIn: Long): Long {
    val step = 50L
    val rounded = ((raw + step / 2) / step) * step
    return rounded.coerceIn(RoomSettings.MIN_BUY_IN, maxBuyIn.coerceAtLeast(RoomSettings.MIN_BUY_IN))
}

/**
 * A labelled horizontally-scrolling shelf of cosmetics (felts or card backs):
 * the host's owned options first — each a live [CosmeticPreview], the selected
 * one carrying an accent ring — then the not-yet-owned catalog items as dimmed
 * locked tiles (ROOM-20). A locked tile isn't selectable; tapping it surfaces
 * the shared not-yours-yet sheet via [onLockedSelect].
 */
@Composable
internal fun CosmeticPickerRow(
    label: String,
    choices: List<CosmeticChoice>,
    selectedProductId: String?,
    onSelect: (String) -> Unit,
    locked: List<BuyableCosmetic> = emptyList(),
    onLockedSelect: (BuyableCosmetic) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimension.D400),
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.content,
            modifier = Modifier.padding(horizontal = Dimension.D600),
        )
        Spacer(Modifier.height(Dimension.D300))
        // A plain LazyRow, not EdgeToEdgeRow: this shelf lives inside the
        // clipped Rules card, so escaping the screen padding would only get
        // clipped at the card border and land item 0 flush against it, one
        // D600 left of the label. Content padding keeps item 0 under the
        // label while the scroll still bleeds to the card's edges.
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimension.D600),
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
        ) {
            items(items = choices, key = { it.productId }) { choice ->
                CosmeticPickerTile(
                    choice = choice,
                    selected = choice.productId == selectedProductId,
                    onClick = { onSelect(choice.productId) },
                )
            }
            items(items = locked, key = { it.productId }) { item ->
                LockedCosmeticPickerTile(
                    item = item,
                    onClick = { onLockedSelect(item) },
                )
            }
        }
    }
}

/** Preview edge inside a picker tile — the square the felt/card-back centers in. */
private val PickerPreviewSize = 56.dp

@Composable
private fun CosmeticPickerTile(
    choice: CosmeticChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ringColor = if (selected) AppTheme.colors.accentPrimary.color else AppTheme.colors.border.color
    // Bubbly rounded tile with a constant 2dp ring (accent when picked, border
    // otherwise) so selecting never nudges the row's layout, plus a faint accent
    // wash on the picked tile. The preview centers in a fixed square footprint so
    // felts (square) and card backs (portrait) both sit balanced — no lopsided
    // gap or clipped edge (ROOM-14).
    Box(
        modifier = Modifier
            .clip(Radii.Button.shape)
            .background(
                if (selected) AppTheme.colors.accentPrimary.color.copy(alpha = 0.12f) else Color.Transparent,
            )
            .border(2.dp, ringColor, Radii.Button.shape)
            .clickable(onClick = onClick)
            .padding(Dimension.D400),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(PickerPreviewSize),
            contentAlignment = Alignment.Center,
        ) {
            CosmeticPreview(
                productId = choice.productId,
                emoji = choice.emoji,
                size = PickerPreviewSize,
            )
        }
    }
}

/**
 * A not-yet-owned cosmetic on the picker shelf: same footprint as
 * [CosmeticPickerTile] so the row scans as one shelf, but never selectable —
 * the preview dims while the corner lock badge stays full-opacity (mirroring
 * the profile bookshelf's buyable tiles) so "not yours yet" reads clearly.
 */
@Composable
private fun LockedCosmeticPickerTile(
    item: BuyableCosmetic,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(Radii.Button.shape)
            .border(2.dp, AppTheme.colors.border.color, Radii.Button.shape)
            .clickable(onClick = onClick)
            .padding(Dimension.D400),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PickerPreviewSize)
                .alpha(LockedTileAlpha),
            contentAlignment = Alignment.Center,
        ) {
            CosmeticPreview(
                productId = item.productId,
                emoji = item.iconEmoji,
                size = PickerPreviewSize,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .cutout(
                    ringColor = AppTheme.colors.surface.color,
                    fillColor = AppTheme.colors.surfaceHigh.color,
                    shape = CircleShape,
                    ringWidth = 2.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon = Icons.Lock(stringResource(Res.string.profile_item_sheet_locked_a11y)),
                size = IconSize.Smallest,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}

private const val LockedTileAlpha = 0.45f

@Composable
internal fun MaxPlayersRow(value: Int, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimension.D600, vertical = Dimension.D400),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.private_create_max_players_label),
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.content,
            modifier = Modifier.weight(1f),
        )
        StepperButton("–", AppTheme.colors.surfaceRaised, AppTheme.colors.content, onDecrement)
        Spacer(Modifier.size(Dimension.D500))
        Text(
            text = value.toString(),
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        Spacer(Modifier.size(Dimension.D500))
        StepperButton("+", AppTheme.colors.accentPrimary, AppTheme.colors.onAccentPrimary, onIncrement)
    }
}

@Composable
private fun StepperButton(
    glyph: String,
    background: ColorResource,
    foreground: ColorResource,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(Radii.Round.shape)
            .background(background.color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            typography = AppTheme.typography.Heading.H700,
            color = foreground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun RuleDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.border.color),
    )
}

