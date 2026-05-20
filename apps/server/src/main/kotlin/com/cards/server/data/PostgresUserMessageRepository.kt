package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.UserMessagesTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.CreateMessageOutcome
import com.dangerfield.cards.server.domain.MessageSweepResult
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessage
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
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
 * Idempotency: the V8 `user_messages_user_idempotency_uq` unique index
 * is the dedup boundary on create. Pre-check by `(userId, key)` before
 * the insert — Postgres aborts the txn on a unique violation, and we
 * can't keep reading inside an aborted txn without a savepoint.
 *
 * The unread read filters `acked_at IS NULL AND (expires_at IS NULL OR
 * expires_at > now)` so a stale notice never reaches the client even
 * if the sweep cron is lagging.
 *
 * [ackMany] runs as a single UPDATE … WHERE id IN (…) AND user_id = …
 * AND acked_at IS NULL — natural compare-and-set against the user
 * boundary, so a client can't ack another user's messages.
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
        kind: UserMessageKind,
        emoji: String?,
        title: String,
        body: String,
        deepLink: String?,
        expiresAt: Instant?,
    ): CreateMessageOutcome = database.transaction {
        readByIdempotencyKey(userId, idempotencyKey)?.let { existing ->
            return@transaction CreateMessageOutcome(message = existing, wasAlreadyCreated = true)
        }
        val now = clock.now()
        UserMessagesTable.insert {
            it[UserMessagesTable.id] = id
            it[UserMessagesTable.userId] = userId.value
            it[UserMessagesTable.idempotencyKey] = idempotencyKey
            it[UserMessagesTable.kind] = kind.wire
            it[UserMessagesTable.emoji] = emoji
            it[UserMessagesTable.title] = title
            it[UserMessagesTable.body] = body
            it[UserMessagesTable.deepLink] = deepLink
            it[UserMessagesTable.createdAt] = now.toJavaInstant()
            it[UserMessagesTable.expiresAt] = expiresAt?.toJavaInstant()
            it[UserMessagesTable.ackedAt] = null
        }
        val inserted = readById(id) ?: error("Message row missing immediately after insert: $id")
        CreateMessageOutcome(message = inserted, wasAlreadyCreated = false)
    }

    override suspend fun unreadFor(
        userId: UserId,
        now: Instant,
        limit: Int,
    ): List<UserMessage> = database.transaction {
        val javaNow = now.toJavaInstant()
        UserMessagesTable
            .selectAll()
            .where {
                (UserMessagesTable.userId eq userId.value) and
                    UserMessagesTable.ackedAt.isNull() and
                    (UserMessagesTable.expiresAt.isNull() or (UserMessagesTable.expiresAt greater javaNow))
            }
            .orderBy(UserMessagesTable.createdAt to SortOrder.ASC)
            .limit(limit)
            .map { it.toDomain() }
    }

    override suspend fun ackMany(userId: UserId, ids: List<UUID>, at: Instant): Int =
        if (ids.isEmpty()) 0
        else database.transaction {
            UserMessagesTable.update({
                (UserMessagesTable.userId eq userId.value) and
                    (UserMessagesTable.id inList ids) and
                    UserMessagesTable.ackedAt.isNull()
            }) {
                it[UserMessagesTable.ackedAt] = at.toJavaInstant()
            }
        }

    override suspend fun sweepExpiredAndAcked(now: Instant): MessageSweepResult =
        database.transaction {
            val javaNow = now.toJavaInstant()
            // Two-pass sweep via selectAll-then-deleteWhere-by-id. The
            // `deleteWhere` lambda's receiver doesn't expose every
            // SqlExpressionBuilder operator (notably `isNotNull` and
            // `less`), so we resolve the row ids in a `where` block first
            // and delete by primary key.
            val ackedIds = UserMessagesTable
                .selectAll()
                .where { UserMessagesTable.ackedAt.isNotNull() }
                .map { it[UserMessagesTable.id] }
            val ackedPurged = if (ackedIds.isEmpty()) 0
                else UserMessagesTable.deleteWhere { UserMessagesTable.id inList ackedIds }

            val expiredIds = UserMessagesTable
                .selectAll()
                .where {
                    UserMessagesTable.ackedAt.isNull() and
                        UserMessagesTable.expiresAt.isNotNull() and
                        (UserMessagesTable.expiresAt less javaNow)
                }
                .map { it[UserMessagesTable.id] }
            val expiredPurged = if (expiredIds.isEmpty()) 0
                else UserMessagesTable.deleteWhere { UserMessagesTable.id inList expiredIds }

            MessageSweepResult(
                ackedPurged = ackedPurged,
                expiredUnackedPurged = expiredPurged,
            )
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
        kind = UserMessageKind.parse(this[UserMessagesTable.kind]),
        emoji = this[UserMessagesTable.emoji],
        title = this[UserMessagesTable.title],
        body = this[UserMessagesTable.body],
        deepLink = this[UserMessagesTable.deepLink],
        createdAt = this[UserMessagesTable.createdAt].toKotlinInstant(),
        expiresAt = this[UserMessagesTable.expiresAt]?.toKotlinInstant(),
        ackedAt = this[UserMessagesTable.ackedAt]?.toKotlinInstant(),
    )
}

/**
 * `inList` helper for the ack-many UPDATE and the sweep delete-by-id
 * passes. Routes through SqlExpressionBuilder.run since `Column.inList`
 * is defined as an extension inside that scope and `update`/`deleteWhere`
 * lambdas don't expose every operator on their receivers.
 */
private infix fun org.jetbrains.exposed.sql.Column<UUID>.inList(
    values: List<UUID>,
): org.jetbrains.exposed.sql.Op<Boolean> =
    org.jetbrains.exposed.sql.SqlExpressionBuilder.run { this@inList inList values }
