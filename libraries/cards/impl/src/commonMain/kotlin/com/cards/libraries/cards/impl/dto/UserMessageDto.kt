package com.dangerfield.cards.libraries.cards.impl.dto

import kotlinx.serialization.Serializable

/**
 * Wire format for `POST /v1/me/messages/sync`. Mirrors the server's
 * contract in `apps/server/.../routes/MessageRoutes.kt`.
 *
 * Kept internal — the public domain stays narrow ([UserMessage] +
 * [UserMessageRepository]) so the wire shape can evolve without
 * touching consumers.
 */
@Serializable
internal data class MessageSyncRequestDto(
    val ackedIds: List<String> = emptyList(),
)

@Serializable
internal data class MessageSyncResponseDto(
    val schemaVersion: Int = 1,
    val messages: List<UserMessageDto> = emptyList(),
)

@Serializable
internal data class UserMessageDto(
    val id: String,
    /** "dialog" or "inbox" — unknown values fall back to dialog on
     *  the client (see `UserMessageKind.fromWire`). */
    val kind: String? = null,
    val emoji: String? = null,
    val title: String,
    val body: String,
    val deepLink: String? = null,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
)
