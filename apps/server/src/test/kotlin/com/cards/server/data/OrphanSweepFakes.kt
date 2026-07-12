package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.ApplyXpOutcome
import com.dangerfield.cards.server.domain.FindOrCreateProgressionResult
import com.dangerfield.cards.server.domain.FindOrCreateResult
import com.dangerfield.cards.server.domain.FoundingMemberCatalog
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.OwnedItem
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.StarterInventory
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserProgression
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletEvent
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.domain.XpEvent
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Shared fakes for the orphan-sweep tests ([DefaultOrphanInstallSweepTest],
 * [DefaultOrphanAnonymousSweepTest], [OrphanCandidateVerifierTest]) — enough
 * of each repository to drive [OrphanCandidateVerifier]'s guards.
 */
@OptIn(ExperimentalTime::class)
internal object OrphanSweepFakes {

    fun verifier(
        iapSpenders: Set<UserId> = emptySet(),
        owned: Map<UserId, List<OwnedItem>> = emptyMap(),
        xpByUser: Map<UserId, Long> = emptyMap(),
        seatedUsers: Set<UserId> = emptySet(),
    ): OrphanCandidateVerifier = OrphanCandidateVerifier(
        wallets = FakeWallets(iapSpenders),
        inventory = FakeInventory(owned),
        progression = FakeProgression(xpByUser),
        rooms = FakeRoomService(seatedUsers),
    )

    fun starterRows(): List<OwnedItem> =
        StarterInventory.productIds.map { ownedRow(it, AcquisitionSource.Earned) }

    fun foundingMemberRow(): OwnedItem =
        ownedRow(FoundingMemberCatalog.PRODUCT_ID, AcquisitionSource.Earned)

    fun ownedRow(productId: String, source: AcquisitionSource): OwnedItem = OwnedItem(
        productId = productId,
        costChipsAtPurchase = 0L,
        purchasedAt = Instant.fromEpochSeconds(1_700_000_000),
        acquisitionSource = source,
    )

    class FakeWallets(
        private val iapSpenders: Set<UserId> = emptySet(),
    ) : WalletRepository {
        override suspend fun hasIapSpend(userId: UserId): Boolean = userId in iapSpenders
        override suspend fun findOrCreateResult(userId: UserId): FindOrCreateResult = error("unused")
        override suspend fun find(userId: UserId): Wallet? = null
        override suspend fun apply(
            userId: UserId,
            idempotencyKey: String,
            delta: Long,
            reason: String,
        ): ApplyOutcome = error("unused")
        override suspend fun recentEvents(userId: UserId, limit: Int): List<WalletEvent> = emptyList()
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    class FakeInventory(
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

    class FakeProgression(
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

    class FakeRoomService(
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
            buyIn: Long,
            visibility: com.dangerfield.cards.server.domain.RoomVisibility,
            feltProductId: String?,
            cardBackProductId: String?,
        ): com.dangerfield.cards.server.domain.CreateResult = error("unused")
        override suspend fun findOrJoinPublic(
            userId: UserId,
            name: String,
            minBuyIn: Long,
            maxBuyIn: Long,
            blockedUserIds: Set<UserId>,
            avatarEmoji: String,
            avatarBackgroundColor: String?,
        ): com.dangerfield.cards.server.domain.MatchmakingResult = error("unused")
        override suspend fun findPublicCandidates(
            userId: UserId,
            minBuyIn: Long,
            maxBuyIn: Long,
            blockedUserIds: Set<UserId>,
        ): List<com.dangerfield.cards.server.domain.Room> = error("unused")
        override suspend fun join(
            code: String,
            userId: UserId,
            name: String,
            avatarEmoji: String,
            avatarBackgroundColor: String?,
        ): com.dangerfield.cards.server.domain.JoinResult = error("unused")
        override suspend fun leave(code: String, userId: UserId): com.dangerfield.cards.server.domain.LeaveResult = error("unused")
        override suspend fun addBot(
            code: String,
            requestedBy: UserId,
            difficulty: com.dangerfield.cards.libraries.bots.BotDifficulty,
            seatIndex: Int?,
            revealed: Boolean,
        ): com.dangerfield.cards.server.domain.AddBotResult = error("unused")
        override suspend fun fillBotsUpTo(
            code: String,
            requestedBy: UserId,
            target: Int,
            difficulty: com.dangerfield.cards.libraries.bots.BotDifficulty,
            revealed: Boolean,
        ): com.dangerfield.cards.server.domain.AddBotResult = error("unused")
        override suspend fun removeBot(
            code: String,
            requestedBy: UserId,
            botUserId: UserId,
        ): com.dangerfield.cards.server.domain.RemoveBotResult = error("unused")
        override suspend fun trimBotForNewHumans(code: String, handNumber: Int): UserId? = null
        override suspend fun markConnected(code: String, userId: UserId, connected: Boolean): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun openSocketConnection(code: String, userId: UserId): Long? = null
        override suspend fun closeSocketConnectionIfCurrent(code: String, userId: UserId, connectionId: Long): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun markPlaying(code: String): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun markFinished(code: String): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun find(code: String): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun observe(code: String): kotlinx.coroutines.flow.Flow<com.dangerfield.cards.server.domain.Room>? = null
        override suspend fun sweepDisconnected(maxIdle: kotlin.time.Duration): com.dangerfield.cards.server.domain.RoomSweepResult = error("unused")
        override suspend fun reapIfStillDisconnected(code: String, userId: UserId, expectedDisconnectedAt: Instant): Boolean = false
    }
}
