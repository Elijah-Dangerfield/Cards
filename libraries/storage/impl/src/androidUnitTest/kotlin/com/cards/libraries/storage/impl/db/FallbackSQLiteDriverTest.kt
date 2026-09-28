package com.dangerfield.cards.libraries.storage.impl.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import com.dangerfield.cards.libraries.core.logging.EXTRA_APP_EVENT
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogId
import com.dangerfield.cards.libraries.core.logging.LogTree
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FallbackSQLiteDriverTest {

    private val tree = RecordingTree()

    @AfterTest
    fun tearDown() {
        KLog.clearTrees()
    }

    @Test
    fun open_bundledCannotLoadNativeLib_connectionComesFromFallback() {
        val primary = FakeDriver(failure = nativeLibMissing())
        val fallback = FakeDriver()
        val driver = FallbackSQLiteDriver(primary = primary, fallback = fallback)

        val connection = driver.open("cards.db")

        assertSame(fallback.connections.single(), connection)
        assertEquals(listOf("cards.db"), fallback.openedFileNames)
    }

    @Test
    fun open_bundledClassAlreadyErroneous_connectionComesFromFallback() {
        val primary = FakeDriver(
            failure = NoClassDefFoundError("androidx.sqlite.driver.bundled.BundledSQLiteDriver\$NativeLibraryObject"),
        )
        val fallback = FakeDriver()
        val driver = FallbackSQLiteDriver(primary = primary, fallback = fallback)

        val connection = driver.open("cards.db")

        assertSame(fallback.connections.single(), connection)
    }

    @Test
    fun open_afterFallback_decisionLatchesAndPrimaryIsNeverRetried() {
        val primary = FakeDriver(failure = nativeLibMissing())
        val fallback = FakeDriver()
        val driver = FallbackSQLiteDriver(primary = primary, fallback = fallback)

        repeat(3) { driver.open("cards.db") }

        assertEquals(1, primary.openedFileNames.size, "primary is probed exactly once")
        assertEquals(3, fallback.openedFileNames.size)
    }

    @Test
    fun open_primaryLoads_fallbackUntouchedAndProbeClosed() {
        val primary = FakeDriver()
        val fallback = FakeDriver()
        val driver = FallbackSQLiteDriver(primary = primary, fallback = fallback)

        val connection = driver.open("cards.db")

        assertEquals(listOf(":memory:", "cards.db"), primary.openedFileNames)
        assertTrue(primary.connections.first().closed, "probe connection is closed")
        assertSame(primary.connections.last(), connection)
        assertTrue(fallback.openedFileNames.isEmpty())
    }

    @Test
    fun hasConnectionPool_reportsTheDriverThatWon() {
        val primary = FakeDriver(failure = nativeLibMissing(), pooled = false)
        val fallback = FakeDriver(pooled = true)
        val driver = FallbackSQLiteDriver(primary = primary, fallback = fallback)

        assertTrue(driver.hasConnectionPool)
        driver.open("cards.db")

        assertEquals(1, primary.openedFileNames.size, "reading the pool flag already settled the decision")
    }

    @Test
    fun open_primaryFailsForAnUnrelatedReason_propagatesWithoutFallingBack() {
        val primary = FakeDriver(failure = IllegalStateException("disk full"))
        val fallback = FakeDriver()
        val driver = FallbackSQLiteDriver(primary = primary, fallback = fallback)

        assertFailsWith<IllegalStateException> { driver.open("cards.db") }

        assertTrue(fallback.openedFileNames.isEmpty())
    }

    @Test
    fun fallback_emitsOneEventCarryingTheCause() {
        KLog.plant(tree)
        val driver = FallbackSQLiteDriver(primary = FakeDriver(failure = nativeLibMissing()), fallback = FakeDriver())

        driver.open("cards.db")
        driver.open("cards.db")

        val event = tree.eventEntries("db.driver_fallback").single()
        assertEquals("UnsatisfiedLinkError", event.context.extras["cause"])
    }

    @Test
    fun primaryLoads_emitsNoEvent() {
        KLog.plant(tree)
        val driver = FallbackSQLiteDriver(primary = FakeDriver(), fallback = FakeDriver())

        driver.open("cards.db")

        assertFalse(tree.entries.any { it.context.extras.containsKey(EXTRA_APP_EVENT) })
    }

    private fun nativeLibMissing() =
        UnsatisfiedLinkError("dlopen failed: library \"libsqliteJni.so\" not found")

    private class FakeDriver(
        private val failure: Throwable? = null,
        private val pooled: Boolean = false,
    ) : SQLiteDriver {
        val openedFileNames = mutableListOf<String>()
        val connections = mutableListOf<FakeConnection>()

        @Suppress("INAPPLICABLE_JVM_NAME")
        @get:JvmName("hasConnectionPool")
        override val hasConnectionPool: Boolean
            get() = pooled

        override fun open(fileName: String): SQLiteConnection {
            openedFileNames += fileName
            failure?.let { throw it }
            return FakeConnection().also { connections += it }
        }
    }

    private class FakeConnection : SQLiteConnection {
        var closed = false
            private set

        override fun prepare(sql: String): SQLiteStatement = error("not exercised")

        override fun close() {
            closed = true
        }
    }

    private class RecordingTree : LogTree() {
        val entries = mutableListOf<LogEntry>()

        override fun log(entry: LogEntry): LogId? {
            entries += entry
            return null
        }

        fun eventEntries(eventName: String): List<LogEntry> =
            entries.filter { it.context.extras[EXTRA_APP_EVENT] == eventName }
    }
}
