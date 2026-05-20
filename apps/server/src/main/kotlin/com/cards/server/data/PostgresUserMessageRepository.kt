package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.UserMessagesTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.CreateMessageOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageRepository
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed-backed implementation of [UserMessageRepository].
 *
 * Idempotency: the `user_messages_user_idempotency_uq` unique index
 * is the dedup boundary on create. We attempt the insert; if it
 * raises 23505 we re-read the existing row and return it with
 * `wasAlreadyCreated = true`.
 *
 * Ack: a single UPDATE … WHERE acked_at IS NULL is the natural
 * compare-and-set — a concurrent second ack sees zero rows updated
 * and is reported as "already acked / unknown" without distinguishing
 * the two (intentional; see the interface doc).
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresUserMessageRepository(
    private val database: Database,
    private val clock: Clock,
) : UserMessageRepository {

    override suspend fun create(
        id: UUID,
        userId: UserId,
        idempotencyKey: String,
        emoji: String?,
        title: String,
        body: String,
        deepLink: String?,
    ): CreateMessageOutcome = database.transaction {
        // Pre-check by (userId, key). Replays hit this path. Postgres
        // aborts the transaction on any error so we can't catch a
        // duplicate-insert and re-read in the same txn — pre-checking
        // dodges that entirely.
        readByIdempotencyKey(userId, idempotencyKey)?.let { existing ->
            return@transaction CreateMessageOutcome(message = existing, wasAlreadyCreated = true)
        }
        val now = clock.now()
        UserMessagesTable.insert {
            it[UserMessagesTable.id] = id
            it[UserMessagesTable.userId] = userId.value
            it[UserMessagesTable.idempotencyKey] = idempotencyKey
            it[UserMessagesTable.emoji] = emoji
            it[UserMessagesTable.title] = title
            it[UserMessagesTable.body] = body
            it[UserMessagesTable.deepLink] = deepLink
            it[UserMessagesTable.createdAt] = now.toJavaInstant()
            it[UserMessagesTable.ackedAt] = null
        }
        val inserted = readById(id) ?: error("Message row missing immediately after insert: $id")
        CreateMessageOutcome(message = inserted, wasAlreadyCreated = false)
    }

    override suspend fun unreadFor(userId: UserId, limit: Int): List<UserMessage> =
        database.transaction {
            UserMessagesTable
                .selectAll()
                .where {
                    (UserMessagesTable.userId eq userId.value) and
                        UserMessagesTable.ackedAt.isNull()
                }
                .orderBy(UserMessagesTable.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toDomain() }
        }

    override suspend fun ack(userId: UserId, id: UUID, at: Instant): Boolean =
        database.transaction {
            val rows = UserMessagesTable.update({
                (UserMessagesTable.id eq id) and
                    (UserMessagesTable.userId eq userId.value) and
                    UserMessagesTable.ackedAt.isNull()
            }) {
                it[UserMessagesTable.ackedAt] = at.toJavaInstant()
            }
            rows > 0
        }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            UserMessagesTable.deleteWhere { UserMessagesTable.userId eq userId.value }
        }
    }

    private fun readById(id: UUID): UserMessage? = UserMessagesTable
        .selectAll()
        .where { UserMessagesTable.id eq id }
        .singleOrNull()
        ?.toDomain()

    private fun readByIdempotencyKey(userId: UserId, key: String): UserMessage? = UserMessagesTable
        .selectAll()
        .where {
            (UserMessagesTable.userId eq userId.value) and
                (UserMessagesTable.idempotencyKey eq key)
        }
        .singleOrNull()
        ?.toDomain()

    private fun ResultRow.toDomain(): UserMessage = UserMessage(
        id = this[UserMessagesTable.id],
        userId = UserId(this[UserMessagesTable.userId]),
        idempotencyKey = this[UserMessagesTable.idempotencyKey],
        emoji = this[UserMessagesTable.emoji],
        title = this[UserMessagesTable.title],
        body = this[UserMessagesTable.body],
        deepLink = this[UserMessagesTable.deepLink],
        createdAt = this[UserMessagesTable.createdAt].toKotlinInstant(),
        ackedAt = this[UserMessagesTable.ackedAt]?.toKotlinInstant(),
    )

}
