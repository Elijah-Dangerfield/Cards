package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.AchievementGrantApi
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.storage.db.AchievementDao
import com.dangerfield.cards.libraries.cards.storage.db.AchievementEarnedEntity
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [AchievementRepositoryImpl.sync] reconciliation via a Ktor MockEngine
 * stubbing `POST /v1/me/achievements/sync`. Mirrors the XP/chips sync tests.
 *
 *  - Unsynced local earned → posted + marked synced.
 *  - Server-only earned (another device) → inserted locally as synced.
 *  - Achievements are monotonic — a local earned row is never removed.
 *  - Network failure → Result.failure, rows stay unsynced.
 *  - onUserChanged(current != null) fires a sync; sign-out does not.
 */
class AchievementRepositoryImplSyncTest : CoroutineTest() {

    @Test
    fun localUnsynced_postedAndMarkedSynced() = runUnitTest {
        val dao = FakeAchievementDao(earned("A", synced = false))
        val repo = buildRepo(dao) {
            respondJson("""{"schemaVersion":1,"earned":[{"achievementId":"A","earnedAtEpochMs":1}]}""")
        }

        repo.sync()

        assertTrue(dao.getUnsyncedEarned().isEmpty(), "posted row is marked synced")
    }

    @Test
    fun serverOnlyEarned_insertedLocally_asSynced_localUntouched() = runUnitTest {
        val dao = FakeAchievementDao(earned("A", synced = true))
        val repo = buildRepo(dao) {
            respondJson(
                """{"schemaVersion":1,"earned":[
                  {"achievementId":"A","earnedAtEpochMs":1},
                  {"achievementId":"B","earnedAtEpochMs":2}
                ]}""".trimIndent(),
            )
        }

        repo.sync()

        val ids = dao.getEarned().map { it.achievementId }.toSet()
        assertEquals(setOf("A", "B"), ids, "server-only B is pulled in; A stays")
        assertTrue(dao.getUnsyncedEarned().isEmpty(), "the pulled-down row is synced")
    }

    @Test
    fun emptyLocal_stillSyncs_andHydratesServerSet() = runUnitTest {
        val dao = FakeAchievementDao()
        var hits = 0
        val repo = buildRepo(dao) {
            hits++
            respondJson("""{"schemaVersion":1,"earned":[{"achievementId":"X","earnedAtEpochMs":5}]}""")
        }

        repo.sync()

        assertEquals(1, hits, "empty local set still issues the hydrate sync")
        assertEquals(listOf("X"), dao.getEarned().map { it.achievementId })
    }

    @Test
    fun networkFailure_returnsFailure_andLeavesRowsPending() = runUnitTest {
        val dao = FakeAchievementDao(earned("A", synced = false))
        val repo = buildRepo(dao) {
            respondJson("""{"error":"boom"}""", status = HttpStatusCode.InternalServerError)
        }

        val result = repo.sync()

        assertTrue(result.isFailure)
        assertEquals(listOf("A"), dao.getUnsyncedEarned().map { it.achievementId }, "failed sync keeps rows pending")
    }

    // ---------- Scaffolding ----------

    @Test
    fun serverMintedAchievementChips_triggerAWalletRePull() = runUnitTest {
        // PROG-12: achievement chips are minted server-side ON the
        // achievements sync (ENG-9) — without a re-pull the reward stays
        // invisible until the next sync trigger. The response's walletBalance
        // is the mint signal.
        val chips = RecordingChips()
        val repo = buildRepo(FakeAchievementDao(earned("A", synced = false)), chips) {
            respondJson(
                """{"schemaVersion":1,"earned":[{"achievementId":"A","earnedAtEpochMs":1}],"walletBalance":11000}""",
            )
        }

        repo.sync()

        assertEquals(1, chips.syncCalls, "a server-side mint must trigger a wallet re-pull")
    }

    @Test
    fun noMint_doesNotTouchTheWallet() = runUnitTest {
        val chips = RecordingChips()
        val repo = buildRepo(FakeAchievementDao(), chips) {
            respondJson("""{"schemaVersion":1,"earned":[]}""")
        }

        repo.sync()

        assertEquals(0, chips.syncCalls, "a mint-less sync must not spam wallet pulls")
    }

