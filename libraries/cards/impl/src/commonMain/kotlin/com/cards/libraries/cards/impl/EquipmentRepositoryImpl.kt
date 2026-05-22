package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.impl.dto.EquipmentOpDto
import com.dangerfield.cards.libraries.cards.impl.dto.EquipmentSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.EquipmentSyncResponseDto
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentDao
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentEntity
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * Room-backed equipment store. Persists optimistic toggles (Pending)
 * immediately, mirrors them back to the UI so equip/unequip feels instant,
 * and applies the server's authoritative snapshot via [sync] on cold boot
 * + warm foreground.
 *
 * No-op suppression for equip/unequip ([EquipmentToggleResult.NoChange])
 * happens when the requested state matches a SYNCED row — avoids thrashing
 * the DB with no-op writes when the user double-taps. Pending → Pending
 * re-toggles are NOT suppressed because the intervening `updatedAtEpochMs`
 * bump may matter for LWW.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = EquipmentRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class EquipmentRepositoryImpl(
    private val equipmentDao: EquipmentDao,
    private val networkClient: NetworkClient,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
) : EquipmentRepository, AppEventListener {

    private val syncLogger = KLog.withTag("EquipmentSync")
    private val syncMutex = Mutex()

    override fun observeEquipped(): Flow<List<EquipmentEntry>> =
        equipmentDao.observeEquipped().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<EquipmentEntry> =
        equipmentDao.getAll().map { it.toDomain() }

    override suspend fun equip(productId: String): EquipmentToggleResult =
        toggleTo(productId, equipped = true)

    override suspend fun unequip(productId: String): EquipmentToggleResult =
        toggleTo(productId, equipped = false)

    private suspend fun toggleTo(productId: String, equipped: Boolean): EquipmentToggleResult {
        val existing = equipmentDao.getByProductId(productId)
        if (existing != null &&
            existing.isEquipped == equipped &&
            existing.syncState == EquipmentSyncState.Synced.name
        ) {
            return EquipmentToggleResult.NoChange
        }
        val now = clock.now().toEpochMilliseconds()
        equipmentDao.upsert(
            EquipmentEntity(
                productId = productId,
                isEquipped = equipped,
                syncState = EquipmentSyncState.Pending.name,
                updatedAtEpochMs = now,
            ),
        )
        return EquipmentToggleResult.Success
    }

    override suspend fun applyServerSnapshot(authoritative: List<EquipmentEntry>) {
        val authoritativeIds = authoritative.map { it.productId }.toSet()
        val localPending = equipmentDao.getAll()
            .filter { it.syncState == EquipmentSyncState.Pending.name }

        // 1) Server-truth rows: REPLACE locally as Synced. If a local
        //    Pending equip matches a server equip, that's a successful
        //    upload — the row flips to Synced and the LWW timestamp wins.
        if (authoritative.isNotEmpty()) {
            equipmentDao.insertAll(
                authoritative.map { entry ->
                    EquipmentEntity(
                        productId = entry.productId,
                        isEquipped = true,
                        syncState = EquipmentSyncState.Synced.name,
                        updatedAtEpochMs = entry.updatedAtEpochMs,
                    )
                },
            )
        }

        // 2) Local Pending UNEQUIPS whose product isn't in the server set:
        //    the server agrees the product is unequipped — our intent was
        //    fulfilled (perhaps as a no-op). Flip to Synced; the DAO's
        //    `purgeSyncedUnequips()` reclaims the row on the next pass.
        val confirmedUnequips = localPending
            .filter { !it.isEquipped && it.productId !in authoritativeIds }
            .map { it.productId }
        if (confirmedUnequips.isNotEmpty()) {
            equipmentDao.markSynced(confirmedUnequips)
        }

        // 3) Local rows that say "equipped" but the server snapshot doesn't
        //    include: a Pending one is still in-flight (next cycle retries);
        //    a Synced one means the server has since unequipped it on a
        //    different device — local should follow. We write an explicit
        //    Synced-unequipped row, which the purge step reclaims.
        val supersededEquips = equipmentDao.getAll()
            .filter { it.isEquipped && it.productId !in authoritativeIds && it.syncState == EquipmentSyncState.Synced.name }
        if (supersededEquips.isNotEmpty()) {
            equipmentDao.insertAll(
                supersededEquips.map { row ->
                    row.copy(
                        isEquipped = false,
                        syncState = EquipmentSyncState.Synced.name,
                        updatedAtEpochMs = row.updatedAtEpochMs,
                    )
                },
            )
        }

        equipmentDao.purgeSyncedUnequips()
    }

    override suspend fun dropOrphanEquipment(ownedProductIds: Set<String>): List<String> {
        val orphans = equipmentDao.getAll()
            .map { it.productId }
            .filter { it !in ownedProductIds }
        if (orphans.isNotEmpty()) {
            equipmentDao.deleteByProductIds(orphans)
        }
        return orphans
    }

    override suspend fun deleteAll() {
        equipmentDao.deleteAll()
    }

    override fun onColdBoot(event: AppEvent.ColdBoot) {
        appScope.launch { sync() }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        if (event.isColdBoot) return
        appScope.launch { sync() }
    }

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
        Catching {
            val all = getAll()
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

            syncLogger.d { "Syncing ${pending.size} pending equipment ops." }
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
            applyServerSnapshot(authoritative)
            syncLogger.d { "Equipment sync complete: ${authoritative.size} server-equipped." }
            Unit
        }.onFailure { syncLogger.w(it) { "Equipment sync failed; pending rows stay Pending." } }
    }

    private fun EquipmentEntity.toDomain(): EquipmentEntry = EquipmentEntry(
        productId = productId,
        isEquipped = isEquipped,
        syncState = Catching { EquipmentSyncState.valueOf(syncState) }
            .getOrDefault(EquipmentSyncState.Pending),
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
