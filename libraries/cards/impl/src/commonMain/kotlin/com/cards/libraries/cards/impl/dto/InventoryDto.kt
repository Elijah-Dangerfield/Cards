package com.dangerfield.cards.libraries.cards.impl.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for `POST /v1/inventory/sync`. Mirrors the server's contract
 * in `apps/server/src/.../routes/InventoryDto.kt`.
 *
 * Kept distinct from the public domain ([com.dangerfield.cards.libraries.cards.InventoryItem])
 * so wire and domain can evolve separately.
 */
@Serializable
internal data class InventorySyncRequestDto(
    val purchases: List<PendingPurchaseDto>,
)

@Serializable
internal data class PendingPurchaseDto(
    val productId: String,
    val purchasedAtEpochMs: Long,
    val costChipsAtPurchase: Long,
)

@Serializable
internal data class InventorySyncResponseDto(
    val schemaVersion: Int = 1,
    val results: List<InventorySyncResultDto> = emptyList(),
    /**
     * Server-authoritative snapshot of everything the user owns after the
     * submitted purchases were reconciled. Mirrors `POST /v1/equipment/sync`
     * — the sync response IS the truth. Older servers that don't return
     * this field deserialize as empty; the client treats an empty snapshot
     * as "trust local state" (see InventoryRepositoryImpl.sync handling).
     */
    val owned: List<OwnedInventoryItemDto> = emptyList(),
)

@Serializable
internal data class OwnedInventoryItemDto(
    val productId: String,
    val costChipsAtPurchase: Long,
    val purchasedAtEpochMs: Long,
    /**
     * How the item entered inventory. `"purchased"` (default) for chip /
     * IAP buys, `"earned"` for achievement / league rewards. Servers
     * predating the V13 column emit no value; the default keeps older
     * snapshots interpreting cleanly as Purchased.
     */
    val acquisitionSource: String = "purchased",
)

@Serializable
internal data class InventorySyncResultDto(
    val productId: String,
    val outcome: SyncOutcomeDto = SyncOutcomeDto.Unknown,
    val chipsToRefund: Long? = null,
    val message: String? = null,
)

/**
 * Outcomes the server may report per purchase.
 *
 * [Unknown] is the fallback when the server adds a new enum value the
 * client doesn't know. With `coerceInputValues = !isDebug` on the network
 * Json (see :libraries:networking/NetworkJson), unknown values deserialize
 * as the default — Unknown — instead of crashing the app on release.
 */
@Serializable
internal enum class SyncOutcomeDto {
    @SerialName("Confirmed") Confirmed,
    @SerialName("Reverted") Reverted,
    @SerialName("Unknown") Unknown,
}
