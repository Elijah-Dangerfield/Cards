package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserScopedWorkRegistryTest : CoroutineTest() {

    @Test
    fun stopWorkFor_cancelsTrackedWork_andReturnsOnlyOnceItFinished() = runUnitTest {
        val registry = UserScopedWorkRegistry()
        var cleanedUp = false

        val job = launch {
            registry.tracked("u1") {
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    cleanedUp = true
                }
            }
        }
        runCurrent()
        assertFalse(cleanedUp, "work is parked in-flight")

        registry.stopWorkFor("u1")

        assertTrue(cleanedUp, "stopWorkFor returned before the work finished cancelling")
        assertTrue(job.isCancelled)
    }

    @Test
    fun stopWorkFor_leavesOtherUsersWorkAlone() = runUnitTest {
        val registry = UserScopedWorkRegistry()
        val completions = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()

        listOf("u1", "u2").forEach { user ->
            launch {
                registry.tracked(user) {
                    gate.await()
                    completions += user
                }
            }
        }
        runCurrent()

        registry.stopWorkFor("u1")
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("u2"), completions)
    }

    @Test
    fun workTrackedAfterAStop_isStoppableAgain() = runUnitTest {
        val registry = UserScopedWorkRegistry()
        registry.stopWorkFor("u1")

        var completed = false
        launch {
            registry.tracked("u1") {
                CompletableDeferred<Unit>().await()
                completed = true
            }
        }
        runCurrent()

        registry.stopWorkFor("u1")
        runCurrent()
        assertFalse(completed, "a fresh registration after a stop is tracked, not grandfathered in")
    }
}
