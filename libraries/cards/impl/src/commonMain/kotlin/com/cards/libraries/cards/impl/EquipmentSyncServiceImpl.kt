package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentSyncService
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.impl.dto.EquipmentOpDto
import com.dangerfield.cards.libraries.cards.impl.dto.EquipmentSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.EquipmentSyncResponseDto
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

/**
 * Pushes every Pending equipment row to /v1/equipment/sync, then folds
 * the server's authoritative snapshot back into local state via
 * [EquipmentRepository.applyServerSnapshot].
 *
 * Always POSTs — even with an empty pending set — because the response
 * IS the server's current snapshot. That doubles as the cold-start fetch
 * for "what's equipped right now" (the alternative would be a separate
 * GET endpoint; one fewer route is one fewer thing to test and protect).
 *
 * Single-flight via mutex. Failures leave Pending rows intact for the
 * next bootstrap cycle to retry — no permanent loss, no double-toggling.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class EquipmentSyncServiceImpl(
    private val equipmentRepository: EquipmentRepository,
    private val networkClient: NetworkClient,
) : EquipmentSyncService {

    private val logger = KLog.withTag("EquipmentSync")
    private val mutex = Mutex()

    override suspend fun sync(): Result<Unit> = mutex.withLock {
        Catching {
            val all = equipmentRepository.getAll()
            val pending = all.filter { it.syncState == EquipmentSyncState.Pending }

            val request = EquipmentSyncRequestDto(
                ops = pending.map { row ->
                    EquipmentOpDto(
                        productId = row.productId,
                        equip = row.isEquipped,
                        updatedAtEpochMs = row.updatedAtEpochMs,
                    )
                },
            )

            logger.d { "Syncing ${pending.size} pending equipment ops." }
            val response: EquipmentSyncResponseDto = networkClient.authenticatedClient
                .post("/v1/equipment/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                .body()

            val authoritative = response.equipped.map { item ->
                EquipmentEntry(
                    productId = item.productId,
                    isEquipped = true,
                    syncState = EquipmentSyncState.Synced,
                    updatedAtEpochMs = item.updatedAtEpochMs,
                )
            }
            equipmentRepository.applyServerSnapshot(authoritative)
            logger.d { "Equipment sync complete: ${authoritative.size} server-equipped." }
            Unit
        }.onFailure { logger.w(it) { "Equipment sync failed; pending rows stay Pending." } }
    }
}
