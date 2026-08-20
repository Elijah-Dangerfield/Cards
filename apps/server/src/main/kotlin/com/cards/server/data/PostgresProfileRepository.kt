package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.InventoryTable
import com.dangerfield.cards.server.db.ProfilesTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.AvatarGenerator
import com.dangerfield.cards.server.domain.FindOrCreateProfileResult
import com.dangerfield.cards.server.domain.FoundingMemberCatalog
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileDisplayName
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.StarterInventory
import com.dangerfield.cards.server.domain.UnknownAuthUserException
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

    override suspend fun findByIds(userIds: List<UserId>): List<Profile> =
        if (userIds.isEmpty()) {
            emptyList()
        } else {
            database.transaction {
                val ids = userIds.map { it.value }
                ProfilesTable
                    .selectAll()
                    .where { ProfilesTable.userId inList ids }
                    .map { it.toProfile() }
            }
        }

    override suspend fun delete(userId: UserId) {
        database.transaction {
            ProfilesTable.deleteWhere { ProfilesTable.userId eq userId.value }
        }
    }

    override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? =
        database.transaction {
            val priorInstallId = ProfilesTable
                .selectAll()
                .where { ProfilesTable.userId eq userId.value }
                .singleOrNull()
                ?.get(ProfilesTable.installId)

            if (priorInstallId == installId) return@transaction null

            val updated = ProfilesTable.update({ ProfilesTable.userId eq userId.value }) { stmt ->
                stmt[ProfilesTable.installId] = installId
            }
            if (updated == 0) null else priorInstallId
        }

    override suspend fun findInstallSiblings(
        installId: UUID,
        currentUserId: UserId,
    ): List<UserId> = database.transaction {
        // Raw SQL because the query crosses into Supabase's `auth` schema
        // (which Supabase owns + we don't model in Exposed). The cheap
        // gate is the LEFT JOIN against wallet_events filtered to
        // `reason LIKE 'iap.%'` — IS NULL means no real-money spend.
        val candidates = mutableListOf<UserId>()
        TransactionManager.current().exec(
            stmt = """
                SELECT p.user_id
                FROM profiles p
                LEFT JOIN wallet_events we
                  ON we.user_id = p.user_id AND we.reason LIKE 'iap.%'
                WHERE p.install_id = ?
                  AND p.user_id <> ?
                  AND p.user_id IN (SELECT id FROM auth.users WHERE is_anonymous = TRUE)
                  AND we.user_id IS NULL
            """.trimIndent(),
            args = listOf(
                org.jetbrains.exposed.sql.UUIDColumnType() to installId,
                org.jetbrains.exposed.sql.UUIDColumnType() to currentUserId.value,
            ),
        ) { rs ->
            while (rs.next()) {
                val raw = rs.getObject("user_id", UUID::class.java)
                    ?: continue
                candidates += UserId(raw)
            }
        }
        candidates.toList()
    }

    override suspend fun listAllDisplayNames(): List<ProfileDisplayName> = database.transaction {
        ProfilesTable
            .select(ProfilesTable.userId, ProfilesTable.displayName)
            .map { ProfileDisplayName(UserId(it[ProfilesTable.userId]), it[ProfilesTable.displayName]) }
    }

    override suspend fun findInstallLineage(userId: UserId): Set<UserId> =
        database.transaction {
            val installId = ProfilesTable
                .selectAll()
                .where { ProfilesTable.userId eq userId.value }
                .singleOrNull()
                ?.get(ProfilesTable.installId)
                ?: return@transaction setOf(userId)

            ProfilesTable
                .selectAll()
                .where { ProfilesTable.installId eq installId }
                .mapTo(mutableSetOf(userId)) { UserId(it[ProfilesTable.userId]) }
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

    override suspend fun findOrCreate(userId: UserId): Profile =
        findOrCreateResult(userId).profile

    override suspend fun findOrCreateResult(userId: UserId): FindOrCreateProfileResult = database.transaction {
        // Fast path: already exists → not a new account.
        val existing = ProfilesTable
            .selectAll()
            .where { ProfilesTable.userId eq userId.value }
            .singleOrNull()
        if (existing != null) {
            return@transaction FindOrCreateProfileResult(existing.toProfile(), created = false)
        }

        // A profile row is proof of an auth.users row (V11's FK, plus its
        // ON DELETE CASCADE), so only the create branch can be about to write
        // against a user that no longer exists. Ask before writing: an FK
        // violation here would abort the transaction and surface as a 500
        // naming the constraint (AUTH-29).
        if (!authUserExists(userId)) throw UnknownAuthUserException(userId)

        // Insert with retry on display_name collisions. The userId-key
        // collision (concurrent first-contact for the same user) is also
        // handled — if some other request just inserted the profile, our
        // own insert fails, we re-read.
        try {
            FindOrCreateProfileResult(insertWithUniqueName(userId), created = true)
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) {
                // Either the userId PK or the displayName UQ tripped. The
                // userId case means a concurrent request beat us; re-read.
                // The displayName case is handled inside the retry loop —
                // if it bubbles out here we exhausted retries and surface
                // the failure. We didn't win the insert on this request, so
                // created = false (the winning request reports the new flag).
                val raced = ProfilesTable
                    .selectAll()
                    .where { ProfilesTable.userId eq userId.value }
                    .singleOrNull()
                raced?.let { FindOrCreateProfileResult(it.toProfile(), created = false) } ?: throw e
            } else {
                throw e
            }
        }
    }

    /**
     * Raw SQL for the same reason [findInstallSiblings] uses it — the `auth`
     * schema is Supabase's and we don't model it in Exposed. `SELECT 1` on the
     * primary key, and only on the create branch, so it costs one index probe
     * per account lifetime.
     */
    private fun authUserExists(userId: UserId): Boolean {
        var exists = false
        TransactionManager.current().exec(
            stmt = "SELECT 1 FROM auth.users WHERE id = ?",
            args = listOf(org.jetbrains.exposed.sql.UUIDColumnType() to userId.value),
        ) { rs -> exists = rs.next() }
        return exists
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
     * The seq column is `BIGSERIAL` (V43) — Postgres assigned a value
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
            body = "You're one of the first ${String.format(java.util.Locale.US, "%,d", foundingMemberThreshold)} players to find a seat. Your founding-member badge is in My Items — equip it any time and it shows at your seat.",
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
