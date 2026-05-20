package com.dangerfield.cards.libraries.cards.impl.dto

import kotlinx.serialization.Serializable

/**
 * Wire format for `GET /v1/me/messages`. Mirrors the server's
 * contract in `apps/server/.../routes/MessageRoutes.kt`.
 *
 * Kept internal — the public domain stays narrow ([UserMessage] +
 * [UserMessageRepository]) so the wire shape can evolve without
 * touching consumers.
 */
@Serializable
internal data class MessagesResponseDto(
    val schemaVersion: Int = 1,
    val messages: List<UserMessageDto> = emptyList(),
)

@Serializable
internal data class UserMessageDto(
    val id: String,
    val emoji: String? = null,
    val title: String,
    val body: String,
    val deepLink: String? = null,
    val createdAtEpochMs: Long,
)
