package com.dangerfield.cards.libraries.cards.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsEntity
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventDao
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventEntity
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.InternalNetworkingApi
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins [ChipsRepositoryImpl]'s local-write path: `addChips`,
 * `subtractChips`, `setBalance`, and `deleteAll`. The server round-trip
 * is covered by [ChipsRepositoryImplSyncTest]; this file stays offline
 * and exercises the DAO writes directly.
 *
 * Key invariants pinned here:
 *  - addChips inserts a wallet event row keyed by the idempotency key
 *    (caller-supplied or repo-generated UUID) AND applies the delta to
 *    the optimistic local balance.
 *  - The first write seeds the chips row at zero before the delta so
 *    `applyDelta` has something to update.
 *  - subtractChips writes the same shape with a negative delta and may
 *    drive the local balance negative — the optimistic write happens
 *    even if the user "doesn't have enough" locally; sync reconciles.
 *  - setBalance seeds a row when missing and reconciles via delta when
 *    one exists; a no-op (delta == 0) doesn't churn the timestamp.
 *  - deleteAll wipes both tables, used by the signed-out cleanup.
 *  - addChips/subtractChips reject non-positive amounts at the
 *    `require()` boundary (defensive against caller bugs).
 */
class ChipsRepositoryImplTest : CoroutineTest() {

