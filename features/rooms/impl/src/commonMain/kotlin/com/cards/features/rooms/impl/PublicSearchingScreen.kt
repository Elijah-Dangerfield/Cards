package com.dangerfield.cards.features.rooms.impl

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import cards.libraries.resources.generated.resources.public_searching_buyin_label
import cards.libraries.resources.generated.resources.public_searching_cancel
import cards.libraries.resources.generated.resources.public_searching_choose_blinds
import cards.libraries.resources.generated.resources.public_searching_choose_buyin
import cards.libraries.resources.generated.resources.public_searching_choose_humans
import cards.libraries.resources.generated.resources.public_searching_choose_humans_one
import cards.libraries.resources.generated.resources.public_searching_choose_join
import cards.libraries.resources.generated.resources.public_searching_choose_need_chips
import cards.libraries.resources.generated.resources.public_searching_choose_none
import cards.libraries.resources.generated.resources.public_searching_choose_start_fresh
import cards.libraries.resources.generated.resources.public_searching_choose_subtitle
import cards.libraries.resources.generated.resources.public_searching_choose_title
import cards.libraries.resources.generated.resources.public_searching_error_body
import cards.libraries.resources.generated.resources.public_searching_error_back
import cards.libraries.resources.generated.resources.public_searching_error_cannot_be_seated
import cards.libraries.resources.generated.resources.public_searching_error_insufficient
import cards.libraries.resources.generated.resources.public_searching_error_retry
import cards.libraries.resources.generated.resources.public_searching_joined_leave
import cards.libraries.resources.generated.resources.public_searching_joined_players_count
import cards.libraries.resources.generated.resources.public_searching_joined_ready_to_deal
import cards.libraries.resources.generated.resources.public_searching_joined_title
import cards.libraries.resources.generated.resources.public_searching_joined_waiting_for_players
import cards.libraries.resources.generated.resources.public_searching_joining_bots_body
import cards.libraries.resources.generated.resources.public_searching_joining_bots_title
import cards.libraries.resources.generated.resources.public_searching_offer_body
import cards.libraries.resources.generated.resources.public_searching_offer_keep_waiting
import cards.libraries.resources.generated.resources.public_searching_offer_play_bots
import cards.libraries.resources.generated.resources.public_searching_offer_subsidy_exhausted
import cards.libraries.resources.generated.resources.public_searching_offer_subsidy_remaining
import cards.libraries.resources.generated.resources.public_searching_offer_title
import cards.libraries.resources.generated.resources.public_searching_offer_try_later
import cards.libraries.resources.generated.resources.public_searching_rotate_1
import cards.libraries.resources.generated.resources.public_searching_rotate_2
import cards.libraries.resources.generated.resources.public_searching_rotate_3
import cards.libraries.resources.generated.resources.public_searching_rotate_4
import cards.libraries.resources.generated.resources.public_searching_rotate_5
import cards.libraries.resources.generated.resources.public_searching_rotate_6
import cards.libraries.resources.generated.resources.public_searching_title
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.isReduceMotionEnabled
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.NonLazyVerticalGrid
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.room.RoomSeat
import com.dangerfield.cards.libraries.ui.components.room.RoomSeatPlayer
import com.dangerfield.cards.libraries.ui.components.room.RoomSeatStatus
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.room.MatchmakingRadar
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

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
    // The header follows where the user actually is: browsing tables, seated in the
    // pre-deal lobby, or still hunting. An error drops back to the hunting title
    // since that's the flow it interrupts (CARDS-AX / CARDS-AV).
    val title = when {
        state.error != null -> Res.string.public_searching_title
        state.phase == SearchPhase.Choosing -> Res.string.public_searching_choose_title
        state.phase == SearchPhase.Joined -> Res.string.public_searching_joined_title
        else -> Res.string.public_searching_title
    }
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(title),
                onNavigateBack = { onAction(PublicSearchingAction.Cancel) },
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
                state.phase == SearchPhase.Choosing -> ChoosingContent(state.candidates, onAction)
                state.phase == SearchPhase.Joined -> JoinedTableContent(state, onAction)
                state.phase == SearchPhase.BotFallbackOffer -> BotFallbackContent(state.subsidyNotice, onAction)
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
    // A single line carries the wait: it opens on a steady "looking for players"
    // message, then begins rotating the reassurance copy after a beat so the wait
    // feels alive without a second static line repeating the same idea.
    RotatingReassurance()

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
private fun ColumnScope.ChoosingContent(
    candidates: List<TableCandidate>,
    onAction: (PublicSearchingAction) -> Unit,
) {
    Spacer(Modifier.height(Dimension.D500))
    Text(
        text = stringResource(Res.string.public_searching_choose_title),
        typography = AppTheme.typography.Heading.H800,
        color = AppTheme.colors.content,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Dimension.D200))
    Text(
        text = stringResource(Res.string.public_searching_choose_subtitle),
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.contentSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Dimension.D600))
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimension.D400),
    ) {
        items(items = candidates, key = { it.code }) { candidate ->
            CandidateCard(
                candidate = candidate,
                onJoin = { onAction(PublicSearchingAction.JoinCandidate(candidate.code)) },
            )
        }
    }
    Spacer(Modifier.height(Dimension.D500))
    Text(
        text = stringResource(Res.string.public_searching_choose_none),
        typography = AppTheme.typography.Body.B400,
        color = AppTheme.colors.contentTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Dimension.D400))
    ButtonSecondary(
        onClick = { onAction(PublicSearchingAction.StartFreshTable) },
        style = ButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.public_searching_choose_start_fresh))
    }
    Spacer(Modifier.height(Dimension.D800))
}

