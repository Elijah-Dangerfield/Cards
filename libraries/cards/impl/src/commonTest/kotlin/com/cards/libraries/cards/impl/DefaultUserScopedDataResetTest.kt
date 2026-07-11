package com.dangerfield.cards.libraries.cards.impl

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
}
