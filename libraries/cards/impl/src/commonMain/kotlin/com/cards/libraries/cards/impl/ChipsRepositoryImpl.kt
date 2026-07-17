package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipSyncRejection
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.impl.dto.WalletEventDto
import com.dangerfield.cards.libraries.cards.impl.dto.WalletEventOutcomeDto
import com.dangerfield.cards.libraries.cards.impl.dto.WalletSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.WalletSyncResponseDto
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsEntity
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventDao
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventEntity
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 * The `chips` row holds only the last authoritative **server snapshot**;
 * [addChips] / [subtractChips] enqueue a [WalletEventEntity] keyed by the
 * caller-supplied idempotency key (or a generated UUID), and the balance
 * the app shows is always derived: snapshot + SUM(pending deltas). That
 * derivation is the PROG-11 fix — the old design blended optimistic
 * deltas into one mutable balance that [sync]'s authoritative overwrite
 * then stomped, hiding any grant that landed while the request was in
 * flight. Now such a grant stays a pending row and keeps counting until
 * the server itself resolves it. [sync] flushes the outbox through
 * `POST /v1/me/wallet/sync` on the coordinator's triggers.
 *
 * **No client-side starter grant.** The server's `findOrCreate` seeds
 * the wallet with the authoritative starter grant; the first [sync]
 * hydrates the snapshot via [setBalance]. Until that lands (and while
 * the outbox is empty) [observeBalance] / [getBalance] return null. UI
 * renders a spinner / hides the badge in that window — much cleaner than
 * the old "flash a placeholder, then replace with real" UX.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ChipsRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = UserScopedSyncer::class)
@Inject
class ChipsRepositoryImpl(
    private val chipsDao: ChipsDao,
    private val walletEventDao: WalletEventDao,
    private val networkClient: NetworkClient,
    private val clock: Clock,
) : ChipsRepository, UserScopedSyncer {

    private val syncLogger = KLog.withTag("ChipsSync")
    private val syncMutex = Mutex()

    private val _isReconciling = MutableStateFlow(false)
    override val isReconciling: StateFlow<Boolean> = _isReconciling.asStateFlow()

    private val syncRejections = MutableSharedFlow<ChipSyncRejection>(extraBufferCapacity = 8)

    override fun observeSyncRejections(): Flow<ChipSyncRejection> = syncRejections.asSharedFlow()

    override fun observeBalance(): Flow<Long?> =
        combine(chipsDao.observeChips(), walletEventDao.observePendingDelta()) { snapshot, pending ->
            foldBalance(snapshot = snapshot?.balance, pending = pending)
        }

    override suspend fun getBalance(): Long? =
        foldBalance(snapshot = chipsDao.getChips()?.balance, pending = walletEventDao.pendingDelta())

    private fun foldBalance(snapshot: Long?, pending: Long?): Long? = when {
        snapshot != null -> snapshot + (pending ?: 0L)
        else -> pending
    }

    override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
        require(amount > 0) { "addChips amount must be positive; got $amount" }
        applyDeltaInternal(delta = +amount, reason = reason, idempotencyKey = idempotencyKey)
    }

    override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) {
        require(amount > 0) { "subtractChips amount must be positive; got $amount" }
        applyDeltaInternal(delta = -amount, reason = reason, idempotencyKey = idempotencyKey)
    }

    /**
     * Shared path for both directions: enqueue the outbox row, nothing
     * else. The displayed balance derives from snapshot + pending, so the
     * write is visible immediately, a duplicate idempotency key (IGNOREd
     * by the insert) can't double-count, and a crash right after this
     * line loses nothing.
     */
    private suspend fun applyDeltaInternal(delta: Long, reason: String, idempotencyKey: String?) {
        walletEventDao.insert(
            WalletEventEntity(
                idempotencyKey = idempotencyKey ?: Uuid.random().toString(),
                delta = delta,
                reason = reason,
                appliedAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    override suspend fun setBalance(authoritativeBalance: Long) {
        // Zero-delta short-circuit: an unchanged snapshot must not churn
        // the row's timestamp (every sync would otherwise be a "touch").
        if (chipsDao.getChips()?.balance == authoritativeBalance) return
        chipsDao.upsert(
            ChipsEntity(
                balance = authoritativeBalance,
                updatedAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    override suspend fun deleteAll() {
        chipsDao.deleteAll()
        walletEventDao.deleteAll()
    }

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
        _isReconciling.value = true
        try {
            syncLocked()
        } finally {
            _isReconciling.value = false
        }
    }

    private suspend fun syncLocked(): Result<Unit> =
        // Always POST — an empty events list is a valid "hydrate
        // balance" call. That's how a second device picks up a chip
        // grant the user collected elsewhere.
        networkClient.authedCall("wallet.sync", retry = RetryPolicy.idempotent()) { client ->
            val pending = walletEventDao.getAll()

            val request = WalletSyncRequestDto(
                events = pending.map { it.toDto() },
            )
            val response: WalletSyncResponseDto = client
                .post("/v1/me/wallet/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                .body()

            // Per-event reconciliation. Applied + AlreadyApplied →
            // server has it, drop our pending row. InsufficientChips →
            // server refused; we drop the row too (retrying will just
            // bounce again) and let setBalance below restore the
            // authoritative value. RefusedServerOwned → the server mints
            // this reward itself (ENG-9); drop the row and let the
            // authoritative balance carry the server's grant. Unknown →
            // leave the row; a newer client will know what to do.
            val resolvedKeys = response.results
                .filter { it.outcome in RESOLVED_OUTCOMES }
                .map { it.idempotencyKey }
            if (resolvedKeys.isNotEmpty()) {
                walletEventDao.deleteByKeys(resolvedKeys)
            }

            val rejectedKeys = response.results
                .filter { it.outcome == WalletEventOutcomeDto.InsufficientChips }
                .map { it.idempotencyKey }
                .toSet()
            if (rejectedKeys.isNotEmpty()) {
                val rejectedChips = pending
                    .filter { it.idempotencyKey in rejectedKeys }
                    .sumOf { kotlin.math.abs(it.delta) }
                syncLogger.w {
                    "Server rejected ${rejectedKeys.size} chip events ($rejectedChips chips) as insufficient — " +
                        "dropping them and telling the user."
                }
                syncRejections.tryEmit(
                    ChipSyncRejection(rejectedEvents = rejectedKeys.size, rejectedChips = rejectedChips),
                )
            }

            // Overwrite the snapshot with the authoritative value. Events
            // still pending (enqueued mid-request, or Unknown outcomes)
            // keep riding on top of it via the fold; if the server applied
            // something we hadn't seen (cross-device grant), this is also
            // where we pick it up.
            setBalance(response.balance)

            syncLogger.d {
                "Sync complete: ${pending.size} sent, " +
                    "${resolvedKeys.size} resolved, " +
                    "balance now ${response.balance}."
            }
            Unit
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
            WalletEventOutcomeDto.RefusedServerOwned,
        )
    }
}
