package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageKind
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.cards.storage.db.UserMessageDao
import com.dangerfield.cards.libraries.cards.storage.db.UserMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class UserMessageRepositoryImpl(
    private val dao: UserMessageDao,
    private val clock: Clock,
) : UserMessageRepository {

    override fun observeInbox(): Flow<List<UserMessage>> =
        dao.observeInbox(nowEpochMs = clock.now().toEpochMilliseconds())
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeUnreadInboxCount(): Flow<Int> =
        dao.observeUnreadInboxCount(nowEpochMs = clock.now().toEpochMilliseconds())

    override suspend fun consumeNextDialog(): UserMessage? =
        dao.consumeNextDialog(nowEpochMs = clock.now().toEpochMilliseconds())?.toDomain()

    override suspend fun markAllInboxShown(): Int =
        dao.markAllUnreadInboxShown(nowEpochMs = clock.now().toEpochMilliseconds())

    override suspend fun replaceCache(messages: List<UserMessage>) {
        dao.replaceCache(messages.map { it.toEntity() })
    }

    override suspend fun pendingAckIds(): List<String> = dao.pendingAckIds()

    private fun UserMessageEntity.toDomain(): UserMessage = UserMessage(
        id = id,
        kind = UserMessageKind.fromWire(kind),
        emoji = emoji,
        title = title,
        body = body,
        deepLink = deepLink,
        createdAtEpochMs = createdAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
    )

    private fun UserMessage.toEntity(): UserMessageEntity = UserMessageEntity(
        id = id,
        kind = when (kind) {
            UserMessageKind.Dialog -> "dialog"
            UserMessageKind.Inbox -> "inbox"
        },
        emoji = emoji,
        title = title,
        body = body,
        deepLink = deepLink,
        createdAtEpochMs = createdAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        // shown_at + acked_pending get preserved by `dao.replaceCache`
        // for any id that survives the diff. New rows start unshown
        // and unacked.
        shownAtEpochMs = null,
        ackedPending = false,
    )
}
