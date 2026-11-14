package com.dangerfield.cards.features.lobby.impl

import com.dangerfield.cards.features.lobby.impl.create.BuyInRow
import com.dangerfield.cards.features.lobby.impl.create.MaxPlayersRow
import com.dangerfield.cards.features.lobby.impl.create.CosmeticPickerRow
import com.dangerfield.cards.features.lobby.impl.create.RuleDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.private_create_card_back_label
import cards.libraries.resources.generated.resources.private_create_cta
import cards.libraries.resources.generated.resources.private_create_default_room_name
import cards.libraries.resources.generated.resources.private_create_felt_label
import cards.libraries.resources.generated.resources.private_create_invite_note
import cards.libraries.resources.generated.resources.private_create_blinds_caption
import cards.libraries.resources.generated.resources.private_create_open_caption
import cards.libraries.resources.generated.resources.private_create_open_label
import cards.libraries.resources.generated.resources.private_create_open_note
import cards.libraries.resources.generated.resources.private_create_room_name_label
import cards.libraries.resources.generated.resources.private_create_rules_label
import cards.libraries.resources.generated.resources.private_create_title
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Switch
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.poker.BuyableCosmetic
import com.dangerfield.cards.libraries.ui.components.poker.CosmeticPreview
import com.dangerfield.cards.libraries.ui.components.poker.LockedCosmeticSheet
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A single selectable cosmetic in the create-room felt / card-back picker.
 * Owned-only by construction (the entry point builds these lists from
 * inventory), so the host can never pick something they don't own — the
 * not-yet-owned catalog items render separately as locked tiles (ROOM-20).
 * [emoji] is the fallback glyph when [CosmeticPreview] has no richer
 * rendering for the product id.
 */
data class CosmeticChoice(
    val productId: String,
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
    hostName: String? = null,
    hostAvatarEmoji: String? = null,
    hostAvatarColorHex: String? = null,
    felts: List<CosmeticChoice> = emptyList(),
    cardBacks: List<CosmeticChoice> = emptyList(),
    initialFeltProductId: String? = null,
    initialCardBackProductId: String? = null,
    lockedFelts: List<BuyableCosmetic> = emptyList(),
    lockedCardBacks: List<BuyableCosmetic> = emptyList(),
    onOpenLockedInShop: (String) -> Unit = {},
) {
    var maxPlayers by remember { mutableStateOf(6) }
    var openToAnyone by remember { mutableStateOf(false) }
    // A tapped locked tile opens the same not-yours-yet sheet the profile
    // bookshelf uses (ROOM-20); its CTA deep-links into the shop.
    var lockedSelected by remember { mutableStateOf<BuyableCosmetic?>(null) }
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
                    AvatarCircle(
                        name = hostName ?: "You",
                        emoji = hostAvatarEmoji,
                        backgroundColorHex = hostAvatarColorHex,
                        size = 28.dp,
                    )
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
                        onDecrement = { maxPlayers = (maxPlayers - 1).coerceAtLeast(RoomSettings.MIN_SEATS) },
                        onIncrement = { maxPlayers = (maxPlayers + 1).coerceAtMost(RoomSettings.MAX_SEATS) },
                    )
                    RuleDivider()
                    OpenToAnyoneRow(
                        checked = openToAnyone,
                        onCheckedChange = { openToAnyone = it },
                    )
                    if (felts.isNotEmpty() || lockedFelts.isNotEmpty()) {
                        RuleDivider()
                        CosmeticPickerRow(
                            label = stringResource(Res.string.private_create_felt_label),
                            choices = felts,
                            selectedProductId = selectedFelt,
                            onSelect = { selectedFelt = it },
                            locked = lockedFelts,
                            onLockedSelect = { lockedSelected = it },
                        )
                    }
                    if (cardBacks.isNotEmpty() || lockedCardBacks.isNotEmpty()) {
                        RuleDivider()
                        CosmeticPickerRow(
                            label = stringResource(Res.string.private_create_card_back_label),
                            choices = cardBacks,
                            selectedProductId = selectedCardBack,
                            onSelect = { selectedCardBack = it },
                            locked = lockedCardBacks,
                            onLockedSelect = { lockedSelected = it },
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

    lockedSelected?.let { item ->
        LockedCosmeticSheet(
            item = item,
            onOpenInShop = onOpenLockedInShop,
            onDismiss = { lockedSelected = null },
        )
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

@Preview
@Composable
private fun PrivateCreateScreenPreview() {
    PreviewContent {
        PrivateCreateScreen(
            chipBalance = 25_000,
            onBack = {},
            onCreate = { _, _, _, _, _ -> },
            hostName = "Elijah",
            hostAvatarEmoji = "🦊",
            hostAvatarColorHex = "#E48A58",
            felts = listOf(
                CosmeticChoice("felt_default", "🟩"),
                CosmeticChoice("felt_royal_red", "🟥"),
                CosmeticChoice("felt_midnight_blue", "🟦"),
            ),
            cardBacks = listOf(
                CosmeticChoice("cardback_default", "🂠"),
                CosmeticChoice("cardback_marble", "🃏"),
            ),
            initialFeltProductId = "felt_royal_red",
            initialCardBackProductId = "cardback_default",
            lockedFelts = listOf(
                BuyableCosmetic(productId = "felt_sunset", title = "Sunset", iconEmoji = "🟧"),
            ),
            lockedCardBacks = listOf(
                BuyableCosmetic(productId = "cardback_galaxy", title = "Galaxy", iconEmoji = "✨"),
            ),
        )
    }
}

@Preview
@Composable
private fun PrivateCreateScreenPreview_NoCosmetics_NoProfile() {
    // Cosmetics failed to load (picker rows hidden) and the profile hasn't
    // resolved — the avatar falls back to a name initial.
    PreviewContent {
        PrivateCreateScreen(
            chipBalance = null,
            onBack = {},
            onCreate = { _, _, _, _, _ -> },
        )
    }
}
