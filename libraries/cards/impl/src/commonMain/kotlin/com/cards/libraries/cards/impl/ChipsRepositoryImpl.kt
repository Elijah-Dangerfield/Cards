package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.impl.dto.WalletEventDto
import com.dangerfield.cards.libraries.cards.impl.dto.WalletEventOutcomeDto
import com.dangerfield.cards.libraries.cards.impl.dto.WalletSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.WalletSyncResponseDto
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsEntity
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventDao
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventEntity
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Optimistic local chips, write-through to the server's wallet ledger.
 *
 * [applyDelta] mutates the singleton chips row AND enqueues a
 * [WalletEventEntity] keyed by the caller-supplied idempotency key (or a
 * generated UUID if none was supplied). [sync] picks the pending events
 * up on cold boot / foreground and flushes them through
 * `POST /v1/me/wallet/sync`.
 *
 * [setBalance] is the inverse direction: [sync] overwrites the local
 * balance with the server's authoritative value after a successful
 * round-trip. Other callers should stay on [applyDelta].
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ChipsRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class ChipsRepositoryImpl(
    private val chipsDao: ChipsDao,
    private val walletEventDao: WalletEventDao,
    private val networkClient: NetworkClient,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
) : ChipsRepository, AppEventListener {

    private val syncLogger = KLog.withTag("ChipsSync")
    private val syncMutex = Mutex()

    override fun observeBalance(): Flow<Long> = chipsDao.observeChips()
        .onStart { ensureSeeded() }
        .map { it?.balance ?: ChipsRepository.STARTING_GRANT }

    override suspend fun getBalance(): Long {
        ensureSeeded()
        return chipsDao.getChips()?.balance ?: ChipsRepository.STARTING_GRANT
    }

    override suspend fun applyDelta(
        delta: Long,
        reason: String,
        idempotencyKey: String?,
    ) {
        ensureSeeded()
        val nowEpochMs = clock.now().toEpochMilliseconds()
        val key = idempotencyKey ?: Uuid.random().toString()

        // Order: enqueue the ledger row first, then mutate the balance.
        // If a crash lands between the two, the sync treats the orphaned
        // event as "the user wanted this delta to land, the server might
        // or might not have it." A duplicate enqueue is harmless (INSERT
        // OR IGNORE on the idempotency key).
        walletEventDao.insert(
            WalletEventEntity(
                idempotencyKey = key,
                delta = delta,
                reason = reason,
                appliedAtEpochMs = nowEpochMs,
            ),
        )
        chipsDao.applyDelta(delta = delta, updatedAtEpochMs = nowEpochMs)
    }

    override suspend fun setBalance(authoritativeBalance: Long) {
        ensureSeeded()
        val current = chipsDao.getChips()?.balance ?: 0L
        val delta = authoritativeBalance - current
        if (delta != 0L) {
            chipsDao.applyDelta(
                delta = delta,
                updatedAtEpochMs = clock.now().toEpochMilliseconds(),
            )
        }
    }

    override suspend fun deleteAll() {
        chipsDao.deleteAll()
        walletEventDao.deleteAll()
    }

    override fun onColdBoot(event: AppEvent.ColdBoot) {
        appScope.launch { sync() }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        // ColdBoot fires alongside OnForeground; let the cold-boot path
        // own that first sync. Warm-resume IS where we want a fresh
        // reconcile though, so this branch handles that.
        if (event.isColdBoot) return
        appScope.launch { sync() }
    }

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
        Catching {
            val pending = walletEventDao.getAll()

            // Always POST — an empty events list is a valid "hydrate
            // balance" call. That's how a second device picks up a chip
            // grant the user collected elsewhere.
            val request = WalletSyncRequestDto(
                events = pending.map { it.toDto() },
            )
            val response: WalletSyncResponseDto = networkClient.authenticatedClient
                .post("/v1/me/wallet/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                .body()

            // Per-event reconciliation. Applied + AlreadyApplied →
            // server has it, drop our pending row. InsufficientChips →
            // server refused; we drop the row too (retrying will just
            // bounce again) and let setBalance below restore the
            // authoritative value. Unknown → leave the row; a newer
            // client will know what to do.
            val resolvedKeys = response.results
                .filter { it.outcome in RESOLVED_OUTCOMES }
                .map { it.idempotencyKey }
            if (resolvedKeys.isNotEmpty()) {
                walletEventDao.deleteByKeys(resolvedKeys)
            }

            val rejectedCount = response.results.count {
                it.outcome == WalletEventOutcomeDto.InsufficientChips
            }
            if (rejectedCount > 0) {
                syncLogger.w {
                    "Server rejected $rejectedCount chip events as insufficient — resetting local balance to authoritative."
                }
            }

            // Overwrite the local balance with the authoritative value.
            // After the dropped events that's the correct sum; if the
            // server applied something we hadn't seen (cross-device
            // grant), this is also where we pick it up.
            setBalance(response.balance)

            syncLogger.d {
                "Sync complete: ${pending.size} sent, " +
                    "${resolvedKeys.size} resolved, " +
                    "balance now ${response.balance}."
            }
            Unit
        }.onFailure {
            syncLogger.w(it) { "Chips sync failed; pending events stay queued for next launch." }
        }
    }

    private suspend fun ensureSeeded() {
        chipsDao.insertIfMissing(
            ChipsEntity(
                balance = ChipsRepository.STARTING_GRANT,
                updatedAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun WalletEventEntity.toDto(): WalletEventDto = WalletEventDto(
        idempotencyKey = idempotencyKey,
        delta = delta,
        reason = reason,
    )

    private companion object {
        // Set of outcomes that mean "the event is done"; the client
        // drops its local pending row. Unknown stays so a newer client
        // can handle it.
        val RESOLVED_OUTCOMES = setOf(
            WalletEventOutcomeDto.Applied,
            WalletEventOutcomeDto.AlreadyApplied,
            WalletEventOutcomeDto.InsufficientChips,
        )
    }
}
