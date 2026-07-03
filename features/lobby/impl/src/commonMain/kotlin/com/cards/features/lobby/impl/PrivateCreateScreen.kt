package com.dangerfield.cards.features.lobby.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.private_create_card_back_label
import cards.libraries.resources.generated.resources.private_create_cta
import cards.libraries.resources.generated.resources.private_create_default_room_name
import cards.libraries.resources.generated.resources.private_create_felt_label
import cards.libraries.resources.generated.resources.private_create_invite_note
import cards.libraries.resources.generated.resources.private_create_blinds_caption
import cards.libraries.resources.generated.resources.private_create_buyin_label
import cards.libraries.resources.generated.resources.private_create_max_players_label
import cards.libraries.resources.generated.resources.private_create_open_caption
import cards.libraries.resources.generated.resources.private_create_open_label
import cards.libraries.resources.generated.resources.private_create_open_note
import cards.libraries.resources.generated.resources.private_create_room_name_label
import cards.libraries.resources.generated.resources.private_create_rules_label
import cards.libraries.resources.generated.resources.private_create_title
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.EdgeToEdgeRow
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Slider
import com.dangerfield.cards.libraries.ui.components.Switch
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.poker.CosmeticPreview
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.color.ProvideContentColor
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A single selectable cosmetic in the create-room felt / card-back picker.
 * Owned-only by construction (the entry point builds these lists from
 * inventory), so the host can never pick something they don't own.
 */
data class CosmeticChoice(
    val productId: String,
    val label: String,
    val emoji: String,
)

/**
 * Private "Create a room" screen (SPEC §6) — pick the buy-in (blinds scale off
 * it), max players, open-to-anyone, and the table cosmetics, then create.
 *
 * [onCreate] hands those settings to the seated lobby ([LobbyRoute] with
 * `autoCreate`), which owns the actual room creation and forwards each setting
 * to the server. The Open toggle chooses invite-only vs. open matchmaking.
 */
@Composable
fun PrivateCreateScreen(
    chipBalance: Long?,
    onBack: () -> Unit,
    onCreate: (
        maxPlayers: Int,
        buyIn: Long,
        openToAnyone: Boolean,
        feltProductId: String?,
        cardBackProductId: String?,
    ) -> Unit,
    felts: List<CosmeticChoice> = emptyList(),
    cardBacks: List<CosmeticChoice> = emptyList(),
    initialFeltProductId: String? = null,
    initialCardBackProductId: String? = null,
) {
    var maxPlayers by remember { mutableStateOf(6) }
    var openToAnyone by remember { mutableStateOf(false) }
    // Seed the picker from the host's equipped look (SHOP-3), then let them
    // change it. Falls back to the first owned option so a shelf is never
    // "nothing selected" if the equipped id isn't in the list.
    var selectedFelt by remember(initialFeltProductId, felts) {
        mutableStateOf(initialFeltProductId ?: felts.firstOrNull()?.productId)
    }
    var selectedCardBack by remember(initialCardBackProductId, cardBacks) {
        mutableStateOf(initialCardBackProductId ?: cardBacks.firstOrNull()?.productId)
    }
    // The buy-in tops out at what the host can actually afford (their chip
    // balance); until the balance loads we cap at the default. The slider can
    // never go below the engine's minimum valid buy-in.
    val maxBuyIn = (chipBalance ?: RoomSettings.DEFAULT_BUY_IN).coerceAtLeast(RoomSettings.MIN_BUY_IN)
    // ROOM-13: a first-time host opens on a sensible fraction of their bankroll,
    // not half of it. The slider still lets them go higher up to their balance.
    var buyIn by remember { mutableStateOf(RoomSettings.DEFAULT_HOST_BUY_IN) }
    // Re-clamp when the affordable ceiling changes (balance arrives / shrinks).
    LaunchedEffect(maxBuyIn) {
        buyIn = buyIn.coerceIn(RoomSettings.MIN_BUY_IN, maxBuyIn)
    }
    val settings = RoomSettings.forBuyIn(buyIn, maxPlayers)
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.private_create_title),
                onNavigateBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(Dimension.D500))

                Eyebrow(stringResource(Res.string.private_create_room_name_label))
                Spacer(Modifier.height(Dimension.D300))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Radii.R700.shape)
                        .background(AppTheme.colors.surface.color)
                        .border(1.dp, AppTheme.colors.border.color, Radii.R700.shape)
                        .padding(horizontal = Dimension.D600, vertical = Dimension.D500),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimension.D400),
                ) {
                    AvatarCircle(name = "You", emoji = "🦊", backgroundColorHex = "#E48A58", size = 28.dp)
                    Text(
                        text = stringResource(Res.string.private_create_default_room_name),
                        typography = AppTheme.typography.Label.L500,
                        color = AppTheme.colors.content,
                    )
                }

                Spacer(Modifier.height(Dimension.D800))
                Eyebrow(stringResource(Res.string.private_create_rules_label))
                Spacer(Modifier.height(Dimension.D300))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Radii.R750.shape)
                        .background(AppTheme.colors.surface.color)
                        .border(1.dp, AppTheme.colors.border.color, Radii.R750.shape),
                ) {
                    // Buy-in is the one number the host picks; blinds scale off it
                    // automatically. The slider is capped at the host's chip balance.
                    BuyInRow(
                        buyIn = buyIn,
                        blindsCaption = stringResource(
                            Res.string.private_create_blinds_caption,
                            settings.smallBlind,
                            settings.bigBlind,
                        ),
                        maxBuyIn = maxBuyIn,
                        onBuyInChange = { buyIn = it },
                    )
                    RuleDivider()
                    MaxPlayersRow(
                        value = maxPlayers,
                        onDecrement = { maxPlayers = (maxPlayers - 1).coerceAtLeast(2) },
                        onIncrement = { maxPlayers = (maxPlayers + 1).coerceAtMost(9) },
                    )
                    RuleDivider()
                    OpenToAnyoneRow(
                        checked = openToAnyone,
                        onCheckedChange = { openToAnyone = it },
                    )
                    if (felts.isNotEmpty()) {
                        RuleDivider()
                        CosmeticPickerRow(
                            label = stringResource(Res.string.private_create_felt_label),
                            choices = felts,
                            selectedProductId = selectedFelt,
                            onSelect = { selectedFelt = it },
                        )
                    }
                    if (cardBacks.isNotEmpty()) {
                        RuleDivider()
                        CosmeticPickerRow(
                            label = stringResource(Res.string.private_create_card_back_label),
                            choices = cardBacks,
                            selectedProductId = selectedCardBack,
                            onSelect = { selectedCardBack = it },
                        )
                    }
                }

                Spacer(Modifier.height(Dimension.D600))
                Text(
                    text = stringResource(
                        if (openToAnyone) Res.string.private_create_open_note
                        else Res.string.private_create_invite_note,
                    ),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                )
            }

            Spacer(Modifier.height(Dimension.D600))
            ButtonPrimary(
                onClick = { onCreate(maxPlayers, buyIn, openToAnyone, selectedFelt, selectedCardBack) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.private_create_cta))
            }
            Spacer(Modifier.height(Dimension.D800))
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Label.L300,
        color = AppTheme.colors.contentTertiary,
        allCaps = true,
    )
}

