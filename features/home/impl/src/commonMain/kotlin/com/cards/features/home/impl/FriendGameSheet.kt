package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.friend_game_sheet_code_label
import cards.libraries.resources.generated.resources.friend_game_sheet_create_button
import cards.libraries.resources.generated.resources.friend_game_sheet_join_button
import cards.libraries.resources.generated.resources.friend_game_sheet_join_label
import cards.libraries.resources.generated.resources.friend_game_sheet_subtitle
import cards.libraries.resources.generated.resources.friend_game_sheet_title
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.resources.stringResource

/**
 * Friend Game entry sheet — the "further direction" step for a private match:
 * spin up a fresh room, or join a friend's by code. Both paths route into the
 * existing lobby (create lands seated via [LobbyRoute.autoCreate]; join via
 * [LobbyRoute.prefilledCode]), so the lobby stays the source of truth for the
 * seated experience.
 */
@Composable
internal fun FriendGameSheet(
    onCreate: () -> Unit,
    onJoin: (code: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val trimmed = code.trim()
    BottomSheet(
        title = stringResource(Res.string.friend_game_sheet_title),
        onDismissRequest = onDismiss,
        dragHandle = topAccessoryEmoji(emoji = "♣").asDragHandle(),
    ) {
        Text(
            text = stringResource(Res.string.friend_game_sheet_subtitle),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpacerD800()
        ButtonPrimary(
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.friend_game_sheet_create_button))
        }
        VerticalSpacerD800()
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(Res.string.friend_game_sheet_join_label),
                typography = AppTheme.typography.Label.L400,
                color = AppTheme.colors.contentSecondary,
            )
            VerticalSpacerD300()
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go,
                    capitalization = KeyboardCapitalization.Characters,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { if (trimmed.isNotBlank()) onJoin(trimmed) },
                ),
                label = { Text(stringResource(Res.string.friend_game_sheet_code_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD500()
            ButtonSecondary(
                onClick = { onJoin(trimmed) },
                enabled = trimmed.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.friend_game_sheet_join_button))
            }
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun FriendGameSheetPreview() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        FriendGameSheet(onCreate = {}, onJoin = {}, onDismiss = {})
    }
}
