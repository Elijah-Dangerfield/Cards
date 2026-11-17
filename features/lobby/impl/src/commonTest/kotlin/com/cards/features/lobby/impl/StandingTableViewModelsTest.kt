package com.dangerfield.cards.features.lobby.impl

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import com.dangerfield.cards.libraries.core.fixed
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.rooms.CreateStandingTableOutcome
import com.dangerfield.cards.libraries.rooms.StandingTable
import com.dangerfield.cards.libraries.rooms.StandingTablesEnabled
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.StandingTableSummary
import kotlin.time.Clock
import kotlin.time.Instant

class StandingTableViewModelsTest : CoroutineTest() {
    @Test
    fun creationHandlesEveryOutcomeAndDoesNotResubmitWhileBusy() = runUnitTest {
        val repo = FakeStandingTableRepository()
        val vm = createVm(repo)
        vm.takeAction(StandingTableCreateAction.NameChanged("Friday Friends"))
        repo.createOutcome = CreateStandingTableOutcome.TooMany
        vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
        assertEquals(StandingTableFormError.TooMany, vm.state.error)
        repo.createOutcome = CreateStandingTableOutcome.Invalid("localTime")
        vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
        assertEquals(StandingTableFormError.Invalid("localTime"), vm.state.error)
        repo.createOutcome = CreateStandingTableOutcome.NetworkError
        vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
        assertEquals(StandingTableFormError.Network, vm.state.error)
        repo.createOutcome = CreateStandingTableOutcome.Created(table())
        vm.eventFlow.test {
            vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
            assertEquals(StandingTableCreateEvent.Created("K7M2QX"), awaitItem())
        }
        assertEquals("Friday Friends", repo.lastDraft?.name)
        assertFalse(vm.state.isSubmitting)
    }

    @Test
    fun blankNameUsesPlaceholderAndFlagOffNeverCreates() = runUnitTest {
        val repo = FakeStandingTableRepository()
        val vm = createVm(repo)
        vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
        assertTrue(repo.lastDraft?.name?.endsWith("Night Poker") == true)
        val disabledRepo = FakeStandingTableRepository()
        createVm(disabledRepo, false).takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
        assertEquals(null, disabledRepo.lastDraft)
    }

    @Test
    fun repeatedSubmitWhileRequestPendingCreatesOnlyOneTable() = runUnitTest {
        val repo = FakeStandingTableRepository()
        val gate = CompletableDeferred<Unit>()
        repo.createGate = gate
        repo.createOutcome = CreateStandingTableOutcome.Created(table())
        val vm = createVm(repo)
        vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
        vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
        assertTrue(vm.state.isSubmitting)
        assertEquals(1, repo.createCalls)
        vm.eventFlow.test {
            gate.complete(Unit)
            assertEquals(StandingTableCreateEvent.Created("K7M2QX"), awaitItem())
            vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
            expectNoEvents()
        }
        assertEquals(1, repo.createCalls)
    }

    @Test
    fun failedEditAndRemoveKeepScreenOpenForRetry() = runUnitTest {
        val repo = FakeStandingTableRepository(listOf(table()))
        repo.updateOutcome = Result.failure(IllegalStateException("offline"))
        val editor = StandingTableCreateViewModel("K7M2QX", repo, clock, StandingTablesEnabled.forTest(true))
        editor.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
        assertEquals(StandingTableFormError.Network, editor.state.error)
        assertFalse(editor.state.isSubmitting)
        val regularRepo = FakeStandingTableRepository(listOf(table().copy(isOwner = false)))
        regularRepo.leaveOutcome = Result.failure(IllegalStateException("offline"))
        val regular = detailVm(regularRepo)
        regular.eventFlow.test {
            regular.takeAction(StandingTableAction.Remove)
            expectNoEvents()
        }
        assertTrue(regular.state.error)
        assertFalse(regular.state.busy)
    }

    @Test
    fun editingLoadsExistingSettingsAndSavesWithoutCreating() = runUnitTest {
        val repo = FakeStandingTableRepository(listOf(table()))
        repo.updateOutcome = Result.success(table().copy(name = "New name"))
        val vm = StandingTableCreateViewModel("K7M2QX", repo, clock, StandingTablesEnabled.forTest(true))
        assertEquals("Thursday Night Poker", vm.state.name)
        vm.takeAction(StandingTableCreateAction.NameChanged("New name"))
        vm.eventFlow.test {
            vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
            assertEquals(StandingTableCreateEvent.Saved, awaitItem())
        }
        assertEquals("New name", repo.lastDraft?.name)
    }

    @Test
    fun editCannotSaveUntilOwnerSettingsLoadAndCanRetry() = runUnitTest {
        val repo = FakeStandingTableRepository()
        repo.getOutcome = Result.failure(IllegalStateException("offline"))
        val vm = StandingTableCreateViewModel("K7M2QX", repo, clock, StandingTablesEnabled.forTest(true))
        vm.takeAction(StandingTableCreateAction.Submit("Thursday Night Poker"))
        assertEquals(null, repo.lastDraft)
        assertFalse(vm.state.isOwner)
        repo.getOutcome = Result.success(table())
        vm.takeAction(StandingTableCreateAction.Load)
        assertTrue(vm.state.isOwner)
        assertEquals("Thursday Night Poker", vm.state.name)
    }