    private fun buildRepo(
        dao: FakeAchievementDao,
        chips: ChipsRepository = NoopChips,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): AchievementRepositoryImpl {
        val httpClient = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; coerceInputValues = true }) }
            expectSuccess = true
        }
        @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
        val networkClient = object : NetworkClient {
            override val client: HttpClient = httpClient
            override val authenticatedClient: HttpClient = httpClient
            override suspend fun awaitAuthReady() = Unit
        }
        return AchievementRepositoryImpl(
            achievementDao = dao,
            playerStatsRepository = NoopPlayerStats,
            progressionRepository = NoopProgression,
            chipsRepository = chips,
            grantApi = NoopGrantApi,
            inventoryRepository = NoopInventory,
            networkClient = networkClient,
            progressionConfig = FakeProgressionConfig(),
            appScope = AppCoroutineScope(dispatchers),
            clock = FixedClock,
        )
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )

    private fun earned(id: String, synced: Boolean) =
        AchievementEarnedEntity(achievementId = id, earnedAtEpochMs = 1L, synced = synced)

    private object FixedClock : kotlin.time.Clock {
        override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(1_000)
    }

    /** Earned-focused AchievementDao fake; counters are no-ops (sync ignores them). */
    private class FakeAchievementDao(vararg seed: AchievementEarnedEntity) : AchievementDao {
        private val earned = seed.associateBy { it.achievementId }.toMutableMap()
        private val flow = MutableStateFlow(earned.values.toList())

        override fun observeEarned(): Flow<List<AchievementEarnedEntity>> = flow.asStateFlow()
        override suspend fun getEarned(): List<AchievementEarnedEntity> = earned.values.toList()
        override suspend fun insertEarned(entity: AchievementEarnedEntity) {
            earned.putIfAbsent(entity.achievementId, entity)
            flow.value = earned.values.toList()
        }
        override suspend fun getUnsyncedEarned(): List<AchievementEarnedEntity> =
            earned.values.filter { !it.synced }
        override suspend fun markEarnedSynced(ids: List<String>) {
            ids.forEach { id -> earned[id]?.let { earned[id] = it.copy(synced = true) } }
            flow.value = earned.values.toList()
        }
        override suspend fun deleteAllEarned() { earned.clear(); flow.value = emptyList() }
    }

    private object NoopPlayerStats : com.dangerfield.cards.libraries.cards.PlayerStatsRepository {
        override fun observeStats(): Flow<com.dangerfield.cards.libraries.cards.PlayerStats?> = MutableStateFlow(null)
        override suspend fun getStats(): com.dangerfield.cards.libraries.cards.PlayerStats? = null
        override fun observeEffectiveCounters(): Flow<Map<String, Long>> = MutableStateFlow(emptyMap())
        override suspend fun effectiveCounters(): Map<String, Long> = emptyMap()
        override suspend fun recordHand(
            summary: com.dangerfield.cards.libraries.cards.PlayerStatHandSummary,
        ) = Unit
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAll() = Unit
    }

    private object NoopProgression : ProgressionRepository {
        override fun observeProgression(): Flow<Progression> = MutableStateFlow(Progression.Empty)
        override suspend fun getProgression(): Progression = Progression.Empty
        override suspend fun awardForHand(summary: HandResultSummary): List<XpEvent> = emptyList()
        override suspend fun applyAchievementXp(delta: Int, description: String?): XpEvent =
            error("unused")
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAll() = Unit
        override suspend fun debugSetTotalXp(totalXp: Long) = Unit
    }

    private class RecordingChips : ChipsRepository {
        var syncCalls = 0
            private set
        override fun observeBalance(): Flow<Long?> = MutableStateFlow(null)
        override suspend fun getBalance(): Long? = null
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
        override suspend fun setBalance(authoritativeBalance: Long) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun sync(): Result<Unit> {
            syncCalls++
            return Result.success(Unit)
        }
    }

    private object NoopChips : ChipsRepository {
        override fun observeBalance(): Flow<Long?> = MutableStateFlow(0L)
        override suspend fun getBalance(): Long? = 0L
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
        override suspend fun setBalance(authoritativeBalance: Long) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }

    private object NoopGrantApi : AchievementGrantApi {
        override suspend fun grantAchievement(id: AchievementId): Boolean = false
    }

    private object NoopInventory : InventoryRepository {
        override fun observeInventory(): Flow<List<com.dangerfield.cards.libraries.cards.InventoryItem>> =
            MutableStateFlow(emptyList())
        override suspend fun getInventory(): List<com.dangerfield.cards.libraries.cards.InventoryItem> = emptyList()
        override suspend fun redeemChipOffer(productId: String, costChips: Long) = error("unused")
        override suspend fun markConfirmed(productIds: Collection<String>) = Unit
        override suspend fun revertPurchase(productId: String) = Unit
        override suspend fun applyServerSnapshot(
            authoritative: List<com.dangerfield.cards.libraries.cards.InventoryItem>,
        ) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }
}
