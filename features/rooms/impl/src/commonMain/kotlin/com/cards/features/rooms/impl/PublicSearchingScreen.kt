package com.dangerfield.cards.features.rooms.impl

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.public_searching_alone
import cards.libraries.resources.generated.resources.public_searching_buyin_label
import cards.libraries.resources.generated.resources.public_searching_cancel
import cards.libraries.resources.generated.resources.public_searching_error_body
import cards.libraries.resources.generated.resources.public_searching_error_back
import cards.libraries.resources.generated.resources.public_searching_error_insufficient
import cards.libraries.resources.generated.resources.public_searching_error_retry
import cards.libraries.resources.generated.resources.public_searching_joined
import cards.libraries.resources.generated.resources.public_searching_joining_bots_body
import cards.libraries.resources.generated.resources.public_searching_joining_bots_title
import cards.libraries.resources.generated.resources.public_searching_offer_body
import cards.libraries.resources.generated.resources.public_searching_offer_keep_waiting
import cards.libraries.resources.generated.resources.public_searching_offer_play_bots
import cards.libraries.resources.generated.resources.public_searching_offer_title
import cards.libraries.resources.generated.resources.public_searching_offer_try_later
import cards.libraries.resources.generated.resources.public_searching_rotate_1
import cards.libraries.resources.generated.resources.public_searching_rotate_2
import cards.libraries.resources.generated.resources.public_searching_rotate_3
import cards.libraries.resources.generated.resources.public_searching_rotate_4
import cards.libraries.resources.generated.resources.public_searching_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.isReduceMotionEnabled
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.room.MatchmakingRadar
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.room.RoomVisibility
import com.dangerfield.cards.libraries.ui.components.room.VisTag
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Public Searching / matchmaking screen — the honest, real-humans-first search.
 *
 * Three faces, driven entirely by [PublicSearchingState.phase]:
 *  - **Searching:** the radar, a found-count, and reassuring copy that rotates
 *    every few seconds so the wait feels alive (we genuinely look for people).
 *  - **BotFallbackOffer:** the honest "we couldn't find anyone — that's on us"
 *    panel. Play disclosed bots for real chips (they step aside the moment a
 *    real player joins), keep waiting, or bow out.
 *  - **JoiningBots:** a brief "setting up your table" beat while the bots seat.
 *
 * Navigation (hand-off to the live table, leaving) is the entry point's job via
 * the VM's events; this screen only renders state + forwards actions.
 */
@Composable
fun PublicSearchingScreen(
    state: PublicSearchingState,
    onAction: (PublicSearchingAction) -> Unit,
) {
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.public_searching_title),
                onNavigateBack = { onAction(PublicSearchingAction.Cancel) },
                right = { VisTag(kind = RoomVisibility.Public) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state.error != null -> ErrorContent(state.error, onAction)
                state.phase == SearchPhase.BotFallbackOffer -> BotFallbackContent(onAction)
                state.phase == SearchPhase.JoiningBots -> JoiningBotsContent()
                else -> SearchingContent(state, onAction)
            }
        }
    }
}

@Composable
private fun ColumnScope.SearchingContent(
    state: PublicSearchingState,
    onAction: (PublicSearchingAction) -> Unit,
) {
    Spacer(Modifier.weight(1f))

    MatchmakingRadar(reduceMotion = isReduceMotionEnabled())

    Spacer(Modifier.height(Dimension.D900))
    // Rotating reassurance — keeps the wait feeling alive while we genuinely
    // hunt for real players. Purely cosmetic, so it lives in the UI.
    RotatingReassurance()

    Spacer(Modifier.height(Dimension.D500))
    Text(
        text = if (state.realPlayersFound > 0) stringResource(Res.string.public_searching_joined)
        else stringResource(Res.string.public_searching_alone),
        typography = AppTheme.typography.Label.L400,
        color = AppTheme.colors.accentSecondary,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.weight(1f))

    BuyInRangeCard(state.minBuyIn, state.maxBuyIn)
    Spacer(Modifier.height(Dimension.D500))
    ButtonSecondary(
        onClick = { onAction(PublicSearchingAction.Cancel) },
        style = ButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.public_searching_cancel))
    }
    Spacer(Modifier.height(Dimension.D800))
}

