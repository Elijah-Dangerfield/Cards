package com.dangerfield.cards.features.lobby.impl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.standing_table_calendar_notes
import cards.libraries.resources.generated.resources.standing_table_calendar_title
import cards.libraries.resources.generated.resources.ui_schedule_am
import cards.libraries.resources.generated.resources.ui_schedule_pm
import com.dangerfield.cards.features.lobby.LobbyRoute
import com.dangerfield.cards.features.lobby.RoomInvite
import com.dangerfield.cards.features.lobby.StandingTableCreateRoute
import com.dangerfield.cards.features.lobby.StandingTableEditRoute
import com.dangerfield.cards.features.lobby.StandingTableRoute
import com.dangerfield.cards.features.lobby.nextValidCalendarStart
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.navigation.CalendarResult
import com.dangerfield.cards.libraries.navigation.RecurringCalendarEvent
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.rooms.StandingTable
import com.dangerfield.cards.libraries.ui.components.room.formatStandingTime
import com.dangerfield.cards.libraries.ui.components.room.weekdayNameResource
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.getString

internal fun NavGraphBuilder.standingTableScreens(
    router: Router,
    enabled: () -> Boolean,
    baseUrl: () -> String,
    createFactory: (String?) -> StandingTableCreateViewModel,
    detailFactory: (String, Boolean) -> StandingTableViewModel,
    chips: ChipsRepository,
    cosmetics: () -> Flow<OwnedTableCosmetics>,
) {
    var newlyCreatedCode: String? = null
    screen<StandingTableCreateRoute> {
        if (!enabled()) {
            LaunchedEffect(Unit) { router.goBack() }
            return@screen
        }
        val vm = viewModel { createFactory(null) }
        val state by vm.stateFlow.collectAsStateWithLifecycle()
        val balance by chips.observeBalance().collectAsStateWithLifecycle(initialValue = null)
        val choices by cosmetics().collectAsStateWithLifecycle(initialValue = OwnedTableCosmetics())
        LaunchedEffect(choices.equippedFeltProductId, choices.equippedCardBackProductId) {
            choices.equippedFeltProductId?.let { vm.takeAction(StandingTableCreateAction.FeltChanged(it)) }
            choices.equippedCardBackProductId?.let { vm.takeAction(StandingTableCreateAction.CardBackChanged(it)) }
        }
        LaunchedEffect(vm) {
            vm.eventFlow.collect { event ->
                if (event is StandingTableCreateEvent.Created) {
                    newlyCreatedCode = event.code
                    router.batch {
                        popBackTo(StandingTableCreateRoute::class, inclusive = true)
                        navigate(StandingTableRoute(event.code))
                    }
                }
            }
        }
        StandingTableCreateScreen(state, vm::takeAction, { router.goBack() }, balance, choices.felts, choices.cardBacks)
    }
    screen<StandingTableEditRoute> { entry ->
        if (!enabled()) {
            LaunchedEffect(Unit) { router.goBack() }
            return@screen
        }
        val code = entry.toRoute<StandingTableEditRoute>().code
        val vm = viewModel { createFactory(code) }
        val state by vm.stateFlow.collectAsStateWithLifecycle()
        LaunchedEffect(vm) {
            vm.eventFlow.collect { event ->
                if (event == StandingTableCreateEvent.Saved) router.goBack()
            }
        }
        StandingTableCreateScreen(state, vm::takeAction, { router.goBack() })
    }
    screen<StandingTableRoute> { entry ->
        if (!enabled()) {
            LaunchedEffect(Unit) { router.goBack() }
            return@screen
        }
        val code = entry.toRoute<StandingTableRoute>().code
        val vm = viewModel {
            val justCreated = newlyCreatedCode == code
            if (justCreated) newlyCreatedCode = null
            detailFactory(code, justCreated)
        }
        val state by vm.stateFlow.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.takeAction(StandingTableAction.Refresh) }
        LaunchedEffect(vm) {
            vm.eventFlow.collect { event ->
                when (event) {
                    is StandingTableEvent.Open -> router.navigate(LobbyRoute(prefilledCode = event.code))
                    is StandingTableEvent.Edit -> router.navigate(StandingTableEditRoute(event.code))
                    is StandingTableEvent.Share -> shareStandingTable(router, event.table, baseUrl(), "detail")
                    is StandingTableEvent.AddCalendar -> addStandingTableCalendar(router, event.table, baseUrl(), "detail")
                    StandingTableEvent.Removed -> router.goBack()
                }
            }
        }
        StandingTableScreen(state, vm::takeAction, { router.goBack() })
    }
}

internal suspend fun shareStandingTable(router: Router, table: StandingTable, baseUrl: String, surface: String) {
    val weekday = getString(weekdayNameResource(table.weekday))
    val time = formatStandingTime(
        table.localTime,
        getString(Res.string.ui_schedule_am),
        getString(Res.string.ui_schedule_pm),
    )
    router.shareText(RoomInvite.standingShareText(table.name, weekday, time, table.code, baseUrl))
    KLog.logEvent("standing_table.invite_shared", "surface" to surface)
}

internal suspend fun addStandingTableCalendar(router: Router, table: StandingTable, baseUrl: String, surface: String) {
    val url = RoomInvite.shareUrlForCode(table.code, baseUrl)
    val result = router.addToCalendar(
        RecurringCalendarEvent(
            title = getString(Res.string.standing_table_calendar_title, table.name),
            notes = getString(Res.string.standing_table_calendar_notes, url, table.code),
            url = url,
            firstStart = nextValidCalendarStart(table.nextStartsAt, table.localTime, table.weekday, table.timeZone),
            durationMinutes = table.durationMinutes,
            timeZone = table.timeZone,
            weekday = table.weekday,
        ),
    )
    if (result == CalendarResult.Unavailable) router.openWebLink("$url/calendar.ics")
    val label = when (result) {
        CalendarResult.Added -> if (BuildInfo.platform == Platform.Android) "opened" else "added"
        CalendarResult.Cancelled -> "cancelled"
        CalendarResult.Unavailable -> "unavailable"
    }
    KLog.logEvent("standing_table.calendar_added", "surface" to surface, "result" to label)
}
