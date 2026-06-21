package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * End-to-end tests for /v1/rooms. Exercises real auth + serialization
 * against an in-memory RoomService (the unit semantics get their own
 * direct test in InMemoryRoomServiceTest).
 *
 * What we pin:
 *  - create + join + get + leave happy paths
 *  - 401 when the JWT is missing
 *  - 404 when the code is unknown
 *  - 409 when the room is full
 *  - 409 when you try to leave a room you're not in
 *  - join is idempotent on the wire (`alreadyJoined: true`)
 *  - host display name comes from the profile, not the request body
 *  - leaving the last member reaps the room (next GET → 404)
 */
@OptIn(ExperimentalTime::class)
class RoomRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val host = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val alice = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

    @Test
    fun create_returnsRoomWithHostSeated() = runTest {
        withRooms { client ->
            val resp = client.createRoom(maxSeats = 4, asUser = host)
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<CreateRoomResponse>()
            val room = body.room
            assertEquals(host.value.toString(), room.hostUserId)
            assertEquals(RoomStatusDto.Lobby, room.status)
            assertEquals(4, room.maxSeats)
            assertEquals(1, room.members.size)
            assertEquals(host.value.toString(), room.members.single().userId)
            assertEquals(0, room.members.single().seatIndex)
        }
    }

    @Test
    fun create_400_whenMaxSeatsOutOfRange() = runTest {
        withRooms { client ->
            val resp = client.createRoom(maxSeats = 1, asUser = host)
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(resp.bodyAsText().contains("invalid_max_seats"))
        }
    }

    @Test
    fun get_returnsRoom() = runTest {
        withRooms { client ->
            val created = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            val resp = client.getRoom(created.code, asUser = host)
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(created.code, resp.body<GetRoomResponse>().room.code)
        }
    }

    @Test
    fun get_404_whenUnknownCode() = runTest {
        withRooms { client ->
            val resp = client.getRoom("ZZZZZZ", asUser = host)
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }
    }

    @Test
    fun join_addsAMember_andReturnsRoom() = runTest {
        withRooms { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            val resp = client.joinRoom(room.code, asUser = alice)
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<JoinRoomResponse>()
            assertEquals(false, body.alreadyJoined)
            assertEquals(2, body.room.members.size)
            assertEquals(
                listOf(0, 1),
                body.room.members.map { it.seatIndex }.sorted(),
                "second join lands in seat 1",
            )
        }
    }

    @Test
    fun join_idempotent_setsAlreadyJoined() = runTest {
        withRooms { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            client.joinRoom(room.code, asUser = alice)
            val resp = client.joinRoom(room.code, asUser = alice)
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(true, resp.body<JoinRoomResponse>().alreadyJoined)
        }
    }

    @Test
    fun join_409_whenFull() = runTest {
        withRooms { client ->
            val room = client.createRoom(maxSeats = 2, asUser = host).body<CreateRoomResponse>().room
            client.joinRoom(room.code, asUser = alice)
            val third = UserId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
            val resp = client.joinRoom(room.code, asUser = third)
            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertTrue(resp.bodyAsText().contains("room_full"))
        }
    }

    @Test
    fun join_404_whenUnknownCode() = runTest {
        withRooms { client ->
            val resp = client.joinRoom("ZZZZZZ", asUser = alice)
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }
    }

    @Test
    fun leave_204_andLastLeaverReapsTheRoom() = runTest {
        withRooms { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            val resp = client.leaveRoom(room.code, asUser = host)
            assertEquals(HttpStatusCode.NoContent, resp.status)
            assertEquals(HttpStatusCode.NotFound, client.getRoom(room.code, asUser = host).status)
        }
    }

    @Test
    fun leave_409_whenNotInRoom() = runTest {
        withRooms { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            val resp = client.leaveRoom(room.code, asUser = alice)
            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertTrue(resp.bodyAsText().contains("not_in_room"))
        }
    }

    @Test
    fun all_routes_401_whenAuthHeaderMissing() = runTest {
        withRooms { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.createRoom(asUser = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.getRoom("ANY123", asUser = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.joinRoom("ANY123", asUser = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.leaveRoom("ANY123", asUser = null).status)
        }
    }

    @Test
    fun hostDisplayName_comesFromProfile_notTheRequestBody() = runTest {
        withRooms(profileNameFor = { uid ->
            if (uid == host) "ServerSourcedName" else "OtherUser"
        }) { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            assertEquals("ServerSourcedName", room.members.single().displayName)
        }
    }

    @Test
    fun create_returns409_whenHostExceedsRoomCap() = runTest {
        withRooms { client ->
            // Burn the cap with successive successful creates.
            repeat(com.dangerfield.cards.server.domain.RoomService.MAX_ROOMS_PER_HOST) {
                val resp = client.createRoom(asUser = host)
                assertEquals(HttpStatusCode.OK, resp.status)
            }
            // One past the cap surfaces as 409 with `too_many_rooms` so
            // the client UI can show a tailored message.
            val refused = client.createRoom(asUser = host)
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertTrue(
                refused.bodyAsText().contains("too_many_rooms"),
                "expected too_many_rooms problem code, got ${refused.bodyAsText()}",
            )
        }
    }

    @Test
    fun addBot_seatsRevealedBot_thenRemoveFreesSeat() = runTest {
        withRooms { client ->
            val room = client.createRoom(maxSeats = 4, asUser = host).body<CreateRoomResponse>().room

            val added = client.addBot(room.code, asUser = host).body<AddBotResponse>().room
            assertEquals(2, added.members.size)
            val bot = added.members.single { it.isBot }
            assertEquals("🤖", bot.avatarEmoji, "revealed bot wears the reserved robot avatar on the wire")

            val removed = client.removeBot(room.code, bot.userId, asUser = host)
            assertEquals(HttpStatusCode.NoContent, removed.status)
            val after = client.getRoom(room.code, asUser = host).body<GetRoomResponse>().room
            assertTrue(after.members.none { it.isBot }, "removing the bot frees its seat")
        }
    }

    @Test
    fun addBot_403_whenNotHost() = runTest {
        withRooms { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            client.joinRoom(room.code, asUser = alice)
            val resp = client.addBot(room.code, asUser = alice)
            assertEquals(HttpStatusCode.Forbidden, resp.status)
            assertTrue(resp.bodyAsText().contains("not_host"))
        }
    }

    @Test
    fun memberDto_hidesStealthBot_butRevealsLobbyBot() {
        val base = com.dangerfield.cards.server.domain.RoomMember(
            userId = host,
            displayName = "Decoy",
            seatIndex = 1,
            joinedAt = Instant.fromEpochMilliseconds(0),
            isConnected = false,
            avatarEmoji = "🦊",
        )
        val personality = com.dangerfield.cards.libraries.bots.BotPersonality.Jane
        val hidden = base.copy(
            bot = com.dangerfield.cards.server.domain.BotSeat(personality, com.dangerfield.cards.libraries.bots.BotDifficulty.Standard, revealed = false),
        ).toDto()
        val revealed = base.copy(
            avatarEmoji = "🤖",
            bot = com.dangerfield.cards.server.domain.BotSeat(personality, com.dangerfield.cards.libraries.bots.BotDifficulty.Standard, revealed = true),
        ).toDto()

        assertEquals(false, hidden.isBot, "a hidden bot must not advertise itself on the wire")
        assertEquals("🦊", hidden.avatarEmoji, "a hidden bot keeps its ordinary avatar")
        assertEquals(true, revealed.isBot)
        assertEquals("🤖", revealed.avatarEmoji)
    }

    @Test
    fun lowercaseCode_isAcceptedViaUppercasing() = runTest {
        // The URL might roll back through some pathway in lowercase; the
        // route normalizes by uppercasing.
        withRooms { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            val resp = client.getRoom(room.code.lowercase(), asUser = host)
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    // ---------- scaffolding ----------

    private fun jwt(forUserId: UserId): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(forUserId.value.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    private suspend fun withRooms(
        profileNameFor: (UserId) -> String = { uid -> "P-${uid.value.toString().take(4)}" },
        block: suspend (RoomsTestClient) -> Unit,
    ) {
        val rooms = InMemoryRoomService(
            clock = FixedClock(),
            random = Random(0L),
        )
        val profiles = FakeProfileRepository(profileNameFor)
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { roomRoutes(rooms, profiles) }
            }
            val raw = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            block(RoomsTestClient(this, raw))
        }
    }

    private inner class RoomsTestClient(
        private val app: ApplicationTestBuilder,
        private val raw: io.ktor.client.HttpClient,
    ) {
        suspend fun createRoom(maxSeats: Int? = null, asUser: UserId?): HttpResponse =
            raw.post("/v1/rooms") {
                contentType(ContentType.Application.Json)
                bearer(asUser)
                setBody(CreateRoomRequest(maxSeats = maxSeats))
            }

        suspend fun getRoom(code: String, asUser: UserId?): HttpResponse =
            raw.get("/v1/rooms/$code") { bearer(asUser) }

        suspend fun joinRoom(code: String, asUser: UserId?): HttpResponse =
            raw.post("/v1/rooms/$code/join") {
                contentType(ContentType.Application.Json)
                bearer(asUser)
            }

        suspend fun leaveRoom(code: String, asUser: UserId?): HttpResponse =
            raw.delete("/v1/rooms/$code/me") { bearer(asUser) }

        suspend fun addBot(code: String, asUser: UserId?): HttpResponse =
            raw.post("/v1/rooms/$code/bots") {
                contentType(ContentType.Application.Json)
                bearer(asUser)
                setBody(AddBotRequest())
            }

        suspend fun removeBot(code: String, botUserId: String, asUser: UserId?): HttpResponse =
            raw.delete("/v1/rooms/$code/bots/$botUserId") { bearer(asUser) }

        private fun io.ktor.client.request.HttpRequestBuilder.bearer(asUser: UserId?) {
            asUser?.let { header(HttpHeaders.Authorization, "Bearer ${jwt(it)}") }
        }
    }

    private class FixedClock(private val ms: Long = 1_700_000_000_000) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
    }

    private class FakeProfileRepository(
        private val nameFor: (UserId) -> String,
    ) : ProfileRepository {
        override suspend fun findById(userId: UserId): Profile? = null
        override suspend fun findOrCreate(userId: UserId): Profile = Profile(
            userId = userId,
            displayName = nameFor(userId),
            avatarEmoji = "🃏",
            avatarBackgroundColor = null,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

        override suspend fun update(
            userId: UserId,
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("not used in this test")

        override suspend fun delete(userId: UserId) { /* no-op for route tests */ }

        override suspend fun touchInstallId(userId: UserId, installId: java.util.UUID): java.util.UUID? = null

        override suspend fun findInstallSiblings(
            installId: java.util.UUID,
            currentUserId: UserId,
        ): List<UserId> = emptyList()
    }
}
