package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.FriendRelationsTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.RespondResult
import com.dangerfield.cards.server.domain.SendRequestResult
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
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

/**
 * Exposed-backed [FriendRepository]. One row per canonical user pair
 * (`user_a` < `user_b`); every read/write canonicalises first so direction
 * never matters. `acted_by` records who set the current state. Reads scan
 * either side of the pair (`user_a` is the PK's leading column, `user_b` is
 * indexed), so no per-user summary row is kept.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresFriendRepository(
    private val database: Database,
    private val clock: Clock,
) : FriendRepository {

    override suspend fun sendRequest(from: UserId, to: UserId): SendRequestResult {
        if (from == to) return SendRequestResult.SelfRequest
        val pair = canonical(from, to)
        return database.transaction {
            when (val existing = findRow(pair)) {
                null -> try {
                    insertRelation(pair, state = STATE_REQUESTED, actedBy = from.value)
                    SendRequestResult.Sent
                } catch (e: ExposedSQLException) {
                    if (e.isUniqueViolation()) SendRequestResult.AlreadyRequested else throw e
                }
                else -> when {
                    existing.state == STATE_BLOCKED -> SendRequestResult.Blocked
                    existing.state == STATE_ACCEPTED -> SendRequestResult.AlreadyFriends
                    existing.actedBy == from.value -> SendRequestResult.AlreadyRequested
                    else -> {
                        updateState(pair, state = STATE_ACCEPTED, actedBy = from.value)
                        SendRequestResult.NowFriends
                    }
                }
            }
        }
    }

    override suspend fun accept(me: UserId, other: UserId): RespondResult {
        if (me == other) return RespondResult.NotFound
        val pair = canonical(me, other)
        return database.transaction {
            val existing = findRow(pair) ?: return@transaction RespondResult.NotFound
            when (existing.state) {
                STATE_BLOCKED -> RespondResult.Forbidden
                STATE_ACCEPTED -> RespondResult.Ok
                else -> if (existing.actedBy == me.value) {
                    RespondResult.Forbidden
                } else {
                    updateState(pair, state = STATE_ACCEPTED, actedBy = me.value)
                    RespondResult.Ok
                }
            }
        }
    }

    override suspend fun decline(me: UserId, other: UserId): RespondResult {
        if (me == other) return RespondResult.NotFound
        val pair = canonical(me, other)
        return database.transaction {
            val existing = findRow(pair) ?: return@transaction RespondResult.NotFound
            when (existing.state) {
                STATE_REQUESTED -> {
                    deleteRow(pair)
                    RespondResult.Ok
                }
                else -> RespondResult.Forbidden
            }
        }
    }

    override suspend fun block(me: UserId, other: UserId) {
        if (me == other) return
        val pair = canonical(me, other)
        database.transaction {
            if (findRow(pair) == null) {
                insertRelation(pair, state = STATE_BLOCKED, actedBy = me.value)
            } else {
                updateState(pair, state = STATE_BLOCKED, actedBy = me.value)
            }
        }
    }

    override suspend fun listFriends(userId: UserId): List<UserId> = database.transaction {
        FriendRelationsTable
            .selectAll()
            .where {
                (FriendRelationsTable.state eq STATE_ACCEPTED) and userIsMember(userId.value)
            }
            .orderBy(FriendRelationsTable.updatedAt to SortOrder.DESC)
            .map { it.otherMember(userId.value) }
    }

    override suspend fun listBlockedUserIds(userId: UserId): Set<UserId> = database.transaction {
        FriendRelationsTable
            .selectAll()
            .where { (FriendRelationsTable.state eq STATE_BLOCKED) and userIsMember(userId.value) }
            .map { it.otherMember(userId.value) }
            .toSet()
    }

    override suspend fun listIncomingRequests(userId: UserId): List<UserId> = database.transaction {
        FriendRelationsTable
            .selectAll()
            .where {
                (FriendRelationsTable.state eq STATE_REQUESTED) and
                    (FriendRelationsTable.actedBy neq userId.value) and
                    userIsMember(userId.value)
            }
            .orderBy(FriendRelationsTable.createdAt to SortOrder.DESC)
            .map { UserId(it[FriendRelationsTable.actedBy]) }
    }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            FriendRelationsTable.deleteWhere { userIsMember(userId.value) }
        }
    }

    private fun findRow(pair: CanonicalPair): RelationRow? =
        FriendRelationsTable
            .selectAll()
            .where { (FriendRelationsTable.userA eq pair.a) and (FriendRelationsTable.userB eq pair.b) }
            .firstOrNull()
            ?.let { RelationRow(it[FriendRelationsTable.state], it[FriendRelationsTable.actedBy]) }

    private fun insertRelation(pair: CanonicalPair, state: String, actedBy: UUID) {
        val now = clock.now().toJavaInstant()
        FriendRelationsTable.insert {
            it[userA] = pair.a
            it[userB] = pair.b
            it[FriendRelationsTable.state] = state
            it[FriendRelationsTable.actedBy] = actedBy
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private fun updateState(pair: CanonicalPair, state: String, actedBy: UUID) {
        FriendRelationsTable.update({
            (FriendRelationsTable.userA eq pair.a) and (FriendRelationsTable.userB eq pair.b)
        }) {
            it[FriendRelationsTable.state] = state
            it[FriendRelationsTable.actedBy] = actedBy
            it[updatedAt] = clock.now().toJavaInstant()
        }
    }

    private fun deleteRow(pair: CanonicalPair) {
        FriendRelationsTable.deleteWhere {
            (FriendRelationsTable.userA eq pair.a) and (FriendRelationsTable.userB eq pair.b)
        }
    }

    private fun ResultRow.otherMember(me: UUID): UserId {
        val a = this[FriendRelationsTable.userA]
        return UserId(if (a == me) this[FriendRelationsTable.userB] else a)
    }

    private fun ExposedSQLException.isUniqueViolation(): Boolean {
        val sqlState = (cause as? java.sql.SQLException)?.sqlState
            ?: (this as? java.sql.SQLException)?.sqlState
        return sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE
    }

    private data class RelationRow(val state: String, val actedBy: UUID)

    private data class CanonicalPair(val a: UUID, val b: UUID)

    private companion object {
        const val STATE_REQUESTED = "requested"
        const val STATE_ACCEPTED = "accepted"
        const val STATE_BLOCKED = "blocked"
        const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"

        /**
         * Canonicalise an unordered pair so `a` is the lexicographically smaller
         * UUID. Compares the canonical lowercase-hex string, which orders the
         * same as Postgres's `uuid` `<` operator (fixed-width hex byte order) —
         * matching the `CHECK (user_a < user_b)` the migration enforces.
         */
        fun canonical(x: UserId, y: UserId): CanonicalPair {
            val xs = x.value.toString()
            val ys = y.value.toString()
            return if (xs < ys) CanonicalPair(x.value, y.value) else CanonicalPair(y.value, x.value)
        }
    }
}

private fun userIsMember(userId: UUID): Op<Boolean> =
    (FriendRelationsTable.userA eq userId) or (FriendRelationsTable.userB eq userId)
