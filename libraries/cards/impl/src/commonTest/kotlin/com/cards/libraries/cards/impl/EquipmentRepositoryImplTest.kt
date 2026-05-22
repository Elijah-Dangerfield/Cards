package com.dangerfield.cards.libraries.cards.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentDao
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentEntity
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins [EquipmentRepositoryImpl] behavior. Same in-memory-fake approach
 * as InventoryRepositoryImplTest — the repo is pure logic on top of the
 * DAO interface; Room's real semantics get covered in instrumented tests
 * upstream.
 *
 * Key behaviors pinned:
 *  - Equip writes a Pending row and the flow re-emits.
 *  - Unequip writes a Pending row with is_equipped = false.
 *  - Re-equipping an already-Synced row is a NoChange no-op (no DB churn).
 *  - applyServerSnapshot folds the authoritative set in: matches go
 *    Synced, missing-from-server Synced equips flip to Synced unequipped,
 *    Pending unequips for absent products get marked Synced (then purged).
 */
class EquipmentRepositoryImplTest : CoroutineTest() {

    @Test
    fun equip_writesPendingRow_andFlowReEmits() = runUnitTest {
        val dao = FakeEquipmentDao()
        val repo = buildRepo(dao, FixedClock(now = 1_000))

        repo.observeEquipped().test {
            assertEquals(emptyList(), awaitItem())

            val result = repo.equip("cardback_marble")
            assertEquals(EquipmentToggleResult.Success, result)

            val emitted = awaitItem().single()
            assertEquals("cardback_marble", emitted.productId)
            assertEquals(true, emitted.isEquipped)
            assertEquals(EquipmentSyncState.Pending, emitted.syncState)
            assertEquals(1_000L, emitted.updatedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unequip_writesPendingFalseRow() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "cardback_marble",
                    isEquipped = true,
                    syncState = "Synced",
                    updatedAtEpochMs = 100,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(now = 5_000))

        val result = repo.unequip("cardback_marble")
        assertEquals(EquipmentToggleResult.Success, result)

        val all = dao.getAll()
        val row = all.single()
        assertEquals(false, row.isEquipped)
        assertEquals("Pending", row.syncState)
        assertEquals(5_000L, row.updatedAtEpochMs)
    }

    @Test
    fun equip_synced_andSameValue_returnsNoChange() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "felt_royal_red",
                    isEquipped = true,
                    syncState = "Synced",
                    updatedAtEpochMs = 100,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(now = 9_999))

        val result = repo.equip("felt_royal_red")

