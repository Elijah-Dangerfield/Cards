package com.dangerfield.cards.libraries.storage.impl.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent

/**
 * Hands out connections from [primary] unless its native code cannot be loaded, in
 * which case every connection for the rest of the process comes from [fallback].
 *
 * Constructing `BundledSQLiteDriver()` proves nothing: `libsqliteJni.so` is only
 * `dlopen`ed inside a static initialiser that runs on the first `open()`, deep in
 * Room's connection manager. So the first touch of this driver probes the primary
 * with an in-memory open, which is what forces that load, and latches the outcome.
 *
 * The probe runs on the first read of [hasConnectionPool] too, not just on
 * [open], because Room reads that flag once at construction to decide whether to
 * wrap the driver in its own pool, and the bundled and platform drivers answer it
 * differently. Deciding late would leave Room pooling the wrong driver.
 *
 * Only [UnsatisfiedLinkError] and [NoClassDefFoundError] trigger the fallback: the
 * first is the load itself failing, the second is the JVM re-raising it after
 * marking the class erroneous. Anything else is not a missing native library and
 * propagates as before.
 */
class FallbackSQLiteDriver(
    private val primary: SQLiteDriver,
    private val fallback: SQLiteDriver,
) : SQLiteDriver {

    private val resolved: SQLiteDriver by lazy { resolve() }

    @Suppress("INAPPLICABLE_JVM_NAME")
    @get:JvmName("hasConnectionPool")
    override val hasConnectionPool: Boolean
        get() = resolved.hasConnectionPool

    override fun open(fileName: String): SQLiteConnection = resolved.open(fileName)

    private fun resolve(): SQLiteDriver {
        val nativeLoadFailure = try {
            primary.open(PROBE_FILE_NAME).close()
            return primary
        } catch (e: UnsatisfiedLinkError) {
            e
        } catch (e: NoClassDefFoundError) {
            e
        }
        KLog.w(nativeLoadFailure) { "Bundled SQLite driver cannot load its native library; using the platform driver" }
        KLog.logEvent(FALLBACK_EVENT, "cause" to nativeLoadFailure::class.simpleName)
        return fallback
    }

    private companion object {
        const val PROBE_FILE_NAME = ":memory:"
        const val FALLBACK_EVENT = "db.driver_fallback"
    }
}
