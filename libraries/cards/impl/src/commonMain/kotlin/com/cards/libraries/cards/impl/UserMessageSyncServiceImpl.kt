package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.cards.UserMessageSyncService
import com.dangerfield.cards.libraries.cards.impl.dto.MessagesResponseDto
import com.dangerfield.cards.libraries.cards.impl.dto.UserMessageDto
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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
            val response: MessagesResponseDto = networkClient.authenticatedClient
                .get("/v1/me/messages")
                .body()
            repository.setUnread(response.messages.map { it.toDomain() })
            logger.d { "Sync complete: ${response.messages.size} unread." }
            Unit
        }.onFailure {
            logger.w(it) { "User-message sync failed; leaving local cache untouched." }
        }
    }

    private fun UserMessageDto.toDomain(): UserMessage = UserMessage(
        id = id,
        emoji = emoji,
        title = title,
        body = body,
        deepLink = deepLink,
        createdAtEpochMs = createdAtEpochMs,
    )
}
