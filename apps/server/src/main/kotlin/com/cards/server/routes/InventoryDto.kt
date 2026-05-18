package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire format for `POST /v1/inventory/sync`.
 *
 * Client posts every locally-pending purchase. Server reconciles against
 * its own ledger (with auth this means: validate the user's chip balance
 * across all their devices, ensure no double-spend) and returns one
 * [InventorySyncResultDto] per submitted purchase.
 *
 * Last-write-wins semantics: if multiple devices report different states,
 * the server's authoritative balance + ledger decides. The V1 server
 * (pre-auth) accepts everything as Confirmed — real reconciliation lands
 * with the user account work.
 *
 * camelCase JSON matches the rest of the API.
 */
@Serializable
data class InventorySyncRequest(
    val purchases: List<PendingPurchaseDto> = emptyList(),
)

@Serializable
data class PendingPurchaseDto(
    val productId: String,
    /** Wall-clock ms the client recorded at redemption. Server uses this to
     *  order writes if the same product appears in multiple sync requests. */
    val purchasedAtEpochMs: Long,
    /** Chip cost charged client-side. Server may re-verify against catalog
     *  pricing — drift means a stale client. */
    val costChipsAtPurchase: Long,
)

@Serializable
data class InventorySyncResponse(
    val schemaVersion: Int = 1,
    val results: List<InventorySyncResultDto>,
)

@Serializable
data class InventorySyncResultDto(
    val productId: String,
    val outcome: SyncOutcomeDto,
    /**
     * Set when [outcome] is [SyncOutcomeDto.Reverted] — chips to credit
     * back to the user. The client adjusts its local balance by this
     * amount before deleting the inventory entry. Always positive.
     */
    val chipsToRefund: Long? = null,
    /**
     * Human-readable reason, intended for debug + telemetry. The client
     * may surface this in a toast for advanced users; production UI should
     * fall back to a generic "purchase reverted" message.
     */
    val message: String? = null,
)

/**
 * Per-purchase outcomes. New variants must be additive — the client falls
 * back to [Confirmed] (most permissive) if it receives an unknown enum
 * value at runtime.
 */
@Serializable
enum class SyncOutcomeDto {
    /** Purchase honored. Client marks the row as Confirmed. */
    Confirmed,

    /** Server rejected the purchase — client should remove the row AND
     *  credit `chipsToRefund` back to the wallet. */
    Reverted,
}
