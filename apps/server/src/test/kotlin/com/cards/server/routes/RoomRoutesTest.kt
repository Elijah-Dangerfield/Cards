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
 *  - leave is idempotent: 204 whether you're a member, not a member,
 *    or the room is already gone
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
    fun create_withBuyIn_returnsDerivedStakes() = runTest {
        withRooms { client ->
            val resp = client.createRoom(asUser = host, buyIn = 20_000)
            assertEquals(HttpStatusCode.OK, resp.status)
            val room = resp.body<CreateRoomResponse>().room
            assertEquals(20_000, room.buyIn)
            assertEquals(200, room.bigBlind)
            assertEquals(100, room.smallBlind)
        }
    }

    @Test
    fun create_400_whenBuyInTooSmall() = runTest {
        withRooms { client ->
            val resp = client.createRoom(asUser = host, buyIn = 1)
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(resp.bodyAsText().contains("invalid_buy_in"))
        }
    }

    @Test
    fun create_default_isPrivate() = runTest {
        withRooms { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            assertEquals(RoomVisibilityDto.Private, room.visibility, "no visibility → a code-only Private room")
        }
    }

    @Test
    fun create_openToAnyone_returnsAnOpenRoom() = runTest {
        withRooms { client ->
            val resp = client.createRoom(asUser = host, visibility = "Open")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(RoomVisibilityDto.Open, resp.body<CreateRoomResponse>().room.visibility)
        }
    }

    @Test
    fun create_carriesHostTableCosmetics_ontoTheRoomSnapshot() = runTest {
        // SHOP-3: the host's equipped felt + card back ride the create body onto the
        // room so every player renders the host's look. Stored opaquely + echoed.
        withRooms { client ->
            val room = client.createRoom(
                asUser = host,
                feltProductId = "felt_royal_red",
                cardBackProductId = "cardback_gold",
            ).body<CreateRoomResponse>().room
            assertEquals("felt_royal_red", room.feltProductId)
            assertEquals("cardback_gold", room.cardBackProductId)
        }
    }

    @Test
    fun create_blankCosmetics_readAsNoOverride() = runTest {
        withRooms { client ->
            val room = client.createRoom(
                asUser = host,
                feltProductId = "  ",
                cardBackProductId = "",
            ).body<CreateRoomResponse>().room
            assertNull(room.feltProductId)
            assertNull(room.cardBackProductId)
        }
    }

    @Test
    fun create_400_whenClientTriesToMintAPublicTable() = runTest {
        withRooms { client ->
            // Only the matchmaker mints Public tables; a client can't.
            val resp = client.createRoom(asUser = host, visibility = "Public")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(resp.bodyAsText().contains("invalid_visibility"))
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
    fun join_400_whenBuyInExceedsWalletBalance() = runTest {
        withRooms(balanceFor = { uid -> if (uid == alice) 5_000L else Long.MAX_VALUE }) { client ->
            val room = client.createRoom(asUser = host, buyIn = 20_000).body<CreateRoomResponse>().room
            val resp = client.joinRoom(room.code, asUser = alice)
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(resp.bodyAsText().contains("insufficient_balance"))
        }
    }

    @Test
    fun join_succeeds_whenBalanceCoversBuyIn() = runTest {
        withRooms(balanceFor = { 20_000L }) { client ->
            val room = client.createRoom(asUser = host, buyIn = 20_000).body<CreateRoomResponse>().room
            val resp = client.joinRoom(room.code, asUser = alice)
            assertEquals(HttpStatusCode.OK, resp.status)
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
    fun leave_200_cashesOutSynchronously_andReturnsSettledBalance() = runTest {
        // MP-29: a member holding a table stack must have it cashed out *in* the
        // leave call, and the DELETE returns the authoritative post-settlement
        // balance so the client's leave *is* the wallet reconcile — no racy sync.
        withSettlingRooms { client, tableSessions, wallets ->
            wallets.setBalance(alice, 10_000L)
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            client.joinRoom(room.code, asUser = alice)
            // Seat alice: buy-in debits the wallet; leaving with no live stack
            // refunds the full funded amount, so the balance returns to 10_000.
            val sit = tableSessions.sitDown(
                userId = alice,
                roomCode = room.code,
                buyIn = 2_000L,
                enforceEntryBar = false,
            )
            assertTrue(sit is com.dangerfield.cards.server.domain.SitDownResult.Funded)

            val resp = client.leaveRoom(room.code, asUser = alice)
            assertEquals(HttpStatusCode.OK, resp.status)
            val settled = resp.body<LeaveRoomResponse>()
            assertEquals(10_000L, settled.balance, "leave returns the post-cash-out balance")
            assertEquals(10_000L, wallets.balanceOf(alice), "wallet was actually settled")
            // Idempotent: a re-issued leave once the session is closed settles
            // nothing and falls back to 204 (the dead-back-button guard).
            assertEquals(HttpStatusCode.NoContent, client.leaveRoom(room.code, asUser = alice).status)
        }
    }

    @Test
    fun leave_204_whenNothingToSettle() = runTest {
        // A member who never sat at a real-chip table (lobby-only leave) has no
        // stack to cash out — the leave settles nothing, so it stays a 204.
        withSettlingRooms { client, _, _ ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            client.joinRoom(room.code, asUser = alice)
            assertEquals(HttpStatusCode.NoContent, client.leaveRoom(room.code, asUser = alice).status)
        }
    }

    @Test
    fun leave_204_whenNotInRoom_isIdempotent() = runTest {
        withRooms { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            val resp = client.leaveRoom(room.code, asUser = alice)
            assertEquals(HttpStatusCode.NoContent, resp.status)
        }
    }

    @Test
    fun leave_204_whenRoomGone_isIdempotent() = runTest {
        withRooms { client ->
            val room = client.createRoom(asUser = host).body<CreateRoomResponse>().room
            assertEquals(HttpStatusCode.NoContent, client.leaveRoom(room.code, asUser = host).status)
            val reLeave = client.leaveRoom(room.code, asUser = host)
            assertEquals(HttpStatusCode.NoContent, reLeave.status)
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
        balanceFor: (UserId) -> Long = { Long.MAX_VALUE },
        block: suspend (RoomsTestClient) -> Unit,
    ) {
        val rooms = InMemoryRoomService(
            clock = FixedClock(),
            random = Random(0L),
        )
        val profiles = FakeProfileRepository(profileNameFor)
        val wallets = FixedBalanceWalletRepository(balanceFor)
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing {
                    roomRoutes(
                        rooms = rooms,
                        profiles = profiles,
                        wallets = wallets,
                        gameSessions = newRegistry(),
                        tableSessions = InMemoryTestTableSessionService(InMemoryTestWalletRepository()),
                    )
                }
            }
            val raw = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            block(RoomsTestClient(this, raw))
        }
    }

    /**
     * MP-29 variant that wires a real settlement stack: an
     * [InMemoryTestTableSessionService] over a shared wallet, so a member who
     * sat down (has an open table session) cashes out synchronously on leave and
     * the DELETE returns the authoritative post-settlement balance. Exposes the
     * table-session service + wallet so the test can seat the leaver first.
     */
    private suspend fun withSettlingRooms(
        block: suspend (RoomsTestClient, InMemoryTestTableSessionService, InMemoryTestWalletRepository) -> Unit,
    ) {
        val rooms = InMemoryRoomService(clock = FixedClock(), random = Random(0L))
        val profiles = FakeProfileRepository { uid -> "P-${uid.value.toString().take(4)}" }
        val wallets = FixedBalanceWalletRepository { Long.MAX_VALUE }
        val settlementWallets = InMemoryTestWalletRepository()
        val tableSessions = InMemoryTestTableSessionService(settlementWallets)
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing {
                    roomRoutes(
                        rooms = rooms,
                        profiles = profiles,
                        wallets = wallets,
                        gameSessions = newRegistry(),
                        tableSessions = tableSessions,
                    )
                }
            }
            val raw = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            block(RoomsTestClient(this, raw), tableSessions, settlementWallets)
        }
    }

    private inner class RoomsTestClient(
        private val app: ApplicationTestBuilder,
        private val raw: io.ktor.client.HttpClient,
    ) {
        suspend fun createRoom(
            maxSeats: Int? = null,
            buyIn: Long? = null,
            visibility: String? = null,
            feltProductId: String? = null,
            cardBackProductId: String? = null,
            asUser: UserId?,
        ): HttpResponse =
            raw.post("/v1/rooms") {
                contentType(ContentType.Application.Json)
                bearer(asUser)
                setBody(
                    CreateRoomRequest(
                        maxSeats = maxSeats,
                        buyIn = buyIn,
                        visibility = visibility,
                        feltProductId = feltProductId,
                        cardBackProductId = cardBackProductId,
                    ),
                )
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

    private class FixedBalanceWalletRepository(
        private val balanceFor: (UserId) -> Long,
    ) : com.dangerfield.cards.server.domain.WalletRepository {
        override suspend fun findOrCreateResult(
            userId: UserId,
        ): com.dangerfield.cards.server.domain.FindOrCreateResult =
            com.dangerfield.cards.server.domain.FindOrCreateResult(
                wallet = wallet(userId),
                created = false,
            )

        override suspend fun find(userId: UserId): com.dangerfield.cards.server.domain.Wallet = wallet(userId)

        override suspend fun apply(
            userId: UserId,
            idempotencyKey: String,
            delta: Long,
            reason: String,
        ): com.dangerfield.cards.server.domain.ApplyOutcome = error("not used in this test")

        override suspend fun recentEvents(
            userId: UserId,
            limit: Int,
        ): List<com.dangerfield.cards.server.domain.WalletEvent> = emptyList()

        override suspend fun hasIapSpend(userId: UserId): Boolean = false

        override suspend fun deleteAllForUser(userId: UserId) { /* no-op */ }

        private fun wallet(userId: UserId) = com.dangerfield.cards.server.domain.Wallet(
            userId = userId,
            balance = balanceFor(userId),
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
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
