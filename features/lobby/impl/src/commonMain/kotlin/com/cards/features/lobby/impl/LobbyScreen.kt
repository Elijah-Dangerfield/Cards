package com.dangerfield.cards.features.lobby.impl

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.lobby_connection_connected
import cards.libraries.resources.generated.resources.lobby_connection_connecting
import cards.libraries.resources.generated.resources.lobby_connection_disconnected
import cards.libraries.resources.generated.resources.lobby_connection_reconnecting
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
import cards.libraries.resources.generated.resources.lobby_idle_code_field_label
import cards.libraries.resources.generated.resources.lobby_idle_create_button
import cards.libraries.resources.generated.resources.lobby_idle_create_button_progress
import cards.libraries.resources.generated.resources.lobby_idle_join_button
import cards.libraries.resources.generated.resources.lobby_idle_join_button_progress
import cards.libraries.resources.generated.resources.lobby_idle_or_join_heading
import cards.libraries.resources.generated.resources.lobby_idle_subtitle
import cards.libraries.resources.generated.resources.lobby_in_room_code_label
import cards.libraries.resources.generated.resources.lobby_in_room_code_share_hint
import cards.libraries.resources.generated.resources.lobby_in_room_leave_button
import cards.libraries.resources.generated.resources.lobby_in_room_leave_button_progress
import cards.libraries.resources.generated.resources.lobby_in_room_member_seat_label
import cards.libraries.resources.generated.resources.lobby_in_room_players_count
import cards.libraries.resources.generated.resources.lobby_in_room_start_button
import cards.libraries.resources.generated.resources.lobby_in_room_start_button_waiting
import cards.libraries.resources.generated.resources.lobby_in_room_waiting_for_host
import cards.libraries.resources.generated.resources.lobby_topbar_title_idle
import cards.libraries.resources.generated.resources.lobby_topbar_title_in_room
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource

/**
 * Multiplayer lobby. Two-mode layout:
 *  - Idle (room == null): show the create-room CTA + a join-by-code form.
 *  - InRoom: show the code (so it can be shared), live member list, and
 *    a leave button. Connection status banner sits above the list when
 *    we're mid-reconnect.
 *
 * No gameplay UI here — once the room transitions to `Playing`, this
 * screen hands off to the table (Phase 4.2). For now `Playing` is
 * surfaced as a "Game in progress" badge until the handoff exists.
 */
@Composable
fun LobbyScreen(
    state: LobbyState,
    onAction: (LobbyAction) -> Unit,
    onBack: () -> Unit,
) {
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
        topBar = {
            TopBar(
                title = stringResource(
                    if (state.isInRoom) Res.string.lobby_topbar_title_in_room
                    else Res.string.lobby_topbar_title_idle,
                ),
                onNavigateBack = onBack,
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

            if (!state.isInRoom) {
                IdleContent(state = state, onAction = onAction)
            } else {
                InRoomContent(state = state, onAction = onAction)
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
}

@Composable
private fun IdleContent(state: LobbyState, onAction: (LobbyAction) -> Unit) {
    Text(
        text = stringResource(Res.string.lobby_idle_subtitle),
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.onSurfaceSecondary,
    )

    Spacer(modifier = Modifier.height(Dimension.D800))

    Button(
        onClick = { onAction(LobbyAction.CreateRoom) },
        enabled = state.canCreate,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (state.creating) Res.string.lobby_idle_create_button_progress
                else Res.string.lobby_idle_create_button,
            ),
        )
    }

    Spacer(modifier = Modifier.height(Dimension.D700))

    Text(
        text = stringResource(Res.string.lobby_idle_or_join_heading),
        typography = AppTheme.typography.Heading.H500,
        color = AppTheme.colors.onSurfacePrimary,
    )
    Spacer(modifier = Modifier.height(Dimension.D300))

    OutlinedTextField(
        value = state.codeInput,
        onValueChange = { onAction(LobbyAction.CodeChanged(it)) },
        enabled = !state.isBusy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Go,
            capitalization = KeyboardCapitalization.Characters,
        ),
        keyboardActions = KeyboardActions(onGo = { onAction(LobbyAction.SubmitJoin) }),
        label = { Text(stringResource(Res.string.lobby_idle_code_field_label)) },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(Dimension.D500))

    Button(
        onClick = { onAction(LobbyAction.SubmitJoin) },
        enabled = state.canSubmitJoin,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (state.joining) Res.string.lobby_idle_join_button_progress
                else Res.string.lobby_idle_join_button,
            ),
        )
    }
}