    @Test
    fun detailLoadsJustCreatedAndRoutesActionsThroughEvents() = runUnitTest {
        val repo = FakeStandingTableRepository(listOf(table()))
        val vm = detailVm(repo, justCreated = true)
        assertEquals(table(), vm.state.table)
        assertTrue(vm.state.justCreated)
        vm.eventFlow.test {
            vm.takeAction(StandingTableAction.Open)
            assertEquals(StandingTableEvent.Open("K7M2QX"), awaitItem())
            vm.takeAction(StandingTableAction.Share)
            assertIs<StandingTableEvent.Share>(awaitItem())
            vm.takeAction(StandingTableAction.AddCalendar)
            assertIs<StandingTableEvent.AddCalendar>(awaitItem())
        }
    }

    @Test
    fun deleteRequiresConfirmationAndOwnershipAndKeepsErrorOnFailure() = runUnitTest {
        val repo = FakeStandingTableRepository(listOf(table()))
        val vm = detailVm(repo)
        vm.takeAction(StandingTableAction.ConfirmDelete)
        assertEquals(null, repo.archivedCode)
        vm.takeAction(StandingTableAction.RequestDelete)
        assertTrue(vm.state.confirmingDelete)
        repo.archiveOutcome = Result.failure(IllegalStateException("offline"))
        vm.takeAction(StandingTableAction.ConfirmDelete)
        assertTrue(vm.state.error)
        repo.archiveOutcome = Result.success(Unit)
        vm.eventFlow.test {
            vm.takeAction(StandingTableAction.ConfirmDelete)
            assertEquals(StandingTableEvent.Removed, awaitItem())
        }
        val regularRepo = FakeStandingTableRepository(listOf(table().copy(isOwner = false)))
        val regular = detailVm(regularRepo)
        regular.takeAction(StandingTableAction.RequestDelete)
        regular.takeAction(StandingTableAction.ConfirmDelete)
        assertEquals(null, regularRepo.archivedCode)
        regular.eventFlow.test {
            regular.takeAction(StandingTableAction.Remove)
            assertEquals(StandingTableEvent.Removed, awaitItem())
        }
        assertEquals("K7M2QX", regularRepo.leftCode)
    }

    @Test
    fun detailGetFailureAndDisabledFlagDoNotLeakTable() = runUnitTest {
        val repo = FakeStandingTableRepository()
        repo.getOutcome = Result.failure(IllegalStateException("offline"))
        val vm = detailVm(repo)
        assertTrue(vm.state.error)
        val disabled = detailVm(FakeStandingTableRepository(listOf(table())), enabled = false)
        assertEquals(null, disabled.state.table)
    }

    @Test
    fun detailFetchesOnlyWhenTheScreenAsksSoOpeningLoadsOnce() = runUnitTest {
        val repo = FakeStandingTableRepository(listOf(table()))
        val vm = StandingTableViewModel("K7M2QX", false, repo, StandingTablesEnabled.forTest(true))
        assertEquals(0, repo.getCalls)
        vm.takeAction(StandingTableAction.Refresh)
        assertEquals(1, repo.getCalls)
        assertEquals(table(), vm.state.table)
    }

    @Test
    fun lobbyProjectionShowsStandingNameOnlyWhenFlagEnabled() {
        val summary = StandingTableSummary("Thursday Friends", DayOfWeek.THURSDAY, LocalTime(20, 0), TimeZone.UTC, Instant.parse("2026-11-20T01:00:00Z"))
        val room = Room("K7M2QX", "owner", 0, 6, RoomStatus.Lobby, emptyList(), standingTable = summary)
        assertEquals("Thursday Friends", visibleStandingTable(room, true)?.name)
        assertEquals(null, visibleStandingTable(room, false))
        assertEquals(null, visibleStandingTable(null, true))
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-19T17:00:00Z"))
    private fun createVm(repo: FakeStandingTableRepository, enabled: Boolean = true) =
        StandingTableCreateViewModel(null, repo, clock, StandingTablesEnabled.forTest(enabled))

    private fun detailVm(
        repo: FakeStandingTableRepository,
        justCreated: Boolean = false,
        enabled: Boolean = true,
    ) = StandingTableViewModel("K7M2QX", justCreated, repo, StandingTablesEnabled.forTest(enabled)).also {
        it.takeAction(StandingTableAction.Refresh)
    }

    private fun table() = StandingTable(
        "K7M2QX", "Thursday Night Poker", "owner", "Alex", true, 5000, 6,
        DayOfWeek.THURSDAY, LocalTime(20, 0), TimeZone.of("America/New_York"),
        120, Instant.parse("2026-11-20T01:00:00Z"), 5,
    )
}