@Composable
private fun CandidateCard(candidate: TableCandidate, onJoin: () -> Unit) {
    // Blinds come from the same buy-in → stakes derivation the server uses, so the
    // card shows the exact table the user is about to sit at.
    val blinds = RoomSettings.forBuyIn(candidate.buyIn, candidate.maxSeats)
    // An unaffordable table stays listed (aspirational) but reads as out of reach:
    // dimmed content, a disabled Join, and the exact chips it takes to sit.
    val buyInColor = if (candidate.affordable) AppTheme.colors.content else AppTheme.colors.contentDisabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color)
            .border(1.dp, AppTheme.colors.border.color, Radii.R700.shape)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipCoin(size = 16.dp)
                Spacer(Modifier.size(Dimension.D200))
                Text(
                    text = stringResource(Res.string.public_searching_choose_buyin, formatThousands(candidate.buyIn)),
                    typography = AppTheme.typography.Label.L500,
                    color = buyInColor,
                )
            }
            Spacer(Modifier.height(Dimension.D100))
            Text(
                text = stringResource(
                    Res.string.public_searching_choose_blinds,
                    formatThousands(blinds.smallBlind),
                    formatThousands(blinds.bigBlind),
                ),
                typography = AppTheme.typography.Label.L300,
                color = AppTheme.colors.contentTertiary,
            )
            Spacer(Modifier.height(Dimension.D100))
            if (candidate.affordable) {
                Text(
                    text = if (candidate.humans == 1) {
                        stringResource(Res.string.public_searching_choose_humans_one)
                    } else {
                        stringResource(Res.string.public_searching_choose_humans, candidate.humans)
                    },
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.accentSecondary,
                )
            } else {
                Text(
                    text = stringResource(
                        Res.string.public_searching_choose_need_chips,
                        formatThousands(candidate.minBalanceToSit),
                    ),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.contentTertiary,
                )
            }
        }
        Spacer(Modifier.size(Dimension.D400))
        ButtonPrimary(onClick = onJoin, enabled = candidate.affordable) {
            Text(stringResource(Res.string.public_searching_choose_join))
        }
    }
}

