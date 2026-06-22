package com.dangerfield.cards.features.rooms.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.public_leave_table
import cards.libraries.resources.generated.resources.public_lobby_leave_dialog_body
import cards.libraries.resources.generated.resources.public_lobby_leave_dialog_primary
import cards.libraries.resources.generated.resources.public_lobby_leave_dialog_secondary
import cards.libraries.resources.generated.resources.public_lobby_leave_dialog_title
import cards.libraries.resources.generated.resources.public_lobby_autodeal_body
import cards.libraries.resources.generated.resources.public_lobby_autodeal_title
import cards.libraries.resources.generated.resources.public_lobby_buyin_label
import cards.libraries.resources.generated.resources.public_lobby_buyin_value
import cards.libraries.resources.generated.resources.public_lobby_filling
import cards.libraries.resources.generated.resources.public_lobby_reserved
import cards.libraries.resources.generated.resources.public_lobby_seats_count
import cards.libraries.resources.generated.resources.public_lobby_stakes_label
import cards.libraries.resources.generated.resources.public_lobby_stakes_value
import cards.libraries.resources.generated.resources.public_lobby_subtitle
import cards.libraries.resources.generated.resources.public_lobby_timer
import cards.libraries.resources.generated.resources.public_lobby_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.NonLazyVerticalGrid
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.room.RoomSeat
import com.dangerfield.cards.libraries.ui.components.room.RoomVisibility
import com.dangerfield.cards.libraries.ui.components.room.VisTag
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource

/**
 * Public Lobby shell (SPEC §3) — matched into a table that hasn't dealt yet; a
 * timer auto-deals (no host). Static placeholder seats; "Leave table" exits.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PublicLobbyScreen(
    onLeave: () -> Unit,
) {
    val seats = lobbySeats()
    val filled = seats.count { it != null }
    // Leaving forfeits the held seat, so confirm first — both the header back
    // arrow and the system back gesture route through the same gate (matching
    // the multiplayer LobbyScreen's leave-confirm).
    var confirmingLeave by remember { mutableStateOf(false) }
    BackHandler(enabled = !confirmingLeave) { confirmingLeave = true }
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.public_lobby_title),
                sub = stringResource(Res.string.public_lobby_subtitle),
                onNavigateBack = { confirmingLeave = true },
                right = { VisTag(kind = RoomVisibility.Public) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
        ) {
            Spacer(Modifier.height(Dimension.D500))

            PublicHeroCard(
                title = stringResource(Res.string.public_lobby_autodeal_title),
                body = stringResource(Res.string.public_lobby_autodeal_body, filled),
                leading = {
                    HeroBadge {
                        Text(
                            text = stringResource(Res.string.public_lobby_timer),
                            typography = AppTheme.typography.Display.D800.Italic,
                            color = AppTheme.colors.content,
                        )
                    }
                },
            )

            Spacer(Modifier.height(Dimension.D800))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.public_lobby_seats_count, filled, seats.size),
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.content,
                )
                Text(
                    text = stringResource(Res.string.public_lobby_filling),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.contentTertiary,
                )
            }
            Spacer(Modifier.height(Dimension.D400))
            NonLazyVerticalGrid(
                columns = 3,
                data = seats,
                horizontalSpacing = Dimension.D500,
                verticalSpacing = Dimension.D500,
                modifier = Modifier.fillMaxWidth(),
            ) { _, player ->
                RoomSeat(player = player)
            }

            Spacer(Modifier.height(Dimension.D700))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimension.D400)) {
                ReadOnlyStakeCard(
                    label = stringResource(Res.string.public_lobby_stakes_label),
                    value = stringResource(Res.string.public_lobby_stakes_value),
                    showCoin = true,
                    modifier = Modifier.weight(1f),
                )
                ReadOnlyStakeCard(
                    label = stringResource(Res.string.public_lobby_buyin_label),
                    value = stringResource(Res.string.public_lobby_buyin_value),
                    showCoin = false,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R700.shape)
                    .background(AppTheme.colors.surface.color)
                    .border(1.dp, AppTheme.colors.border.color, Radii.R700.shape)
                    .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.success.color),
                )
                Spacer(Modifier.size(Dimension.D400))
                Text(
                    text = stringResource(Res.string.public_lobby_reserved),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                )
            }
            Spacer(Modifier.height(Dimension.D200))
            ButtonSecondary(
                onClick = { confirmingLeave = true },
                style = ButtonStyle.Text,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.public_leave_table))
            }
            Spacer(Modifier.height(Dimension.D800))
        }
    }

    if (confirmingLeave) {
        Dialog(
            title = stringResource(Res.string.public_lobby_leave_dialog_title),
            description = stringResource(Res.string.public_lobby_leave_dialog_body),
            primaryButtonText = stringResource(Res.string.public_lobby_leave_dialog_primary),
            secondaryButtonText = stringResource(Res.string.public_lobby_leave_dialog_secondary),
            onDismissRequest = { confirmingLeave = false },
            onPrimaryButtonClicked = {
                confirmingLeave = false
                onLeave()
            },
            onSecondaryButtonClicked = { confirmingLeave = false },
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PublicLobbyScreenPreview() {
    PreviewContent {
        PublicLobbyScreen(onLeave = {})
    }
}