@Composable
private fun InRoomContent(state: LobbyState, onAction: (LobbyAction) -> Unit) {
    val room = state.room ?: return

    // Code is the share-this-with-your-friends artefact. Big, centered,
    // its own surface — it's the one thing the user is here to read
    // aloud or type into another phone. A future enhancement adds a
    // share-sheet + clipboard-copy affordance on tap.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R800.shape)
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(vertical = Dimension.D900),
    ) {
        Text(
            text = stringResource(Res.string.lobby_in_room_code_label),
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.onSurfaceSecondary,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = room.code,
            typography = AppTheme.typography.Display.D1000,
            color = AppTheme.colors.onSurfacePrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Dimension.D400))
        Text(
            text = stringResource(Res.string.lobby_in_room_code_share_hint),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
        )
    }

    Spacer(modifier = Modifier.height(Dimension.D500))

    ConnectionStatusRow(state.connectionStatus)

    Spacer(modifier = Modifier.height(Dimension.D700))

    Text(
        text = stringResource(Res.string.lobby_in_room_players_count, room.seatCount, room.maxSeats),
        typography = AppTheme.typography.Heading.H500,
        color = AppTheme.colors.onSurfacePrimary,
    )
    Spacer(modifier = Modifier.height(Dimension.D300))

    // The member list uses LazyColumn even though seat counts are tiny
    // (≤9) so the recomposition cost is bounded as the room mutates
    // (joins, presence flips). Plays well with the outer scrollable
    // column because we cap the height.
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Dimension.D300),
        modifier = Modifier
            .fillMaxWidth()
            .height((64 * room.maxSeats).dp.coerceAtLeast(64.dp)),
    ) {
        items(room.members, key = { it.userId }) { member ->
            MemberRow(member)
        }
    }

    Spacer(modifier = Modifier.height(Dimension.D700))

    // Host gets the primary CTA — disabled until at least one other
    // player joins, with copy that tells them what they're waiting on.
    // Non-hosts see a "Waiting for the host to start" hint instead so
    // the room doesn't feel like everyone has the same role.
    if (state.isHost) {
        Button(
            onClick = { onAction(LobbyAction.StartGame) },
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (state.canStart) Res.string.lobby_in_room_start_button
                    else Res.string.lobby_in_room_start_button_waiting,
                ),
            )
        }
        Spacer(modifier = Modifier.height(Dimension.D400))
    } else if (state.currentUserId != null) {
        // Hide the hint for the brief pre-identity window so it doesn't
        // flash to non-host viewers who are actually the host.
        Text(
            text = stringResource(Res.string.lobby_in_room_waiting_for_host),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
        )
        Spacer(modifier = Modifier.height(Dimension.D500))
    }

    com.dangerfield.cards.libraries.ui.components.button.ButtonDanger(
        onClick = { onAction(LobbyAction.Leave) },
        enabled = !state.leaving,
        style = ButtonStyle.Outlined,
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

@Composable
private fun ConnectionStatusRow(status: ConnectionStatus) {
    val label = when (status) {
        ConnectionStatus.Disconnected -> stringResource(Res.string.lobby_connection_disconnected)
        ConnectionStatus.Connecting -> stringResource(Res.string.lobby_connection_connecting)
        is ConnectionStatus.Reconnecting -> stringResource(Res.string.lobby_connection_reconnecting, status.attempt)
        ConnectionStatus.Connected -> stringResource(Res.string.lobby_connection_connected)
    }
    val tone = when (status) {
        ConnectionStatus.Disconnected -> AppTheme.colors.status.bad
        ConnectionStatus.Connecting -> AppTheme.colors.status.warning
        is ConnectionStatus.Reconnecting -> AppTheme.colors.status.warning
        ConnectionStatus.Connected -> AppTheme.colors.status.okay
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
            color = AppTheme.colors.onSurfaceSecondary,
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
private fun MemberRow(member: RoomMember) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R500.shape)
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D400),
    ) {
        // Tiny presence dot — green when their socket's live, gray when
        // we're holding their seat through a reconnect.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    (if (member.isConnected) AppTheme.colors.status.okay
                    else AppTheme.colors.onSurfaceSecondary).color,
                ),
        )
        Spacer(modifier = Modifier.size(Dimension.D400))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = member.displayName,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Text(
                text = stringResource(Res.string.lobby_in_room_member_seat_label, member.seatIndex + 1),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
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
}
