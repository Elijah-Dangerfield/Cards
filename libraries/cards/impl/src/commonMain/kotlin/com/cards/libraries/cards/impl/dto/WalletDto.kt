package com.dangerfield.cards.libraries.cards.impl.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for `GET /v1/me/wallet` and `POST /v1/me/wallet/sync`.
 * Mirrors the server's contract in
 * `apps/server/src/.../routes/WalletDto.kt`.
 *
 * Kept internal — the public domain stays narrow ([ChipsRepository])
 * and the wire shape can evolve without touching
 * consumers.
 */
@Serializable
internal data class WalletResponseDto(
    val balance: Long,
)

@Serializable
internal data class WalletSyncRequestDto(
    val events: List<WalletEventDto>,
)

@Serializable
internal data class WalletEventDto(
    val idempotencyKey: String,
    val delta: Long,
    val reason: String,
)

@Serializable
internal data class WalletSyncResponseDto(
    val schemaVersion: Int = 1,
    val balance: Long,
    val results: List<WalletEventResultDto> = emptyList(),
)

@Serializable
internal data class WalletEventResultDto(
    val idempotencyKey: String,
    val outcome: WalletEventOutcomeDto = WalletEventOutcomeDto.Unknown,
    val balance: Long,
    val message: String? = null,
)

/**
 * Per-event sync outcomes the server may report.
 *
 * [Unknown] is the fallback when the server adds a new enum value the
 * client doesn't know about — `coerceInputValues = !isDebug` on the
 * network Json (see :libraries:networking/NetworkJson) deserializes
 * unknown values as the default instead of crashing the app on
 * release.
 */
@Serializable
internal enum class WalletEventOutcomeDto {
    @SerialName("Applied") Applied,
    @SerialName("AlreadyApplied") AlreadyApplied,
    @SerialName("InsufficientChips") InsufficientChips,
    @SerialName("Unknown") Unknown,
}
