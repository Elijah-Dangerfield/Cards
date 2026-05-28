package com.dangerfield.cards.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.ClientGrantableAchievements
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.OwnedItem
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalog
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.http.ClientContext
import com.dangerfield.cards.server.plugins.installAuthenticationWithVerifier
import com.dangerfield.cards.server.plugins.installRateLimits
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
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
 * Route-level tests for `POST /v1/me/grants/achievement/{achievementId}`.
 *
 * Pins all four resolution branches of [ClientGrantableAchievements] plus
 * the auth gate:
 *  - Client-grantable id → 200 + OwnedItemDto, repo sees one
 *    `recordEarnedGrant` with the right productId.
 *  - Server-witnessed id → 403, repo untouched. This is the load-bearing
 *    branch the tests exist to lock down — without it, a future PvP
 *    achievement added to `serverWitnessed` could be self-granted by a
 *    malicious client.
 *  - Unknown id → 204, repo untouched (graceful client/server skew).
 *  - Catalog absence for a client-grantable id → 204 (graceful degrade
 *    when a cosmetic is removed in a future build).
 *  - Missing / wrong-secret bearer → 401.
 *  - Two posts for the same achievement → repo called twice; route stays
 *    dumb, storage owns idempotency (same split as `inventoryRoutes`).
 */
@OptIn(ExperimentalTime::class)
class GrantsRoutesTest {

    private val testIssuer = "https://test-project.supabase.co/auth/v1"
    private val testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val userId = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    private val defaultPolicy = ClientGrantableAchievements.Default
    private val policyWithServerWitnessed = ClientGrantableAchievements(
        clientGrantable = mapOf("POT_5000" to "title_pot_magnet"),
        serverWitnessed = setOf("RANKED_TOP_FINISH"),
    )

