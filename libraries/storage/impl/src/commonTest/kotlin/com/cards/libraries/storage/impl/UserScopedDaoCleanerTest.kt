package com.dangerfield.cards.libraries.storage.impl

import com.dangerfield.cards.libraries.cards.storage.db.ClearableDao
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the contract callers of the multibound [ClearableDao] set rely on:
 *  - every dao in the set has `deleteAll()` invoked when the active user changes
 *  - one dao throwing doesn't block the rest (a stuck row can't strand user
 *    data across an account switch / sign-out)
 *  - `clear` is suspend and awaited by the caller — the user-scoped dump runs
 *    to completion before the new user's state is announced
 */
class UserScopedDaoCleanerTest : CoroutineTest() {

    @Test
    fun clear_clearsEveryDao() = runUnitTest {
        val daos = List(3) { RecordingDao() }
        val cleaner = UserScopedDaoCleaner(clearableDaos = daos.toSet())

        cleaner.clear(previousUserId = "user-1")

        assertTrue(daos.all { it.cleared }, "expected every dao to be cleared")
    }

    @Test
    fun clear_oneDaoThrows_othersStillClear() = runUnitTest {
        val healthy1 = RecordingDao()
        val poison = ThrowingDao()
        val healthy2 = RecordingDao()
        val cleaner = UserScopedDaoCleaner(
            clearableDaos = setOf(healthy1, poison, healthy2),
        )

        cleaner.clear(previousUserId = "user-1")

        assertTrue(healthy1.cleared)
        assertTrue(healthy2.cleared)
        assertEquals(1, poison.attempts, "poison dao was still attempted")
    }

    private class RecordingDao : ClearableDao {
        var cleared: Boolean = false
            private set

        override suspend fun deleteAll() {
            cleared = true
        }
    }

    private class ThrowingDao : ClearableDao {
        var attempts: Int = 0
            private set

        override suspend fun deleteAll() {
            attempts++
            throw IllegalStateException("simulated wipe failure")
        }
    }
}
