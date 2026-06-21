package com.dangerfield.cards.features.lobby.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.lobby_connection_connected
import cards.libraries.resources.generated.resources.lobby_connection_connecting
import cards.libraries.resources.generated.resources.lobby_connection_disconnected
import cards.libraries.resources.generated.resources.lobby_connection_reconnecting
import cards.libraries.resources.generated.resources.lobby_error_bot_action_failed
import cards.libraries.resources.generated.resources.lobby_error_connect_rejected
import cards.libraries.resources.generated.resources.lobby_error_create_network
import cards.libraries.resources.generated.resources.lobby_error_create_not_signed_in
import cards.libraries.resources.generated.resources.lobby_error_create_unknown
import cards.libraries.resources.generated.resources.lobby_error_join_blank_code
import cards.libraries.resources.generated.resources.lobby_error_join_full
import cards.libraries.resources.generated.resources.lobby_error_join_network
import cards.libraries.resources.generated.resources.lobby_error_join_not_accepting
import cards.libraries.resources.generated.resources.lobby_error_join_not_found
import cards.libraries.resources.generated.resources.lobby_error_join_not_signed_in
import cards.libraries.resources.generated.resources.lobby_error_join_unknown
import cards.libraries.resources.generated.resources.lobby_error_leave_server_not_notified
import cards.libraries.resources.generated.resources.lobby_error_room_was_closed
import cards.libraries.resources.generated.resources.lobby_error_start_coming_soon
import cards.libraries.resources.generated.resources.lobby_in_room_buyin_label
import cards.libraries.resources.generated.resources.lobby_in_room_buyin_value
import cards.libraries.resources.generated.resources.lobby_in_room_code_label
import cards.libraries.resources.generated.resources.lobby_in_room_copy_button
import cards.libraries.resources.generated.resources.lobby_in_room_deal_button
import cards.libraries.resources.generated.resources.lobby_in_room_leave_button
import cards.libraries.resources.generated.resources.lobby_in_room_leave_button_progress
import cards.libraries.resources.generated.resources.lobby_leave_dialog_body
import cards.libraries.resources.generated.resources.lobby_leave_dialog_primary
import cards.libraries.resources.generated.resources.lobby_leave_dialog_secondary
import cards.libraries.resources.generated.resources.lobby_leave_dialog_title
import cards.libraries.resources.generated.resources.lobby_in_room_players_count
import cards.libraries.resources.generated.resources.lobby_in_room_ready_to_deal
import cards.libraries.resources.generated.resources.lobby_in_room_stakes_label
import cards.libraries.resources.generated.resources.lobby_in_room_stakes_value
import cards.libraries.resources.generated.resources.lobby_in_room_code_copied
import cards.libraries.resources.generated.resources.lobby_in_room_start_button_waiting
import cards.libraries.resources.generated.resources.lobby_in_room_waiting_for_host
import cards.libraries.resources.generated.resources.lobby_setting_up
import cards.libraries.resources.generated.resources.lobby_topbar_title_idle
import cards.libraries.resources.generated.resources.lobby_topbar_title_in_room
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.CircularProgressIndicator
import com.dangerfield.cards.libraries.ui.components.NonLazyVerticalGrid
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.room.RoomSeat
import com.dangerfield.cards.libraries.ui.components.room.RoomSeatPlayer
import com.dangerfield.cards.libraries.ui.components.room.RoomSeatStatus
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource

