package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.cards.AppEvents
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the edge-gating every [UserScopedSyncer] now relies on the coordinator
 * for — the logic the repos used to each hand-roll as AppEventListeners:
 *
 *  - UserChanged to a user, and AccountClaimed, sync every syncer.
 *  - A sign-out (UserChanged to null) does not, and leaves no active user.
 *  - A warm foreground syncs only while a user is active; a cold-boot foreground
 *    never does (its sync is owned by the UserChanged that follows auth resolve).
 */
class UserScopedSyncCoordinatorTest : CoroutineTest() {

    @Test
    fun userChanged_toAUser_syncsEverySyncer() = runUnitTest {
        val f = fixture()
        f.bus.dispatch(AppEvent.UserChanged(previous = null, current = "u1"))
        assertEquals(1, f.a.syncs)
        assertEquals(1, f.b.syncs)
    }

    @Test
    fun accountClaimed_syncsEverySyncer() = runUnitTest {
        val f = fixture()
        f.bus.dispatch(AppEvent.AccountClaimed(userId = "u1"))
        assertEquals(1, f.a.syncs)
        assertEquals(1, f.b.syncs)
    }

    @Test
    fun signOut_doesNotSync() = runUnitTest {
        val f = fixture()
        f.bus.dispatch(AppEvent.UserChanged(previous = "u1", current = null))
        assertEquals(0, f.a.syncs)
    }

    @Test
    fun coldBootForeground_doesNotSync() = runUnitTest {
        val f = fixture()
        f.bus.dispatch(AppEvent.UserChanged(previous = null, current = "u1"))
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = true))
        assertEquals(1, f.a.syncs, "only the UserChanged synced; the cold-boot foreground did not")
    }

    @Test
    fun warmForeground_syncsOnlyWhileAUserIsActive() = runUnitTest {
        val f = fixture()

        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = false))
        assertEquals(0, f.a.syncs, "no session yet — nothing to reconcile")

        f.bus.dispatch(AppEvent.UserChanged(previous = null, current = "u1"))
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = false))
        assertEquals(2, f.a.syncs, "activation synced once, the warm resume once more")

        f.bus.dispatch(AppEvent.UserChanged(previous = "u1", current = null))
        f.bus.dispatch(AppEvent.OnForeground(isColdBoot = false))
        assertEquals(2, f.a.syncs, "after sign-out the warm resume has no session to sync")
    }

    private fun TestScope.fixture(): Fixture {
        val bus = FakeBus()
        val a = RecordingSyncer()
        val b = RecordingSyncer()
        UserScopedSyncCoordinator(
            appEvents = AppEvents(bus),
            syncers = setOf(a, b),
            appScope = AppCoroutineScope(dispatchers),
        )
        return Fixture(bus, a, b)
    }

    private class Fixture(val bus: FakeBus, val a: RecordingSyncer, val b: RecordingSyncer)

    private class FakeBus : AppEventBus {
        private val flow = MutableSharedFlow<AppEvent>(replay = 1, extraBufferCapacity = 64)
        override fun dispatch(event: AppEvent) { flow.tryEmit(event) }
        override fun eventStream(): Flow<AppEvent> = flow
    }

    private class RecordingSyncer : UserScopedSyncer {
        var syncs = 0
            private set

        override suspend fun sync(): Result<Unit> {
            syncs++
            return Result.success(Unit)
        }
    }
}
