package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsEntity
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventDao
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
 * generated UUID if none was supplied). [ChipsSyncServiceImpl] picks the
 * pending events up on cold boot / foreground and flushes them through
 * `POST /v1/me/wallet/sync`.
 *
 * [setBalance] is the inverse direction: the sync service overwrites the
 * local balance with the server's authoritative value after a successful
 * round-trip. Other callers should stay on [applyDelta].
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ChipsRepositoryImpl(
    private val chipsDao: ChipsDao,
    private val walletEventDao: WalletEventDao,
    private val clock: Clock,
) : ChipsRepository {

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
        // If a crash lands between the two, the sync service treats the
        // orphaned event as "the user wanted this delta to land, the
        // server might or might not have it." A duplicate enqueue is
        // harmless (INSERT OR IGNORE on the idempotency key).
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

    private suspend fun ensureSeeded() {
        chipsDao.insertIfMissing(
            ChipsEntity(
                balance = ChipsRepository.STARTING_GRANT,
                updatedAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }
}
