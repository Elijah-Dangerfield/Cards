package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RespondResult
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.SendRequestResult
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.http.ClientContext
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
    fun find_atTheDefaultBand_withAStarterGrant_seatsAtTheThousandChipTier() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        // A fresh 10,000 grant searching a band that straddles the 1k and 5k tiers.
        // Pre-fix this snapped to 5,000 (needs 20k to sit) and stranded the player;
        // affordable matchmaking snaps to the 1,000 tier they can actually fund.
        withApp(rooms, walletBalance = 10_000) { client ->
            val body = client.find(jwt(UUID.randomUUID()), min = 1_000, max = 5_000)
                .body<MatchmakingFindResponse>()
            assertTrue(body.created, "first searcher opens a fresh table")
            assertEquals(1_000, body.room.buyIn, "snaps to the affordable anchor tier, not 5,000")
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
    fun find_belowEntryBarForSmallestTable_returns400_insufficientBalance() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        // Wallet holds 3k; the smallest table in range (1k) needs 4× = 4,000 to
        // clear the entry bar, so no table in the range is sit-able — the server
        // fences it rather than matching to a table the sit-down escrow bounces.
        withApp(rooms, walletBalance = 3_000) { client ->
            val resp = client.find(jwt(UUID.randomUUID()), min = 1_000, max = 5_000)
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(
                resp.bodyAsText().contains("insufficient_balance"),
                "the problem code distinguishes affordability from a malformed range",
            )
        }
    }

    @Test
    fun find_whenBalanceClearsTheSmallestTablesEntryBar_returns200() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        // 4,000 clears the 4× bar for the 1k floor exactly — the smallest table is
        // sit-able, so the search is allowed even though the top of the range isn't.
        withApp(rooms, walletBalance = 4_000) { client ->
            val resp = client.find(jwt(UUID.randomUUID()), min = 1_000, max = 5_000)
            assertEquals(HttpStatusCode.OK, resp.status)
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

    @Test
    fun playBots_whenARealPlayerIsPresent_returns409_realPlayerPresent() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val token = jwt(UUID.randomUUID())
            // I open a table; a second real human finds the same tier and lands here.
            val code = client.find(token, 5_000, 5_000).body<MatchmakingFindResponse>().room.code
            client.find(jwt(UUID.randomUUID()), 5_000, 5_000) // 2nd human joins my table

            // A real player is here now, so the bot fallback must refuse — we play
            // the human, not bots.
            val resp = client.post("/v1/matchmaking/$code/play-bots") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.Conflict, resp.status)
        }
    }

    @Test
    fun botFallbackTable_staysFindable_soALaterSearcherRescuesTheLonePlayer() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val lonely = jwt(UUID.randomUUID())
            val code = client.find(lonely, 5_000, 5_000).body<MatchmakingFindResponse>().room.code
            // The lonely player gives up and plays bots — 1 human + 3 bots, dealing.
            client.post("/v1/matchmaking/$code/play-bots") {
                header(HttpHeaders.Authorization, "Bearer $lonely")
            }

            // A later searcher at the same tier must still land HERE (rescue), not a
            // fresh table — a bot-fallback table stays matchmaking inventory.
            val rescuer = client.find(jwt(UUID.randomUUID()), 5_000, 5_000).body<MatchmakingFindResponse>()
            assertFalse(rescuer.created, "the searcher joins the bot table, not a new one")
            assertEquals(code, rescuer.room.code)
            assertEquals(2, rescuer.room.members.count { !it.isBot }, "two humans now share the table")
        }
    }

    @Test
    fun playBots_doubleTap_isIdempotent_doesNotOverfill() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val token = jwt(UUID.randomUUID())
            val code = client.find(token, 5_000, 5_000).body<MatchmakingFindResponse>().room.code
            client.post("/v1/matchmaking/$code/play-bots") { header(HttpHeaders.Authorization, "Bearer $token") }

            val second = client.post("/v1/matchmaking/$code/play-bots") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, second.status, "a double-tap is benign")
            assertEquals(4, second.body<MatchmakingFindResponse>().room.members.size, "never overfills past the target")
        }
    }

    @Test
    fun find_atADifferentTier_opensANewTable_ratherThanJoiningTheWrongStakes() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val small = client.find(jwt(UUID.randomUUID()), 1_000, 1_000).body<MatchmakingFindResponse>()
            val big = client.find(jwt(UUID.randomUUID()), 100_000, 100_000).body<MatchmakingFindResponse>()
            assertTrue(big.created, "a different tier never seats you into the wrong stakes")
            assertFalse(big.room.code == small.room.code)
        }
    }

    @Test
    fun reFind_sameUserSameTier_isIdempotent_returnsTheSameTable() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val me = jwt(UUID.randomUUID())
            val first = client.find(me, 5_000, 5_000).body<MatchmakingFindResponse>()
            val again = client.find(me, 5_000, 5_000).body<MatchmakingFindResponse>()
            assertEquals(first.room.code, again.room.code, "a double-tap doesn't mint a second table")
            assertEquals(1, again.room.members.size, "still just me, not seated twice")
        }
    }

    @Test
    fun find_seatsASearcherIntoAHumanHostedOpenRoom() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        // A human opens their room to anyone at the 5k tier.
        val open = (
            rooms.create(
                hostUserId = UserId(UUID.randomUUID()),
                hostName = "Host",
                buyIn = 5_000,
                visibility = com.dangerfield.cards.server.domain.RoomVisibility.Open,
            ) as com.dangerfield.cards.server.domain.CreateResult.Success
            ).room

        withApp(rooms) { client ->
            val found = client.find(jwt(UUID.randomUUID()), 5_000, 5_000).body<MatchmakingFindResponse>()
            assertFalse(found.created, "the searcher is seated into the existing Open room, not a fresh table")
            assertEquals(open.code, found.room.code)
        }
    }

    @Test
    fun candidates_listsQualifyingTables_withoutJoining() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            // One searcher opens a 5k table.
            val opened = client.find(jwt(UUID.randomUUID()), 5_000, 5_000).body<MatchmakingFindResponse>()

            val resp = client.candidates(jwt(UUID.randomUUID()), 5_000, 5_000)
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MatchmakingCandidatesResponse>()
            assertEquals(listOf(opened.room.code), body.rooms.map { it.room.code })
            // Read-only: nobody new was seated into the table.
            assertEquals(1, body.rooms.single().room.members.size, "browsing doesn't seat the browser")
        }
    }

    @Test
    fun candidates_flagsAffordabilityPerTable_showingUnaffordableOnesDisabled() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        // A 1k table (affordable at a 5k balance) and a 25k table (needs 100k).
        // Minted directly as Open tables so a wealthy searcher isn't needed to
        // create the high-stakes one. Both are in the browse range and must be
        // returned — the unaffordable one is shown disabled with its "need X chips"
        // number, never silently dropped.
        rooms.create(
            hostUserId = UserId(UUID.randomUUID()), hostName = "Small", buyIn = 1_000,
            visibility = com.dangerfield.cards.server.domain.RoomVisibility.Open,
        )
        rooms.create(
            hostUserId = UserId(UUID.randomUUID()), hostName = "Big", buyIn = 25_000,
            visibility = com.dangerfield.cards.server.domain.RoomVisibility.Open,
        )
        withApp(rooms, walletBalance = 5_000) { client ->
            val body = client.candidates(jwt(UUID.randomUUID()), 1_000, 25_000)
                .body<MatchmakingCandidatesResponse>()
            assertEquals(2, body.rooms.size, "both in-range tables are listed")
            val affordable = body.rooms.single { it.room.buyIn == 1_000L }
            val unaffordable = body.rooms.single { it.room.buyIn == 25_000L }
            assertTrue(affordable.affordable, "5k clears the 4× bar for a 1k table")
            assertFalse(unaffordable.affordable, "5k can't clear the 4× bar for a 25k table")
            assertEquals(100_000, unaffordable.minBalanceToSit, "25k needs 4× = 100,000 to sit")
        }
    }

    @Test
    fun candidates_withNoMatch_returnsEmptyList() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            val body = client.candidates(jwt(UUID.randomUUID()), 5_000, 5_000).body<MatchmakingCandidatesResponse>()
            assertTrue(body.rooms.isEmpty(), "no table at this tier yet")
        }
    }

    @Test
    fun candidates_neverOffersATableWithABlockedMember() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        val enemy = UUID.randomUUID()
        withApp(rooms, friends = BlockingFriends(setOf(UserId(enemy)))) { client ->
            client.find(jwt(enemy), 5_000, 5_000)
            val body = client.candidates(jwt(UUID.randomUUID()), 5_000, 5_000).body<MatchmakingCandidatesResponse>()
            assertTrue(body.rooms.isEmpty(), "the enemy's table is filtered out of the chooser")
        }
    }

    @Test
    fun candidates_withInvertedRange_returns400() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            assertEquals(HttpStatusCode.BadRequest, client.candidates(jwt(UUID.randomUUID()), 100_000, 1_000).status)
        }
    }

    @Test
    fun candidates_aboveBalance_returns200_notFenced_soUnaffordableTablesStillShow() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        // Unlike find, the chooser never fences on balance — it lists in-range
        // tables (flagged) so the client can show the unaffordable ones disabled.
        withApp(rooms, walletBalance = 5_000) { client ->
            val resp = client.candidates(jwt(UUID.randomUUID()), 1_000, 100_000)
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun candidates_withoutJwt_returns401() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.candidates(bearer = null, min = 1_000, max = 100_000).status)
        }
    }

    @Test
    fun subsidyBudget_freshPlayer_returnsTheFullCapAsRemaining() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms, subsidyCap = 25_000, subsidyGranted = 0) { client ->
            val resp = client.subsidyBudget(jwt(UUID.randomUUID()))
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<SubsidyBudgetResponse>()
            assertEquals(0, body.grantedToday)
            assertEquals(25_000, body.cap)
            assertEquals(25_000, body.remaining)
        }
    }

    @Test
    fun subsidyBudget_partlyDrawnDown_reportsTheRemainingHeadroom() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms, subsidyCap = 25_000, subsidyGranted = 18_000) { client ->
            val body = client.subsidyBudget(jwt(UUID.randomUUID())).body<SubsidyBudgetResponse>()
            assertEquals(18_000, body.grantedToday)
            assertEquals(7_000, body.remaining)
        }
    }

    @Test
    fun subsidyBudget_atOrOverCap_clampsRemainingToZero() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms, subsidyCap = 25_000, subsidyGranted = 30_000) { client ->
            val body = client.subsidyBudget(jwt(UUID.randomUUID())).body<SubsidyBudgetResponse>()
            assertEquals(0, body.remaining, "draw-down past the cap never reports negative headroom")
        }
    }

    @Test
    fun subsidyBudget_withoutJwt_returns401() = runTest {
        val rooms = InMemoryRoomService(clock = clock, random = Random(0L))
        withApp(rooms) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.subsidyBudget(bearer = null).status)
        }
    }

    // --- harness ------------------------------------------------------------

    private suspend fun withApp(
        rooms: RoomService,
        friends: FriendRepository = BlockingFriends(emptySet()),
        // Effectively unlimited by default so buy-in tests exercise matchmaking,
        // not the affordability fence; the insufficient-balance test lowers it.
        walletBalance: Long = Long.MAX_VALUE,
        subsidyCap: Long = 25_000L,
        subsidyGranted: Long = 0L,
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                val registry = com.dangerfield.cards.server.game.DefaultGameSessionRegistry(
                    com.dangerfield.cards.server.game.NoOpSessionSnapshotStore(),
                    Clock.System,
                )
                val wallets = InMemoryTestWalletRepository(defaultBalance = walletBalance)
                routing {
                    matchmakingRoutes(
                        rooms, friends, StubProfiles, registry,
                        InMemoryTestTableSessionService(wallets, subsidyCap, subsidyGranted),
                        StubEquipment, StubProgression,
                        wallets,
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

    private suspend fun io.ktor.client.HttpClient.candidates(bearer: String?, min: Long, max: Long) =
        get("/v1/matchmaking/candidates?minBuyIn=$min&maxBuyIn=$max") {
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    private suspend fun io.ktor.client.HttpClient.subsidyBudget(bearer: String?) =
        get("/v1/matchmaking/subsidy-budget") {
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
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
        override suspend fun applyXpBatch(
            userId: UserId,
            events: List<com.dangerfield.cards.server.domain.XpEventInput>,
        ) = error("unused")
        override suspend fun recentEvents(userId: UserId, limit: Int) =
            emptyList<com.dangerfield.cards.server.domain.XpEvent>()
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object StubProfiles : ProfileRepository {
        override suspend fun findById(userId: UserId): Profile? = null
        override suspend fun findOrCreate(userId: UserId, signupPlatform: ClientContext.Platform): Profile = Profile(
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
