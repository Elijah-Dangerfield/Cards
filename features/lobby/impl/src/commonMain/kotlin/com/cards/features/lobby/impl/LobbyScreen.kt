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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension

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
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimension.D800),
            ) {
                Spacer(modifier = Modifier.height(Dimension.D200))
                IconButton(
                    icon = Icons.ArrowBack("Back"),
                    onClick = onBack,
                    enabled = !state.isBusy,
                    iconColor = AppTheme.colors.onSurfacePrimary,
                )
                Spacer(modifier = Modifier.height(Dimension.D700))

                Text(
                    text = if (state.isInRoom) "Lobby" else "Play with friends",
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.onSurfacePrimary,
                )
                Spacer(modifier = Modifier.height(Dimension.D300))

                if (!state.isInRoom) {
                    IdleContent(state = state, onAction = onAction)
                } else {
                    InRoomContent(state = state, onAction = onAction)
                }

                state.error?.let { err ->
                    Spacer(modifier = Modifier.height(Dimension.D500))
                    Text(
                        text = err,
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.danger,
                    )
                }
                Spacer(modifier = Modifier.height(Dimension.D900))
            }
        }
    }
}

@Composable
private fun IdleContent(state: LobbyState, onAction: (LobbyAction) -> Unit) {
    Text(
        text = "Start a new room and share the code — or type a friend's code to join theirs.",
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.onSurfaceSecondary,
    )

    Spacer(modifier = Modifier.height(Dimension.D800))

    Button(
        onClick = { onAction(LobbyAction.CreateRoom) },
        enabled = state.canCreate,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.creating) "Creating…" else "Create a room")
    }

    Spacer(modifier = Modifier.height(Dimension.D700))

    Text(
        text = "Or join with a code",
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
        label = { Text("Room code") },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(Dimension.D500))

    Button(
        onClick = { onAction(LobbyAction.SubmitJoin) },
        enabled = state.canSubmitJoin,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.joining) "Joining…" else "Join room")
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
            .clip(RoundedCornerShape(20.dp))
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(vertical = Dimension.D900),
    ) {
        Text(
            text = "Room code",
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
            text = "Share with your friends to invite",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
        )
    }

    Spacer(modifier = Modifier.height(Dimension.D500))

    ConnectionStatusRow(state.connectionStatus)

    Spacer(modifier = Modifier.height(Dimension.D700))

    Text(
        text = "Players (${room.seatCount}/${room.maxSeats})",
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

    com.dangerfield.cards.libraries.ui.components.button.ButtonDanger(
        onClick = { onAction(LobbyAction.Leave) },
        enabled = !state.leaving,
        style = ButtonStyle.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.leaving) "Leaving…" else "Leave room")
    }
}

@Composable
private fun ConnectionStatusRow(status: ConnectionStatus) {
    val (label, tone) = when (status) {
        ConnectionStatus.Disconnected -> "Disconnected" to AppTheme.colors.status.bad
        ConnectionStatus.Connecting -> "Connecting…" to AppTheme.colors.status.warning
        is ConnectionStatus.Reconnecting -> "Reconnecting (attempt ${status.attempt})…" to AppTheme.colors.status.warning
        ConnectionStatus.Connected -> "Connected" to AppTheme.colors.status.okay
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

@Composable
private fun MemberRow(member: RoomMember) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
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
                text = "Seat ${member.seatIndex + 1}",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
    }
}
