package com.dangerfield.cards.libraries.cards.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.cards.storage.db.InventoryDao
import com.dangerfield.cards.libraries.cards.storage.db.InventoryEntity
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins the [InventoryRepositoryImpl] contract.
 *
 * Uses in-memory fakes for both DAOs rather than a Room test fixture — the
 * repo is pure logic on top of the DAO interfaces, and the actual Room
 * behavior (txn, primary-key conflict, etc.) is covered by Room's own
 * tests + an instrumented test would be needed for real Room semantics
 * anyway.
 *
 * What we pin:
 *  - Insufficient chips: returns InsufficientChips without touching DAO state.
 *  - Successful redemption: inserts Pending row + deducts chips, exactly once.
 *  - Already owned: returns AlreadyOwned without re-charging chips.
 *  - Compensating delete: if chip deduction throws, the inventory row is
 *    rolled back.
 *  - markConfirmed flips state.
 *  - revertPurchase deletes.
 *  - Flow propagation.
 */
class InventoryRepositoryImplTest : CoroutineTest() {

    @Test
    fun redeem_insufficientChips_returnsInsufficient_doesNotInsertOrCharge() = runUnitTest {
        val inv = FakeInventoryDao()
        val chips = FakeChipsRepository(balance = 100)
        val repo = InventoryRepositoryImpl(inv, chips, FixedClock(now = 1_000))

        val result = repo.redeemChipOffer("emote_dance", costChips = 2_500)

        assertEquals(RedeemResult.InsufficientChips, result)
        assertEquals(0, inv.inserted.size, "no insert when broke")
        assertEquals(0, chips.deltas.size, "no chip deduction either")
    }

    @Test
    fun redeem_success_insertsPendingRow_andDeductsChips() = runUnitTest {
        val inv = FakeInventoryDao()
        val chips = FakeChipsRepository(balance = 10_000)
        val repo = InventoryRepositoryImpl(inv, chips, FixedClock(now = 5_000))

        val result = repo.redeemChipOffer("emote_dance", costChips = 2_500)

        assertEquals(RedeemResult.Success, result)
        val inserted = inv.inserted.single()
        assertEquals("emote_dance", inserted.productId)
        assertEquals(PurchaseState.Pending.name, inserted.syncState)
        assertEquals(5_000L, inserted.purchasedAtEpochMs)
        assertEquals(2_500L, inserted.costChipsAtPurchase)
        assertEquals(listOf(-2_500L), chips.deltas, "chip deduction is the cost, negated")
    }

    @Test
    fun redeem_alreadyOwned_returnsAlreadyOwned_doesNotChargeChips() = runUnitTest {
        val inv = FakeInventoryDao().apply {
            insertReturnsDuplicate = true
        }
        val chips = FakeChipsRepository(balance = 10_000)
        val repo = InventoryRepositoryImpl(inv, chips, FixedClock(now = 0))

        val result = repo.redeemChipOffer("emote_dance", costChips = 2_500)

        assertEquals(RedeemResult.AlreadyOwned, result)
        assertEquals(0, chips.deltas.size, "duplicate insert must not deduct chips")
    }

    @Test
    fun redeem_chipDeductionFails_compensatesByDeletingTheRow() = runUnitTest {
        val inv = FakeInventoryDao()
        val chips = FakeChipsRepository(balance = 10_000).apply {
            failOnNextApplyDelta = RuntimeException("disk full")
        }
        val repo = InventoryRepositoryImpl(inv, chips, FixedClock(now = 0))

        val thrown = runCatching {
            repo.redeemChipOffer("emote_dance", costChips = 2_500)
        }.exceptionOrNull()
        assertTrue(thrown is RuntimeException, "expected the underlying failure to propagate")

        // Compensation: the inserted row was deleted.
        assertEquals(
            listOf("emote_dance"),
            inv.deleted,
            "inventory row deleted to keep state consistent",
        )
    }

    @Test
    fun markConfirmed_flipsState() = runUnitTest {
        val inv = FakeInventoryDao()
        val chips = FakeChipsRepository(balance = 10_000)
        val repo = InventoryRepositoryImpl(inv, chips, FixedClock(now = 0))

        repo.markConfirmed(listOf("a", "b"))

        assertEquals(1, inv.confirmCalls.size)
        assertEquals(listOf("a", "b"), inv.confirmCalls.first().toList())
    }

    @Test
    fun markConfirmed_emptyList_isNoOp() = runUnitTest {
        val inv = FakeInventoryDao()
        val chips = FakeChipsRepository(balance = 10_000)
        val repo = InventoryRepositoryImpl(inv, chips, FixedClock(now = 0))

        repo.markConfirmed(emptyList())

        assertEquals(0, inv.confirmCalls.size, "no DAO call for empty input")
    }

