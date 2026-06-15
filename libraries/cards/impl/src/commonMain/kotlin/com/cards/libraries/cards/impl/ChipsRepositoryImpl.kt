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
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
 * [addChips] / [subtractChips] mutate the singleton chips row AND
 * enqueue a [WalletEventEntity] keyed by the caller-supplied idempotency
 * key (or a generated UUID if none was supplied). [sync] picks the
 * pending events up on cold boot / foreground and flushes them through
 * `POST /v1/me/wallet/sync`.
 *
 * **No client-side starter grant.** The server's `findOrCreate` seeds
 * the wallet with the authoritative starter grant; the first [sync]
 * hydrates the local row via [setBalance]. Until that lands, the local
 * store has no row and [observeBalance] / [getBalance] return null. UI
 * renders a spinner / hides the badge in that window — much cleaner than
 * the old "flash a placeholder, then replace with real" UX.
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

    private val _walletJustCreated = MutableStateFlow(false)
    override val walletJustCreated: StateFlow<Boolean> = _walletJustCreated.asStateFlow()

    override fun observeBalance(): Flow<Long?> = chipsDao.observeChips().map { it?.balance }

    override suspend fun getBalance(): Long? = chipsDao.getChips()?.balance

    override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
        require(amount > 0) { "addChips amount must be positive; got $amount" }
        applyDeltaInternal(delta = +amount, reason = reason, idempotencyKey = idempotencyKey)
    }

    override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) {
        require(amount > 0) { "subtractChips amount must be positive; got $amount" }
        applyDeltaInternal(delta = -amount, reason = reason, idempotencyKey = idempotencyKey)
    }

    /**
     * Shared path for both directions. Order matters:
     * 1. Insert the ledger row (idempotent on the key).
     * 2. Insert a zero-balance row if missing — UPDATE needs something
     *    to update. First optimistic write before the first sync lands
     *    here; the row stays at the delta until sync hydrates it. A
     *    crash between enqueue + UPDATE leaves the user with the right
     *    pending event, the wrong local balance — the next sync still
     *    arrives at the authoritative answer.
     * 3. Apply the delta.
     */
    private suspend fun applyDeltaInternal(delta: Long, reason: String, idempotencyKey: String?) {
        val nowEpochMs = clock.now().toEpochMilliseconds()
        val key = idempotencyKey ?: Uuid.random().toString()
        walletEventDao.insert(
            WalletEventEntity(
                idempotencyKey = key,
                delta = delta,
                reason = reason,
                appliedAtEpochMs = nowEpochMs,
            ),
        )
        chipsDao.insertIfMissing(
            ChipsEntity(balance = 0L, updatedAtEpochMs = nowEpochMs),
        )
        chipsDao.applyDelta(delta = delta, updatedAtEpochMs = nowEpochMs)
    }

    override suspend fun setBalance(authoritativeBalance: Long) {
        val nowEpochMs = clock.now().toEpochMilliseconds()
        val existing = chipsDao.getChips()
        if (existing == null) {
            // First sync after install — insert directly at the
            // authoritative value. No prior local row to reconcile.
            chipsDao.insertIfMissing(
                ChipsEntity(balance = authoritativeBalance, updatedAtEpochMs = nowEpochMs),
            )
        } else {
            val delta = authoritativeBalance - existing.balance
            if (delta != 0L) {
                chipsDao.applyDelta(delta = delta, updatedAtEpochMs = nowEpochMs)
            }
        }
    }

    override suspend fun deleteAll() {
        chipsDao.deleteAll()
        walletEventDao.deleteAll()
    }

    override fun onUserChanged(event: AppEvent.UserChanged) {
        // A user just became active — cold-boot resolve, sign-in, OR an
        // account switch. Their local wallet was just wiped by the user-scoped
        // clear (on a switch), so re-hydrate from the server now instead of
        // stranding the new user on a stale/empty balance until the next
        // foreground. Sign-out (current == null) has nothing to fetch.
        if (event.current == null) return
        appScope.launch { sync() }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        // The cold-boot initial sync is owned by [onUserChanged] (fired when
        // auth resolves a user), so skip the cold-boot-flagged foreground.
        // Warm resume IS where we want a fresh reconcile, so this handles that.
        if (event.isColdBoot) return
        appScope.launch { sync() }
    }

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
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
            // grant), this is also where we pick it up. setBalance
            // handles the no-local-row case by inserting directly.
            setBalance(response.balance)

            // Brand-new account: the server just lazy-created the wallet. Flip
            // the live, in-memory signal — `walletCreated` is true only on this
            // first-contact response, false on every later sync. The Home gate
            // ANDs it with !didSeeInitialGrantInOnboarding to reveal the grant.
            // Not persisted on purpose: a one-shot, server-sourced fact can't
            // then leak across an account switch.
            if (response.walletCreated) {
                _walletJustCreated.value = true
            }

            syncLogger.d {
                "Sync complete: ${pending.size} sent, " +
                    "${resolvedKeys.size} resolved, " +
                    "balance now ${response.balance}."
            }
            Unit
        }
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