    @Test
    fun addChips_insertsWalletEvent_andAppliesDeltaToBalance() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = null)
        val walletDao = FakeWalletEventDao()
        val repo = buildRepo(chipsDao, walletDao)

        repo.addChips(amount = 250, reason = "achievement.first_win", idempotencyKey = "k1")

        val events = walletDao.getAll()
        assertEquals(1, events.size)
        assertEquals("k1", events.single().idempotencyKey)
        assertEquals(250L, events.single().delta)
        assertEquals("achievement.first_win", events.single().reason)
        assertEquals(FIXED_NOW_EPOCH_MS, events.single().appliedAtEpochMs)

        assertEquals(250L, chipsDao.getChips()?.balance)
        assertEquals(FIXED_NOW_EPOCH_MS, chipsDao.getChips()?.updatedAtEpochMs)
    }

    @Test
    fun addChips_seedsChipsRowAtZero_whenMissing_beforeApplyingDelta() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = null)
        val walletDao = FakeWalletEventDao()
        val repo = buildRepo(chipsDao, walletDao)

        repo.addChips(amount = 100, reason = "starter_grant", idempotencyKey = null)

        // The presence of a row + the +100 balance proves the
        // insertIfMissing(0) → applyDelta(+100) ordering ran. Without
        // the insertIfMissing, applyDelta on an empty `chips` table
        // would be a no-op and the balance would stay null.
        assertEquals(100L, chipsDao.getChips()?.balance)
    }

    @Test
    fun addChips_withoutIdempotencyKey_generatesAUuid() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = null)
        val walletDao = FakeWalletEventDao()
        val repo = buildRepo(chipsDao, walletDao)

        repo.addChips(amount = 50, reason = "test", idempotencyKey = null)

        val key = walletDao.getAll().single().idempotencyKey
        // UUID v4 stringified is 36 chars (8-4-4-4-12 + 4 hyphens) and
        // non-empty. We don't pin the exact value — just that the repo
        // generated *something* keyable so a future sync can dedup.
        assertEquals(36, key.length, "expected stringified UUID, got '$key'")
        assertTrue(key.contains("-"), "expected UUID hyphens")
    }

    @Test
    fun addChips_rejectsZeroOrNegativeAmount() = runUnitTest {
        val repo = buildRepo(FakeChipsDao(initial = null), FakeWalletEventDao())

        assertFails { repo.addChips(amount = 0, reason = "test", idempotencyKey = null) }
        assertFails { repo.addChips(amount = -1, reason = "test", idempotencyKey = null) }
    }

    @Test
    fun subtractChips_writesNegativeDelta_andAppliesIt() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = ChipsEntity(balance = 1_000L, updatedAtEpochMs = 0))
        val walletDao = FakeWalletEventDao()
        val repo = buildRepo(chipsDao, walletDao)

        repo.subtractChips(amount = 250, reason = "shop.purchase", idempotencyKey = "p1")

        val event = walletDao.getAll().single()
        assertEquals(-250L, event.delta, "subtract must persist the sign-flipped delta")
        assertEquals("shop.purchase", event.reason)
        assertEquals(750L, chipsDao.getChips()?.balance)
    }

    @Test
    fun subtractChips_rejectsZeroOrNegativeAmount() = runUnitTest {
        val repo = buildRepo(FakeChipsDao(initial = null), FakeWalletEventDao())

        assertFails { repo.subtractChips(amount = 0, reason = "test", idempotencyKey = null) }
        assertFails { repo.subtractChips(amount = -1, reason = "test", idempotencyKey = null) }
    }

    @Test
    fun subtractChips_canDriveLocalBalanceNegative_optimisticWriteContract() = runUnitTest {
        // Repo doesn't gate writes on solvency — that's the server's job.
        // A debit larger than the local balance still goes through; the
        // server's InsufficientChips outcome on sync resets the
        // authoritative balance. This test pins that boundary so a future
        // change to "client-side guard" is deliberate.
        val chipsDao = FakeChipsDao(initial = ChipsEntity(balance = 100L, updatedAtEpochMs = 0))
        val walletDao = FakeWalletEventDao()
        val repo = buildRepo(chipsDao, walletDao)

        repo.subtractChips(amount = 500, reason = "shop.purchase", idempotencyKey = "p1")

        assertEquals(-400L, chipsDao.getChips()?.balance)
        assertEquals(-500L, walletDao.getAll().single().delta)
    }

    @Test
    fun observeBalance_emitsCurrentValue_andUpdatesOnWrite() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = ChipsEntity(balance = 100L, updatedAtEpochMs = 0))
        val walletDao = FakeWalletEventDao()
        val repo = buildRepo(chipsDao, walletDao)

        repo.observeBalance().test {
            assertEquals(100L, awaitItem())

            repo.addChips(amount = 50, reason = "test", idempotencyKey = "k1")
            assertEquals(150L, awaitItem())

            repo.subtractChips(amount = 25, reason = "test", idempotencyKey = "k2")
            assertEquals(125L, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeBalance_emitsNull_whenChipsRowMissing() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = null)
        val walletDao = FakeWalletEventDao()
        val repo = buildRepo(chipsDao, walletDao)

        repo.observeBalance().test {
            assertNull(
                awaitItem(),
                "the wallet has no row pre-first-sync — UI hides the badge in that window",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setBalance_seedsRowAtAuthoritativeValue_whenMissing() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = null)
        val repo = buildRepo(chipsDao, FakeWalletEventDao())

        repo.setBalance(authoritativeBalance = 12_345L)

        assertEquals(12_345L, chipsDao.getChips()?.balance)
        assertEquals(FIXED_NOW_EPOCH_MS, chipsDao.getChips()?.updatedAtEpochMs)
    }

    @Test
    fun setBalance_appliesDelta_whenRowExists() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = ChipsEntity(balance = 1_000L, updatedAtEpochMs = 0))
        val repo = buildRepo(chipsDao, FakeWalletEventDao())

        repo.setBalance(authoritativeBalance = 1_500L)

        assertEquals(1_500L, chipsDao.getChips()?.balance)
        assertEquals(FIXED_NOW_EPOCH_MS, chipsDao.getChips()?.updatedAtEpochMs)
    }

    @Test
    fun setBalance_canReconcileDownward() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = ChipsEntity(balance = 5_000L, updatedAtEpochMs = 0))
        val repo = buildRepo(chipsDao, FakeWalletEventDao())

        // Pin the InsufficientChips reconciliation path: the server says
        // "your balance is actually 1_000, you tried to debit too much"
        // and we converge by applying a negative delta to land there.
        repo.setBalance(authoritativeBalance = 1_000L)

        assertEquals(1_000L, chipsDao.getChips()?.balance)
    }

    @Test
    fun setBalance_isNoOp_whenAlreadyAtAuthoritative() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = ChipsEntity(balance = 4_242L, updatedAtEpochMs = 0))
        val repo = buildRepo(chipsDao, FakeWalletEventDao())

        repo.setBalance(authoritativeBalance = 4_242L)

        assertEquals(4_242L, chipsDao.getChips()?.balance)
        // Zero-delta short-circuit: the timestamp must NOT advance. This
        // is load-bearing for the "every sync is a touch" misread —
        // pinning the boundary makes future churn changes deliberate.
        assertEquals(0L, chipsDao.getChips()?.updatedAtEpochMs)
    }

    @Test
    fun deleteAll_wipesChipsAndWalletEvents() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = ChipsEntity(balance = 7_777L, updatedAtEpochMs = 0))
        val walletDao = FakeWalletEventDao().apply {
            insert(walletEvent("k1", delta = 100))
            insert(walletEvent("k2", delta = -50))
        }
        val repo = buildRepo(chipsDao, walletDao)

        repo.deleteAll()

        assertNull(chipsDao.getChips(), "signed-out cleanup must drop the chips row")
        assertTrue(walletDao.getAll().isEmpty(), "pending wallet events must drop on sign-out")
    }

    @Test
    fun addChips_withDuplicateIdempotencyKey_dropsTheSecondEvent_butStillAppliesDelta() = runUnitTest {
        // Current behavior — the wallet-events table de-dups on the
        // primary key (IGNORE on conflict), so a same-key call inserts
        // exactly one row. The chips DAO's `applyDelta` is not key-aware
        // and runs both times, so the optimistic local balance ends up
        // double-applied (one server event, two local debits) until the
        // next sync's setBalance reconciles to the authoritative value.
        //
        // In production this corner is unreachable because callers don't
        // re-invoke addChips with the same key (sync, not the local
        // write, is the retry path). Pinning the current behavior here
        // makes a future change — guarding applyDelta on a pre-existing
        // wallet event — deliberate rather than accidental.
        val chipsDao = FakeChipsDao(initial = null)
        val walletDao = FakeWalletEventDao()
        val repo = buildRepo(chipsDao, walletDao)

        repo.addChips(amount = 100, reason = "test", idempotencyKey = "k1")
        repo.addChips(amount = 100, reason = "test", idempotencyKey = "k1")

        assertEquals(1, walletDao.getAll().size, "duplicate idempotency key → one row, IGNORE on conflict")
        assertEquals(200L, chipsDao.getChips()?.balance, "applyDelta runs both times — optimistic double-apply")
    }

    @Test
    fun getBalance_returnsCurrent_orNullWhenRowMissing() = runUnitTest {
        val populated = FakeChipsDao(initial = ChipsEntity(balance = 42L, updatedAtEpochMs = 0))
        val empty = FakeChipsDao(initial = null)

        assertEquals(42L, buildRepo(populated, FakeWalletEventDao()).getBalance())
        assertNull(buildRepo(empty, FakeWalletEventDao()).getBalance())
    }

    @Test
    fun addChips_appliedAtTimestamp_isFromInjectedClock() = runUnitTest {
        val chipsDao = FakeChipsDao(initial = null)
        val walletDao = FakeWalletEventDao()
        val repo = buildRepo(chipsDao, walletDao)

        repo.addChips(amount = 1, reason = "test", idempotencyKey = "k1")

        val event = walletDao.getAll().single()
        // Pins that the wallet event timestamp comes from the injected
        // clock (so tests can drive it). A test-only FixedClock returns
        // [FIXED_NOW_EPOCH_MS] for both `appliedAtEpochMs` and the chips
        // row's `updatedAtEpochMs`.
        assertEquals(FIXED_NOW_EPOCH_MS, event.appliedAtEpochMs)
        assertNotNull(chipsDao.getChips())
        assertEquals(FIXED_NOW_EPOCH_MS, chipsDao.getChips()?.updatedAtEpochMs)
    }

    // ---------- Scaffolding ----------

    private fun buildRepo(
        chipsDao: ChipsDao,
        walletDao: WalletEventDao,
    ): ChipsRepositoryImpl {
        // The local-write path never touches the network. A MockEngine
        // that errors on every request would still be fine — but a
        // zero-handler engine is cheaper. We only need the type.
        val mockEngine = MockEngine { respond(content = ByteReadChannel(""), status = HttpStatusCode.OK) }
        val client = HttpClient(mockEngine)
        @OptIn(InternalNetworkingApi::class)
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
            override suspend fun awaitAuthReady() = Unit
        }
        return ChipsRepositoryImpl(
            chipsDao = chipsDao,
            walletEventDao = walletDao,
            networkClient = networkClient,
            appScope = AppCoroutineScope(dispatchers),
            clock = FixedClock,
        )
    }

    private fun walletEvent(
        key: String,
        delta: Long,
        reason: String = "test",
        appliedAtEpochMs: Long = FIXED_NOW_EPOCH_MS,
    ) = WalletEventEntity(
        idempotencyKey = key,
        delta = delta,
        reason = reason,
        appliedAtEpochMs = appliedAtEpochMs,
    )

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(FIXED_NOW_EPOCH_MS)
    }

    private class FakeWalletEventDao : WalletEventDao {
        private val rows = mutableListOf<WalletEventEntity>()
        override suspend fun getAll(): List<WalletEventEntity> =
            rows.sortedBy { it.appliedAtEpochMs }

        override suspend fun insert(entity: WalletEventEntity) {
            // Match Room's `OnConflictStrategy.IGNORE` — same-PK insert
            // keeps the existing row.
            if (rows.none { it.idempotencyKey == entity.idempotencyKey }) {
                rows += entity
            }
        }

        override suspend fun deleteByKeys(keys: List<String>) {
            rows.removeAll { it.idempotencyKey in keys }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    private class FakeChipsDao(initial: ChipsEntity?) : ChipsDao {
        private val state = MutableStateFlow(initial)

        override fun observeChips(): Flow<ChipsEntity?> = state.asStateFlow()
        override suspend fun getChips(): ChipsEntity? = state.value

        override suspend fun insertIfMissing(entity: ChipsEntity) {
            // Match Room's `OnConflictStrategy.IGNORE` — no-op when a
            // row exists; populate when missing.
            if (state.value == null) state.value = entity
        }

        override suspend fun applyDelta(delta: Long, updatedAtEpochMs: Long) {
            val current = state.value ?: return
            state.value = current.copy(
                balance = current.balance + delta,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        }

        override suspend fun deleteAll() {
            state.value = null
        }
    }

    private companion object {
        const val FIXED_NOW_EPOCH_MS: Long = 1_700_000_000_000
    }
}