/**
 * Multiplayer lobby. Two-mode layout:
 *  - Loading (room == null): a spinner while the create/join call is in
 *    flight. The lobby is only entered via Create (autoCreate) or Join
 *    (prefilledCode), so there's no standalone create/join form here.
 *  - InRoom: the room-code hero (copy/share), the seat grid, editable-looking
 *    stakes, and the deal/leave controls. A connection banner appears when
 *    we're not cleanly connected.
 *
 * No gameplay UI here; once the room transitions to `Playing` the entry point
 * hands off to the table.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun LobbyScreen(
    state: LobbyState,
    onAction: (LobbyAction) -> Unit,
    onBack: () -> Unit,
) {
    // Leaving via the in-room Leave button notifies the server and drops
    // the seat cleanly. Backing out (top-bar or OS back) used to do the
    // same drop silently, so once seated we intercept back with a confirm
    // and route the confirmed exit through Leave so the server is told.
    var confirmingLeave by remember { mutableStateOf(false) }
    val requestBack: () -> Unit = {
        if (state.isInRoom) confirmingLeave = true else onBack()
    }
    BackHandler(enabled = state.isInRoom && !state.leaving) { confirmingLeave = true }

    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
        topBar = {
            TopBar(
                title = stringResource(
                    if (state.isInRoom) Res.string.lobby_topbar_title_in_room
                    else Res.string.lobby_topbar_title_idle,
                ),
                onNavigateBack = requestBack,
                backEnabled = !state.isBusy,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .screenContentPadding(paddingValues = padding, includeImePadding = true),
        ) {
            Spacer(modifier = Modifier.height(Dimension.D300))

            if (state.isInRoom) {
                InRoomContent(state = state, onAction = onAction)
            } else {
                // We only ever enter the lobby via Create (autoCreate) or Join
                // (prefilledCode), so room == null means the create/join call
                // is still in flight. Show a spinner, not the old create/join
                // form (that entry path is gone).
                LoadingContent()
            }

            state.error?.let { err ->
                Spacer(modifier = Modifier.height(Dimension.D500))
                Text(
                    text = err.message(),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.danger,
                )
            }
            Spacer(modifier = Modifier.height(Dimension.D900))
        }
    }

    if (confirmingLeave) {
        Dialog(
            title = stringResource(Res.string.lobby_leave_dialog_title),
            description = stringResource(Res.string.lobby_leave_dialog_body),
            primaryButtonText = stringResource(Res.string.lobby_leave_dialog_primary),
            secondaryButtonText = stringResource(Res.string.lobby_leave_dialog_secondary),
            onDismissRequest = { confirmingLeave = false },
            onPrimaryButtonClicked = {
                confirmingLeave = false
                onAction(LobbyAction.Leave)
                onBack()
            },
            onSecondaryButtonClicked = { confirmingLeave = false },
        )
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimension.D1900),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(Dimension.D600))
        Text(
            text = stringResource(Res.string.lobby_setting_up),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

@Composable
private fun InRoomContent(state: LobbyState, onAction: (LobbyAction) -> Unit) {
    val room = state.room ?: return
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(Res.string.lobby_in_room_code_copied)
    val copyCode = {
        clipboard.setText(AnnotatedString(room.code))
        showSnackBar(message = copiedMessage)
    }

    // Room-code hero — the share-this artefact, in the gold italic serif
    // that signals "this is the headline of the screen".
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(Res.string.lobby_in_room_code_label),
            typography = AppTheme.typography.Label.L300,
            color = AppTheme.colors.contentTertiary,
            allCaps = true,
        )
        Spacer(modifier = Modifier.height(Dimension.D200))
        Text(
            text = room.code,
            typography = AppTheme.typography.Display.D1000.Italic,
            color = AppTheme.colors.accentPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Dimension.D400))
        // Copy-only for now. A real share sheet (with a deep link into the
        // lobby) is tracked in docs/todo.md — until then a second "Share"
        // button that also just copies is misleading, so it's gone.
        ButtonSecondary(
            onClick = copyCode,
            size = ButtonSize.Small,
            style = ButtonStyle.Outlined,
        ) {
            Text(stringResource(Res.string.lobby_in_room_copy_button))
        }
    }

    Spacer(modifier = Modifier.height(Dimension.D700))

    // Surface the connection banner only when we're not cleanly connected,
    // so the happy path stays uncluttered but reconnects are visible.
    if (state.connectionStatus != ConnectionStatus.Connected) {
        ConnectionStatusRow(state.connectionStatus)
        Spacer(modifier = Modifier.height(Dimension.D500))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(Res.string.lobby_in_room_players_count, room.seatCount, room.maxSeats),
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        if (state.canStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.success.color),
                )
                Spacer(modifier = Modifier.size(Dimension.D200))
                Text(
                    text = stringResource(Res.string.lobby_in_room_ready_to_deal),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.success,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(Dimension.D400))

    // 3-column seat grid. Members slot into their seatIndex; empty seats render
    // as "Add a bot" for the host (tap to seat a backend bot) and neutral
    // "Open" tiles for everyone else. Tapping a seated bot (host only) removes it.
    val seats: List<RoomMember?> = (0 until room.maxSeats).map { index ->
        room.members.firstOrNull { it.seatIndex == index }
    }
    NonLazyVerticalGrid(
        columns = 3,
        data = seats,
        horizontalSpacing = Dimension.D500,
        verticalSpacing = Dimension.D500,
        modifier = Modifier.fillMaxWidth(),
    ) { seatIndex, member ->
        RoomSeat(
            player = member?.toSeatPlayer(state),
            addBot = member == null && state.isHost,
            onClick = when {
                member == null && state.isHost -> {
                    { onAction(LobbyAction.AddBot(seatIndex)) }
                }
                member?.isBot == true && state.isHost -> {
                    { onAction(LobbyAction.RemoveBot(member.userId)) }
                }
                else -> null
            },
        )
    }

    Spacer(modifier = Modifier.height(Dimension.D700))

    // Stakes / buy-in — display-only for now (room creation owns these).
    Row(horizontalArrangement = Arrangement.spacedBy(Dimension.D400)) {
        StakeCard(
            label = stringResource(Res.string.lobby_in_room_stakes_label),
            value = stringResource(Res.string.lobby_in_room_stakes_value),
            showCoin = true,
            modifier = Modifier.weight(1f),
        )
        StakeCard(
            label = stringResource(Res.string.lobby_in_room_buyin_label),
            value = stringResource(Res.string.lobby_in_room_buyin_value),
            showCoin = false,
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(modifier = Modifier.height(Dimension.D700))

    // Host runs the deal; non-hosts see a "waiting for the host" hint.
    if (state.isHost) {
        ButtonPrimary(
            onClick = { onAction(LobbyAction.StartGame) },
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.canStart) {
                    stringResource(Res.string.lobby_in_room_deal_button, room.seatCount)
                } else {
                    stringResource(Res.string.lobby_in_room_start_button_waiting)
                },
            )
        }
        Spacer(modifier = Modifier.height(Dimension.D400))
    } else if (state.currentUserId != null) {
        Text(
            text = stringResource(Res.string.lobby_in_room_waiting_for_host),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Dimension.D500))
    }

    com.dangerfield.cards.libraries.ui.components.button.ButtonDanger(
        onClick = { onAction(LobbyAction.Leave) },
        enabled = !state.leaving,
        style = ButtonStyle.Text,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (state.leaving) Res.string.lobby_in_room_leave_button_progress
                else Res.string.lobby_in_room_leave_button,
            ),
        )
    }
}

/** Map a live [RoomMember] into the presentational [RoomSeatPlayer]. */
private fun RoomMember.toSeatPlayer(state: LobbyState): RoomSeatPlayer {
    val isYou = userId == state.currentUserId
    // Prefer the server-snapshotted avatar (now sent for every member, incl. the
    // reserved 🤖 for a revealed bot). Fall back to our own local profile avatar
    // for our seat before the first snapshot lands; otherwise initials.
    val emoji = avatarEmoji ?: state.localAvatarEmoji.takeIf { isYou }
    val colorHex = avatarBackgroundColorHex ?: state.localAvatarColorHex.takeIf { isYou }
    return RoomSeatPlayer(
        name = displayName,
        emoji = emoji,
        avatarBackgroundColorHex = colorHex,
        isHost = userId == state.effectiveHostUserId,
        isBot = isBot,
        isYou = isYou,
        // Bots are always "connected" server-side, so they read as Seated.
        status = if (isConnected) RoomSeatStatus.Seated else RoomSeatStatus.Joining,
    )
}

