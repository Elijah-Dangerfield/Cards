package com.dangerfield.cards.server.db

import com.dangerfield.cards.server.config.DatabaseConfig
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the single-writer contract that keeps two server instances from
 * split-braining live room state: exactly one process may hold the Postgres
 * advisory lock, a second one fails fast, and releasing hands off cleanly.
 *
 * Uses a raw Testcontainer Postgres with NO migrations — advisory locks are
 * schema-independent, so this suite only needs a live server to lock against.
 */
class SingleWriterGuardTest {

    // Short budget so the contention path fails in ~0.5s instead of the 15s
    // production default; the retry loop is still exercised.
    private val fastTimeoutMs = 500L
    private val fastRetryMs = 100L

    @Test
    fun acquire_whenLockIsFree_succeeds() {
        val guard = SingleWriterGuard.acquire(config())
        try {
            // Reaching here without throwing is the assertion: the writer booted.
            assertTrue(true)
        } finally {
            guard.release()
        }
    }

    @Test
    fun secondAcquire_whileFirstHolds_failsFast() {
        val first = SingleWriterGuard.acquire(config())
        try {
            val start = System.currentTimeMillis()
            val error = assertFailsWith<IllegalStateException> {
                SingleWriterGuard.acquire(config(), fastTimeoutMs, fastRetryMs)
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                error.message?.contains("Refusing to boot") == true,
                "expected a single-writer refusal, got: ${error.message}",
            )
            // Bounded by the timeout budget, not hung: it gave up promptly.
            assertTrue(elapsed < fastTimeoutMs + 2_000, "acquire took too long: ${elapsed}ms")
        } finally {
            first.release()
        }
    }

    @Test
    fun release_freesLock_soNextInstanceCanAcquire() {
        SingleWriterGuard.acquire(config()).release()

        // The successor acquires with the fast budget: if release hadn't freed
        // the lock this would exhaust the timeout and throw.
        val successor = SingleWriterGuard.acquire(config(), fastTimeoutMs, fastRetryMs)
        successor.release()
    }

    private fun config(): DatabaseConfig {
        val c = container ?: error("Postgres not initialized; @BeforeClass must run")
        return DatabaseConfig(
            jdbcUrl = c.jdbcUrl,
            username = c.username,
            password = c.password,
            poolMaxSize = 1,
            poolMinIdle = 1,
        )
    }

    companion object {
        private const val POSTGRES_IMAGE = "postgres:16-alpine"

        private var container: PostgreSQLContainer<*>? = null

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            Assume.assumeTrue(
                "Docker is not available; skipping Postgres integration tests",
                isDockerAvailable(),
            )
            container = PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("cards_test")
                .withUsername("cards")
                .withPassword("cards")
                .also { it.start() }
        }

        private fun isDockerAvailable(): Boolean = try {
            DockerClientFactory.instance().client().pingCmd().exec()
            true
        } catch (_: Throwable) {
            false
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            container?.stop()
            container = null
        }
    }
}
