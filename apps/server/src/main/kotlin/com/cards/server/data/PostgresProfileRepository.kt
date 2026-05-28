package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.InventoryTable
import com.dangerfield.cards.server.db.ProfilesTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.AvatarGenerator
import com.dangerfield.cards.server.domain.FoundingMemberCatalog
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.StarterInventory
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.UsernameGenerator
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
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
    private val userMessageRepository: UserMessageRepository,
    private val clock: Clock,
    /**
     * Inclusive upper bound on `profiles.seq` that qualifies a user for
     * the founding-member badge grant. Defaults to the production value;
     * tests override with a lower number so they don't need to insert
     * thousands of profiles to exercise the boundary.
     */
    private val foundingMemberThreshold: Long = FoundingMemberCatalog.FOUNDING_MEMBER_THRESHOLD,
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

    private suspend fun insertWithUniqueName(userId: UserId): Profile {
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
                seedStarterInventory(userId, nowJava)
                val seq = readSeq(userId)
                if (seq <= foundingMemberThreshold) {
                    grantFoundingMemberBadge(userId, nowJava)
                }
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

    /**
     * The seq column is `BIGSERIAL` (V26) — Postgres assigned a value
     * at insert time. We read it back rather than threading it through
     * [tryInsert]'s return so the existing display-name-collision retry
     * loop stays focused on its own concern.
     */
    private fun readSeq(userId: UserId): Long = ProfilesTable
        .selectAll()
        .where { ProfilesTable.userId eq userId.value }
        .single()[ProfilesTable.seq]

    /**
     * Drop the founding-member badge inventory row + an inbox UserMessage
     * pointing the user at the cosmetic. Both writes ride the outer
     * `findOrCreate` transaction so a partial grant (badge without
     * notification, or vice versa) can't be observed by a later read.
     *
     * Inventory is `insertIgnore`-shaped — a duplicate grant on retry
     * would be a no-op rather than a unique-violation that aborts the
     * outer transaction. The UserMessage path goes through
     * [UserMessageRepository.create]; its `(userId, idempotencyKey)`
     * dedup guarantees the welcome doesn't double-deliver on a retried
     * `findOrCreate`.
     */
    private suspend fun grantFoundingMemberBadge(userId: UserId, nowJava: java.time.Instant) {
        InventoryTable.insertIgnore {
            it[InventoryTable.userId] = userId.value
            it[InventoryTable.productId] = FoundingMemberCatalog.PRODUCT_ID
            it[InventoryTable.costChipsAtPurchase] = 0L
            it[InventoryTable.purchasedAt] = nowJava
            it[InventoryTable.acquisitionSource] = AcquisitionSource.Earned.wire
        }
        userMessageRepository.create(
            id = UUID.randomUUID(),
            userId = userId,
            idempotencyKey = FOUNDING_MEMBER_MESSAGE_KEY,
            kind = UserMessageKind.Inbox,
            emoji = "🏛",
            title = "You're a founding member",
            body = "You're one of the first ${foundingMemberThreshold} players to find a seat. Your founding-member badge is in My Items — equip it any time and it shows at your seat.",
            deepLink = null,
            expiresAt = null,
        )
    }

    /**
     * Insert [StarterInventory.productIds] rows for the newly-created
     * profile in the same outer transaction. `insertIgnore` so a future
     * pre-grant path can't trip a unique-violation that aborts the whole
     * `findOrCreate` — first-grant-wins matches how the inventory repo
     * handles concurrent writes elsewhere.
     */
    private fun seedStarterInventory(userId: UserId, nowJava: java.time.Instant) {
        StarterInventory.productIds.forEach { productId ->
            InventoryTable.insertIgnore {
                it[InventoryTable.userId] = userId.value
                it[InventoryTable.productId] = productId
                it[InventoryTable.costChipsAtPurchase] = 0L
                it[InventoryTable.purchasedAt] = nowJava
                it[InventoryTable.acquisitionSource] = AcquisitionSource.Earned.wire
            }
        }
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
        private const val FOUNDING_MEMBER_MESSAGE_KEY: String = "founding_member_v1"
    }
}