@Composable
private fun BuyInRow(
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

@Composable
private fun OpenToAnyoneRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimension.D600, vertical = Dimension.D400),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.private_create_open_label),
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.content,
            )
            Text(
                text = stringResource(Res.string.private_create_open_caption),
                typography = AppTheme.typography.Caption.C300,
                color = AppTheme.colors.contentTertiary,
            )
        }
        Spacer(Modifier.size(Dimension.D400))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A labelled horizontally-scrolling shelf of owned cosmetics (felts or card
 * backs). Each tile is a live [CosmeticPreview]; the selected one carries an
 * accent ring. Owned-only by construction — [choices] is the host's inventory.
 */
@Composable
private fun CosmeticPickerRow(
    label: String,
    choices: List<CosmeticChoice>,
    selectedProductId: String?,
    onSelect: (String) -> Unit,
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
        EdgeToEdgeRow {
            items(items = choices, key = { it.productId }) { choice ->
                CosmeticPickerTile(
                    choice = choice,
                    selected = choice.productId == selectedProductId,
                    onClick = { onSelect(choice.productId) },
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

@Composable
private fun MaxPlayersRow(value: Int, onDecrement: () -> Unit, onIncrement: () -> Unit) {
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
private fun RuleDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.border.color),
    )
}

@Preview
@Composable
private fun PrivateCreateScreenPreview() {
    PreviewContent {
        PrivateCreateScreen(
            chipBalance = 25_000,
            onBack = {},
            onCreate = { _, _, _, _, _ -> },
            felts = listOf(
                CosmeticChoice("felt_default", "Default", "🟩"),
                CosmeticChoice("felt_royal_red", "Royal Red", "🟥"),
                CosmeticChoice("felt_midnight_blue", "Midnight", "🟦"),
            ),
            cardBacks = listOf(
                CosmeticChoice("cardback_default", "Classic", "🂠"),
                CosmeticChoice("cardback_marble", "Marble", "🃏"),
            ),
            initialFeltProductId = "felt_royal_red",
            initialCardBackProductId = "cardback_default",
        )
    }
}
