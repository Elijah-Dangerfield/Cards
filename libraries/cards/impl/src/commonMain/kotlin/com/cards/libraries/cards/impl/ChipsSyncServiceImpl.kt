package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.ChipsSyncService
import com.dangerfield.cards.libraries.cards.impl.dto.WalletEventDto
import com.dangerfield.cards.libraries.cards.impl.dto.WalletEventOutcomeDto
import com.dangerfield.cards.libraries.cards.impl.dto.WalletSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.WalletSyncResponseDto
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventDao
import com.dangerfield.cards.libraries.cards.storage.db.WalletEventEntity
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ChipsSyncServiceImpl(
    private val walletEventDao: WalletEventDao,
    private val chipsRepository: ChipsRepository,
    private val networkClient: NetworkClient,
) : ChipsSyncService {

    private val logger = KLog.withTag("ChipsSync")
    private val mutex = Mutex()

    override suspend fun sync(): Result<Unit> = mutex.withLock {
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

            val anyRejected = response.results.any {
                it.outcome == WalletEventOutcomeDto.InsufficientChips
            }
            if (anyRejected) {
                logger.w {
                    "Server rejected ${response.results.count { it.outcome == WalletEventOutcomeDto.InsufficientChips }} chip events as insufficient — resetting local balance to authoritative."
                }
            }

            // Overwrite the local balance with the authoritative value.
            // After the dropped events that's the correct sum; if the
            // server applied something we hadn't seen (cross-device
            // grant), this is also where we pick it up.
            chipsRepository.setBalance(response.balance)

            logger.d {
                "Sync complete: ${pending.size} sent, " +
                    "${resolvedKeys.size} resolved, " +
                    "balance now ${response.balance}."
            }
            Unit
        }.onFailure {
            logger.w(it) { "Chips sync failed; pending events stay queued for next launch." }
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