    @Test
    fun clientGrantableAchievement_returnsOwnedItem_andRecordsEarnedGrant() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("title_pot_magnet"))
        post(inventory, catalog, defaultPolicy, "POT_5000") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("title_pot_magnet", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals(0L, body.costChipsAtPurchase)
            assertEquals(1, inventory.earnedGrants.size)
            val grant = inventory.earnedGrants.single()
            assertEquals(userId, grant.userId)
            assertEquals("title_pot_magnet", grant.productId)
        }
    }

    @Test
    fun defaultPolicy_grantsComebackKidCardBack_forDontCallItComeback() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("cardback_comeback_kid"))
        post(inventory, catalog, defaultPolicy, "DONT_CALL_IT_COMEBACK") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("cardback_comeback_kid", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("cardback_comeback_kid", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsEliminatorEmotePack_forBustDealt5() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("emotes_eliminator"))
        post(inventory, catalog, defaultPolicy, "BUST_DEALT_5") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("emotes_eliminator", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("emotes_eliminator", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsBallerEmotePack_forTripleUp() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("emotes_baller"))
        post(inventory, catalog, defaultPolicy, "TRIPLE_UP") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("emotes_baller", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("emotes_baller", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsIronStackEmotePack_forNoBust100() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("emotes_iron_stack"))
        post(inventory, catalog, defaultPolicy, "NO_BUST_100") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("emotes_iron_stack", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("emotes_iron_stack", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsConvincerEmotePack_forWinByFold10() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("emotes_convincer"))
        post(inventory, catalog, defaultPolicy, "WIN_BY_FOLD_10") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("emotes_convincer", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("emotes_convincer", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsDisciplinedEmotePack_forGoodFold25() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("emotes_disciplined"))
        post(inventory, catalog, defaultPolicy, "GOOD_FOLD_25") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("emotes_disciplined", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("emotes_disciplined", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsGrinderEmotePack_forHands1000() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("emotes_grinder"))
        post(inventory, catalog, defaultPolicy, "HANDS_1000") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("emotes_grinder", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("emotes_grinder", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsDoublerEmotePack_forDoubleUp() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("emotes_doubler"))
        post(inventory, catalog, defaultPolicy, "DOUBLE_UP") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("emotes_doubler", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("emotes_doubler", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsTacticianEmotePack_forChallenging10Wins() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("emotes_tactician"))
        post(inventory, catalog, defaultPolicy, "CHALLENGING_10_WINS") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("emotes_tactician", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("emotes_tactician", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsFeltVeteranTitle_forReachLevel25() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("title_felt_veteran"))
        post(inventory, catalog, defaultPolicy, "REACH_LEVEL_25") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("title_felt_veteran", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("title_felt_veteran", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsBotWhispererTitle_forBotWhispererCapstone() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("title_bot_whisperer"))
        post(inventory, catalog, defaultPolicy, "BOT_WHISPERER") { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.body<OwnedItemDto>()
            assertEquals("title_bot_whisperer", body.productId)
            assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource)
            assertEquals("title_bot_whisperer", inventory.earnedGrants.single().productId)
        }
    }

    @Test
    fun defaultPolicy_grantsBeatBotSignaturePacks_forBeat10AchievementsPerBot() = runTest {
        val pairings = listOf(
            "BEAT_JANE_10" to "emotes_inspector",
            "BEAT_DAVID_10" to "emotes_showstopper",
            "BEAT_GINA_10" to "emotes_outsmarter",
            "BEAT_STEVE_10" to "emotes_marathoner",
            "BEAT_MIKE_10" to "emotes_tamer",
        )
        for ((achievementId, expectedProductId) in pairings) {
            val inventory = CapturingInventory()
            val catalog = FakeCatalog.with(stubProduct(expectedProductId))
            post(inventory, catalog, defaultPolicy, achievementId) { resp ->
                assertEquals(HttpStatusCode.OK, resp.status, "id=$achievementId")
                val body = resp.body<OwnedItemDto>()
                assertEquals(expectedProductId, body.productId, "id=$achievementId")
                assertEquals(AcquisitionSource.Earned.wire, body.acquisitionSource, "id=$achievementId")
                assertEquals(expectedProductId, inventory.earnedGrants.single().productId, "id=$achievementId")
            }
        }
    }

    @Test
    fun serverWitnessedAchievement_returnsForbidden_andDoesNotRecord() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.empty()
        post(inventory, catalog, policyWithServerWitnessed, "RANKED_TOP_FINISH") { resp ->
            assertEquals(HttpStatusCode.Forbidden, resp.status)
            assertTrue(inventory.earnedGrants.isEmpty())
        }
    }

    @Test
    fun defaultPolicy_refusesMultiplayerBustAchievements_with403() = runTest {
        // MP-mode achievements (FIRST_BUST_DEALT_MP / BUST_DEALT_5_MP) live in
        // the default `serverWitnessed` set so a client posting them never
        // self-grants — they will be granted server-side once Phase 4.2
        // server-authoritative gameplay lands.
        for (id in listOf("FIRST_BUST_DEALT_MP", "BUST_DEALT_5_MP")) {
            val inventory = CapturingInventory()
            val catalog = FakeCatalog.empty()
            post(inventory, catalog, defaultPolicy, id) { resp ->
                assertEquals(HttpStatusCode.Forbidden, resp.status, "id=$id")
                assertTrue(inventory.earnedGrants.isEmpty(), "id=$id")
            }
        }
    }

    @Test
    fun unknownAchievement_returnsNoContent_andDoesNotRecord() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.empty()
        post(inventory, catalog, defaultPolicy, "FIRST_HAND") { resp ->
            assertEquals(HttpStatusCode.NoContent, resp.status)
            assertTrue(inventory.earnedGrants.isEmpty())
        }
    }

    @Test
    fun catalogMissingMappedProduct_returnsNoContent_andDoesNotRecord() = runTest {
        // POT_5000 is allowlisted, but the catalog source returns null
        // (simulates the cosmetic being removed in a future server build).
        // Route degrades to 204 rather than 500 so a stale client doesn't
        // surface an error.
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.empty()
        post(inventory, catalog, defaultPolicy, "POT_5000") { resp ->
            assertEquals(HttpStatusCode.NoContent, resp.status)
            assertTrue(inventory.earnedGrants.isEmpty())
        }
    }

    @Test
    fun idempotent_repoCalledTwice_butRouteResponseIsStable() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("title_short_stack_hero"))
        post(inventory, catalog, defaultPolicy, "COMEBACK_FROM_5BB") { first ->
            assertEquals(HttpStatusCode.OK, first.status)
            val firstBody = first.body<OwnedItemDto>()
            post(inventory, catalog, defaultPolicy, "COMEBACK_FROM_5BB") { second ->
                assertEquals(HttpStatusCode.OK, second.status)
                val secondBody = second.body<OwnedItemDto>()
                assertEquals(firstBody.productId, secondBody.productId)
                // Repo is called both times — `recordEarnedGrant` is idempotent
                // at the storage layer, not at the route, matching how
                // `inventoryRoutes` handles `recordPurchase`.
                assertEquals(2, inventory.earnedGrants.size)
            }
        }
    }

    @Test
    fun returns401_whenAuthHeaderMissing() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("title_pot_magnet"))
        post(inventory, catalog, defaultPolicy, "POT_5000", withBearer = false) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertTrue(inventory.earnedGrants.isEmpty())
        }
    }

    @Test
    fun returns401_whenJwtSignedWithWrongSecret() = runTest {
        val foreign = JWT.create()
            .withIssuer(testIssuer)
            .withAudience("authenticated")
            .withSubject(userId.value.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("wrong-secret-wrong-secret-wrong-secret-wrong-secret"))
        post(
            CapturingInventory(),
            FakeCatalog.with(stubProduct("title_pot_magnet")),
            defaultPolicy,
            "POT_5000",
            bearer = foreign,
        ) { resp ->
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test
    fun perUserGrants_areIsolated() = runTest {
        val inventory = CapturingInventory()
        val catalog = FakeCatalog.with(stubProduct("title_pot_magnet"))
        val otherUser = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        post(inventory, catalog, defaultPolicy, "POT_5000") {}
        post(inventory, catalog, defaultPolicy, "POT_5000", bearer = jwt(forUserId = otherUser)) {}
        assertEquals(setOf(userId, otherUser), inventory.earnedGrants.map { it.userId }.toSet())
    }

    // ---------- scaffolding ----------

    private fun jwt(forUserId: UserId = userId): String = JWT.create()
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

    private suspend fun post(
        inventory: InventoryRepository,
        catalog: ProductCatalogSource,
        policy: ClientGrantableAchievements,
        achievementId: String,
        withBearer: Boolean = true,
        bearer: String = jwt(),
        assert: suspend (HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installRateLimits()
                installStatusPages()
                installAuthenticationWithVerifier(testVerifier)
                routing { grantsRoutes(inventory, catalog, policy) }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.post("/v1/me/grants/achievement/$achievementId") {
                if (withBearer) header(HttpHeaders.Authorization, "Bearer $bearer")
            }
            assert(response)
        }
    }

    private fun stubProduct(id: String): Product.ChipOffer = Product.ChipOffer(
        id = id,
        titleByLocale = mapOf("en" to "Stub"),
        subtitleByLocale = mapOf("en" to "Stub"),
        iconEmoji = "🏆",
        costChips = 0L,
        grantsKey = id,
        isEquippable = true,
    )

    private class FakeCatalog private constructor(
        private val byId: Map<String, Product>,
    ) : ProductCatalogSource {
        override suspend fun read(context: ClientContext): ProductCatalog =
            ProductCatalog(chipPacks = emptyList(), chipOffers = emptyList())

        override suspend fun readById(id: String, context: ClientContext): Product? = byId[id]

        companion object {
            fun empty(): FakeCatalog = FakeCatalog(emptyMap())
            fun with(vararg products: Product): FakeCatalog =
                FakeCatalog(products.associateBy { it.id })
        }
    }

    private class CapturingInventory : InventoryRepository {
        data class EarnedGrant(val userId: UserId, val productId: String)

        val earnedGrants = mutableListOf<EarnedGrant>()

        override suspend fun listOwned(userId: UserId): List<OwnedItem> = emptyList()

        override suspend fun recordPurchase(
            userId: UserId,
            productId: String,
            costChipsAtPurchase: Long,
            purchasedAt: Instant,
        ): OwnedItem = error("recordPurchase not used in this test")

        override suspend fun recordEarnedGrant(
            userId: UserId,
            productId: String,
            grantedAt: Instant,
        ): OwnedItem {
            earnedGrants += EarnedGrant(userId, productId)
            return OwnedItem(
                productId = productId,
                costChipsAtPurchase = 0L,
                purchasedAt = grantedAt,
                acquisitionSource = AcquisitionSource.Earned,
            )
        }

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }
}
