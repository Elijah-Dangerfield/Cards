package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.SecureSessionStorage
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the AUTH-16 session-store contract: sessions round-trip through the
 * OS-encrypted [SecureSessionStorage], a session left in the pre-AUTH-16
 * plaintext store migrates exactly once (read old → write new → clear old,
 * so nobody gets signed out by the upgrade), and the storage key matches
 * supabase-kt's default `SettingsSessionManager` derivation byte-for-byte —
 * that key equality is what makes the migration find the legacy entry.
 */
class SecureSessionManagerTest : CoroutineTest() {

    private val storage = FakeSecureSessionStorage()

    private fun manager(legacy: SessionManager? = null) = SecureSessionManager(
        storage = storage,
        key = KEY,
        legacy = legacy,
        dispatchers = dispatchers,
    )

    @Test
    fun savedSession_roundTripsThroughSecureStorage() = runUnitTest {
        val manager = manager()

        manager.saveSession(session(refreshToken = "refresh-1"))

        assertEquals("refresh-1", manager.loadSession().refreshToken)
        assertNotNull(storage.values[KEY], "session persists under the derived key")
    }

    @Test
    fun load_migratesLegacyPlaintextSession_andClearsTheOldStore() = runUnitTest {
        val legacy = FakeSessionManager(session(refreshToken = "legacy-refresh"))
        val manager = manager(legacy)

        val loaded = manager.loadSession()

        assertEquals("legacy-refresh", loaded.refreshToken)
        assertNotNull(storage.values[KEY], "migrated session lives in the secure store")
        assertNull(legacy.stored, "plaintext copy is cleared after migration")
    }

    @Test
    fun load_prefersSecureStore_overALingeringLegacyEntry() = runUnitTest {
        val legacy = FakeSessionManager(session(refreshToken = "stale-legacy"))
        val manager = manager(legacy)
        manager.saveSession(session(refreshToken = "current"))

        assertEquals("current", manager.loadSession().refreshToken)
        assertNotNull(legacy.stored, "legacy entry is untouched when no migration runs")
    }

    @Test
    fun delete_clearsBothTheSecureAndLegacyStores() = runUnitTest {
        val legacy = FakeSessionManager(session(refreshToken = "legacy"))
        val manager = manager(legacy)
        manager.saveSession(session(refreshToken = "current"))

        manager.deleteSession()

        assertNull(storage.values[KEY])
        assertNull(legacy.stored, "sign-out never leaves a resurrectable plaintext session")
    }

    @Test
    fun load_withNothingStoredAnywhere_reportsNoSession() = runUnitTest {
        assertNull(manager(FakeSessionManager(stored = null)).loadSessionOrNull())
        assertNull(manager(legacy = null).loadSessionOrNull())
    }

    @Test
    fun storageKey_matchesSupabaseDefaultSessionKeyDerivation() {
        // supabase-kt: "sb-" + supabaseUrl (scheme stripped, '/' and '.' → '-') + "-session".
        assertEquals(
            "sb-abc-supabase-co-session",
            SecureSessionManager.storageKeyFor("https://abc.supabase.co"),
        )
        assertEquals(
            "sb-abc-supabase-co-session",
            SecureSessionManager.storageKeyFor("https://abc.supabase.co/"),
        )
    }

    private fun session(refreshToken: String) = UserSession(
        accessToken = "access",
        refreshToken = refreshToken,
        expiresIn = 3_600,
        tokenType = "bearer",
    )

    private class FakeSecureSessionStorage : SecureSessionStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) {
            values[key] = value
        }
        override fun delete(key: String) {
            values.remove(key)
        }
    }

    private class FakeSessionManager(var stored: UserSession?) : SessionManager {
        override suspend fun saveSession(session: UserSession) {
            stored = session
        }
        override suspend fun loadSession(): UserSession = stored ?: error("no session")
        override suspend fun deleteSession() {
            stored = null
        }
    }

    private companion object {
        const val KEY = "sb-test-supabase-co-session"
    }
}