@Composable
private fun ColumnScope.JoinedTableContent(
    state: PublicSearchingState,
    onAction: (PublicSearchingAction) -> Unit,
) {
    // ROOM-11: after the user explicitly takes a seat from the chooser, this is
    // their pre-deal lobby — visibly a *joined table* (seated players + a deal is
    // imminent), not the still-hunting radar. The deal flips us straight onto the
    // live multiplayer screen, so nothing here drives the deal; it just waits.
    val room = state.joinedRoom ?: return
    val others = state.realPlayersFound
    Spacer(Modifier.height(Dimension.D500))
    Text(
        text = stringResource(Res.string.public_searching_joined_title),
        typography = AppTheme.typography.Heading.H800,
        color = AppTheme.colors.content,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Dimension.D200))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (others > 0) AppTheme.colors.success.color else AppTheme.colors.contentTertiary.color,
                ),
        )
        Spacer(Modifier.size(Dimension.D200))
        Text(
            text = if (others > 0) {
                stringResource(Res.string.public_searching_joined_ready_to_deal)
            } else {
                stringResource(Res.string.public_searching_joined_waiting_for_players)
            },
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
    }
    Spacer(Modifier.height(Dimension.D600))
    Text(
        text = stringResource(
            Res.string.public_searching_joined_players_count,
            room.seatCount,
            room.maxSeats,
        ),
        typography = AppTheme.typography.Label.L400,
        color = AppTheme.colors.contentTertiary,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Dimension.D400))

    val seats: List<RoomMember?> = (0 until room.maxSeats).map { index ->
        room.members.firstOrNull { it.seatIndex == index }
    }
    NonLazyVerticalGrid(
        columns = 3,
        data = seats,
        horizontalSpacing = Dimension.D500,
        verticalSpacing = Dimension.D500,
        modifier = Modifier.fillMaxWidth(),
    ) { _, member ->
        RoomSeat(player = member?.toSeatPlayer(state.localUserId))
    }

    Spacer(Modifier.weight(1f))
    BuyInRangeCard(state.minBuyIn, state.maxBuyIn)
    Spacer(Modifier.height(Dimension.D500))
    ButtonSecondary(
        onClick = { onAction(PublicSearchingAction.Cancel) },
        style = ButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.public_searching_joined_leave))
    }
    Spacer(Modifier.height(Dimension.D800))
}

private fun RoomMember.toSeatPlayer(localUserId: String?): RoomSeatPlayer = RoomSeatPlayer(
    name = displayName,
    emoji = avatarEmoji,
    avatarBackgroundColorHex = avatarBackgroundColorHex,
    isBot = isBot,
    isYou = userId == localUserId,
    status = if (isConnected) RoomSeatStatus.Seated else RoomSeatStatus.Joining,
)

@Composable
private fun RotatingReassurance() {
    val messages = listOf(
        Res.string.public_searching_rotate_1,
        Res.string.public_searching_rotate_2,
        Res.string.public_searching_rotate_3,
        Res.string.public_searching_rotate_4,
        Res.string.public_searching_rotate_5,
        Res.string.public_searching_rotate_6,
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
private fun ColumnScope.BotFallbackContent(
    subsidyNotice: SubsidyNotice?,
    onAction: (PublicSearchingAction) -> Unit,
) {
    // Honest no-results empty state (ROOM-9): a calm centered message rather than
    // a promo-gradient banner. The headline owns the "we couldn't find anyone"
    // truth; the body frames playing bots as the offered next step, with the
    // subsidy disclosure kept legible underneath. Action hierarchy: keep waiting
    // (the honest default) is primary, playing bots is the secondary offer.
    Spacer(Modifier.weight(1f))
    Text(
        text = stringResource(Res.string.public_searching_offer_title),
        typography = AppTheme.typography.Heading.H800,
        color = AppTheme.colors.content,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Dimension.D300))
    Text(
        text = stringResource(Res.string.public_searching_offer_body),
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.contentSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (subsidyNotice != null) {
        Spacer(Modifier.height(Dimension.D500))
        SubsidyNoticeCard(subsidyNotice)
    }
    Spacer(Modifier.weight(1f))

    ButtonPrimary(
        onClick = { onAction(PublicSearchingAction.KeepWaiting) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.public_searching_offer_keep_waiting))
    }
    Spacer(Modifier.height(Dimension.D400))
    ButtonSecondary(
        onClick = { onAction(PublicSearchingAction.PlayBots) },
        style = ButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.public_searching_offer_play_bots))
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
    // Two errors a retry can't fix — the fix is to go back and lower the range or
    // top up — so we drop the "Try again" button for them. CannotBeSeated names the
    // exact chips needed; InsufficientBalance is the pre-search range check.
    val message = when (error) {
        SearchError.InsufficientBalance -> stringResource(Res.string.public_searching_error_insufficient)
        is SearchError.CannotBeSeated ->
            stringResource(Res.string.public_searching_error_cannot_be_seated, formatThousands(error.neededChips))
        else -> stringResource(Res.string.public_searching_error_body)
    }
    val retryable = error != SearchError.InsufficientBalance && error !is SearchError.CannotBeSeated
    Spacer(Modifier.weight(1f))
    Text(
        text = message,
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.contentSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.weight(1f))
    if (retryable) {
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
private fun SubsidyNoticeCard(notice: SubsidyNotice) {
    Text(
        text = if (notice.remaining <= 0) {
            stringResource(Res.string.public_searching_offer_subsidy_exhausted)
        } else {
            stringResource(Res.string.public_searching_offer_subsidy_remaining, formatThousands(notice.remaining))
        },
        typography = AppTheme.typography.Body.B400,
        color = AppTheme.colors.contentSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color)
            .border(1.dp, AppTheme.colors.border.color, Radii.R700.shape)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D400),
    )
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

private const val ROTATE_INTERVAL_MS = 8_000L

@Preview
@Composable
private fun PublicSearchingScreenPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(minBuyIn = 1_000, maxBuyIn = 25_000),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PublicSearchingChooserPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(
                phase = SearchPhase.Choosing,
                minBuyIn = 1_000,
                maxBuyIn = 25_000,
                candidates = listOf(
                    TableCandidate(code = "ABC123", buyIn = 1_000, maxSeats = 6, humans = 3),
                    TableCandidate(code = "DEF456", buyIn = 5_000, maxSeats = 6, humans = 2),
                    TableCandidate(
                        code = "GHI789",
                        buyIn = 25_000,
                        maxSeats = 6,
                        humans = 1,
                        affordable = false,
                        minBalanceToSit = 100_000,
                    ),
                ),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PublicSearchingJoinedWaitingPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(
                phase = SearchPhase.Joined,
                minBuyIn = 1_000,
                maxBuyIn = 25_000,
                localUserId = "me",
                realPlayersFound = 0,
                joinedRoom = previewRoom(
                    members = listOf(previewMember("me", "You", "🦊", seatIndex = 0)),
                ),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PublicSearchingJoinedReadyPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(
                phase = SearchPhase.Joined,
                minBuyIn = 1_000,
                maxBuyIn = 25_000,
                localUserId = "me",
                realPlayersFound = 2,
                joinedRoom = previewRoom(
                    members = listOf(
                        previewMember("me", "You", "🦊", seatIndex = 0),
                        previewMember("p1", "Jordan", "🐱", seatIndex = 1),
                        previewMember("p2", "Priya", "🐼", seatIndex = 2),
                    ),
                ),
            ),
            onAction = {},
        )
    }
}

