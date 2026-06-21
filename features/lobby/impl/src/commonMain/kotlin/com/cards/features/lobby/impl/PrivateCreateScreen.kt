package com.dangerfield.cards.features.lobby.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.private_create_cta
import cards.libraries.resources.generated.resources.private_create_default_room_name
import cards.libraries.resources.generated.resources.private_create_invite_note
import cards.libraries.resources.generated.resources.private_create_blinds_caption
import cards.libraries.resources.generated.resources.private_create_buyin_label
import cards.libraries.resources.generated.resources.private_create_buyin_value
import cards.libraries.resources.generated.resources.private_create_max_players_label
import cards.libraries.resources.generated.resources.private_create_room_name_label
import cards.libraries.resources.generated.resources.private_create_rules_label
import cards.libraries.resources.generated.resources.private_create_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.room.RoomVisibility
import com.dangerfield.cards.libraries.ui.components.room.VisTag
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource

/**
 * Private "Create a room" screen (SPEC §6) — set the room name, table rules,
 * and max players, then create. Always invite-only; there's no public-room
 * creation.
 *
 * The rule selectors are presentational for now — "Create room" funnels into
 * the existing seated lobby ([LobbyRoute] with `autoCreate`), which owns room
 * creation. Plumbing custom stakes/stack into room creation is backend work
 * out of scope for this pass.
 */
@Composable
fun PrivateCreateScreen(
    onBack: () -> Unit,
    onCreate: (maxPlayers: Int) -> Unit,
) {
    var maxPlayers by remember { mutableStateOf(6) }
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.private_create_title),
                onNavigateBack = onBack,
                right = { VisTag(kind = RoomVisibility.Private) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
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
                // automatically, so we just show the derived value as a caption.
                RuleValueRow(
                    label = stringResource(Res.string.private_create_buyin_label),
                    value = stringResource(Res.string.private_create_buyin_value),
                    caption = stringResource(Res.string.private_create_blinds_caption),
                )
                RuleDivider()
                MaxPlayersRow(
                    value = maxPlayers,
                    onDecrement = { maxPlayers = (maxPlayers - 1).coerceAtLeast(2) },
                    onIncrement = { maxPlayers = (maxPlayers + 1).coerceAtMost(9) },
                )
            }

            Spacer(Modifier.height(Dimension.D600))
            Text(
                text = stringResource(Res.string.private_create_invite_note),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )

            Spacer(Modifier.weight(1f))
            ButtonPrimary(onClick = { onCreate(maxPlayers) }, modifier = Modifier.fillMaxWidth()) {
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
private fun RuleValueRow(label: String, value: String, caption: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimension.D600, vertical = Dimension.D500),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.content,
            )
            caption?.let {
                Text(
                    text = it,
                    typography = AppTheme.typography.Caption.C300,
                    color = AppTheme.colors.contentTertiary,
                )
            }
        }
        ChipCoin(size = 14.dp)
        Spacer(Modifier.size(Dimension.D200))
        Text(text = value, typography = AppTheme.typography.Label.L500, color = AppTheme.colors.content)
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
    background: com.dangerfield.cards.libraries.ui.system.color.ColorResource,
    foreground: com.dangerfield.cards.libraries.ui.system.color.ColorResource,
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

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PrivateCreateScreenPreview() {
    PreviewContent {
        PrivateCreateScreen(onBack = {}, onCreate = {})
    }
}
