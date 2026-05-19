package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire format for the server-authoritative wallet endpoints.
 *
 * `GET /v1/me/wallet` returns [WalletResponse]. `POST /v1/me/wallet/sync`
 * accepts [WalletSyncRequest] (a batch of locally-applied chip events
 * the client wants to flush to the server) and returns
 * [WalletSyncResponse] with the post-sync authoritative balance plus
 * per-event outcomes.
 *
 * The sync model is "client tells the server what already happened
 * locally, server reconciles." The idempotency key (a client-generated
 * unique string, typically UUID) is the dedup boundary — replaying the
 * same key is a no-op and the response says so via
 * [WalletEventOutcomeDto.AlreadyApplied]. That's how a flaky network
 * doesn't double-grant chips on retry.
 *
 * Deltas can be negative (debits). The server's wallet has a
 * non-negative invariant, so an event that would push the balance below
 * zero comes back as [WalletEventOutcomeDto.InsufficientChips] and the
 * client surfaces a soft reconcile toast.
 */
@Serializable
data class WalletResponse(
    val balance: Long,
)

@Serializable
data class WalletSyncRequest(
    val events: List<WalletEventDto> = emptyList(),
)

@Serializable
data class WalletEventDto(
    /** Client-generated unique key — typically a UUID. Same key across
     *  retries = single application. */
    val idempotencyKey: String,
    /** Signed; negative = debit. */
    val delta: Long,
    /** Free-form short string describing the source. Examples:
     *  "starter_grant", "iap.chip_pack_medium",
     *  "achievement.first_full_house", "shop.cardback_marble". */
    val reason: String,
)

@Serializable
data class WalletSyncResponse(
    val schemaVersion: Int = 1,
    /** Post-sync authoritative balance, after every Applied event ran. */
    val balance: Long,
    val results: List<WalletEventResultDto>,
)

@Serializable
data class WalletEventResultDto(
    val idempotencyKey: String,
    val outcome: WalletEventOutcomeDto,
    /** Balance immediately after this specific event was applied. Echoed
     *  back so the client can pin a per-row activity log. Same value as
     *  [WalletSyncResponse.balance] for the final event in the batch. */
    val balance: Long,
    /** Optional human-readable message. The client surfaces it on
     *  [WalletEventOutcomeDto.InsufficientChips] as a soft toast, e.g.
     *  "Not enough chips for that purchase — your balance is back to X." */
    val message: String? = null,
)

/**
 * Per-event sync outcomes. Additive — clients fall back to
 * [Applied] (most permissive) if they receive an unknown enum value
 * at runtime, mirroring the inventory-sync pattern.
 */
@Serializable
enum class WalletEventOutcomeDto {
    /** Event committed for the first time. */
    Applied,

    /** Event with this idempotency key was already on the server; the
     *  current call is a no-op replay. Balance is the existing value. */
    AlreadyApplied,

    /** Negative delta would have dipped the wallet below zero. Server
     *  did NOT write the event; balance is unchanged. Client should
     *  surface a reconcile message and stop retrying this event. */
    InsufficientChips,
}
