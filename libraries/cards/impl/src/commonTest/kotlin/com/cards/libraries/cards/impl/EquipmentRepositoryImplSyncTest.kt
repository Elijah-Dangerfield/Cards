package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.storage.db.EquipmentDao
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentEntity
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins [EquipmentRepositoryImpl.sync] reconciliation logic via a Ktor
 * MockEngine stubbing `POST /v1/equipment/sync`.
 *
 * The non-sync DAO-only behaviors (toggle semantics, NoChange suppression,
 * applyServerSnapshot called in isolation) are covered by
 * [EquipmentRepositoryImplTest] — this file is the HTTP-mocked half that
 * exercises the full sync flow end-to-end.
 *
 * What's pinned:
 *  - Empty Pending list still POSTs (the response carries the
 *    authoritative snapshot — that's how a cross-device equip lands).
 *  - Pending equip confirmed by server → row flips to Synced.
 *  - Pending unequip whose product isn't in the server set → row gets
 *    marked Synced then purged by `purgeSyncedUnequips`.
 *  - Local Synced equip absent from the server snapshot → row flips to
 *    Synced-unequipped then purged (cross-device unequip catch-up).
 *  - Server snapshot contains a product we don't have locally → row
 *    inserted as Synced equipped.
 *  - Network failure → returns `Result.failure`, leaves local rows
 *    untouched for next-launch retry.
 *  - Endpoint + method are stable (`POST /v1/equipment/sync`).
 *  - POST body carries the pending ops.
 */
class EquipmentRepositoryImplSyncTest : CoroutineTest() {

    @Test
    fun emptyPending_stillPOSTs_andAppliesServerSnapshot() = runUnitTest {
        val dao = FakeEquipmentDao()
        var hitCount = 0
        val repo = buildRepo(dao) {
            hitCount++
            respondJson(
                """
                {
                  "schemaVersion":1,
                  "equipped":[
                    {"productId":"felt_neon","updatedAtEpochMs":42}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, hitCount, "empty pending list still issues a sync")
        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("felt_neon", rows.single().productId)
        assertEquals(true, rows.single().isEquipped)
        assertEquals("Synced", rows.single().syncState, "server snapshot lands as Synced")
    }

    @Test
    fun pendingEquip_confirmedByServer_flipsToSynced() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "cardback_marble",
                    isEquipped = true,
                    syncState = "Pending",
                    updatedAtEpochMs = 100,
                ),
            )
        }
        val repo = buildRepo(dao) {
            respondJson(
                """
                {
                  "schemaVersion":1,
                  "equipped":[
                    {"productId":"cardback_marble","updatedAtEpochMs":150}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        val row = dao.getAll().single()
        assertEquals("Synced", row.syncState)
        assertEquals(150L, row.updatedAtEpochMs, "server's timestamp wins")
    }

    @Test
    fun pendingUnequip_absentFromServerSet_marksSynced_thenPurged() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "felt_classic",
                    isEquipped = false,
                    syncState = "Pending",
                    updatedAtEpochMs = 200,
                ),
            )
        }
        val repo = buildRepo(dao) {
            respondJson("""{"schemaVersion":1,"equipped":[]}""")
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertTrue(
            dao.getAll().isEmpty(),
            "Pending unequip the server agrees with → marked Synced → purged by purgeSyncedUnequips",
        )
    }

    @Test
    fun localSyncedEquip_missingFromServerSnapshot_flipsToUnequippedThenPurged() = runUnitTest {
        // Scenario: user equipped on device B; device A's last sync saw
        // that as Synced. On a different device they unequip; now device
        // A re-syncs and the server omits the product. Local follows.
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "cardback_dark",
                    isEquipped = true,
                    syncState = "Synced",
                    updatedAtEpochMs = 300,
                ),
            )
        }
        val repo = buildRepo(dao) {
            respondJson("""{"schemaVersion":1,"equipped":[]}""")
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        assertTrue(
            dao.getAll().isEmpty(),
            "Synced equip not in server snapshot → flipped to Synced unequipped → purged",
        )
    }

    @Test
    fun serverIntroducesNewProduct_insertedAsSyncedEquipped() = runUnitTest {
        val dao = FakeEquipmentDao()
        val repo = buildRepo(dao) {
            respondJson(
                """
                {
                  "schemaVersion":1,
                  "equipped":[
                    {"productId":"avatar_legend","updatedAtEpochMs":999}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        val row = dao.getAll().single()
        assertEquals("avatar_legend", row.productId)
        assertEquals(true, row.isEquipped)
        assertEquals("Synced", row.syncState)
        assertEquals(999L, row.updatedAtEpochMs)
    }

    @Test
    fun networkFailure_returnsFailure_leavesPendingRowsUntouched() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "cardback_marble",
                    isEquipped = true,
                    syncState = "Pending",
                    updatedAtEpochMs = 100,
                ),
            )
        }
        val repo = buildRepo(dao) {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError)
        }

        val result = repo.sync()

        assertTrue(result.isFailure)
        val row = dao.getAll().single()
        assertEquals(
            "Pending",
            row.syncState,
            "5xx must not apply a server snapshot — local Pending rows stay for retry",
        )
    }

    @Test
    fun sync_hitsExactEndpoint_withPostMethod_andCarriesPendingOps() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "felt_classic",
                    isEquipped = true,
                    syncState = "Pending",
                    updatedAtEpochMs = 500,
                ),
            )
            seed(
                EquipmentEntity(
                    productId = "cardback_dark",
                    isEquipped = false,
                    syncState = "Pending",
                    updatedAtEpochMs = 600,
                ),
            )
        }
        var capturedPath: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null
        val repo = buildRepo(dao) { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method
            capturedBody = request.body.toBodyString()
            respondJson("""{"schemaVersion":1,"equipped":[]}""")
        }

        repo.sync()

        assertEquals("/v1/equipment/sync", capturedPath)
        assertEquals(HttpMethod.Post, capturedMethod)
        assertNotNull(capturedBody)
        assertTrue(
            capturedBody!!.contains("\"felt_classic\""),
            "POST body must include each Pending op; was: $capturedBody",
        )
        assertTrue(
            capturedBody!!.contains("\"cardback_dark\""),
            "POST body must include each Pending op; was: $capturedBody",
        )
        // Equip flag round-trips both directions.
        assertTrue(
            capturedBody!!.contains("\"equip\":true") && capturedBody!!.contains("\"equip\":false"),
            "POST body must encode the equip flag for both ops; was: $capturedBody",
        )
    }

    @Test
    fun mixedReconciliation_keepsServerEquipped_dropsLocalOnly() = runUnitTest {
        // Three local rows:
        //   A — Pending equip the server confirms → flips to Synced.
        //   B — Synced equip the server omits → flips to Synced-unequipped → purged.
        //   C — Pending unequip the server omits → marked Synced → purged.
        // After sync, only A should remain.
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity("A", isEquipped = true, syncState = "Pending", updatedAtEpochMs = 10),
            )
            seed(
                EquipmentEntity("B", isEquipped = true, syncState = "Synced", updatedAtEpochMs = 20),
            )
            seed(
                EquipmentEntity("C", isEquipped = false, syncState = "Pending", updatedAtEpochMs = 30),
            )
        }
        val repo = buildRepo(dao) {
            respondJson(
                """
                {
                  "schemaVersion":1,
                  "equipped":[
                    {"productId":"A","updatedAtEpochMs":11}
                  ]
                }
                """.trimIndent(),
            )
        }

        val result = repo.sync()

        assertTrue(result.isSuccess)
        val rows = dao.getAll()
        assertEquals(1, rows.size, "only the server-confirmed equip survives")
        val a = rows.single()
        assertEquals("A", a.productId)
        assertEquals("Synced", a.syncState)
        assertEquals(true, a.isEquipped)
        assertFalse(rows.any { it.productId == "B" }, "B purged after cross-device unequip catch-up")
        assertFalse(rows.any { it.productId == "C" }, "C purged after the server-agreed unequip")
    }

    // ---------- Scaffolding ----------

    private fun buildRepo(
        dao: FakeEquipmentDao,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): EquipmentRepositoryImpl {
        val mockEngine = MockEngine(handler)
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        coerceInputValues = true
                    },
                )
            }
        }
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
        }
        return EquipmentRepositoryImpl(
            equipmentDao = dao,
            networkClient = networkClient,
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

    private fun io.ktor.http.content.OutgoingContent.toBodyString(): String =
        when (this) {
            is io.ktor.http.content.TextContent -> text
            is io.ktor.http.content.ByteArrayContent -> bytes().decodeToString()
            else -> toString()
        }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }

    private class FakeEquipmentDao : EquipmentDao {
        private val rowsState = MutableStateFlow<List<EquipmentEntity>>(emptyList())

        fun seed(entity: EquipmentEntity) {
            rowsState.value = rowsState.value + entity
        }

        override fun observeEquipped(): Flow<List<EquipmentEntity>> =
            rowsState.asStateFlow().map { list -> list.filter { it.isEquipped } }

        override suspend fun getAll(): List<EquipmentEntity> = rowsState.value

        override suspend fun getPending(): List<EquipmentEntity> =
            rowsState.value.filter { it.syncState == "Pending" }

        override suspend fun getByProductId(productId: String): EquipmentEntity? =
            rowsState.value.firstOrNull { it.productId == productId }

        override suspend fun upsert(entity: EquipmentEntity) {
            rowsState.value = rowsState.value.filter { it.productId != entity.productId } + entity
        }

        override suspend fun markSynced(productIds: Collection<String>) {
            rowsState.value = rowsState.value.map { row ->
                if (row.productId in productIds) row.copy(syncState = "Synced") else row
            }
        }

        override suspend fun purgeSyncedUnequips() {
            rowsState.value = rowsState.value.filterNot {
                !it.isEquipped && it.syncState == "Synced"
            }
        }

        override suspend fun deleteAll() {
            rowsState.value = emptyList()
        }

        override suspend fun insertAll(rows: List<EquipmentEntity>) {
            val replaceIds = rows.map { it.productId }.toSet()
            rowsState.value = rowsState.value.filter { it.productId !in replaceIds } + rows
        }

        override suspend fun deleteByProductIds(productIds: Collection<String>) {
            rowsState.value = rowsState.value.filterNot { it.productId in productIds }
        }
    }
}
