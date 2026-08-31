package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.data.InMemoryRoomService
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.NoOpHandsFinishedRepository
import com.dangerfield.cards.server.domain.NoOpRecentOpponentsRepository
import com.dangerfield.cards.server.domain.OwnedItem
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.CreateMessageOutcome
import com.dangerfield.cards.server.domain.CreateResult
import com.dangerfield.cards.server.domain.JoinResult
import com.dangerfield.cards.server.domain.MessageSweepResult
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomSweepResult
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.UpdateDisplayNameResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.FindOrCreateResult
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletEvent
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.http.ClientContext
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Route-level tests for `GET /v1/me`. The repository is faked; we exercise
 * the HTTP-layer + JWT validation concerns end-to-end.
 *
 * Production verifies tokens via JWKS (asymmetric ES256). Tests mint
 * tokens we control with HS256 and pass a matching [JWTVerifier] to the
 * test-only `installAuthenticationWithVerifier` entry point — keeps tests
 * fast + hermetic (no JWKS fetch over the network).
 */
@OptIn(ExperimentalTime::class)
class MeRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    @Test
    fun me_returnsProfile_whenJwtIsValid() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        callMe(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MeResponse>()
            assertEquals(userId.value.toString(), body.userId)
            assertEquals("FakeName", body.displayName)
            assertEquals("🦊", body.avatarEmoji)
            assertEquals(false, body.isAnonymous)
        }
    }

    @Test
    fun me_marksProfileAnonymous_whenJwtCarriesIsAnonymousClaim() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        callMe(repo, bearer = validJwt(isAnonymous = true)) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<MeResponse>()
            assertEquals(true, body.isAnonymous)
        }
    }

    @Test
    fun me_createsProfile_onFirstContact() = runTest {
        val repo = FakeProfileRepository(existing = null)
        callMe(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(1, repo.findOrCreateCalls)
        }
    }

    @Test
    fun me_passesCallerPlatformToTheCreatePath() = runTest {
        val repo = FakeProfileRepository(existing = null)
        callMe(repo, bearer = validJwt(), platformHeader = "ios") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf(ClientContext.Platform.iOS), repo.signupPlatforms)
        }
    }

    @Test
    fun me_recordsOtherPlatform_whenClientSendsNoPlatformHeader() = runTest {
        // A client too old to send X-Platform must still get a profile — the
        // signup-platform column is a reporting dimension, never a gate.
        val repo = FakeProfileRepository(existing = null)
        callMe(repo, bearer = validJwt(), platformHeader = null) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf(ClientContext.Platform.Other), repo.signupPlatforms)
        }
    }

    @Test
    fun me_doesNotInvokeInventoryGrants() = runTest {
        // Starter inventory now seeds inside ProfileRepository.findOrCreate
        // (V18). /v1/me must not call back into the inventory grant pathway —
        // it's reserved for achievement rewards. EmptyInventory.recordEarnedGrant
        // throws so a regression that re-introduces the call fails this test.
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        callMe(repo, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun me_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeProfileRepository(existing = null)
        callMe(repo, bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, repo.findOrCreateCalls)
        }
    }

    @Test
    fun me_returns401_whenJwtSignedWithWrongSecret() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val foreign = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("authenticated")
            .withSubject(userId.value.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("wrong-secret-wrong-secret-wrong-secret-wrong-secret"))
        callMe(repo, bearer = foreign) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun me_returns401_whenJwtIssuerIsWrong() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val foreign = JWT.create()
            .withIssuer("https://different-project.supabase.co/auth/v1")
            .withAudience("authenticated")
            .withSubject(userId.value.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(testSecret))
        callMe(repo, bearer = foreign) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun me_returns401_whenJwtAudienceIsWrong() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val foreign = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("service_role")
            .withSubject(userId.value.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(testSecret))
        callMe(repo, bearer = foreign) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun me_returns401_whenJwtIsExpired() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val expired = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("authenticated")
            .withSubject(userId.value.toString())
            .withIssuedAt(Date(System.currentTimeMillis() - 120_000))
            .withExpiresAt(Date(System.currentTimeMillis() - 60_000))
            .sign(Algorithm.HMAC256(testSecret))
        callMe(repo, bearer = expired) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun me_returns401_whenSubIsNotAUuid() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val malformed = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("authenticated")
            .withSubject("not-a-uuid")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(testSecret))
        callMe(repo, bearer = malformed) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun me_tagsInstallId_whenHeaderProvided() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val install = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        callMe(repo, bearer = validJwt(), installIdHeader = install.toString()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf(userId to install), repo.installIdCalls)
        }
    }

    @Test
    fun me_skipsInstallIdTag_whenHeaderAbsent() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        callMe(repo, bearer = validJwt(), installIdHeader = null) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(repo.installIdCalls.isEmpty(), "no header → no touchInstallId call")
        }
    }

    @Test
    fun me_skipsInstallIdTag_whenHeaderIsMalformed() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        callMe(repo, bearer = validJwt(), installIdHeader = "not-a-uuid") { resp ->
            // Malformed header is dropped silently — request still succeeds.
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(repo.installIdCalls.isEmpty(), "malformed header → no touchInstallId call")
        }
    }

    @Test
    fun me_firesInstallSweep_whenHeaderProvided() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val install = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val sweep = RecordingInstallSweep()
        callMe(
            repo,
            bearer = validJwt(),
            installIdHeader = install.toString(),
            installSweep = sweep,
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            sweep.awaitFirstCall()
            assertEquals(listOf(install to userId), sweep.calls)
        }
    }

    @Test
    fun me_skipsInstallSweep_whenHeaderAbsent() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val sweep = RecordingInstallSweep()
        callMe(
            repo,
            bearer = validJwt(),
            installIdHeader = null,
            installSweep = sweep,
        ) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            // No header → no sweep trigger; nothing to await.
            assertTrue(sweep.calls.isEmpty(), "no header → no install sweep launched")
        }
    }

    @Test
    fun patch_createsProfile_onFirstContact_thenUpdates() = runTest {
        // Fresh install: the guest-creation identity write hits PATCH before any
        // GET created the row. PATCH is now get-or-create, so it must succeed
        // (200) rather than 404 — findOrCreate runs and the update applies.
        val repo = FakeProfileRepository(existing = null)
        callPatch(repo, bearer = validJwt(), jsonBody = """{"displayName":"NewName"}""") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(repo.findOrCreateCalls >= 1, "PATCH must get-or-create the row first")
            assertEquals(1, repo.updateCalls)
        }
    }

    // ---------- Auth display-name mirror ----------

    @Test
    fun get_newAccount_mirrorsGeneratedNameIntoAuthMetadata() = runTest {
        val repo = FakeProfileRepository(existing = null, reportsCreated = true)
        val admin = RecordingDisplayNameAdmin()
        callMe(repo, bearer = validJwt(), adminClient = admin) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            admin.awaitFirstMirror()
            assertEquals(listOf(userId to "GeneratedName"), admin.mirrored)
        }
    }

    @Test
    fun get_returningAccount_doesNotTouchAuthMetadata() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val admin = RecordingDisplayNameAdmin()
        callMe(repo, bearer = validJwt(), adminClient = admin) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(admin.mirrored.isEmpty(), "an existing account's GET must not re-mirror")
        }
    }

    @Test
    fun patch_rename_mirrorsNewNameIntoAuthMetadata() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val admin = RecordingDisplayNameAdmin()
        callPatch(repo, bearer = validJwt(), jsonBody = """{"displayName":"NewName"}""", adminClient = admin) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            admin.awaitFirstMirror()
            assertEquals(listOf(userId to "NewName"), admin.mirrored)
        }
    }

    @Test
    fun patch_withoutName_doesNotTouchAuthMetadata() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val admin = RecordingDisplayNameAdmin()
        callPatch(repo, bearer = validJwt(), jsonBody = """{"avatarEmoji":"🦊"}""", adminClient = admin) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(admin.mirrored.isEmpty(), "an avatar-only patch must not touch auth metadata")
        }
    }

    @Test
    fun patch_mirrorFailure_neverFailsTheRename() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val admin = RecordingDisplayNameAdmin(
            result = UpdateDisplayNameResult.Failure(statusCode = 500, cause = null),
        )
        callPatch(repo, bearer = validJwt(), jsonBody = """{"displayName":"NewName"}""", adminClient = admin) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status, "the mirror is vanity metadata — never the request's fate")
        }
    }

    @Test
    fun patch_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeProfileRepository(existing = null)
        callPatch(repo, bearer = null, jsonBody = """{"displayName":"NewName"}""") { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, repo.findOrCreateCalls)
        }
    }

    @Test
    fun activeRooms_returnsRoomsCallerIsMemberOf() = runTest {
        val rooms = InMemoryRoomService(clock = FixedClock(), random = kotlin.random.Random(0L))
        val otherUser = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        val joined = rooms.create(userId, "Caller").let { (it as CreateResult.Success).room }
        rooms.create(otherUser, "Other")

        callActiveRooms(rooms, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<ActiveRoomsResponse>()
            assertEquals(1, body.rooms.size, "only the caller's room comes back")
            assertEquals(joined.code, body.rooms.single().code)
        }
    }

    @Test
    fun activeRooms_returnsEmpty_whenCallerHoldsNoSeats() = runTest {
        val rooms = InMemoryRoomService(clock = FixedClock(), random = kotlin.random.Random(0L))
        val otherUser = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        rooms.create(otherUser, "Other")

        callActiveRooms(rooms, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(emptyList(), resp.body<ActiveRoomsResponse>().rooms)
        }
    }

    @Test
    fun activeRooms_returns401_whenAuthHeaderMissing() = runTest {
        val rooms = InMemoryRoomService(clock = FixedClock(), random = kotlin.random.Random(0L))
        callActiveRooms(rooms, bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun meStats_returnsDistinctOpponentCount() = runTest {
        val recent = object : com.dangerfield.cards.server.domain.RecentOpponentsRepository {
            override suspend fun recordPlayedTogether(userId: UserId, opponentId: UserId) = Unit
            override suspend fun listRecent(userId: UserId, limit: Int): List<UserId> = emptyList()
            override suspend fun countDistinctOpponents(userId: UserId): Long = 7L
            override suspend fun hasPlayedWith(userId: UserId, opponentId: UserId): Boolean = false
            override suspend fun deleteAllForUser(userId: UserId) = Unit
        }
        callMeStats(recent, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(7L, resp.body<MeStatsResponse>().distinctOpponentsPlayed)
        }
    }

    @Test
    fun meStats_requiresAuth() = runTest {
        callMeStats(NoOpRecentOpponentsRepository, bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    private suspend fun callMeStats(
        recent: com.dangerfield.cards.server.domain.RecentOpponentsRepository,
        bearer: String?,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing {
                    meRoutes(repo, AlwaysSuccessAdmin, EmptyInventory, EmptyWallet, EmptyProgression, EmptyPlayStyle, EmptyPlayerStats, EmptyAchievements, NoOpHandsFinishedRepository, EmptyMessages, EmptyRooms, NoOpInstallSweep, EmptyFriends, recent)
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.get("/v1/me/stats") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(response)
        }
    }

    private suspend fun callActiveRooms(
        rooms: RoomService,
        bearer: String?,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing {
                    meRoutes(repo, AlwaysSuccessAdmin, EmptyInventory, EmptyWallet, EmptyProgression, EmptyPlayStyle, EmptyPlayerStats, EmptyAchievements, NoOpHandsFinishedRepository, EmptyMessages, rooms, NoOpInstallSweep, EmptyFriends, NoOpRecentOpponentsRepository)
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.get("/v1/me/active-rooms") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(response)
        }
    }

    private class FixedClock(private val ms: Long = 1_700_000_000_000) : kotlin.time.Clock {
        override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(ms)
    }

    // ---------- Test scaffolding ----------

    private fun validJwt(isAnonymous: Boolean = false): String {
        val builder = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("authenticated")
            .withSubject(userId.value.toString())
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        if (isAnonymous) builder.withClaim("is_anonymous", true)
        return builder.sign(Algorithm.HMAC256(testSecret))
    }

    private val testVerifier = JWT.require(Algorithm.HMAC256(testSecret))
        .withIssuer(testIssuer)
        .withAudience("authenticated")
        .build()

    private suspend fun callMe(
        repo: ProfileRepository,
        bearer: String?,
        adminClient: SupabaseAdminClient = AlwaysSuccessAdmin,
        inventory: InventoryRepository = EmptyInventory,
        installIdHeader: String? = null,
        platformHeader: String? = null,
        installSweep: com.dangerfield.cards.server.domain.OrphanInstallSweep = NoOpInstallSweep,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { meRoutes(repo, adminClient, inventory, EmptyWallet, EmptyProgression, EmptyPlayStyle, EmptyPlayerStats, EmptyAchievements, NoOpHandsFinishedRepository, EmptyMessages, EmptyRooms, installSweep, EmptyFriends, NoOpRecentOpponentsRepository) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.get("/v1/me") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                installIdHeader?.let { header(INSTALL_ID_HEADER, it) }
                platformHeader?.let { header(ClientContext.HEADER_PLATFORM, it) }
            }
            assert(response)
        }
    }

    private suspend fun callDeleteMe(
        repo: ProfileRepository,
        adminClient: SupabaseAdminClient,
        bearer: String?,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { meRoutes(repo, adminClient, EmptyInventory, EmptyWallet, EmptyProgression, EmptyPlayStyle, EmptyPlayerStats, EmptyAchievements, NoOpHandsFinishedRepository, EmptyMessages, EmptyRooms, NoOpInstallSweep, EmptyFriends, NoOpRecentOpponentsRepository) }
            }
            val response = createClient { }.delete("/v1/me") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            assert(response)
        }
    }

    private suspend fun callPatch(
        repo: ProfileRepository,
        bearer: String?,
        jsonBody: String,
        adminClient: SupabaseAdminClient = AlwaysSuccessAdmin,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits(AdminConfig(apiToken = null, orphanAnonTtlDays = 30, staleRoomTtlHours = 6))
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing {
                    meRoutes(repo, adminClient, EmptyInventory, EmptyWallet, EmptyProgression, EmptyPlayStyle, EmptyPlayerStats, EmptyAchievements, NoOpHandsFinishedRepository, EmptyMessages, EmptyRooms, NoOpInstallSweep, EmptyFriends, NoOpRecentOpponentsRepository)
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.patch("/v1/me") {
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
            assert(response)
        }
    }

    private fun fakeProfile(userId: UserId): Profile {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
        return Profile(
            userId = userId,
            displayName = "FakeName",
            avatarEmoji = "🦊",
            avatarBackgroundColor = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private class FakeProfileRepository(
        private val existing: Profile?,
        private val reportsCreated: Boolean = false,
    ) : ProfileRepository {
        var findOrCreateCalls: Int = 0
            private set
        var deleteCalls: Int = 0
            private set
        var updateCalls: Int = 0
            private set
        val installIdCalls: MutableList<Pair<UserId, UUID>> = mutableListOf()
        val signupPlatforms: MutableList<ClientContext.Platform> = mutableListOf()

        override suspend fun findById(userId: UserId): Profile? = existing

        override suspend fun findOrCreate(
            userId: UserId,
            signupPlatform: ClientContext.Platform,
        ): Profile {
            findOrCreateCalls++
            signupPlatforms += signupPlatform
            return existing ?: Profile(
                userId = userId,
                displayName = "GeneratedName",
                avatarEmoji = "🦊",
                avatarBackgroundColor = null,
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            )
        }

        override suspend fun findOrCreateResult(
            userId: UserId,
            signupPlatform: ClientContext.Platform,
        ): com.dangerfield.cards.server.domain.FindOrCreateProfileResult =
            com.dangerfield.cards.server.domain.FindOrCreateProfileResult(
                profile = findOrCreate(userId, signupPlatform),
                created = reportsCreated,
            )

        override suspend fun update(
            userId: UserId,
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): com.dangerfield.cards.server.domain.UpdateProfileOutcome {
            updateCalls++
            val base = existing ?: findOrCreate(userId)
            return com.dangerfield.cards.server.domain.UpdateProfileOutcome.Success(
                if (displayName != null) base.copy(displayName = displayName) else base,
            )
        }

        override suspend fun delete(userId: UserId) {
            deleteCalls++
        }

        override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? {
            installIdCalls += userId to installId
            return null
        }

        override suspend fun findInstallSiblings(
            installId: UUID,
            currentUserId: UserId,
        ): List<UserId> = emptyList()
    }

    private object AlwaysSuccessAdmin : SupabaseAdminClient {
        override suspend fun deleteUser(userId: UserId): DeleteUserResult = DeleteUserResult.Success
        override suspend fun listAnonymousUsersOlderThan(olderThan: kotlin.time.Instant): List<UserId> = emptyList()
        override suspend fun updateUserDisplayName(userId: UserId, displayName: String): UpdateDisplayNameResult =
            UpdateDisplayNameResult.Success
    }

    /**
     * Records display-name mirrors. The mirror is fired off the request path
     * (app.launch), so tests await the deferred rather than asserting inline.
     */
    private class RecordingDisplayNameAdmin(
        private val result: UpdateDisplayNameResult = UpdateDisplayNameResult.Success,
    ) : SupabaseAdminClient {
        val mirrored: MutableList<Pair<UserId, String>> = mutableListOf()
        private val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
        suspend fun awaitFirstMirror() = completion.await()
        override suspend fun deleteUser(userId: UserId): DeleteUserResult = DeleteUserResult.Success
        override suspend fun listAnonymousUsersOlderThan(olderThan: kotlin.time.Instant): List<UserId> = emptyList()
        override suspend fun updateUserDisplayName(userId: UserId, displayName: String): UpdateDisplayNameResult {
            mirrored += userId to displayName
            completion.complete(Unit)
            return result
        }
    }

    private object NoOpInstallSweep : com.dangerfield.cards.server.domain.OrphanInstallSweep {
        override suspend fun run(
            currentInstallId: UUID,
            currentUserId: UserId,
        ): com.dangerfield.cards.server.domain.InstallSweepResult =
            com.dangerfield.cards.server.domain.InstallSweepResult(0, 0, 0, 0, false)
    }

    private class RecordingInstallSweep : com.dangerfield.cards.server.domain.OrphanInstallSweep {
        val calls: MutableList<Pair<UUID, UserId>> = mutableListOf()
        private val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
        suspend fun awaitFirstCall() = completion.await()
        override suspend fun run(
            currentInstallId: UUID,
            currentUserId: UserId,
        ): com.dangerfield.cards.server.domain.InstallSweepResult {
            calls += currentInstallId to currentUserId
            completion.complete(Unit)
            return com.dangerfield.cards.server.domain.InstallSweepResult(0, 0, 0, 0, false)
        }
    }

    private class StubAdmin(val result: DeleteUserResult) : SupabaseAdminClient {
        var calls: Int = 0
            private set

        override suspend fun deleteUser(userId: UserId): DeleteUserResult {
            calls++
            return result
        }
        override suspend fun listAnonymousUsersOlderThan(olderThan: kotlin.time.Instant): List<UserId> = emptyList()
        override suspend fun updateUserDisplayName(userId: UserId, displayName: String): UpdateDisplayNameResult =
            UpdateDisplayNameResult.Success
    }

    @Test
    fun delete_returns204_andCleansLocalProfile_whenAdminSucceeds() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val admin = StubAdmin(DeleteUserResult.Success)
        callDeleteMe(repo, admin, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.NoContent, resp.status)
            assertEquals(1, admin.calls)
            assertEquals(1, repo.deleteCalls)
        }
    }

    @Test
    fun delete_returns204_whenSupabaseAlreadyHadNoSuchUser() = runTest {
        val repo = FakeProfileRepository(existing = null)
        val admin = StubAdmin(DeleteUserResult.AlreadyGone)
        callDeleteMe(repo, admin, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.NoContent, resp.status)
            assertEquals(1, repo.deleteCalls)
        }
    }

    @Test
    fun delete_returns503_whenServiceRoleKeyNotConfigured() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val admin = StubAdmin(DeleteUserResult.NotConfigured)
        callDeleteMe(repo, admin, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertEquals(0, repo.deleteCalls, "local profile must not be touched if admin call short-circuits")
            assertTrue(resp.bodyAsText().contains("delete_not_configured"))
        }
    }

    @Test
    fun delete_returns503_whenAdminCallFails() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val admin = StubAdmin(DeleteUserResult.Failure(statusCode = 500, cause = null))
        callDeleteMe(repo, admin, bearer = validJwt()) { resp ->
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertEquals(0, repo.deleteCalls, "local profile must not be touched until admin call succeeds")
        }
    }

    @Test
    fun delete_returns401_whenAuthHeaderMissing() = runTest {
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val admin = StubAdmin(DeleteUserResult.Success)
        callDeleteMe(repo, admin, bearer = null) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, admin.calls)
            assertEquals(0, repo.deleteCalls)
        }
    }

    @Test
    fun delete_succeeds_whenJwtIsAnonymous() = runTest {
        // Anonymous accounts are deletable too — the admin delete fires and the
        // local data is cleaned, same as a claimed account.
        val repo = FakeProfileRepository(existing = fakeProfile(userId))
        val admin = StubAdmin(DeleteUserResult.Success)
        callDeleteMe(repo, admin, bearer = validJwt(isAnonymous = true)) { resp ->
            assertEquals(HttpStatusCode.NoContent, resp.status)
            assertEquals(1, admin.calls, "admin delete fires for anon too")
            assertEquals(1, repo.deleteCalls)
        }
    }

    private object EmptyInventory : InventoryRepository {
        override suspend fun listOwned(userId: UserId): List<OwnedItem> = emptyList()
        override suspend fun recordPurchase(
            userId: UserId,
            productId: String,
            costChipsAtPurchase: Long,
            purchasedAt: kotlin.time.Instant,
        ): OwnedItem = error("unused")

        override suspend fun recordEarnedGrant(
            userId: UserId,
            productId: String,
            grantedAt: kotlin.time.Instant,
        ): OwnedItem = error("/v1/me must not grant inventory — starter kit seeds via ProfileRepository.findOrCreate (V18)")

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object EmptyWallet : WalletRepository {
        override suspend fun findOrCreateResult(userId: UserId): FindOrCreateResult = error("unused")
        override suspend fun find(userId: UserId): Wallet? = null
        override suspend fun apply(
            userId: UserId,
            idempotencyKey: String,
            delta: Long,
            reason: String,
        ): ApplyOutcome = error("unused")

        override suspend fun recentEvents(userId: UserId, limit: Int): List<WalletEvent> = emptyList()
        override suspend fun hasIapSpend(userId: UserId): Boolean = false
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object EmptyProgression : com.dangerfield.cards.server.domain.ProgressionRepository {
        override suspend fun findOrCreateResult(userId: UserId) = error("unused")
        override suspend fun find(userId: UserId): com.dangerfield.cards.server.domain.UserProgression? = null
        override suspend fun applyXpBatch(
            userId: UserId,
            events: List<com.dangerfield.cards.server.domain.XpEventInput>,
        ) = error("unused")

        override suspend fun recentEvents(
            userId: UserId,
            limit: Int,
        ): List<com.dangerfield.cards.server.domain.XpEvent> = emptyList()

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object EmptyPlayStyle : com.dangerfield.cards.server.domain.PlayStyleRepository {
        override suspend fun findOrCreateAggregate(userId: UserId) = error("unused")
        override suspend fun find(userId: UserId): com.dangerfield.cards.server.domain.UserPlayStyleAggregate? = null
        override suspend fun applyHand(
            userId: UserId,
            hand: com.dangerfield.cards.server.domain.PlayStyleHand,
        ) = error("unused")

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object EmptyPlayerStats : com.dangerfield.cards.server.domain.PlayerStatsRepository {
        override suspend fun findOrCreate(userId: UserId) = error("unused")
        override suspend fun find(userId: UserId): com.dangerfield.cards.server.domain.PlayerStats? = null
        override suspend fun applyHand(
            userId: UserId,
            facts: com.dangerfield.cards.libraries.achievements.HandFacts,
        ) = error("unused")

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object EmptyAchievements : com.dangerfield.cards.server.domain.AchievementRepository {
        override suspend fun recordEarned(
            userId: UserId,
            achievementId: String,
            earnedAt: kotlin.time.Instant,
        ) = error("unused")

        override suspend fun listEarned(
            userId: UserId,
        ): List<com.dangerfield.cards.server.domain.EarnedAchievement> = emptyList()

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object EmptyFriends : com.dangerfield.cards.server.domain.FriendRepository {
        override suspend fun sendRequest(
            from: UserId,
            to: UserId,
        ): com.dangerfield.cards.server.domain.SendRequestResult = error("unused")

        override suspend fun accept(
            me: UserId,
            other: UserId,
        ): com.dangerfield.cards.server.domain.RespondResult = error("unused")

        override suspend fun decline(
            me: UserId,
            other: UserId,
        ): com.dangerfield.cards.server.domain.RespondResult = error("unused")

        override suspend fun block(me: UserId, other: UserId) = Unit

        override suspend fun listFriends(userId: UserId): List<UserId> = emptyList()

        override suspend fun listBlockedUserIds(userId: UserId): Set<UserId> = emptySet()

        override suspend fun listIncomingRequests(userId: UserId): List<UserId> = emptyList()

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private object EmptyRooms : RoomService {
        override suspend fun create(
            hostUserId: UserId,
            hostName: String,
            maxSeats: Int,
            hostAvatarEmoji: String,
            hostAvatarBackgroundColor: String?,
            buyIn: Long,
            visibility: com.dangerfield.cards.server.domain.RoomVisibility,
            feltProductId: String?,
            cardBackProductId: String?,
        ): CreateResult = error("unused")
        override suspend fun findOrJoinPublic(
            userId: UserId,
            name: String,
            minBuyIn: Long,
            maxBuyIn: Long,
            blockedUserIds: Set<UserId>,
            callerBalance: Long,
            avatarEmoji: String,
            avatarBackgroundColor: String?,
        ): com.dangerfield.cards.server.domain.MatchmakingResult = error("unused")
        override suspend fun findPublicCandidates(
            userId: UserId,
            minBuyIn: Long,
            maxBuyIn: Long,
            blockedUserIds: Set<UserId>,
        ): List<com.dangerfield.cards.server.domain.Room> = error("unused")
        override suspend fun join(
            code: String,
            userId: UserId,
            name: String,
            avatarEmoji: String,
            avatarBackgroundColor: String?,
        ): JoinResult = error("unused")
        override suspend fun leave(code: String, userId: UserId): com.dangerfield.cards.server.domain.LeaveResult =
            error("unused")
        override suspend fun addBot(
            code: String,
            requestedBy: UserId,
            difficulty: com.dangerfield.cards.libraries.bots.BotDifficulty,
            seatIndex: Int?,
            revealed: Boolean,
        ): com.dangerfield.cards.server.domain.AddBotResult = error("unused")
        override suspend fun fillBotsUpTo(
            code: String,
            requestedBy: UserId,
            target: Int,
            difficulty: com.dangerfield.cards.libraries.bots.BotDifficulty,
            revealed: Boolean,
        ): com.dangerfield.cards.server.domain.AddBotResult = error("unused")
        override suspend fun removeBot(
            code: String,
            requestedBy: UserId,
            botUserId: UserId,
        ): com.dangerfield.cards.server.domain.RemoveBotResult = error("unused")
        override suspend fun trimBotForNewHumans(code: String, handNumber: Int): UserId? = null
        override suspend fun markConnected(code: String, userId: UserId, connected: Boolean): Room? = null
        override suspend fun openSocketConnection(code: String, userId: UserId): Long? = null
        override suspend fun closeSocketConnectionIfCurrent(code: String, userId: UserId, connectionId: Long): Room? = null
        override suspend fun markPlaying(code: String): Room? = null
        override suspend fun markFinished(code: String): Room? = null
        override suspend fun find(code: String): Room? = null
        override suspend fun observe(code: String): kotlinx.coroutines.flow.Flow<Room>? = null
        override suspend fun sweepDisconnected(maxIdle: kotlin.time.Duration): RoomSweepResult =
            RoomSweepResult(0, 0, 0)
        override suspend fun reapIfStillDisconnected(
            code: String,
            userId: UserId,
            expectedDisconnectedAt: kotlin.time.Instant,
        ): Boolean = false
        override suspend fun snapshot(): List<Room> = emptyList()
    }

    private object EmptyMessages : UserMessageRepository {
        override suspend fun create(
            id: UUID,
            userId: UserId,
            idempotencyKey: String,
            kind: UserMessageKind,
            emoji: String?,
            title: String,
            body: String,
            deepLink: String?,
            expiresAt: kotlin.time.Instant?,
        ): CreateMessageOutcome = error("unused")

        override suspend fun unreadFor(
            userId: UserId,
            now: kotlin.time.Instant,
            limit: Int,
        ): List<UserMessage> = emptyList()

        override suspend fun ackMany(
            userId: UserId,
            ids: List<UUID>,
            at: kotlin.time.Instant,
        ): Int = 0

        override suspend fun sweepExpiredAndAcked(now: kotlin.time.Instant): MessageSweepResult =
            MessageSweepResult(0, 0)

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }
}