@Composable
private fun RotatingReassurance() {
    val messages = listOf(
        Res.string.public_searching_rotate_1,
        Res.string.public_searching_rotate_2,
        Res.string.public_searching_rotate_3,
        Res.string.public_searching_rotate_4,
    )
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ROTATE_INTERVAL_MS)
            index = (index + 1) % messages.size
        }
    }
    Crossfade(targetState = messages[index]) { message ->
        Text(
            text = stringResource(message),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColumnScope.BotFallbackContent(onAction: (PublicSearchingAction) -> Unit) {
    Spacer(Modifier.weight(1f))
    PublicHeroCard(
        title = stringResource(Res.string.public_searching_offer_title),
        body = stringResource(Res.string.public_searching_offer_body),
        leading = {
            HeroBadge {
                Text("🤖", typography = AppTheme.typography.Heading.H700, color = AppTheme.colors.content)
            }
        },
    )
    Spacer(Modifier.weight(1f))

    ButtonPrimary(
        onClick = { onAction(PublicSearchingAction.PlayBots) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.public_searching_offer_play_bots))
    }
    Spacer(Modifier.height(Dimension.D400))
    ButtonSecondary(
        onClick = { onAction(PublicSearchingAction.KeepWaiting) },
        style = ButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.public_searching_offer_keep_waiting))
    }
    Spacer(Modifier.height(Dimension.D400))
    ButtonGhost(
        onClick = { onAction(PublicSearchingAction.TryAgainLater) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.public_searching_offer_try_later))
    }
    Spacer(Modifier.height(Dimension.D800))
}

@Composable
private fun ColumnScope.JoiningBotsContent() {
    Spacer(Modifier.weight(1f))
    MatchmakingRadar(reduceMotion = isReduceMotionEnabled())
    Spacer(Modifier.height(Dimension.D900))
    Text(
        text = stringResource(Res.string.public_searching_joining_bots_title),
        typography = AppTheme.typography.Heading.H800,
        color = AppTheme.colors.content,
    )
    Spacer(Modifier.height(Dimension.D300))
    Text(
        text = stringResource(Res.string.public_searching_joining_bots_body),
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.contentSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.ErrorContent(
    error: SearchError,
    onAction: (PublicSearchingAction) -> Unit,
) {
    // Insufficient balance is the one error a retry can't fix — the fix is to go
    // back and lower the range, so we drop the "Try again" button for it.
    val insufficient = error == SearchError.InsufficientBalance
    Spacer(Modifier.weight(1f))
    Text(
        text = if (insufficient) stringResource(Res.string.public_searching_error_insufficient)
        else stringResource(Res.string.public_searching_error_body),
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.contentSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.weight(1f))
    if (!insufficient) {
        ButtonPrimary(
            onClick = { onAction(PublicSearchingAction.Retry) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.public_searching_error_retry))
        }
        Spacer(Modifier.height(Dimension.D400))
    }
    ButtonSecondary(
        onClick = { onAction(PublicSearchingAction.Cancel) },
        style = ButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.public_searching_error_back))
    }
    Spacer(Modifier.height(Dimension.D800))
}

@Composable
private fun BuyInRangeCard(minBuyIn: Long, maxBuyIn: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color)
            .border(1.dp, AppTheme.colors.border.color, Radii.R700.shape)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipCoin(size = 18.dp)
        Spacer(Modifier.size(Dimension.D400))
        Column(modifier = Modifier.weight(1f)) {
            Eyebrow(stringResource(Res.string.public_searching_buyin_label))
            Text(
                text = "${formatThousands(minBuyIn)} – ${formatThousands(maxBuyIn)}",
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.content,
            )
        }
    }
}

private fun formatThousands(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

private const val ROTATE_INTERVAL_MS = 3_500L

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PublicSearchingScreenPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(minBuyIn = 1_000, maxBuyIn = 25_000),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PublicSearchingOfferPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(phase = SearchPhase.BotFallbackOffer, minBuyIn = 1_000, maxBuyIn = 25_000),
            onAction = {},
        )
    }
}
