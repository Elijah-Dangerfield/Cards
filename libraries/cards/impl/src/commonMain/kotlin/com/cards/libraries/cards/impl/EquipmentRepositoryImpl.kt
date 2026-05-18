package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentDao
import com.dangerfield.cards.libraries.cards.storage.db.EquipmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * Room-backed equipment store. The repo's three responsibilities:
 *  - Persist optimistic toggles (Pending) immediately.
 *  - Mirror the observed flow back to the UI so equip/unequip feels
 *    instant.
 *  - Apply the server's authoritative snapshot when the sync service
 *    hands it over.
 *
 * No-op suppression for equip/unequip ([EquipmentToggleResult.NoChange])
 * happens when the requested state matches a SYNCED row — avoids
 * thrashing the DB with no-op writes when the user double-taps.
 * Pending → Pending re-toggles are NOT suppressed because the
 * intervening `updatedAtEpochMs` bump may matter for LWW.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class EquipmentRepositoryImpl(
    private val equipmentDao: EquipmentDao,
    private val clock: Clock,
) : EquipmentRepository {

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

    override suspend fun deleteAll() {
        equipmentDao.deleteAll()
    }

    private fun EquipmentEntity.toDomain(): EquipmentEntry = EquipmentEntry(
        productId = productId,
        isEquipped = isEquipped,
        syncState = runCatching { EquipmentSyncState.valueOf(syncState) }
            .getOrDefault(EquipmentSyncState.Pending),
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
