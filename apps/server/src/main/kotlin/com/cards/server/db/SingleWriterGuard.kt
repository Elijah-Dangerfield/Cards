package com.dangerfield.cards.server.db

import com.dangerfield.cards.server.config.DatabaseConfig
import com.dangerfield.cards.libraries.core.Catching
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager

/**
 * Single-writer guard for the game server.
 *
 * Live room and hand state lives in this process's RAM — `GameSessionRegistry`
 * holds `Map<code, GameSession>` and `InMemoryRoomService` holds
 * `Map<code, Room>`, each serialized by a per-room `Mutex`. That mutex only
 * orders writes **within one JVM**. Because both services rehydrate a room from
 * its durable Postgres snapshot on a lookup miss, two server instances can each
 * end up holding a *divergent* in-memory copy of the same table — a split-brain
 * that loses bets and desyncs seats. The whole architecture assumes exactly one
 * writer (see `docs/decisions.md`, 2026-07-03).
 *
 * This holds a Postgres **session-level advisory lock** for the life of the
 * process. Exactly one instance can own the lock; a second one (a stray
 * `fly scale count 2`, an added region, or a blue-green deploy that runs old and
 * new concurrently) fails to acquire it and refuses to boot instead of silently
 * corrupting tables. Fly restarts the loser, which crash-loops harmlessly —
 * loud and safe rather than quiet and wrong.
 *
 * The lock rides a dedicated JDBC connection kept open for the process lifetime.
 * When this process exits — cleanly via [release], or hard via a crash — the
 * connection closes and Postgres frees the lock, handing the next instance a
 * clean acquire. That is exactly the handoff we want on a rolling redeploy.
 */
class SingleWriterGuard private constructor(private val connection: Connection) {

    /** Release the lock ahead of process exit so a redeploy hands off fast. */
    fun release() {
        Catching { connection.close() }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("SingleWriterGuard")

        /**
         * Fixed advisory-lock slot for "the cards game-server writer." Arbitrary
         * but constant. Each Fly app points at its own Supabase database, so a
         * single slot never collides across environments; if that ever changes,
         * namespace this per app name.
         */
        private const val LOCK_KEY = 0x4341_5244_5357_5254L // "CARDSWRT"

        /**
         * How long to keep retrying the acquire before giving up. Covers the
         * brief window where a just-stopped predecessor's Postgres backend is
         * still tearing down (its lock not yet released). A legitimate rolling
         * deploy stops the old machine first, so the common path acquires on the
         * first try with no wait; this budget only ever delays the crash-loop of
         * an *illegitimate* second instance.
         */
        private const val ACQUIRE_TIMEOUT_MS = 15_000L
        private const val RETRY_INTERVAL_MS = 1_000L

        /**
         * Acquire the single-writer lock or throw. Blocks the boot thread by
         * design: we do not want to serve a request — and rehydrate a room into
         * this instance's memory — until we are the confirmed sole writer.
         *
         * Opens its own connection rather than borrowing from the Hikari pool: a
         * session advisory lock is bound to its backing connection, and a pooled
         * connection would be handed to unrelated queries while still holding it.
         */
        fun acquire(config: DatabaseConfig): SingleWriterGuard {
            val connection = DriverManager.getConnection(
                config.jdbcUrl,
                config.username,
                config.password,
            ).apply { autoCommit = true }

            val deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT_MS
            var attempt = 0
            while (true) {
                if (tryLock(connection)) {
                    logger.info("Single-writer lock acquired — this instance is the writer")
                    return SingleWriterGuard(connection)
                }
                attempt++
                if (System.currentTimeMillis() >= deadline) {
                    Catching { connection.close() }
                    error(
                        "Refusing to boot: another cards-server instance already holds the " +
                            "single-writer lock. Live game state is in-memory and single-writer, " +
                            "so two instances would split-brain and corrupt tables. Run exactly " +
                            "one machine (fly scale count 1) and keep the deploy strategy from " +
                            "running old and new concurrently (no blue-green).",
                    )
                }
                logger.warn(
                    "Single-writer lock held by another instance; retrying (attempt {})…",
                    attempt,
                )
                Thread.sleep(RETRY_INTERVAL_MS)
            }
        }

        private fun tryLock(connection: Connection): Boolean =
            connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                statement.setLong(1, LOCK_KEY)
                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
    }
}
