package com.dangerfield.cards.libraries.storage.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.storage.db.ClearableDao
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the contract callers of the multibound [ClearableDao] set rely
 * on:
 *  - every dao in the set has `deleteAll()` invoked
 *  - one dao throwing doesn't block the rest (a stuck row can't strand
 *    user data across sign-outs)
 *  - work is dispatched via [AppCoroutineScope] (the AppEventListener
 *    callback contract is non-suspend)
 */
class SignedOutLocalDataCleanerTest : CoroutineTest() {

    @Test
    fun onSignedOut_clearsEveryDao() = runUnitTest {
        val daos = List(3) { RecordingDao() }
        val cleaner = SignedOutLocalDataCleaner(
            clearableDaos = daos.toSet(),
            appScope = AppCoroutineScope(dispatchers),
        )

        cleaner.onSignedOut(AppEvent.SignedOut)

        assertTrue(daos.all { it.cleared }, "expected every dao to be cleared")
    }

    @Test
    fun onSignedOut_oneDaoThrows_othersStillClear() = runUnitTest {
        val healthy1 = RecordingDao()
        val poison = ThrowingDao()
        val healthy2 = RecordingDao()
        val cleaner = SignedOutLocalDataCleaner(
            clearableDaos = setOf(healthy1, poison, healthy2),
            appScope = AppCoroutineScope(dispatchers),
        )

        cleaner.onSignedOut(AppEvent.SignedOut)

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