private fun previewMember(
    userId: String,
    name: String,
    emoji: String,
    seatIndex: Int,
): RoomMember = RoomMember(
    userId = userId,
    displayName = name,
    seatIndex = seatIndex,
    joinedAtEpochMs = 0,
    isConnected = true,
    avatarEmoji = emoji,
    avatarBackgroundColorHex = "#5894E4",
)

private fun previewRoom(members: List<RoomMember>): Room = Room(
    code = "ABC123",
    hostUserId = "host",
    createdAtEpochMs = 0,
    maxSeats = 6,
    status = RoomStatus.Lobby,
    members = members,
    buyIn = 5_000,
    smallBlind = 25,
    bigBlind = 50,
)

@Preview
@Composable
private fun PublicSearchingOfferPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(phase = SearchPhase.BotFallbackOffer, minBuyIn = 1_000, maxBuyIn = 25_000),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PublicSearchingOfferNearCapPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(
                phase = SearchPhase.BotFallbackOffer,
                minBuyIn = 1_000,
                maxBuyIn = 25_000,
                subsidyNotice = SubsidyNotice(remaining = 1_500, cap = 10_000),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PublicSearchingOfferExhaustedPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(
                phase = SearchPhase.BotFallbackOffer,
                minBuyIn = 1_000,
                maxBuyIn = 25_000,
                subsidyNotice = SubsidyNotice(remaining = 0, cap = 10_000),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PublicSearchingJoiningBotsPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(phase = SearchPhase.JoiningBots, minBuyIn = 1_000, maxBuyIn = 25_000),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PublicSearchingErrorPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(minBuyIn = 1_000, maxBuyIn = 25_000, error = SearchError.Network),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PublicSearchingInsufficientErrorPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(
                minBuyIn = 1_000,
                maxBuyIn = 25_000,
                error = SearchError.InsufficientBalance,
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PublicSearchingCannotBeSeatedPreview() {
    PreviewContent {
        PublicSearchingScreen(
            state = PublicSearchingState(
                minBuyIn = 1_000,
                maxBuyIn = 25_000,
                error = SearchError.CannotBeSeated(neededChips = 4_000),
            ),
            onAction = {},
        )
    }
}
