package com.dangerfield.cards.libraries.navigation.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.access_denied_appeal
import cards.libraries.resources.generated.resources.access_denied_banned_message
import cards.libraries.resources.generated.resources.access_denied_banned_title
import cards.libraries.resources.generated.resources.access_denied_check_again
import cards.libraries.resources.generated.resources.access_denied_checking
import cards.libraries.resources.generated.resources.access_denied_still_blocked
import cards.libraries.resources.generated.resources.access_denied_default_message
import cards.libraries.resources.generated.resources.access_denied_default_title
import cards.libraries.resources.generated.resources.access_denied_suspended_message
import cards.libraries.resources.generated.resources.access_denied_suspended_title
import com.dangerfield.cards.libraries.core.doNothing
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD1600
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AccessDeniedScreen(
    reason: String,
    appealUrl: String?,
    checking: Boolean,
    stillBlocked: Boolean,
    onAppeal: (String) -> Unit,
    onCheckAgain: () -> Unit,
) {
    BackHandler { doNothing() }
    val copy = accessDeniedCopy(reason)
    Screen { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = Dimension.D1000),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(copy.title),
                typography = AppTheme.typography.Display.D1000,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Dimension.D500))
            Text(
                text = stringResource(copy.message),
                typography = AppTheme.typography.Body.B400,
                textAlign = TextAlign.Center,
            )
            if (stillBlocked) {
                Spacer(modifier = Modifier.height(Dimension.D500))
                Text(
                    text = stringResource(Res.string.access_denied_still_blocked),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.weight(2f))

            Button(
                size = ButtonSize.Large,
                type = ButtonType.Secondary,
                enabled = !checking,
                onClick = onCheckAgain,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (checking) Res.string.access_denied_checking else Res.string.access_denied_check_again,
                    ),
                )
            }

            if (appealUrl != null) {
                Spacer(modifier = Modifier.height(Dimension.D500))
                Button(
                    size = ButtonSize.Large,
                    type = ButtonType.Secondary,
                    onClick = { onAppeal(appealUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(Res.string.access_denied_appeal))
                }
            }
            VerticalSpacerD1600()
        }
    }
}

private data class AccessDeniedCopy(val title: StringResource, val message: StringResource)

private fun accessDeniedCopy(reason: String): AccessDeniedCopy = when (reason) {
    "banned" -> AccessDeniedCopy(
        title = Res.string.access_denied_banned_title,
        message = Res.string.access_denied_banned_message,
    )
    "suspended" -> AccessDeniedCopy(
        title = Res.string.access_denied_suspended_title,
        message = Res.string.access_denied_suspended_message,
    )
    else -> AccessDeniedCopy(
        title = Res.string.access_denied_default_title,
        message = Res.string.access_denied_default_message,
    )
}

@Preview
@Composable
private fun AccessDeniedScreenPreview_Banned() {
    PreviewContent {
        AccessDeniedScreen(
            reason = "banned",
            appealUrl = "https://support.cards.app/appeal",
            checking = false,
            stillBlocked = false,
            onAppeal = {},
            onCheckAgain = {},
        )
    }
}

@Preview
@Composable
private fun AccessDeniedScreenPreview_Suspended_NoAppeal() {
    PreviewContent {
        AccessDeniedScreen(
            reason = "suspended",
            appealUrl = null,
            checking = false,
            stillBlocked = false,
            onAppeal = {},
            onCheckAgain = {},
        )
    }
}

@Preview
@Composable
private fun AccessDeniedScreenPreview_Checking() {
    PreviewContent {
        AccessDeniedScreen(
            reason = "banned",
            appealUrl = "https://support.cards.app/appeal",
            checking = true,
            stillBlocked = false,
            onAppeal = {},
            onCheckAgain = {},
        )
    }
}

@Preview
@Composable
private fun AccessDeniedScreenPreview_StillBlockedAfterCheck() {
    PreviewContent {
        AccessDeniedScreen(
            reason = "banned",
            appealUrl = "https://support.cards.app/appeal",
            checking = false,
            stillBlocked = true,
            onAppeal = {},
            onCheckAgain = {},
        )
    }
}

@Preview
@Composable
private fun AccessDeniedScreenPreview_UnknownReason() {
    PreviewContent {
        AccessDeniedScreen(
            reason = "something_new",
            appealUrl = "https://support.cards.app/appeal",
            checking = false,
            stillBlocked = false,
            onAppeal = {},
            onCheckAgain = {},
        )
    }
}
