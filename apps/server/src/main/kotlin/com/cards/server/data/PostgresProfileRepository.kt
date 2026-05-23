package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.ProfilesTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AvatarGenerator
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UsernameGenerator
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Exposed-backed implementation of [ProfileRepository].
 *
 * Idempotency: `userId` is the primary key of `profiles`. Two concurrent
 * first-contact requests for the same user race; the loser gets a unique-
 * violation and falls back to the read path.
 *
 * Display-name collisions are handled the same way at the
 * `profiles_display_name_uq` unique constraint — generate, try, retry on
 * conflict. The DB constraint is the canonical arbiter; the pre-check
 * pattern (`SELECT … WHERE display_name = ?` before insert) would just be
 * a race.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresProfileRepository(
    private val database: Database,
    private val usernameGenerator: UsernameGenerator,
    private val avatarGenerator: AvatarGenerator,
    private val clock: Clock,
) : ProfileRepository {

    override suspend fun findById(userId: UserId): Profile? = database.transaction {
        ProfilesTable
            .selectAll()
            .where { ProfilesTable.userId eq userId.value }
            .singleOrNull()
            ?.toProfile()
    }

    override suspend fun delete(userId: UserId) {
        database.transaction {
            ProfilesTable.deleteWhere { ProfilesTable.userId eq userId.value }
        }
    }

    override suspend fun update(
        userId: UserId,
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = database.transaction {
        val nothingToChange = displayName == null &&
            avatarEmoji == null &&
            avatarBackgroundColor == null &&
            !clearAvatarBackgroundColor
        if (nothingToChange) {
            val current = ProfilesTable
                .selectAll()
                .where { ProfilesTable.userId eq userId.value }
                .singleOrNull()
                ?.toProfile()
                ?: return@transaction UpdateProfileOutcome.NotFound
            return@transaction UpdateProfileOutcome.Success(current)
        }

        val now = clock.now()
        val nowJava = now.toJavaInstant()

        val updated = try {
            ProfilesTable.update({ ProfilesTable.userId eq userId.value }) { stmt ->
                displayName?.let { stmt[ProfilesTable.displayName] = it }
                avatarEmoji?.let { stmt[ProfilesTable.avatarEmoji] = it }
                if (clearAvatarBackgroundColor) {
                    stmt[ProfilesTable.avatarBackgroundColor] = null
                } else {
                    avatarBackgroundColor?.let { stmt[ProfilesTable.avatarBackgroundColor] = it }
                }
                stmt[ProfilesTable.updatedAt] = nowJava
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) return@transaction UpdateProfileOutcome.DisplayNameTaken
            throw e
        }

        if (updated == 0) return@transaction UpdateProfileOutcome.NotFound

        val refreshed = ProfilesTable
            .selectAll()
            .where { ProfilesTable.userId eq userId.value }
            .single()
            .toProfile()
        UpdateProfileOutcome.Success(refreshed)
    }

    override suspend fun findOrCreate(userId: UserId): Profile = database.transaction {
        // Fast path: already exists.
        val existing = ProfilesTable
            .selectAll()
            .where { ProfilesTable.userId eq userId.value }
            .singleOrNull()
        if (existing != null) return@transaction existing.toProfile()

        // Insert with retry on display_name collisions. The userId-key
        // collision (concurrent first-contact for the same user) is also
        // handled — if some other request just inserted the profile, our
        // own insert fails, we re-read.
        try {
            insertWithUniqueName(userId)
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) {
                // Either the userId PK or the displayName UQ tripped. The
                // userId case means a concurrent request beat us; re-read.
                // The displayName case is handled inside the retry loop —
                // if it bubbles out here we exhausted retries and surface
                // the failure.
                val raced = ProfilesTable
                    .selectAll()
                    .where { ProfilesTable.userId eq userId.value }
                    .singleOrNull()
                raced?.toProfile() ?: throw e
            } else {
                throw e
            }
        }
    }

    private fun insertWithUniqueName(userId: UserId): Profile {
        val now = clock.now()
        val nowJava = now.toJavaInstant()
        val emoji = avatarGenerator.random()
        // Every fresh profile gets a real background color so all client
        // rendering surfaces have one source of truth — null is no longer
        // a steady-state. See plan: foamy-tickling-meerkat.md.
        val backgroundColor = avatarGenerator.randomBackgroundColor()

        // The Reddit-style `Adj-Noun-NNN` space is ~450M combos at our
        // word-list size, so collisions are vanishingly rare — but loop
        // anyway as a defense against future shrinkage of either list.
        repeat(MAX_ATTEMPTS) {
            val name = usernameGenerator.random()
            if (tryInsert(userId, name, emoji, backgroundColor, nowJava)) {
                return Profile(
                    userId = userId,
                    displayName = name,
                    avatarEmoji = emoji,
                    avatarBackgroundColor = backgroundColor,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
        error("Unable to generate a unique display name after $MAX_ATTEMPTS attempts")
    }

    private fun tryInsert(
        userId: UserId,
        displayName: String,
        avatarEmoji: String,
        avatarBackgroundColor: String,
        now: java.time.Instant,
    ): Boolean {
        // Wrap the insert in a SAVEPOINT so a unique-constraint violation
        // (SQLSTATE 23505) doesn't poison the surrounding transaction.
        // Without this, Postgres aborts the whole transaction on the first
        // failed insert and every subsequent statement — including retry
        // inserts and the outer re-read SELECT in findOrCreate — fails with
        // 25P02 "current transaction is aborted".
        val connection = TransactionManager.current().connection
        val savepoint = connection.setSavepoint("profile_insert_attempt")
        return try {
            ProfilesTable.insert {
                it[ProfilesTable.userId] = userId.value
                it[ProfilesTable.displayName] = displayName
                it[ProfilesTable.avatarEmoji] = avatarEmoji
                it[ProfilesTable.avatarBackgroundColor] = avatarBackgroundColor
                it[createdAt] = now
                it[updatedAt] = now
            }
            true
        } catch (e: ExposedSQLException) {
            connection.rollback(savepoint)
            // display_name UQ → retry with a new name.
            // user_id PK → bubble out; findOrCreate's catch re-reads.
            if (e.isUniqueViolation() && e.violatesDisplayNameUq()) false
            else throw e
        } finally {
            connection.releaseSavepoint(savepoint)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun ResultRow.toProfile(): Profile = Profile(
        userId = UserId(this[ProfilesTable.userId]),
        displayName = this[ProfilesTable.displayName],
        avatarEmoji = this[ProfilesTable.avatarEmoji],
        avatarBackgroundColor = this[ProfilesTable.avatarBackgroundColor],
        createdAt = this[ProfilesTable.createdAt].toKotlinInstant(),
        updatedAt = this[ProfilesTable.updatedAt].toKotlinInstant(),
    )

    private fun ExposedSQLException.isUniqueViolation(): Boolean =
        cause?.let { it is java.sql.SQLException && it.sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE } == true ||
            (this as java.sql.SQLException).sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE

    /**
     * True if this unique-violation references the `profiles_display_name_uq`
     * constraint. The PG error message format is "duplicate key value violates
     * unique constraint \"<name>\"", which is stable across driver versions.
     */
    private fun ExposedSQLException.violatesDisplayNameUq(): Boolean {
        val msg = cause?.message ?: message ?: return false
        return msg.contains(PROFILES_DISPLAY_NAME_UQ)
    }

    companion object {
        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
        private const val PROFILES_DISPLAY_NAME_UQ = "profiles_display_name_uq"
        private const val MAX_ATTEMPTS = 25
    }
}
