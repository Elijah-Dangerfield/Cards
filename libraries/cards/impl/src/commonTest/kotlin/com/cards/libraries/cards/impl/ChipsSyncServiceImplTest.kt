package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventDao
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventEntity
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.MockRequestHandleScope
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [ChipsSyncServiceImpl] reconciliation logic via a Ktor MockEngine
 * stubbing `POST /v1/me/wallet/sync`. The shape mirrors
 * [InventorySyncServiceImplTest].
 *
 * What's pinned:
 *  - Empty pending list still issues a sync — that's the foreground
 *    hydrate that picks up cross-device grants.
 *  - All-Applied response → events deleted from local; balance reset to
 *    server's authoritative value.
 *  - AlreadyApplied (replay) → events deleted; same balance reset.
 *  - InsufficientChips → events deleted (no retry possible); balance
 *    reset to authoritative; warning logged (not asserted, but the
 *    setBalance call IS).
 *  - Unknown outcome → row stays pending (so a newer client can resolve).
 *  - Network failure → returns Result.failure, leaves rows pending.
 */
class ChipsSyncServiceImplTest : CoroutineTest() {

    @Test
    fun emptyPending_stillIssuesSync_andResetsBalance() = runUnitTest {
        // The empty-batch sync is the hydrate-only pulse. The local
        // balance must converge to the server's value even when we have
        // nothing to flush.
        val dao = FakeWalletEventDao()
        val chips = FakeChips()
        var hitCount = 0
        val service = buildService(
            dao = dao,
            chips = chips,
            handler = {
                hitCount++
                respondJson("""{"schemaVersion":1,"balance":12345,"results":[]}""")
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertEquals(1, hitCount, "empty batch still triggers the sync (hydrate)")
        assertEquals(12_345L, chips.lastSetBalance)
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun allApplied_deletesLocalEvents_andResetsBalanceToAuthoritative() = runUnitTest {
        val dao = FakeWalletEventDao().apply {
            insert(walletEvent("k1", delta = 250))
            insert(walletEvent("k2", delta = -100))
        }
        val chips = FakeChips()
        val service = buildService(
            dao = dao,
            chips = chips,
            handler = {
                respondJson(
                    """
                    {
                      "schemaVersion":1,
                      "balance":11150,
                      "results":[
                        {"idempotencyKey":"k1","outcome":"Applied","balance":11250},
                        {"idempotencyKey":"k2","outcome":"Applied","balance":11150}
                      ]
                    }
                    """.trimIndent(),
                )
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertTrue(dao.getAll().isEmpty(), "both events resolved → all local rows deleted")
        assertEquals(11_150L, chips.lastSetBalance)
    }

    @Test
    fun alreadyApplied_isTreatedAsResolved() = runUnitTest {
        val dao = FakeWalletEventDao().apply {
            insert(walletEvent("retry_key", delta = 50))
        }
        val chips = FakeChips()
        val service = buildService(
            dao = dao,
            chips = chips,
            handler = {
                respondJson(
                    """
                    {
                      "schemaVersion":1,
                      "balance":10000,
                      "results":[
                        {"idempotencyKey":"retry_key","outcome":"AlreadyApplied","balance":10000}
                      ]
                    }
                    """.trimIndent(),
                )
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertTrue(dao.getAll().isEmpty(), "replay-acknowledged events drop from local")
    }

    @Test
    fun insufficientChips_dropsRow_andResetsBalance() = runUnitTest {
        // The client optimistically debited but the server refused; the
        // local balance must converge back to the authoritative value
        // (which is what it was BEFORE the debit, since the server
        // didn't apply the event).
        val dao = FakeWalletEventDao().apply {
            insert(walletEvent("bad_debit", delta = -1_000_000))
        }
        val chips = FakeChips()
        val service = buildService(
            dao = dao,
            chips = chips,
            handler = {
                respondJson(
                    """
                    {
                      "schemaVersion":1,
                      "balance":10000,
                      "results":[
                        {"idempotencyKey":"bad_debit","outcome":"InsufficientChips","balance":10000}
                      ]
                    }
                    """.trimIndent(),
                )
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertTrue(dao.getAll().isEmpty(), "rejected event drops — no retry pathway")
        assertEquals(10_000L, chips.lastSetBalance)
    }

    @Test
    fun unknownOutcome_leavesRowPending_forNewerClientToResolve() = runUnitTest {
        val dao = FakeWalletEventDao().apply {
            insert(walletEvent("mystery", delta = 100))
        }
        val chips = FakeChips()
        val service = buildService(
            dao = dao,
            chips = chips,
            handler = {
                respondJson(
                    """
                    {
                      "schemaVersion":1,
                      "balance":10100,
                      "results":[
                        {"idempotencyKey":"mystery","outcome":"From_The_Future","balance":10100}
                      ]
                    }
                    """.trimIndent(),
                )
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("mystery"),
            dao.getAll().map { it.idempotencyKey },
            "unknown outcome → row stays so a newer client can handle it",
        )
        // The authoritative balance is still synced.
        assertEquals(10_100L, chips.lastSetBalance)
    }

    @Test
    fun networkFailure_returnsFailure_keepsRowsPending() = runUnitTest {
        val dao = FakeWalletEventDao().apply {
            insert(walletEvent("k1", delta = 50))
        }
        val chips = FakeChips()
        val service = buildService(
            dao = dao,
            chips = chips,
            handler = { respond(content = ByteReadChannel(""), status = HttpStatusCode.InternalServerError) },
        )

        val result = service.sync()

        assertTrue(result.isFailure)
        assertEquals(
            listOf("k1"),
            dao.getAll().map { it.idempotencyKey },
            "network failure → local row stays for next-launch retry",
        )
        assertFalse(chips.setBalanceCalled, "no authoritative-balance update on failed sync")
    }

    @Test
    fun mixedOutcomes_dropResolvedOnly() = runUnitTest {
        val dao = FakeWalletEventDao().apply {
            insert(walletEvent("k_applied", delta = 100))
            insert(walletEvent("k_already", delta = 50))
            insert(walletEvent("k_unknown", delta = 10))
            insert(walletEvent("k_rejected", delta = -1_000_000))
        }
        val chips = FakeChips()
        val service = buildService(
            dao = dao,
            chips = chips,
            handler = {
                respondJson(
                    """
                    {
                      "schemaVersion":1,
                      "balance":10150,
                      "results":[
                        {"idempotencyKey":"k_applied","outcome":"Applied","balance":10100},
                        {"idempotencyKey":"k_already","outcome":"AlreadyApplied","balance":10100},
                        {"idempotencyKey":"k_unknown","outcome":"Mystery","balance":10100},
                        {"idempotencyKey":"k_rejected","outcome":"InsufficientChips","balance":10150}
                      ]
                    }
                    """.trimIndent(),
                )
            },
        )

        val result = service.sync()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("k_unknown"),
            dao.getAll().map { it.idempotencyKey },
            "only the unknown event stays — everything else is resolved (incl. rejected)",
        )
    }

    // ---------- Scaffolding ----------

    private fun buildService(
        dao: FakeWalletEventDao,
        chips: FakeChips,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ChipsSyncServiceImpl {
        val mockEngine = MockEngine(handler)
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                    },
                )
            }
        }
        val networkClient = object : NetworkClient {
            override val client: HttpClient = client
            override val authenticatedClient: HttpClient = client
        }
        return ChipsSyncServiceImpl(dao, chips, networkClient)
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )

    private fun walletEvent(
        key: String,
        delta: Long,
        reason: String = "test",
        appliedAtEpochMs: Long = 1_000,
    ) = WalletEventEntity(
        idempotencyKey = key,
        delta = delta,
        reason = reason,
        appliedAtEpochMs = appliedAtEpochMs,
    )

    private class FakeWalletEventDao : WalletEventDao {
        private val rows = mutableListOf<WalletEventEntity>()
        override suspend fun getAll(): List<WalletEventEntity> =
            rows.sortedBy { it.appliedAtEpochMs }

        override suspend fun insert(entity: WalletEventEntity) {
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

    private class FakeChips : ChipsRepository {
        var lastSetBalance: Long? = null
            private set
        var setBalanceCalled: Boolean = false
            private set
        private val state = MutableStateFlow(0L)

        override fun observeBalance(): Flow<Long> = state.asStateFlow()
        override suspend fun getBalance(): Long = state.value
        override suspend fun applyDelta(delta: Long, reason: String, idempotencyKey: String?) {
            state.value += delta
        }
        override suspend fun setBalance(authoritativeBalance: Long) {
            setBalanceCalled = true
            lastSetBalance = authoritativeBalance
            state.value = authoritativeBalance
        }
        override suspend fun deleteAll() {
            state.value = 0
        }
    }
}