@Composable
private fun StakeCard(
    label: String,
    value: String,
    showCoin: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color)
            .border(width = 1.dp, color = AppTheme.colors.border.color, shape = Radii.R700.shape)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D500),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCoin) {
            ChipCoin(size = 18.dp)
            Spacer(modifier = Modifier.size(Dimension.D400))
        }
        Column {
            Text(
                text = label,
                typography = AppTheme.typography.Label.L300,
                color = AppTheme.colors.contentTertiary,
                allCaps = true,
            )
            Text(
                text = value,
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.content,
            )
        }
    }
}

@Composable
private fun ConnectionStatusRow(status: ConnectionStatus) {
    val label = when (status) {
        ConnectionStatus.Disconnected -> stringResource(Res.string.lobby_connection_disconnected)
        ConnectionStatus.Connecting -> stringResource(Res.string.lobby_connection_connecting)
        is ConnectionStatus.Reconnecting -> stringResource(Res.string.lobby_connection_reconnecting, status.attempt)
        ConnectionStatus.Connected -> stringResource(Res.string.lobby_connection_connected)
    }
    val tone = when (status) {
        ConnectionStatus.Disconnected -> AppTheme.colors.danger
        ConnectionStatus.Connecting -> AppTheme.colors.warning
        is ConnectionStatus.Reconnecting -> AppTheme.colors.warning
        ConnectionStatus.Connected -> AppTheme.colors.success
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tone.color),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_Idle() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(state = LobbyState(), onAction = {}, onBack = {})
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(widthDp = 800, heightDp = 380)
@Composable
private fun LobbyScreenPreview_Landscape() {
    // Phone-landscape lens on the most content-rich lobby state (seated room
    // with a member list). Pins it for review before any landscape layout
    // work — the seat list + action buttons compete for a short, wide canvas.
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(
            state = LobbyState(
                currentUserId = "u1",
                room = com.dangerfield.cards.libraries.rooms.Room(
                    code = "ABC123",
                    hostUserId = "u1",
                    createdAtEpochMs = 1_700_000_000_000,
                    maxSeats = 4,
                    status = com.dangerfield.cards.libraries.rooms.RoomStatus.Lobby,
                    members = listOf(
                        RoomMember("u1", "Elijah", seatIndex = 0, joinedAtEpochMs = 0, isConnected = true),
                        RoomMember("u2", "Jane", seatIndex = 1, joinedAtEpochMs = 0, isConnected = true),
                        RoomMember("u3", "Marcus", seatIndex = 2, joinedAtEpochMs = 0, isConnected = false),
                    ),
                ),
                connectionStatus = ConnectionStatus.Connected,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_Idle_WithCode() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(state = LobbyState(codeInput = "ABC123"), onAction = {}, onBack = {})
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_InRoom_AsHost_Ready() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(
            state = LobbyState(
                currentUserId = "u1",
                room = com.dangerfield.cards.libraries.rooms.Room(
                    code = "ABC123",
                    hostUserId = "u1",
                    createdAtEpochMs = 1_700_000_000_000,
                    maxSeats = 4,
                    status = com.dangerfield.cards.libraries.rooms.RoomStatus.Lobby,
                    members = listOf(
                        RoomMember("u1", "Elijah", seatIndex = 0, joinedAtEpochMs = 0, isConnected = true),
                        RoomMember("u2", "Jane", seatIndex = 1, joinedAtEpochMs = 0, isConnected = true),
                        RoomMember("u3", "Marcus", seatIndex = 2, joinedAtEpochMs = 0, isConnected = false),
                    ),
                ),
                connectionStatus = ConnectionStatus.Connected,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_InRoom_AsHost_WaitingForPlayers() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(
            state = LobbyState(
                currentUserId = "u1",
                room = com.dangerfield.cards.libraries.rooms.Room(
                    code = "ABC123",
                    hostUserId = "u1",
                    createdAtEpochMs = 1_700_000_000_000,
                    maxSeats = 4,
                    status = com.dangerfield.cards.libraries.rooms.RoomStatus.Lobby,
                    members = listOf(
                        RoomMember("u1", "Elijah", seatIndex = 0, joinedAtEpochMs = 0, isConnected = true),
                    ),
                ),
                connectionStatus = ConnectionStatus.Connected,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_InRoom_AsGuest() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(
            state = LobbyState(
                currentUserId = "u2",
                room = com.dangerfield.cards.libraries.rooms.Room(
                    code = "ABC123",
                    hostUserId = "u1",
                    createdAtEpochMs = 1_700_000_000_000,
                    maxSeats = 4,
                    status = com.dangerfield.cards.libraries.rooms.RoomStatus.Lobby,
                    members = listOf(
                        RoomMember("u1", "Elijah", seatIndex = 0, joinedAtEpochMs = 0, isConnected = true),
                        RoomMember("u2", "Jane", seatIndex = 1, joinedAtEpochMs = 0, isConnected = true),
                    ),
                ),
                connectionStatus = ConnectionStatus.Connected,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_InRoom_Reconnecting() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(
            state = LobbyState(
                room = com.dangerfield.cards.libraries.rooms.Room(
                    code = "PREFIL",
                    hostUserId = "u1",
                    createdAtEpochMs = 1_700_000_000_000,
                    maxSeats = 6,
                    status = com.dangerfield.cards.libraries.rooms.RoomStatus.Lobby,
                    members = listOf(
                        RoomMember("u1", "Elijah", seatIndex = 0, joinedAtEpochMs = 0, isConnected = true),
                    ),
                ),
                connectionStatus = ConnectionStatus.Reconnecting(attempt = 2),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_Idle_Creating() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(
            state = LobbyState(creating = true),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_Idle_Joining() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(
            state = LobbyState(codeInput = "ABC123", joining = true),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_Idle_Error() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(
            state = LobbyState(
                codeInput = "WXYZ12",
                error = LobbyError.JoinRoomNotFound(code = "WXYZ12"),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun LobbyScreenPreview_InRoom_Leaving() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        LobbyScreen(
            state = LobbyState(
                currentUserId = "u1",
                leaving = true,
                room = com.dangerfield.cards.libraries.rooms.Room(
                    code = "ABC123",
                    hostUserId = "u1",
                    createdAtEpochMs = 1_700_000_000_000,
                    maxSeats = 4,
                    status = com.dangerfield.cards.libraries.rooms.RoomStatus.Lobby,
                    members = listOf(
                        RoomMember("u1", "Elijah", seatIndex = 0, joinedAtEpochMs = 0, isConnected = true),
                        RoomMember("u2", "Jane", seatIndex = 1, joinedAtEpochMs = 0, isConnected = true),
                    ),
                ),
                connectionStatus = ConnectionStatus.Connected,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Composable
private fun LobbyError.message(): String = when (this) {
    is LobbyError.CreateInvalidMaxSeats -> message
    LobbyError.CreateNotSignedIn -> stringResource(Res.string.lobby_error_create_not_signed_in)
    LobbyError.CreateNetworkError -> stringResource(Res.string.lobby_error_create_network)
    LobbyError.CreateUnknownError -> stringResource(Res.string.lobby_error_create_unknown)
    LobbyError.JoinBlankCode -> stringResource(Res.string.lobby_error_join_blank_code)
    is LobbyError.JoinRoomNotFound -> stringResource(Res.string.lobby_error_join_not_found, code)
    LobbyError.JoinRoomFull -> stringResource(Res.string.lobby_error_join_full)
    LobbyError.JoinRoomNotAcceptingPlayers -> stringResource(Res.string.lobby_error_join_not_accepting)
    LobbyError.JoinNotSignedIn -> stringResource(Res.string.lobby_error_join_not_signed_in)
    LobbyError.JoinNetworkError -> stringResource(Res.string.lobby_error_join_network)
    LobbyError.JoinUnknownError -> stringResource(Res.string.lobby_error_join_unknown)
    LobbyError.LeaveServerNotNotified -> stringResource(Res.string.lobby_error_leave_server_not_notified)
    LobbyError.RoomWasClosed -> stringResource(Res.string.lobby_error_room_was_closed)
    LobbyError.ConnectRejected -> stringResource(Res.string.lobby_error_connect_rejected)
    LobbyError.StartGameComingSoon -> stringResource(Res.string.lobby_error_start_coming_soon)
    LobbyError.BotActionFailed -> stringResource(Res.string.lobby_error_bot_action_failed)
}
