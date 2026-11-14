package com.dangerfield.cards.features.lobby.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.lobby_in_room_code_copied
import cards.libraries.resources.generated.resources.standing_table_create_title
import cards.libraries.resources.generated.resources.standing_table_delete_body
import cards.libraries.resources.generated.resources.standing_table_delete_cancel
import cards.libraries.resources.generated.resources.standing_table_delete_title
import cards.libraries.resources.generated.resources.standing_table_detail_calendar
import cards.libraries.resources.generated.resources.standing_table_detail_created_note
import cards.libraries.resources.generated.resources.standing_table_detail_created_title
import cards.libraries.resources.generated.resources.standing_table_detail_delete
import cards.libraries.resources.generated.resources.standing_table_detail_edit
import cards.libraries.resources.generated.resources.standing_table_detail_invite
import cards.libraries.resources.generated.resources.standing_table_detail_live
import cards.libraries.resources.generated.resources.standing_table_detail_loading
import cards.libraries.resources.generated.resources.standing_table_detail_next
import cards.libraries.resources.generated.resources.standing_table_detail_open
import cards.libraries.resources.generated.resources.standing_table_detail_remove
import cards.libraries.resources.generated.resources.standing_table_error_network
import cards.libraries.resources.generated.resources.standing_table_error_retry
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.StandingTable
import com.dangerfield.cards.libraries.rooms.StandingTableLive
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonDanger
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.room.RoomCode
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.room.standingScheduleLabel
import com.dangerfield.cards.libraries.ui.components.room.standingTableNextLabel
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun StandingTableScreen(
    state: StandingTableState,
    onAction: (StandingTableAction) -> Unit,
    onBack: () -> Unit,
    now: Instant = Clock.System.now(),
) {
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.standing_table_create_title),
                onNavigateBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .screenContentPadding(paddingValues = padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimension.D400),
        ) {
            val table = state.table
            if (state.loading) {
                Text(stringResource(Res.string.standing_table_detail_loading))
            } else if (table != null) {
                if (state.justCreated) {
                    Text(
                        text = stringResource(Res.string.standing_table_detail_created_title),
                        typography = AppTheme.typography.Label.L500,
                        color = AppTheme.colors.accentPrimary,
                    )
                }
                Text(table.name, typography = AppTheme.typography.Display.D1000, textAlign = TextAlign.Center)
                Text(
                    text = standingScheduleLabel(table.weekday, table.localTime),
                    typography = AppTheme.typography.Heading.H700,
                )
                Text(
                    text = stringResource(
                        Res.string.standing_table_detail_next,
                        standingTableNextLabel(table.nextStartsAt, table.timeZone, now),
                    ),
                    color = AppTheme.colors.contentSecondary,
                )
                table.live?.let { live ->
                    Text(
                        text = stringResource(Res.string.standing_table_detail_live, live.seatedHumans),
                        color = AppTheme.colors.accentPrimary,
                    )
                }
                if (state.justCreated) {
                    Text(
                        text = stringResource(Res.string.standing_table_detail_created_note),
                        textAlign = TextAlign.Center,
                        color = AppTheme.colors.contentSecondary,
                    )
                }
                val clipboard = LocalClipboardManager.current
                val copied = stringResource(Res.string.lobby_in_room_code_copied)
                RoomCode(
                    code = table.code,
                    onCopy = {
                        clipboard.setText(AnnotatedString(table.code))
                        showSnackBar(copied)
                    },
                )
                Spacer(Modifier.height(Dimension.D300))
                val primaryAction = if (state.justCreated) StandingTableAction.Share else StandingTableAction.Open
                val secondaryAction = if (state.justCreated) StandingTableAction.Open else StandingTableAction.Share
                ButtonPrimary(
                    onClick = { onAction(primaryAction) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                ) {
                    Text(
                        stringResource(
                            if (state.justCreated) Res.string.standing_table_detail_invite
                            else Res.string.standing_table_detail_open,
                        ),
                    )
                }
                ButtonSecondary(
                    onClick = { onAction(secondaryAction) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                ) {
                    Text(
                        stringResource(
                            if (state.justCreated) Res.string.standing_table_detail_open
                            else Res.string.standing_table_detail_invite,
                        ),
                    )
                }
                ButtonSecondary(
                    onClick = { onAction(StandingTableAction.AddCalendar) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(Res.string.standing_table_detail_calendar))
                }
                if (table.isOwner) {
                    ButtonGhost(onClick = { onAction(StandingTableAction.Edit) }, enabled = !state.busy) {
                        Text(stringResource(Res.string.standing_table_detail_edit))
                    }
                    ButtonDanger(
                        onClick = { onAction(StandingTableAction.RequestDelete) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    ) {
                        Text(stringResource(Res.string.standing_table_detail_delete))
                    }
                } else {
                    ButtonGhost(onClick = { onAction(StandingTableAction.Remove) }, enabled = !state.busy) {
                        Text(stringResource(Res.string.standing_table_detail_remove))
                    }
                }
            }
            if (state.error) {
                Text(stringResource(Res.string.standing_table_error_network), color = AppTheme.colors.danger)
                ButtonSecondary(onClick = { onAction(StandingTableAction.Refresh) }) {
                    Text(stringResource(Res.string.standing_table_error_retry))
                }
            }
        }
    }
    if (state.confirmingDelete) StandingTableDeleteDialog(state.busy, state.error, onAction)
}

@Composable
internal fun StandingTableDeleteDialog(
    busy: Boolean,
    error: Boolean,
    onAction: (StandingTableAction) -> Unit,
) {
    Dialog(onDismissRequest = { if (!busy) onAction(StandingTableAction.CancelDelete) }) {
        Column(
            modifier = Modifier.padding(Dimension.D800),
            verticalArrangement = Arrangement.spacedBy(Dimension.D500),
        ) {
            Text(
                stringResource(Res.string.standing_table_delete_title),
                typography = AppTheme.typography.Heading.H800,
            )
            Text(
                stringResource(Res.string.standing_table_delete_body),
                typography = AppTheme.typography.Body.B500,
            )
            if (error) Text(stringResource(Res.string.standing_table_error_network), color = AppTheme.colors.danger)
            ButtonDanger(
                onClick = { onAction(StandingTableAction.ConfirmDelete) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.standing_table_detail_delete))
            }
            ButtonGhost(
                onClick = { onAction(StandingTableAction.CancelDelete) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.standing_table_delete_cancel))
            }
        }
    }
}