        assertEquals(EquipmentToggleResult.NoChange, result)
        val row = dao.getAll().single()
        // Row untouched — no DB write on a true no-op.
        assertEquals(100L, row.updatedAtEpochMs)
        assertEquals("Synced", row.syncState)
    }

    @Test
    fun equip_pending_andSameValue_doesWriteAFreshTimestamp() = runUnitTest {
        // Why: a pending row's timestamp matters for server LWW, and a
        // user-initiated re-tap should always be the freshest intent.
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "emotes_drama",
                    isEquipped = true,
                    syncState = "Pending",
                    updatedAtEpochMs = 100,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(now = 9_999))

        val result = repo.equip("emotes_drama")

        assertEquals(EquipmentToggleResult.Success, result)
        assertEquals(9_999L, dao.getAll().single().updatedAtEpochMs)
    }

    @Test
    fun applyServerSnapshot_marksMatchingRowsSynced_andAcceptsServerTimestamp() = runUnitTest {
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
        val repo = buildRepo(dao, FixedClock(now = 9_999))

        repo.applyServerSnapshot(
            listOf(
                EquipmentEntry(
                    productId = "cardback_marble",
                    isEquipped = true,
                    syncState = EquipmentSyncState.Synced,
                    updatedAtEpochMs = 250,
                ),
            ),
        )

        val row = dao.getAll().single()
        assertEquals(true, row.isEquipped)
        assertEquals("Synced", row.syncState)
        assertEquals(250L, row.updatedAtEpochMs, "server timestamp is authoritative after reconcile")
    }

    @Test
    fun applyServerSnapshot_purgesPendingUnequipsThatServerAgreesAreOff() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "emotes_cute",
                    isEquipped = false,
                    syncState = "Pending",
                    updatedAtEpochMs = 100,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(now = 0))

        repo.applyServerSnapshot(authoritative = emptyList())

        // Pending unequip whose product is absent from server → marked
        // Synced AND purged by the same call (purgeSyncedUnequips runs at the end).
        assertEquals(emptyList(), dao.getAll())
    }

    @Test
    fun applyServerSnapshot_supersedesLocalEquipThatServerNoLongerKnows() = runUnitTest {
        // Scenario: user equipped on device B and we synced; now device A
        // syncs and server says that product isn't equipped anymore (device B
        // unequipped). We had a Synced local row; reconcile flips it off.
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "title_shark",
                    isEquipped = true,
                    syncState = "Synced",
                    updatedAtEpochMs = 100,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(now = 0))

        repo.applyServerSnapshot(authoritative = emptyList())

        // Purged because the superseded row is is_equipped=0 + Synced.
        assertEquals(emptyList(), dao.getAll())
    }

    @Test
    fun applyServerSnapshot_leavesPendingEquipForUnknownProductAlone() = runUnitTest {
        // Server hasn't ack'd this equip yet — but it isn't in the
        // authoritative set either (mid-flight retry). Local Pending
        // stays Pending so the next cycle re-sends it.
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "emotes_fierce",
                    isEquipped = true,
                    syncState = "Pending",
                    updatedAtEpochMs = 100,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(now = 0))

        repo.applyServerSnapshot(authoritative = emptyList())

        val row = dao.getAll().single()
        assertEquals(true, row.isEquipped)
        assertEquals("Pending", row.syncState)
    }

    @Test
    fun dropOrphanEquipment_removesRowsNotInOwnedSet_returnsOrphanIds() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "owned_cardback",
                    isEquipped = true,
                    syncState = "Synced",
                    updatedAtEpochMs = 0,
                ),
            )
            seed(
                EquipmentEntity(
                    productId = "orphan_felt",
                    isEquipped = true,
                    syncState = "Synced",
                    updatedAtEpochMs = 0,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(0))

        val dropped = repo.dropOrphanEquipment(ownedProductIds = setOf("owned_cardback"))

        assertEquals(listOf("orphan_felt"), dropped)
        val remaining = dao.getAll().single()
        assertEquals("owned_cardback", remaining.productId)
    }

    @Test
    fun dropOrphanEquipment_emptyOwnedSet_dropsEverything() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "a",
                    isEquipped = true,
                    syncState = "Synced",
                    updatedAtEpochMs = 0,
                ),
            )
            seed(
                EquipmentEntity(
                    productId = "b",
                    isEquipped = true,
                    syncState = "Synced",
                    updatedAtEpochMs = 0,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(0))

        val dropped = repo.dropOrphanEquipment(ownedProductIds = emptySet())

        assertEquals(setOf("a", "b"), dropped.toSet())
        assertEquals(emptyList(), dao.getAll())
    }

    @Test
    fun dropOrphanEquipment_allRowsOwned_returnsEmptyList_isNoOp() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "owned_cardback",
                    isEquipped = true,
                    syncState = "Synced",
                    updatedAtEpochMs = 0,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(0))

        val dropped = repo.dropOrphanEquipment(ownedProductIds = setOf("owned_cardback", "extra"))

        assertEquals(emptyList(), dropped)
        assertEquals(1, dao.getAll().size, "no DB rows removed when nothing is orphaned")
    }

    @Test
    fun observeEquipped_unknownSyncStateDecaysToPending() = runUnitTest {
        val dao = FakeEquipmentDao().apply {
            seed(
                EquipmentEntity(
                    productId = "from_the_future",
                    isEquipped = true,
                    syncState = "QUANTUM",
                    updatedAtEpochMs = 0,
                ),
            )
        }
        val repo = buildRepo(dao, FixedClock(0))

        repo.observeEquipped().test {
            val item = awaitItem().single()
            assertEquals(
                EquipmentSyncState.Pending,
                item.syncState,
                "unknown enum decays to Pending so stale clients don't crash",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- scaffolding ----------

    private fun buildRepo(dao: EquipmentDao, clock: Clock): EquipmentRepositoryImpl =
        EquipmentRepositoryImpl(
            equipmentDao = dao,
            networkClient = StubNetworkClient,
            appScope = AppCoroutineScope(dispatchers),
            clock = clock,
        )

    private object StubNetworkClient : NetworkClient {
        private val unused = HttpClient(MockEngine {
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NotImplemented)
        })
        override val client: HttpClient = unused
        override val authenticatedClient: HttpClient = unused
    }

    private class FixedClock(private val now: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    }

    private class FakeEquipmentDao : EquipmentDao {
        private val rows = MutableStateFlow<List<EquipmentEntity>>(emptyList())

        fun seed(entity: EquipmentEntity) {
            rows.value = rows.value + entity
        }

        override fun observeEquipped(): Flow<List<EquipmentEntity>> =
            rows.asStateFlow().map { list -> list.filter { it.isEquipped } }

        override suspend fun getAll(): List<EquipmentEntity> = rows.value

        override suspend fun getPending(): List<EquipmentEntity> =
            rows.value.filter { it.syncState == "Pending" }

        override suspend fun getByProductId(productId: String): EquipmentEntity? =
            rows.value.firstOrNull { it.productId == productId }

        override suspend fun upsert(entity: EquipmentEntity) {
            rows.value = rows.value.filter { it.productId != entity.productId } + entity
        }

        override suspend fun markSynced(productIds: Collection<String>) {
            rows.value = rows.value.map { row ->
                if (row.productId in productIds) row.copy(syncState = "Synced") else row
            }
        }

        override suspend fun purgeSyncedUnequips() {
            rows.value = rows.value.filterNot { !it.isEquipped && it.syncState == "Synced" }
        }

        override suspend fun deleteAll() {
            rows.value = emptyList()
        }

        override suspend fun insertAll(rows: List<EquipmentEntity>) {
            // Mirrors Room's REPLACE: existing rows with the same PK get
            // overwritten.
            val toReplace = rows.map { it.productId }.toSet()
            this.rows.value = this.rows.value.filter { it.productId !in toReplace } + rows
        }

        override suspend fun deleteByProductIds(productIds: Collection<String>) {
            this.rows.value = this.rows.value.filterNot { it.productId in productIds }
        }
    }
}
