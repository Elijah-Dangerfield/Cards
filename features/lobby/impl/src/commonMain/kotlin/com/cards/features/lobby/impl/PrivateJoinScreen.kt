package com.dangerfield.cards.features.lobby.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.lobby_error_join_blank_code
import cards.libraries.resources.generated.resources.lobby_error_join_full
import cards.libraries.resources.generated.resources.lobby_error_join_network
import cards.libraries.resources.generated.resources.lobby_error_join_not_accepting
import cards.libraries.resources.generated.resources.lobby_error_join_not_found
import cards.libraries.resources.generated.resources.lobby_error_join_not_signed_in
import cards.libraries.resources.generated.resources.lobby_error_join_unknown
import cards.libraries.resources.generated.resources.private_join_cta
import cards.libraries.resources.generated.resources.private_join_heading
import cards.libraries.resources.generated.resources.private_join_helper
import cards.libraries.resources.generated.resources.private_join_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.room.CodeEntryField
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource

/** Room codes are 6-char alphanumeric (server [InMemoryRoomService.CODE_LENGTH]). */
private const val ROOM_CODE_LENGTH = 6

/**
 * Private "Join by code" screen (SPEC §7) — a [CodeEntryField] for the host's
 * 6-character room code. The code is validated in place by [PrivateJoinViewModel]
 * (the join is attempted here); a bad code shows an inline error and the screen
 * stays put — no navigation. Only a successful join routes on to the seated lobby
 * (ROOM-5).
 */
@Composable
fun PrivateJoinScreen(
    state: PrivateJoinState,
    onAction: (PrivateJoinAction) -> Unit,
    onBack: () -> Unit,
) {
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.private_join_title),
                onNavigateBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            GridIconTile()
            Spacer(Modifier.height(Dimension.D700))
            Text(
                text = stringResource(Res.string.private_join_heading),
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
            )
            Spacer(Modifier.height(Dimension.D300))
            Text(
                text = stringResource(Res.string.private_join_helper),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D900))
            CodeEntryField(
                value = state.code,
                onValueChange = { onAction(PrivateJoinAction.CodeChanged(it)) },
                length = ROOM_CODE_LENGTH,
                onImeDone = { if (state.canSubmit) onAction(PrivateJoinAction.Submit) },
            )

            state.error?.let { error ->
                Spacer(Modifier.height(Dimension.D400))
                Text(
                    text = error.message(),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.danger,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1f))
            ButtonPrimary(
                onClick = { onAction(PrivateJoinAction.Submit) },
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.private_join_cta))
            }
            Spacer(Modifier.height(Dimension.D800))
        }
    }
}

@Composable
private fun PrivateJoinError.message(): String = when (this) {
    PrivateJoinError.BlankCode -> stringResource(Res.string.lobby_error_join_blank_code)
    is PrivateJoinError.RoomNotFound -> stringResource(Res.string.lobby_error_join_not_found, code)
    PrivateJoinError.RoomFull -> stringResource(Res.string.lobby_error_join_full)
    PrivateJoinError.RoomNotAcceptingPlayers -> stringResource(Res.string.lobby_error_join_not_accepting)
    is PrivateJoinError.OverBalance -> message
    PrivateJoinError.NotSignedIn -> stringResource(Res.string.lobby_error_join_not_signed_in)
    PrivateJoinError.NetworkError -> stringResource(Res.string.lobby_error_join_network)
    PrivateJoinError.UnknownError -> stringResource(Res.string.lobby_error_join_unknown)
}

@Composable
private fun GridIconTile() {
    val teal = AppTheme.colors.accentSecondary.color
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(Radii.R800.shape)
            .background(AppTheme.colors.surfaceRaised.color),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(30.dp)) {
            val cell = size.width * 0.38f
            val gap = size.width * 0.24f
            drawRect(teal, topLeft = Offset(0f, 0f), size = Size(cell, cell))
            drawRect(teal, topLeft = Offset(cell + gap, cell + gap), size = Size(cell, cell))
            drawRect(teal, topLeft = Offset(cell + gap, 0f), size = Size(cell, cell), style = Stroke(width = size.width * 0.07f))
            drawRect(teal, topLeft = Offset(0f, cell + gap), size = Size(cell, cell), style = Stroke(width = size.width * 0.07f))
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PrivateJoinScreenPreview() {
    PreviewContent {
        PrivateJoinScreen(state = PrivateJoinState(), onAction = {}, onBack = {})
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PrivateJoinScreenPreview_RoomNotFound() {
    PreviewContent {
        PrivateJoinScreen(
            state = PrivateJoinState(
                code = "WXYZ12",
                error = PrivateJoinError.RoomNotFound("WXYZ12"),
            ),
            onAction = {},
            onBack = {},
        )
    }
}
