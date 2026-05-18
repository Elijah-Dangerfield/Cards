package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire format for `POST /v1/equipment/sync`. Mirrors the inventory sync
 * envelope so clients can adopt the same retry / single-flight loop they
 * already have for inventory.
 *
 * Each [EquipmentOpDto] is a "client wants this state" pending op. The
 * server applies them in [updatedAtEpochMs] order, last-write-wins,
 * then returns the user's full equipped set as the authoritative
 * post-reconcile snapshot.
 *
 * Empty `ops` is valid and means "give me the current state" — the
 * response is the equipped snapshot with no mutation.
 */
@Serializable
data class EquipmentSyncRequest(
    val ops: List<EquipmentOpDto> = emptyList(),
)

@Serializable
data class EquipmentOpDto(
    val productId: String,
    /** True = equip; false = unequip. */
    val equip: Boolean,
    /** Client wall-clock at the moment the user toggled. Used for last-write-wins. */
    val updatedAtEpochMs: Long,
)

@Serializable
data class EquipmentSyncResponse(
    val schemaVersion: Int = 1,
    /** Authoritative server time at response generation. Useful for clients
     *  doing their own consistency reasoning later. */
    val serverNowEpochMs: Long,
    /** Every product the user currently has equipped, per the server. */
    val equipped: List<EquippedItemDto>,
)

@Serializable
data class EquippedItemDto(
    val productId: String,
    val updatedAtEpochMs: Long,
)
