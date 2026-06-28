package com.dangerfield.cards.server

import com.dangerfield.cards.server.config.AccessControlConfig
import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.config.BillingConfig
import com.dangerfield.cards.server.config.SupabaseConfig
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.di.ServerComponent
import com.dangerfield.cards.server.di.create
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.game.PostgresSessionSnapshotStore
import com.dangerfield.cards.server.game.SessionSnapshot
import com.dangerfield.cards.server.plugins.JwtVerification
import com.dangerfield.cards.server.routes.CreateRoomResponse
import com.dangerfield.cards.server.routes.RoomClientFrame
import com.dangerfield.cards.server.routes.RoomSocketTestApp
import com.dangerfield.cards.server.routes.RoomSocketTestAuth
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The full-stack ("Layer 3") integration test: the REAL DI graph
 * ([ServerComponent]) over a REAL Postgres (Testcontainers), driven through the
 * REAL routes via the shared [installApp] bootstrap seam.
 *
 * Where the fast `:apps:integration` harness swaps in in-memory storage + fakes
 * (and is the right default for the client↔server wire), this one proves the
 * pieces only the whole stack exercises together:
 *  - the production Anvil DI graph actually wires + constructs against a live DB,
 *  - `installApp` boots the same way production [module] does,
 *  - a host's `StartHand`, taken through the real `roomSocketRoutes` + real
 *    `DefaultGameSessionRegistry`, **persists to the real `room_sessions`
 *    Postgres table** via `PostgresSessionSnapshotStore` — the load-bearing
 *    reconnect-after-restart path that nothing covered end-to-end before.
 *
 * Skips (assumption-failure, not red) when Docker is unavailable; see
 * [DatabaseTest].
 */
class FullStackRoomTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction { RoomSessionsTable.deleteAll() }
    }

    @Test
    fun createJoinStart_throughRealComponentAndDb_persistsSessionToPostgres() = runTest {
        val host = seedAuthUser()
        val joiner = seedAuthUser()
        val component = ServerComponent::class.create(database, TEST_SUPABASE, TEST_BILLING)

        testApplication {
            application {
                installApp(
                    component = component,
                    verification = JwtVerification.Static(RoomSocketTestAuth.verifier),
                    adminConfig = AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6),
                    accessControl = AccessControlConfig(appealUrl = null),
                )
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(ClientWebSockets)
            }

            // Real routes + real PostgresProfileRepository serve create/join.
            val code = client.createRoom(host)
            client.joinRoom(code, joiner)

            val sockets = RoomSocketTestApp(client)
            try {
                val hostSocket = sockets.connect(code, host)
                val joinerSocket = sockets.connect(code, joiner)
                hostSocket.drainSnapshot()
                joinerSocket.drainSnapshot()

                // Host starts; the real engine deals through the real route.
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "start-1"))
                joinerSocket.receiveUntilGameState() // hand reached the other client

                // The decisive assertion: it landed in real Postgres.
                val persisted = awaitPersisted(PostgresSessionSnapshotStore(database), code)
                assertNotNull(persisted, "StartHand should persist a session to room_sessions")
                assertEquals(code, persisted.code)
            } finally {
                sockets.closeAll()
            }
        }
    }

    private suspend fun io.ktor.client.HttpClient.createRoom(asUser: UserId): String =
        post("/v1/rooms") {
            header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(asUser)}")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.body<CreateRoomResponse>().room.code

    private suspend fun io.ktor.client.HttpClient.joinRoom(code: String, asUser: UserId) {
        post("/v1/rooms/$code/join") {
            header(HttpHeaders.Authorization, "Bearer ${RoomSocketTestAuth.jwt(asUser)}")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
    }

    /**
     * Persistence happens just after the hand starts; poll the real table on a
     * wall-clock deadline (each read is a real query, so it advances even under
     * `runTest`'s virtual time) rather than racing a single read.
     */
    private suspend fun awaitPersisted(
        store: PostgresSessionSnapshotStore,
        code: String,
        timeoutMs: Long = 5_000,
    ): SessionSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            store.readByCode(code)?.let { return it }
            delay(20)
        }
        error("session for $code never persisted to Postgres within ${timeoutMs}ms")
    }

    /** Exposed mapping for cleaning the table between runs. */
    private object RoomSessionsTable : org.jetbrains.exposed.sql.Table("room_sessions") {
        override val primaryKey: PrimaryKey? = null
    }

    private companion object {
        // Component needs a SupabaseConfig; only the (uncalled) admin-delete
        // route would touch it, so a placeholder is fine.
        val TEST_SUPABASE = SupabaseConfig(
            projectUrl = "https://integration-test.supabase.co",
            serviceRoleKey = null,
        )
        // Unconfigured billing — the real validators stay dormant, which is
        // fine: this suite never exercises the redeem path.
        val TEST_BILLING = BillingConfig(
            appleBundleId = null,
            appleEnvironment = "Sandbox",
            appleAppAppleId = null,
            googlePackageName = null,
            googleServiceAccountJson = null,
        )
    }
}
