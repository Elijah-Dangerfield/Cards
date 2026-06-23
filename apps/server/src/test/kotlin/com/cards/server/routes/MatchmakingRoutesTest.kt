package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RespondResult
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.SendRequestResult
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Route-level tests for POST /v1/matchmaking/find. Backed by a real
 * [InMemoryRoomService] (no DB) so the find-or-create behaviour is exercised
 * end-to-end through the HTTP/JSON layer; the friend graph + profiles are faked.
 */
@OptIn(ExperimentalTime::class)
class MatchmakingRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer).withAudience("authenticated").build()

    private val clock = object : Clock {
        private var t = 1_700_000_000_000L
        override fun now(): Instant = Instant.fromEpochMilliseconds(t).also { t += 1_000 }
    }

    @Test
    fun find_withNoRooms_returns200_created() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.find(jwt(UUID.randomUUID()), min = 1_000, max = 100_000)
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MatchmakingFindResponse>()
            assertTrue(body.created, "first searcher opens a new table")
            assertEquals(1, body.room.members.size)
        }
    }

    @Test
    fun secondSearcher_returns200_joined_sameRoom() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val first = client.find(jwt(UUID.randomUUID()), 1_000, 100_000).body<MatchmakingFindResponse>()
            val second = client.find(jwt(UUID.randomUUID()), 1_000, 100_000).body<MatchmakingFindResponse>()
            assertFalse(second.created, "second searcher joins the existing table")
            assertEquals(first.room.code, second.room.code)
            assertEquals(2, second.room.members.size)
        }
    }

    @Test
    fun find_neverSeatsYouWithABlockedUser() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val enemy = UUID.randomUUID()
        // Friends repo reports `enemy` as blocked for everyone (simplest fake).
        withApp(rooms, friends = BlockingFriends(setOf(UserId(enemy)))) { client ->
            // Enemy opens a table first.
            val enemyRoom = client.find(jwt(enemy), 5_000, 5_000).body<MatchmakingFindResponse>()
            // I search the same tier — must not land on the enemy's table.
            val mine = client.find(jwt(UUID.randomUUID()), 5_000, 5_000).body<MatchmakingFindResponse>()
            assertTrue(mine.created, "a table with a blocked member is skipped")
            assertFalse(mine.room.code == enemyRoom.room.code)
        }
    }

    @Test
    fun find_withInvertedRange_returns400() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.find(jwt(UUID.randomUUID()), min = 100_000, max = 1_000)
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun find_withoutJwt_returns401() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val resp = client.find(bearer = null, min = 1_000, max = 100_000)
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun playBots_fillsDisclosedBots_andDealsTheTable() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val me = UUID.randomUUID()
            val token = jwt(me)
            val code = client.find(token, 5_000, 5_000).body<MatchmakingFindResponse>().room.code

            val resp = client.post("/v1/matchmaking/$code/play-bots") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MatchmakingFindResponse>()

            // Filled to the target with DISCLOSED bots — isBot=true on the wire,
            // never masked as human.
            assertEquals(4, body.room.members.size, "filled to the lively target")
            val bots = body.room.members.filter { it.isBot }
            assertEquals(3, bots.size, "1 human + 3 disclosed bots")
            assertTrue(bots.all { it.isBot }, "fallback bots are disclosed, not stealth")
            // The table dealt — server is the dealer for a public table.
            assertEquals(RoomStatusDto.Playing, body.room.status)
        }
    }

    @Test
    fun playBots_onSomeoneElsesTable_returns403() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val code = client.find(jwt(UUID.randomUUID()), 5_000, 5_000).body<MatchmakingFindResponse>().room.code
            // A different user (not seated) can't conjure bots into that table.
            val resp = client.post("/v1/matchmaking/$code/play-bots") {
                header(HttpHeaders.Authorization, "Bearer ${jwt(UUID.randomUUID())}")
            }
            assertEquals(HttpStatusCode.Forbidden, resp.status)
        }
    }

    // --- harness ------------------------------------------------------------

    private suspend fun withApp(
        rooms: RoomService,
        friends: FriendRepository = BlockingFriends(emptySet()),
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                val registry = com.dangerfield.cards.server.game.DefaultGameSessionRegistry(
                    com.dangerfield.cards.server.game.NoOpSessionSnapshotStore(),
                    Clock.System,
                )
                routing {
                    matchmakingRoutes(
                        rooms, friends, StubProfiles, registry,
                        InMemoryTestTableSessionService(InMemoryTestWalletRepository()),
                        StubEquipment, StubProgression,
                    )
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            block(client)
        }
    }

    private suspend fun io.ktor.client.HttpClient.find(bearer: String?, min: Long, max: Long) =
        post("/v1/matchmaking/find") {
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody("""{"minBuyIn":$min,"maxBuyIn":$max}""")
        }

    private fun jwt(sub: UUID): String = JWT.create()
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .withSubject(sub.toString())
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(testSecret))

    /** Friends fake that reports a fixed blocked set for every caller. */
    private class BlockingFriends(private val blocked: Set<UserId>) : FriendRepository {
        override suspend fun sendRequest(from: UserId, to: UserId): SendRequestResult = SendRequestResult.Sent
        override suspend fun accept(me: UserId, other: UserId): RespondResult = RespondResult.Ok
        override suspend fun decline(me: UserId, other: UserId): RespondResult = RespondResult.Ok
        override suspend fun block(me: UserId, other: UserId) = Unit
        override suspend fun listFriends(userId: UserId): List<UserId> = emptyList()
        override suspend fun listBlockedUserIds(userId: UserId): Set<UserId> = blocked
        override suspend fun listIncomingRequests(userId: UserId): List<UserId> = emptyList()
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object StubEquipment : com.dangerfield.cards.server.domain.EquipmentRepository {
        override suspend fun listEquipped(userId: UserId) =
            emptyList<com.dangerfield.cards.server.domain.EquippedItem>()
        override suspend fun equip(userId: UserId, productId: String, newUpdatedAt: Instant) =
            error("unused")
        override suspend fun unequip(userId: UserId, productId: String, opUpdatedAt: Instant) = null
    }

    private object StubProgression : com.dangerfield.cards.server.domain.ProgressionRepository {
        override suspend fun findOrCreateResult(userId: UserId) = error("unused")
        override suspend fun find(userId: UserId): com.dangerfield.cards.server.domain.UserProgression? = null
        override suspend fun applyXp(
            userId: UserId,
            idempotencyKey: String,
            deltaXp: Long,
            source: String,
            mode: String,
            handId: String?,
            wasBoosted: Boolean,
        ) = error("unused")
        override suspend fun recentEvents(userId: UserId, limit: Int) =
            emptyList<com.dangerfield.cards.server.domain.XpEvent>()
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object StubProfiles : ProfileRepository {
        override suspend fun findById(userId: UserId): Profile? = null
        override suspend fun findOrCreate(userId: UserId): Profile = Profile(
            userId = userId,
            displayName = "P-${userId.value.toString().take(4)}",
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
        ): UpdateProfileOutcome = error("unused")
        override suspend fun delete(userId: UserId) = Unit
        override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? = null
        override suspend fun findInstallSiblings(installId: UUID, currentUserId: UserId): List<UserId> = emptyList()
    }
}