    @Test
    fun revertPurchase_deletes() = runUnitTest {
        val inv = FakeInventoryDao()
        val chips = FakeChipsRepository(balance = 10_000)
        val repo = InventoryRepositoryImpl(inv, chips, FixedClock(now = 0))

        repo.revertPurchase("emote_dance")

        assertEquals(listOf("emote_dance"), inv.deleted)
    }

    @Test
    fun observeInventory_mapsRowsToDomain() = runUnitTest {
        val inv = FakeInventoryDao().apply {
            emit(
                listOf(
                    InventoryEntity(
                        productId = "emote_dance",
                        syncState = "Confirmed",
                        purchasedAtEpochMs = 5_000,
                        costChipsAtPurchase = 2_500,
                    ),
                ),
            )
        }
        val repo = InventoryRepositoryImpl(inv, FakeChipsRepository(10_000), FixedClock(0))

        repo.observeInventory().test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("emote_dance", items.first().productId)
            assertEquals(PurchaseState.Confirmed, items.first().state)
            assertEquals(5_000L, items.first().purchasedAtEpochMs)
            assertEquals(2_500L, items.first().costChipsAtPurchase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeInventory_unknownSyncState_fallsBackToPending() = runUnitTest {
        val inv = FakeInventoryDao().apply {
            emit(
                listOf(
                    InventoryEntity(
                        productId = "x",
                        syncState = "FROM_THE_FUTURE",
                        purchasedAtEpochMs = 0,
                        costChipsAtPurchase = 0,
                    ),
                ),
            )
        }
        val repo = InventoryRepositoryImpl(inv, FakeChipsRepository(10_000), FixedClock(0))

        repo.observeInventory().test {
            val item = awaitItem().single()
            assertEquals(
                PurchaseState.Pending,
                item.state,
                "unknown enum decays to Pending so a stale client doesn't crash",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteAll_clearsTable() = runUnitTest {
        val inv = FakeInventoryDao()
        val repo = InventoryRepositoryImpl(inv, FakeChipsRepository(10_000), FixedClock(0))

        repo.deleteAll()

        assertTrue(inv.deleteAllCalled)
    }

    // ---------- Test scaffolding ----------

    private class FixedClock(private val now: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    }

    private class FakeChipsRepository(balance: Long) : ChipsRepository {
        private val state = MutableStateFlow(balance)
        val deltas = mutableListOf<Long>()
        val reasons = mutableListOf<String>()
        val idempotencyKeys = mutableListOf<String?>()
        var failOnNextApplyDelta: Throwable? = null

        override fun observeBalance(): Flow<Long> = state.asStateFlow()
        override suspend fun getBalance(): Long = state.value

        override suspend fun applyDelta(delta: Long, reason: String, idempotencyKey: String?) {
            failOnNextApplyDelta?.let { failOnNextApplyDelta = null; throw it }
            deltas += delta
            reasons += reason
            idempotencyKeys += idempotencyKey
            state.value = state.value + delta
        }

        override suspend fun setBalance(authoritativeBalance: Long) {
            state.value = authoritativeBalance
        }

        override suspend fun deleteAll() {
            state.value = 0
        }
    }

    private class FakeInventoryDao : InventoryDao {
        private val rows = MutableStateFlow<List<InventoryEntity>>(emptyList())
        val inserted = mutableListOf<InventoryEntity>()
        val deleted = mutableListOf<String>()
        val confirmCalls = mutableListOf<Collection<String>>()
        var deleteAllCalled: Boolean = false
            private set
        var insertReturnsDuplicate: Boolean = false

        override fun observeAll(): Flow<List<InventoryEntity>> = rows.asStateFlow()
        override suspend fun getAll(): List<InventoryEntity> = rows.value
        override suspend fun getByProductId(productId: String): InventoryEntity? =
            rows.value.firstOrNull { it.productId == productId }
        override suspend fun getPending(): List<InventoryEntity> =
            rows.value.filter { it.syncState == "Pending" }

        override suspend fun insertIfMissing(entity: InventoryEntity): Long {
            if (insertReturnsDuplicate) return -1L
            if (rows.value.any { it.productId == entity.productId }) return -1L
            inserted += entity
            rows.value = rows.value + entity
            return inserted.size.toLong()
        }

        override suspend fun markConfirmed(productIds: Collection<String>) {
            confirmCalls += productIds
            rows.value = rows.value.map {
                if (it.productId in productIds) it.copy(syncState = "Confirmed") else it
            }
        }

        override suspend fun delete(productId: String) {
            deleted += productId
            rows.value = rows.value.filterNot { it.productId == productId }
        }

        override suspend fun deleteAll() {
            deleteAllCalled = true
            rows.value = emptyList()
        }

        fun emit(items: List<InventoryEntity>) { rows.value = items }
    }
}