private fun previewStandingTable() = StandingTable(
    code = "K7M2QX",
    name = "Thursday Night Poker",
    ownerUserId = "owner",
    ownerDisplayName = "Alex",
    isOwner = true,
    buyIn = 5000,
    maxSeats = 6,
    weekday = DayOfWeek.THURSDAY,
    localTime = LocalTime(20, 0),
    timeZone = TimeZone.of("America/New_York"),
    durationMinutes = 120,
    nextStartsAt = Instant.parse("2026-11-20T01:00:00Z"),
    regularCount = 5,
)

private val PreviewNow = Instant.parse("2026-11-19T20:00:00Z")

@Preview
@Composable
private fun StandingTableScreenPreview_Owner() {
    PreviewContent {
        StandingTableScreen(StandingTableState(table = previewStandingTable(), loading = false), {}, {}, PreviewNow)
    }
}

@Preview
@Composable
private fun StandingTableScreenPreview_NonOwner() {
    PreviewContent {
        StandingTableScreen(StandingTableState(table = previewStandingTable().copy(isOwner = false), loading = false), {}, {}, PreviewNow)
    }
}

@Preview
@Composable
private fun StandingTableScreenPreview_Live() {
    PreviewContent {
        StandingTableScreen(
            state = StandingTableState(
                table = previewStandingTable().copy(live = StandingTableLive(3, RoomStatus.Playing)),
                loading = false,
            ),
            onAction = {},
            onBack = {},
            now = PreviewNow,
        )
    }
}

@Preview
@Composable
private fun StandingTableScreenPreview_JustCreated() {
    PreviewContent {
        StandingTableScreen(StandingTableState(table = previewStandingTable(), loading = false, justCreated = true), {}, {}, PreviewNow)
    }
}

@Preview
@Composable
private fun StandingTableScreenPreview_DeleteConfirmation() {
    PreviewContent {
        StandingTableScreen(
            StandingTableState(table = previewStandingTable(), loading = false, confirmingDelete = true),
            {}, {}, PreviewNow,
        )
    }
}

@Preview
@Composable
private fun StandingTableScreenPreview_Loading() {
    PreviewContent { StandingTableScreen(StandingTableState(), {}, {}, PreviewNow) }
}

@Preview
@Composable
private fun StandingTableScreenPreview_Error() {
    PreviewContent { StandingTableScreen(StandingTableState(loading = false, error = true), {}, {}, PreviewNow) }
}
