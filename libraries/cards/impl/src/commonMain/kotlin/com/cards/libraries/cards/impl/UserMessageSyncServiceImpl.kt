package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageKind
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.cards.UserMessageSyncService
import com.dangerfield.cards.libraries.cards.impl.dto.MessageSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.MessageSyncResponseDto
import com.dangerfield.cards.libraries.cards.impl.dto.UserMessageDto
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Single-flight sync: POSTs the local pending-ack ids, takes the
 * server's response as the truth at the sync boundary, and reconciles
 * the local cache.
 *
 * Failure leaves the local cache untouched — the user keeps seeing
 * cached not-yet-shown messages (offline-first), and the pending ack
 * queue stays for next time. We don't track per-ack success; the next
 * response answers that question for free (any id we acked won't come
 * back, and we drop it locally).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class UserMessageSyncServiceImpl(
    private val networkClient: NetworkClient,
    private val repository: UserMessageRepository,
) : UserMessageSyncService {

    private val logger = KLog.withTag("UserMessageSync")
    private val mutex = Mutex()

    override suspend fun sync(): Result<Unit> = mutex.withLock {
        Catching {
            val pendingAcks = repository.pendingAckIds()
            val response: MessageSyncResponseDto = networkClient.authenticatedClient
                .post("/v1/me/messages/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(MessageSyncRequestDto(ackedIds = pendingAcks))
                }
                .body()
            repository.replaceCache(response.messages.map { it.toDomain() })
            logger.d {
                "Sync ok: sent ${pendingAcks.size} ack(s), got ${response.messages.size} unread."
            }
            Unit
        }.onFailure {
            logger.w(it) { "User-message sync failed; cache + ack queue untouched." }
        }
    }

    private fun UserMessageDto.toDomain(): UserMessage = UserMessage(
        id = id,
        kind = UserMessageKind.fromWire(kind),
        emoji = emoji,
        title = title,
        body = body,
        deepLink = deepLink,
        createdAtEpochMs = createdAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
    )
}
