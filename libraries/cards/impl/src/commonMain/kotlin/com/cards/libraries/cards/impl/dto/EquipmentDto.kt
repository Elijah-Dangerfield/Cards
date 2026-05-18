package com.dangerfield.cards.libraries.cards.impl.dto

import kotlinx.serialization.Serializable

/**
 * Wire format for `POST /v1/equipment/sync`. Mirrors
 * `apps/server/src/.../routes/EquipmentDto.kt`.
 *
 * Internal to `:libraries:cards:impl`; the public domain types live in
 * the api module so the wire shape can evolve without rippling.
 */
@Serializable
internal data class EquipmentSyncRequestDto(
    val ops: List<EquipmentOpDto> = emptyList(),
)

@Serializable
internal data class EquipmentOpDto(
    val productId: String,
    val equip: Boolean,
    val updatedAtEpochMs: Long,
)

@Serializable
internal data class EquipmentSyncResponseDto(
    val schemaVersion: Int = 1,
    val serverNowEpochMs: Long = 0L,
    val equipped: List<EquippedItemDto> = emptyList(),
)

@Serializable
internal data class EquippedItemDto(
    val productId: String,
    val updatedAtEpochMs: Long,
)
