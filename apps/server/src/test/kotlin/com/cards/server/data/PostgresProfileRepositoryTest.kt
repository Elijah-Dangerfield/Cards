package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.InventoryTable
import com.dangerfield.cards.server.db.ProfilesTable
import com.dangerfield.cards.server.db.UserMessagesTable
import com.dangerfield.cards.server.db.WalletEventsTable
import com.dangerfield.cards.server.db.WalletsTable
import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.AvatarGenerator
import com.dangerfield.cards.server.domain.AvatarPalette
import com.dangerfield.cards.server.domain.FoundingMemberCatalog
import com.dangerfield.cards.server.domain.StarterInventory
import com.dangerfield.cards.server.domain.UnknownAuthUserException
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UsernameGenerator
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.After
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class PostgresProfileRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            UserMessagesTable.deleteAll()
            WalletEventsTable.deleteAll()
            WalletsTable.deleteAll()
            InventoryTable.deleteAll()
            ProfilesTable.deleteAll()
            // BIGSERIAL ignores row deletes, so the seq would carry over
            // between tests and break the founding-member boundary tests
            // that assume the first inserted profile lands at seq=1.
            TransactionManager.current().exec("ALTER SEQUENCE profiles_seq_seq RESTART WITH 1")
        }
    }

    @Test
    fun findOrCreate_firstCall_createsFreshProfile() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()

        val profile = repo.findOrCreate(userId)

        assertEquals(userId, profile.userId)
        assertTrue(profile.displayName.isNotBlank())
        assertTrue(profile.avatarEmoji.isNotBlank())
        assertEquals(profile.createdAt, profile.updatedAt)
    }

    @Test
    fun findOrCreateResult_firstCall_createdTrue() = runTest {
        // The brand-new-account signal the client's auth-outcome classifier reads
        // to tell SIGN-UP from SIGN-IN (surfaced as MeResponse.isNewAccount).
        val repo = newRepository()
        val userId = seedAuthUser()

        val result = repo.findOrCreateResult(userId)

        assertTrue(result.created, "first contact for a brand-new account must report created")
        assertEquals(userId, result.profile.userId)
    }

    @Test
    fun findOrCreateResult_secondCall_createdFalse() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()
        repo.findOrCreateResult(userId)

        val result = repo.findOrCreateResult(userId)

        assertFalse(result.created, "returning account must not report a new-account flag")
    }

    @Test
    fun findOrCreate_seedsRandomBackgroundColorFromPalette() = runTest {
        val repo = newRepository()
        val profile = repo.findOrCreate(seedAuthUser())

        val color = profile.avatarBackgroundColor
        assertNotNull(color, "Fresh profile must have a background color")
        assertTrue(
            AvatarPalette.isValid(color),
            "Expected color from AvatarPalette, got: $color",
        )
    }

    @Test
    fun findOrCreate_isIdempotentForSameUserId() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()

        val first = repo.findOrCreate(userId)
        val second = repo.findOrCreate(userId)

        assertEquals(first.userId, second.userId)
        assertEquals(first.displayName, second.displayName)
        assertEquals(first.avatarEmoji, second.avatarEmoji)
    }

    @Test
    fun findOrCreate_seedsStarterInventoryOnFirstCreate() = runTest {
        // Threshold = 0 so the founding-member grant doesn't add an
        // extra row — this test is scoped to the starter set, which
        // is the persistent baseline every user gets regardless of
        // cohort.
        val repo = newRepository(foundingMemberThreshold = 0)
        val userId = seedAuthUser()

        repo.findOrCreate(userId)

        val owned = readInventory(userId)
        assertEquals(
            StarterInventory.productIds.toSet(),
            owned.map { it.productId }.toSet(),
            "Every starter id lands in inventory atomically with the profile insert",
        )
        owned.forEach { row ->
            assertEquals(
                AcquisitionSource.Earned.wire,
                row.acquisitionSource,
                "Starter rows are earned, not purchased",
            )
            assertEquals(0L, row.costChipsAtPurchase)
        }
    }

    @Test
    fun findOrCreate_starterInventoryIsIdempotent() = runTest {
        val repo = newRepository(foundingMemberThreshold = 0)
        val userId = seedAuthUser()

        repo.findOrCreate(userId)
        repo.findOrCreate(userId)

        val owned = readInventory(userId)
        assertEquals(
            StarterInventory.productIds.size,
            owned.size,
            "Second findOrCreate must not double-insert starter rows",
        )
    }

    @Test
    fun findOrCreate_grantsFoundingMemberBadge_whenSeqUnderThreshold() = runTest {
        // Threshold = 2; profile #1 sits at seq=1, profile #2 at seq=2,
        // profile #3 at seq=3. Only the first two qualify for the
        // founding-member cosmetic.
        val repo = newRepository(foundingMemberThreshold = 2)

        val first = repo.findOrCreate(seedAuthUser())
        val second = repo.findOrCreate(seedAuthUser())
        val third = repo.findOrCreate(seedAuthUser())

        assertTrue(
            readInventory(first.userId).any { it.productId == FoundingMemberCatalog.PRODUCT_ID },
            "Profile at seq=1 must receive the founding-member badge",
        )
        assertTrue(
            readInventory(second.userId).any { it.productId == FoundingMemberCatalog.PRODUCT_ID },
            "Profile at seq=2 must receive the founding-member badge (inclusive threshold)",
        )
        assertTrue(
            readInventory(third.userId).none { it.productId == FoundingMemberCatalog.PRODUCT_ID },
            "Profile at seq=3 is outside the cohort and must not receive the badge",
        )
    }

    @Test
    fun findOrCreate_grantedFoundingMemberRow_isEarnedNotPurchased() = runTest {
        val repo = newRepository(foundingMemberThreshold = 1)
        val userId = seedAuthUser()
        repo.findOrCreate(userId)

        val row = readInventory(userId).single { it.productId == FoundingMemberCatalog.PRODUCT_ID }
        assertEquals(
            AcquisitionSource.Earned.wire,
            row.acquisitionSource,
            "Cohort grants follow the same provenance as starter inventory — earned, not purchased",
        )
        assertEquals(0L, row.costChipsAtPurchase)
    }

    @Test
    fun findOrCreate_postsFoundingMemberInboxMessage_whenQualified() = runTest {
        val repo = newRepository(foundingMemberThreshold = 1)
        val userId = seedAuthUser()
        repo.findOrCreate(userId)

        val founding = readUserMessages(userId).single()
        assertEquals(UserMessageKind.Inbox.wire, founding.kind)
        assertEquals("🏛", founding.emoji)
        assertTrue(
            founding.title.contains("founding member", ignoreCase = true),
            "Title should name the cohort so the inbox row is self-describing",
        )
        assertNull(founding.deepLink)
        assertNull(founding.expiresAt)
    }

    @Test
    fun findOrCreate_doesNotPostFoundingMemberMessage_whenOverThreshold() = runTest {
        val repo = newRepository(foundingMemberThreshold = 0)
        val userId = seedAuthUser()
        repo.findOrCreate(userId)

        assertTrue(
            readUserMessages(userId).isEmpty(),
            "Users outside the founding cohort must not receive the welcome inbox row",
        )
    }

    @Test
    fun findById_returnsNullForUnknownUser() = runTest {
        val repo = newRepository()
        assertNull(repo.findById(UserId(UUID.randomUUID())))
    }

    @Test
    fun findById_returnsExistingProfile() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()
        repo.findOrCreate(userId)

        val found = repo.findById(userId)
        assertNotNull(found)
        assertEquals(userId, found.userId)
    }

    @Test
    fun findByIds_returnsKnownProfiles_andOmitsUnknowns() = runTest {
        val repo = newRepository()
        val a = seedAuthUser().also { repo.findOrCreate(it) }
        val b = seedAuthUser().also { repo.findOrCreate(it) }
        val c = seedAuthUser().also { repo.findOrCreate(it) }
        val unknown = UserId(UUID.randomUUID())

        val found = repo.findByIds(listOf(a, unknown, b, c))

        assertEquals(setOf(a, b, c), found.map { it.userId }.toSet())
    }

    @Test
    fun findByIds_emptyInput_returnsEmpty() = runTest {
        val repo = newRepository()
        assertEquals(emptyList(), repo.findByIds(emptyList()))
    }

    @Test
    fun usernameCollision_recoversByRetrying() = runTest {
        // Force a collision: first profile takes "Twin-Ace-100", second
        // tries the same name (collision), then "Twin-Ace-200" succeeds.
        val gen = ScriptedUsernameGenerator(
            listOf("Twin-Ace-100", "Twin-Ace-100", "Twin-Ace-200"),
        )
        val repo = newRepository(usernameGenerator = gen)

        val first = repo.findOrCreate(seedAuthUser())
        val second = repo.findOrCreate(seedAuthUser())

        assertEquals("Twin-Ace-100", first.displayName)
        assertEquals("Twin-Ace-200", second.displayName)
    }

    @Test
    fun differentUserIds_getDifferentProfiles() = runTest {
        val repo = newRepository()
        val a = repo.findOrCreate(seedAuthUser())
        val b = repo.findOrCreate(seedAuthUser())

        assertNotEquals(a.userId, b.userId)
        assertNotEquals(a.displayName, b.displayName)
    }

    @Test
    fun delete_removesExistingProfile() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()
        repo.findOrCreate(userId)
        assertNotNull(repo.findById(userId))

        repo.delete(userId)

        assertNull(repo.findById(userId))
    }

    @Test
    fun delete_isIdempotentWhenProfileNotPresent() = runTest {
        val repo = newRepository()
        // No row to begin with — should not throw.
        repo.delete(UserId(UUID.randomUUID()))
    }

    @Test
    fun touchInstallId_setsValue_onProfileWithoutPriorTag() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()
        repo.findOrCreate(userId)
        val install = UUID.randomUUID()

        val prior = repo.touchInstallId(userId, install)

        assertNull(prior, "Profile starts un-tagged so prior must be null")
        assertEquals(install, readInstallId(userId))
    }

    @Test
    fun touchInstallId_returnsPriorValue_whenInstallIdChanges() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()
        repo.findOrCreate(userId)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        repo.touchInstallId(userId, first)

        val prior = repo.touchInstallId(userId, second)

        assertEquals(first, prior, "Replacing the tag returns the prior install_id (the L1 cleanup cue)")
        assertEquals(second, readInstallId(userId))
    }

    @Test
    fun touchInstallId_isNoOp_whenInstallIdMatches() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()
        repo.findOrCreate(userId)
        val install = UUID.randomUUID()
        repo.touchInstallId(userId, install)

        val prior = repo.touchInstallId(userId, install)

        assertNull(prior, "Same install on re-tag returns null (no write happened)")
        assertEquals(install, readInstallId(userId))
    }

    @Test
    fun touchInstallId_returnsNull_whenProfileDoesNotExist() = runTest {
        val repo = newRepository()
        val unknown = UserId(UUID.randomUUID())

        val prior = repo.touchInstallId(unknown, UUID.randomUUID())

        assertNull(prior, "No profile → defensive no-op, returns null")
    }

    @Test
    fun findInstallSiblings_returnsAnonRows_sharingInstallId_excludingCaller() = runTest {
        val repo = newRepository()
        val install = UUID.randomUUID()
        val caller = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        val sibling = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        val nonAnon = seedAuthUser(isAnonymous = false).also { repo.findOrCreate(it) }
        repo.touchInstallId(caller, install)
        repo.touchInstallId(sibling, install)
        repo.touchInstallId(nonAnon, install)

        val results = repo.findInstallSiblings(install, caller)

        assertEquals(listOf(sibling), results, "Sweep matches anon rows tagged with the same install_id, excluding the caller and non-anon rows")
    }

    @Test
    fun findInstallSiblings_excludesRowsWithIapPurchases() = runTest {
        val repo = newRepository()
        val install = UUID.randomUUID()
        val caller = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        val spender = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        val freeloader = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        repo.touchInstallId(caller, install)
        repo.touchInstallId(spender, install)
        repo.touchInstallId(freeloader, install)
        // A real-money purchase pins `spender` outside the sweep.
        seedIapPurchase(spender, idempotencyKey = "iap_${spender.value}", reason = "iap.chip_pack_small")

        val results = repo.findInstallSiblings(install, caller)

        assertEquals(listOf(freeloader), results, "Anon rows with any iap.% wallet_event are not sweep candidates")
    }

    @Test
    fun findInstallSiblings_returnsEmpty_whenNoSharedInstall() = runTest {
        val repo = newRepository()
        val install = UUID.randomUUID()
        val other = UUID.randomUUID()
        val caller = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        val unrelated = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        repo.touchInstallId(caller, install)
        repo.touchInstallId(unrelated, other)

        val results = repo.findInstallSiblings(install, caller)

        assertTrue(results.isEmpty(), "Sibling lookup is install-scoped — unrelated installs are invisible to it")
    }

    @Test
    fun findInstallLineage_returnsEveryUserSharingTheCallersInstall() = runTest {
        // BILL-11: the lineage is every id that has owned this install — the
        // caller plus prior identities, resolved from the caller's own row.
        // Unlike the L1 sweep it doesn't filter on anon status or IAP spend:
        // the whole point is to reach the identity that made the purchase.
        val repo = newRepository()
        val install = UUID.randomUUID()
        val caller = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        val prior = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        val unrelated = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }
        repo.touchInstallId(caller, install)
        repo.touchInstallId(prior, install)
        repo.touchInstallId(unrelated, UUID.randomUUID())

        val lineage = repo.findInstallLineage(caller)

        assertEquals(setOf(caller, prior), lineage, "Lineage spans everyone on the caller's install, and no one else")
    }

    @Test
    fun findInstallLineage_returnsJustCaller_whenInstallIdUnset() = runTest {
        val repo = newRepository()
        val caller = seedAuthUser(isAnonymous = true).also { repo.findOrCreate(it) }

        val lineage = repo.findInstallLineage(caller)

        assertEquals(setOf(caller), lineage, "No install tag → no lineage to widen to, so the strict binding holds")
    }

    @Test
    fun findInstallLineage_returnsJustCaller_whenProfileMissing() = runTest {
        val repo = newRepository()
        val unknown = UserId(UUID.randomUUID())

        val lineage = repo.findInstallLineage(unknown)

        assertEquals(setOf(unknown), lineage, "Unknown caller falls back to itself, never an empty accepted set")
    }

    @Test
    fun findOrCreate_raisesUnknownAuthUser_whenTheCallerHasNoAuthUsersRow() = runTest {
        // AUTH-29: the account was deleted mid-session (or the token was minted
        // against another Supabase project). Before the pre-flight this hit the
        // profiles_user_id_fk constraint and surfaced as a raw 500.
        val repo = newRepository()
        val stranded = UserId(UUID.randomUUID())

        assertFailsWith<UnknownAuthUserException> { repo.findOrCreate(stranded) }
    }

    @Test
    fun findOrCreate_writesNothing_whenTheCallerHasNoAuthUsersRow() = runTest {
        val repo = newRepository()
        val stranded = UserId(UUID.randomUUID())

        assertFailsWith<UnknownAuthUserException> { repo.findOrCreate(stranded) }

        assertEquals(0, countProfiles(stranded), "a doomed create must not leave a partial row behind")
        assertEquals(0, countInventory(stranded))
    }

    private fun countProfiles(userId: UserId): Int = database.blockingTransaction {
        ProfilesTable.selectAll().where { ProfilesTable.userId eq userId.value }.count().toInt()
    }

    private fun countInventory(userId: UserId): Int = database.blockingTransaction {
        InventoryTable.selectAll().where { InventoryTable.userId eq userId.value }.count().toInt()
    }

    private fun seedIapPurchase(userId: UserId, idempotencyKey: String, reason: String) {
        // wallet_events FKs auth.users directly, not wallets, so we don't
        // need a wallet row — just the event. Matches the V11 schema.
        database.blockingTransaction {
            WalletEventsTable.insert {
                it[WalletEventsTable.userId] = userId.value
                it[WalletEventsTable.idempotencyKey] = idempotencyKey
                it[WalletEventsTable.delta] = 1_000L
                it[WalletEventsTable.reason] = reason
                it[WalletEventsTable.appliedAt] = java.time.Instant.now()
            }
        }
    }

    private fun newRepository(
        usernameGenerator: UsernameGenerator = AdjectiveNounUsernameGenerator(),
        avatarGenerator: AvatarGenerator = EmojiAvatarGenerator(),
        clock: Clock = Clock.System,
        foundingMemberThreshold: Long = FoundingMemberCatalog.FOUNDING_MEMBER_THRESHOLD,
    ): PostgresProfileRepository = PostgresProfileRepository(
        database = database,
        usernameGenerator = usernameGenerator,
        avatarGenerator = avatarGenerator,
        userMessageRepository = PostgresUserMessageRepository(database, clock),
        clock = clock,
        foundingMemberThreshold = foundingMemberThreshold,
    )

    private data class InventoryRow(
        val productId: String,
        val costChipsAtPurchase: Long,
        val acquisitionSource: String,
    )

    private fun readInventory(userId: UserId): List<InventoryRow> = database.blockingTransaction {
        InventoryTable
            .selectAll()
            .where { InventoryTable.userId eq userId.value }
            .map {
                InventoryRow(
                    productId = it[InventoryTable.productId],
                    costChipsAtPurchase = it[InventoryTable.costChipsAtPurchase],
                    acquisitionSource = it[InventoryTable.acquisitionSource],
                )
            }
    }

    private fun readInstallId(userId: UserId): UUID? = database.blockingTransaction {
        ProfilesTable
            .selectAll()
            .where { ProfilesTable.userId eq userId.value }
            .singleOrNull()
            ?.get(ProfilesTable.installId)
    }

    private data class UserMessageRow(
        val kind: String,
        val emoji: String?,
        val title: String,
        val body: String,
        val deepLink: String?,
        val expiresAt: java.time.Instant?,
    )

    private fun readUserMessages(userId: UserId): List<UserMessageRow> = database.blockingTransaction {
        UserMessagesTable
            .selectAll()
            .where { UserMessagesTable.userId eq userId.value }
            .map {
                UserMessageRow(
                    kind = it[UserMessagesTable.kind],
                    emoji = it[UserMessagesTable.emoji],
                    title = it[UserMessagesTable.title],
                    body = it[UserMessagesTable.body],
                    deepLink = it[UserMessagesTable.deepLink],
                    expiresAt = it[UserMessagesTable.expiresAt],
                )
            }
    }

    private class ScriptedUsernameGenerator(names: List<String>) : UsernameGenerator {
        private val iter = names.iterator()
        override fun random(): String =
            if (iter.hasNext()) iter.next() else error("exhausted scripted names")
    }
}
