package com.dangerfield.cards.server.db

import com.dangerfield.cards.server.config.DatabaseConfig
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base class for tests that need a real Postgres. Spins up a single
 * Testcontainer Postgres for the whole test class (via @BeforeClass), runs
 * Flyway migrations, and exposes a [Database] handle.
 *
 * Why class-level (not per-test) containers: starting Postgres costs ~3s
 * cold; per-test containers would make the suite unusable. Tests inside a
 * class should not share state — clean tables in @After if you need
 * isolation, or use unique data per test.
 *
 * The image tag is pinned so CI behaves deterministically. Bump when
 * Supabase bumps theirs.
 *
 * Subclass like:
 *
 * ```
 * class MyRepoTest : DatabaseTest() {
 *     @Test fun something() = runTest {
 *         database.transaction { … }
 *     }
 * }
 * ```
 */
abstract class DatabaseTest {

    protected val database: Database
        get() = sharedDatabase ?: error("Database not initialized; @BeforeClass must run")

    companion object {
        private const val POSTGRES_IMAGE = "postgres:16-alpine"

        private var container: PostgreSQLContainer<*>? = null
        private var sharedDatabase: Database? = null

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            // Skip the suite entirely if Docker isn't reachable. CI provides
            // Docker; local dev without it gets a clear `assumption failed`
            // skip instead of a red test failure.
            Assume.assumeTrue(
                "Docker is not available; skipping Postgres integration tests",
                isDockerAvailable(),
            )
            val c = PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("cards_test")
                .withUsername("cards")
                .withPassword("cards")
                .also { it.start() }
            container = c
            sharedDatabase = Database.connect(
                DatabaseConfig(
                    jdbcUrl = c.jdbcUrl,
                    username = c.username,
                    password = c.password,
                    poolMaxSize = 4,
                    poolMinIdle = 1,
                ),
            )
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
            sharedDatabase?.close()
            sharedDatabase = null
            container?.stop()
            container = null
        }
    }
}
