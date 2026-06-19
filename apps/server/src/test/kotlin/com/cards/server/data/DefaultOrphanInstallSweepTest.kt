package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.FoundingMemberCatalog
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.OwnedItem
import com.dangerfield.cards.server.domain.ApplyXpOutcome
import com.dangerfield.cards.server.domain.FindOrCreateProgressionResult
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.StarterInventory
import com.dangerfield.cards.server.domain.UserProgression
import com.dangerfield.cards.server.domain.XpEvent
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class DefaultOrphanInstallSweepTest {

    private val install = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val caller = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val sibling1 = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val sibling2 = UserId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
    private val sibling3 = UserId(UUID.fromString("44444444-4444-4444-4444-444444444444"))

    @Test
    fun run_deletesAllVerifiedSiblings_whenInventoryIsStarterOnly() = runTest {
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin()
        val inventory = FakeInventory(
            owned = mapOf(
                sibling1 to starterRows(),
                sibling2 to starterRows() + foundingMemberRow(),
            ),
        )
        val rooms = FakeRoomService()
        val sweep = DefaultOrphanInstallSweep(profiles, admin, inventory, FakeProgression(), rooms)

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(2, result.deleted)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failedToDelete)
        assertEquals(false, result.notConfigured)
        assertEquals(listOf(sibling1, sibling2), admin.deletedAdminUsers)
        assertEquals(listOf(sibling1, sibling2), profiles.deletedProfileUsers)
    }

    @Test
    fun run_skipsCandidate_whenInventoryShowsEngagement() = runTest {
        // sibling1 owns a non-starter, non-founding row — earned via
        // gameplay or chip-purchased. The sweep refuses to delete them
        // even though the SQL gate accepted them.
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin()
        val inventory = FakeInventory(
            owned = mapOf(
                sibling1 to starterRows() + ownedRow("felt_blue_velvet", AcquisitionSource.Purchased),
                sibling2 to starterRows(),
            ),
        )
        val rooms = FakeRoomService()
        val sweep = DefaultOrphanInstallSweep(profiles, admin, inventory, FakeProgression(), rooms)

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(1, result.deleted)
        assertEquals(1, result.skipped)
        assertEquals(listOf(sibling2), admin.deletedAdminUsers)
        assertEquals(listOf(sibling2), profiles.deletedProfileUsers)
    }

    @Test
    fun run_skipsCandidate_whenSittingInActiveRoom() = runTest {
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin()
        val inventory = FakeInventory(owned = mapOf(sibling1 to starterRows(), sibling2 to starterRows()))
        val rooms = FakeRoomService(seatedUsers = setOf(sibling1))
        val sweep = DefaultOrphanInstallSweep(profiles, admin, inventory, FakeProgression(), rooms)

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(1, result.deleted)
        assertEquals(1, result.skipped)
        assertEquals(listOf(sibling2), admin.deletedAdminUsers)
    }

    @Test
    fun run_skipsCandidate_whenAboveLevel1() = runTest {
        // sibling1 has crossed into level 2 (>= 100 XP). Even though their
        // inventory is starter-only and they're not in a room, the sweep
        // preserves them — earned progress is never deleted.
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin()
        val inventory = FakeInventory(owned = mapOf(sibling1 to starterRows(), sibling2 to starterRows()))
        val progression = FakeProgression(xpByUser = mapOf(sibling1 to 100L, sibling2 to 99L))
        val rooms = FakeRoomService()
        val sweep = DefaultOrphanInstallSweep(profiles, admin, inventory, progression, rooms)

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(1, result.deleted)
        assertEquals(1, result.skipped)
        assertEquals(listOf(sibling2), admin.deletedAdminUsers)
        assertEquals(listOf(sibling2), profiles.deletedProfileUsers)
    }

    @Test
    fun run_recordsFailedDelete_whenAdminFails() = runTest {
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin(
            failureFor = mapOf(sibling1 to DeleteUserResult.Failure(statusCode = 500, cause = null)),
        )
        val inventory = FakeInventory(owned = mapOf(sibling1 to starterRows(), sibling2 to starterRows()))
        val rooms = FakeRoomService()
        val sweep = DefaultOrphanInstallSweep(profiles, admin, inventory, FakeProgression(), rooms)

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(1, result.deleted)
        assertEquals(1, result.failedToDelete)
        assertEquals(0, result.skipped)
        assertEquals(listOf(sibling2), profiles.deletedProfileUsers)
    }

    @Test
    fun run_shortCircuits_whenServiceRoleKeyNotConfigured() = runTest {
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2, sibling3))
        val admin = FakeAdmin(defaultResult = DeleteUserResult.NotConfigured)
        val inventory = FakeInventory(owned = mapOf(
            sibling1 to starterRows(),
            sibling2 to starterRows(),
            sibling3 to starterRows(),
        ))
        val rooms = FakeRoomService()
        val sweep = DefaultOrphanInstallSweep(profiles, admin, inventory, FakeProgression(), rooms)

        val result = sweep.run(install, caller)

        assertEquals(3, result.candidatesFound)
        assertEquals(0, result.deleted)
        assertEquals(true, result.notConfigured)
        assertTrue(profiles.deletedProfileUsers.isEmpty(), "unconfigured admin must not produce any local deletes")
    }

    @Test
    fun run_returnsEmpty_whenNoSiblings() = runTest {
        val profiles = FakeProfileRepository(siblings = emptyList())
        val sweep = DefaultOrphanInstallSweep(profiles, FakeAdmin(), FakeInventory(), FakeProgression(), FakeRoomService())

        val result = sweep.run(install, caller)

        assertEquals(0, result.candidatesFound)
        assertEquals(0, result.deleted)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failedToDelete)
        assertEquals(false, result.notConfigured)
    }

    // ---------- fakes ----------

    private fun starterRows(): List<OwnedItem> = StarterInventory.productIds.map { ownedRow(it, AcquisitionSource.Earned) }

    private fun foundingMemberRow(): OwnedItem =
        ownedRow(FoundingMemberCatalog.PRODUCT_ID, AcquisitionSource.Earned)

    private fun ownedRow(productId: String, source: AcquisitionSource): OwnedItem = OwnedItem(
        productId = productId,
        costChipsAtPurchase = 0L,
        purchasedAt = Instant.fromEpochSeconds(1_700_000_000),
        acquisitionSource = source,
    )

    private class FakeProfileRepository(
        private val siblings: List<UserId>,
    ) : ProfileRepository {
        val deletedProfileUsers: MutableList<UserId> = mutableListOf()

        override suspend fun findInstallSiblings(
            installId: UUID,
            currentUserId: UserId,
        ): List<UserId> = siblings

        override suspend fun delete(userId: UserId) {
            deletedProfileUsers += userId
        }

        override suspend fun findOrCreate(userId: UserId): Profile = error("unused")
        override suspend fun findById(userId: UserId): Profile? = null
        override suspend fun update(
            userId: UserId,
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("unused")
        override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? = error("unused")
    }

    private class FakeAdmin(
        private val failureFor: Map<UserId, DeleteUserResult> = emptyMap(),
        private val defaultResult: DeleteUserResult = DeleteUserResult.Success,
    ) : SupabaseAdminClient {
        val deletedAdminUsers: MutableList<UserId> = mutableListOf()

        override suspend fun deleteUser(userId: UserId): DeleteUserResult {
            failureFor[userId]?.let { return it }
            if (defaultResult is DeleteUserResult.Success || defaultResult is DeleteUserResult.AlreadyGone) {
                deletedAdminUsers += userId
            }
            return defaultResult
        }

        override suspend fun listAnonymousUsersOlderThan(olderThan: Instant): List<UserId> = emptyList()
    }

    private class FakeInventory(
        private val owned: Map<UserId, List<OwnedItem>> = emptyMap(),
    ) : InventoryRepository {
        override suspend fun listOwned(userId: UserId): List<OwnedItem> = owned[userId] ?: emptyList()
        override suspend fun recordPurchase(
            userId: UserId,
            productId: String,
            costChipsAtPurchase: Long,
            purchasedAt: Instant,
        ): OwnedItem = error("unused")
        override suspend fun recordEarnedGrant(
            userId: UserId,
            productId: String,
            grantedAt: Instant,
        ): OwnedItem = error("unused")
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private class FakeProgression(
        private val xpByUser: Map<UserId, Long> = emptyMap(),
    ) : ProgressionRepository {
        override suspend fun find(userId: UserId): UserProgression? =
            xpByUser[userId]?.let {
                UserProgression(
                    userId = userId,
                    totalXp = it,
                    createdAt = Instant.fromEpochSeconds(1_700_000_000),
                    updatedAt = Instant.fromEpochSeconds(1_700_000_000),
                )
            }

        override suspend fun findOrCreateResult(userId: UserId): FindOrCreateProgressionResult =
            error("unused")
        override suspend fun applyXp(
            userId: UserId,
            idempotencyKey: String,
            deltaXp: Long,
            source: String,
            mode: String,
            handId: String?,
            wasBoosted: Boolean,
        ): ApplyXpOutcome = error("unused")
        override suspend fun recentEvents(userId: UserId, limit: Int): List<XpEvent> = emptyList()
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private class FakeRoomService(
        private val seatedUsers: Set<UserId> = emptySet(),
    ) : com.dangerfield.cards.server.domain.RoomService {
        override suspend fun snapshot(): List<com.dangerfield.cards.server.domain.Room> {
            if (seatedUsers.isEmpty()) return emptyList()
            return listOf(roomWith(seatedUsers.toList()))
        }

        private fun roomWith(users: List<UserId>): com.dangerfield.cards.server.domain.Room =
            com.dangerfield.cards.server.domain.Room(
                code = "ABCDEF",
                hostUserId = users.first(),
                createdAt = Instant.fromEpochSeconds(1_700_000_000),
                maxSeats = com.dangerfield.cards.server.domain.RoomService.MAX_SEATS,
                status = com.dangerfield.cards.server.domain.RoomStatus.Lobby,
                members = users.mapIndexed { i, uid ->
                    com.dangerfield.cards.server.domain.RoomMember(
                        userId = uid,
                        displayName = "User$i",
                        seatIndex = i,
                        joinedAt = Instant.fromEpochSeconds(1_700_000_000),
                        isConnected = true,
                    )
                },
            )

        override suspend fun create(
            hostUserId: UserId,
            hostName: String,
            maxSeats: Int,
            hostAvatarEmoji: String,
            hostAvatarBackgroundColor: String?,
        ): com.dangerfield.cards.server.domain.CreateResult = error("unused")
        override suspend fun join(
            code: String,
            userId: UserId,
            name: String,
            avatarEmoji: String,
            avatarBackgroundColor: String?,
        ): com.dangerfield.cards.server.domain.JoinResult = error("unused")
        override suspend fun leave(code: String, userId: UserId): com.dangerfield.cards.server.domain.LeaveResult = error("unused")
        override suspend fun markConnected(code: String, userId: UserId, connected: Boolean): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun markPlaying(code: String): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun markFinished(code: String): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun find(code: String): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun observe(code: String): kotlinx.coroutines.flow.Flow<com.dangerfield.cards.server.domain.Room>? = null
        override suspend fun sweepDisconnected(maxIdle: kotlin.time.Duration): com.dangerfield.cards.server.domain.RoomSweepResult = error("unused")
        override suspend fun reapIfStillDisconnected(code: String, userId: UserId, expectedDisconnectedAt: Instant): Boolean = false
    }
}
