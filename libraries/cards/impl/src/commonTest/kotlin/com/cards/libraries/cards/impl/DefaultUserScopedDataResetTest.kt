package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.UserScopedClearer
import com.dangerfield.cards.libraries.cards.UserScopedWorkStopper
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultUserScopedDataResetTest : CoroutineTest() {

    @Test
    fun stoppersRunBeforeClearers_soAWipeNeverRacesInFlightWork() = runUnitTest {
        val order = mutableListOf<String>()
        val reset = DefaultUserScopedDataReset(
            appCache = TestAppCache(),
            clearers = setOf(recordingClearer("clear", order)),
            stoppers = setOf(recordingStopper("stop", order)),
        )

        reset.clearFor("u1")

        assertEquals(listOf("stop", "clear"), order)
    }

    @Test
    fun aFailingStopper_doesNotBlockTheClearers() = runUnitTest {
        val order = mutableListOf<String>()
        val reset = DefaultUserScopedDataReset(
            appCache = TestAppCache(),
            clearers = setOf(recordingClearer("clear", order)),
            stoppers = setOf(
                object : UserScopedWorkStopper {
                    override suspend fun stopWorkFor(previousUserId: String) {
                        throw RuntimeException("stopper broke")
                    }
                },
            ),
        )

        reset.clearFor("u1")

        assertEquals(listOf("clear"), order, "the wipe must proceed even when quiescing fails")
    }

    private fun recordingClearer(name: String, order: MutableList<String>) =
        object : UserScopedClearer {
            override suspend fun clear(previousUserId: String) {
                order += name
            }
        }

    private fun recordingStopper(name: String, order: MutableList<String>) =
        object : UserScopedWorkStopper {
            override suspend fun stopWorkFor(previousUserId: String) {
                order += name
            }
        }

    @Test
    fun ensureOwnedBy_dumpsAPreviousOwnerTheProcessNeverSaw() = runUnitTest {
        // AUTH-33: the durable record is the whole point — it answers "whose data
        // is this?" at a cold boot, when nothing in memory knows.
        val cleared = mutableListOf<String>()
        val cache = TestAppCache(AppData(lastActiveUserId = "stranded-user"))
        val reset = DefaultUserScopedDataReset(
            appCache = cache,
            clearers = setOf(recordingClearer(cleared)),
            stoppers = emptySet(),
        )

        reset.ensureOwnedBy("new-user")

        assertEquals(listOf("stranded-user"), cleared)
        assertEquals("new-user", cache.get().lastActiveUserId, "the new owner is recorded")
    }

    @Test
    fun ensureOwnedBy_isANoOpForTheOwnerItAlreadyHas() = runUnitTest {
        val cleared = mutableListOf<String>()
        val cache = TestAppCache(AppData(lastActiveUserId = "same-user"))
        val reset = DefaultUserScopedDataReset(
            appCache = cache,
            clearers = setOf(recordingClearer(cleared)),
            stoppers = emptySet(),
        )

        reset.ensureOwnedBy("same-user")

        assertEquals(emptyList(), cleared, "relaunching as yourself must never dump your own data")
    }

    @Test
    fun ensureOwnedBy_recordsTheFirstOwnerWithoutClearing() = runUnitTest {
        // Fresh install: nobody owned the device, so there is nothing to dump.
        val cleared = mutableListOf<String>()
        val cache = TestAppCache(AppData(lastActiveUserId = null))
        val reset = DefaultUserScopedDataReset(
            appCache = cache,
            clearers = setOf(recordingClearer(cleared)),
            stoppers = emptySet(),
        )

        reset.ensureOwnedBy("first-user")

        assertEquals(emptyList(), cleared)
        assertEquals("first-user", cache.get().lastActiveUserId)
    }

    @Test
    fun clearFor_releasesOwnership_soTheNextSignInDoesNotDumpTwice() = runUnitTest {
        val cleared = mutableListOf<String>()
        val cache = TestAppCache(AppData(lastActiveUserId = "departing"))
        val reset = DefaultUserScopedDataReset(
            appCache = cache,
            clearers = setOf(recordingClearer(cleared)),
            stoppers = emptySet(),
        )

        reset.clearFor("departing")
        assertEquals(null, cache.get().lastActiveUserId, "their data is gone, so nobody owns the device")

        reset.ensureOwnedBy("arriving")
        assertEquals(listOf("departing"), cleared, "exactly once — the record stopped a second dump")
    }

    private fun recordingClearer(into: MutableList<String>) = object : UserScopedClearer {
        override suspend fun clear(previousUserId: String) { into += previousUserId }
    }
}
