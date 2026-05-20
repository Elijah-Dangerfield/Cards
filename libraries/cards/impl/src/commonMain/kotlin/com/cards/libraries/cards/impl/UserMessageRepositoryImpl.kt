package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.request.post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class UserMessageRepositoryImpl(
    private val networkClient: NetworkClient,
) : UserMessageRepository {

    private val logger = KLog.withTag("UserMessages")
    private val _unread = MutableStateFlow<List<UserMessage>>(emptyList())

    override val unread: StateFlow<List<UserMessage>> = _unread.asStateFlow()

    override suspend fun setUnread(messages: List<UserMessage>) {
        _unread.value = messages
    }

    override suspend fun ack(id: String) {
        // Local optimistic removal so the dialog dismisses immediately
        // — the ack endpoint is idempotent + 204s either way, so a flaky
        // network here just leaves a row on the server that the next
        // sync ignores (it filters by acked_at IS NULL).
        _unread.update { list -> list.filterNot { it.id == id } }

        Catching {
            networkClient.authenticatedClient.post("/v1/me/messages/$id/ack")
        }.onFailure {
            logger.w(it) { "Failed to ack message $id on the server; local state already removed." }
        }
    }
}
