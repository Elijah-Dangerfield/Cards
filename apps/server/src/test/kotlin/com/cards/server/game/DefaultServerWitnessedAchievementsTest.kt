package com.dangerfield.cards.server.game

import com.dangerfield.cards.server.domain.AchievementRepository
import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.EarnedAchievement
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.OwnedItem
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalog
import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.FindOrCreateResult
import com.dangerfield.cards.server.domain.PlayerHandOutcome
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletEvent
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.http.ClientContext
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class DefaultServerWitnessedAchievementsTest {

    private val userId = UserId(UUID.randomUUID())

    @Test
    fun crossingHandsThreshold_recordsAchievement_andGrantsCosmetic() = runTest {
        val achievements = CapturingAchievements()
        val inventory = CapturingInventory()
        val evaluator = build(
            handsCount = 100,
            achievements = achievements,
            inventory = inventory,
            catalog = FakeCatalog.with(stubProduct("emotes_grinder")),
        )

        evaluator.evaluate(userId)

        // At 100 finished hands both the first-hand milestone and the
        // 100-hand grind cross; HANDS_100_MP grants chips (not a cosmetic).
        assertEquals(listOf("FIRST_HAND_MP", "HANDS_100_MP"), achievements.earned)
        assertTrue(inventory.earnedGrants.isEmpty())
    }

    @Test
    fun crossingHandsThreshold_grantsChips_notCosmetic() = runTest {
        val achievements = CapturingAchievements()
        val inventory = CapturingInventory()
        val wallet = CapturingWallet()
        val evaluator = build(
            handsCount = 100,
            achievements = achievements,
            inventory = inventory,
            catalog = FakeCatalog.with(stubProduct("emotes_grinder")),
            wallet = wallet,
        )

        evaluator.evaluate(userId)

        assertTrue(inventory.earnedGrants.isEmpty())
        assertEquals(1, wallet.applied.size)
        val grant = wallet.applied.single()
        assertEquals("achievement:HANDS_100_MP", grant.idempotencyKey)
        assertEquals(2_500L, grant.delta)
        assertEquals("achievement_grant:HANDS_100_MP", grant.reason)
    }

    @Test
    fun firstHandMp_grantsNoChips() = runTest {
        val achievements = CapturingAchievements()
        val wallet = CapturingWallet()
        val evaluator = build(
            handsCount = 1,
            achievements = achievements,
            inventory = CapturingInventory(),
            catalog = FakeCatalog.empty(),
            wallet = wallet,
        )

        evaluator.evaluate(userId)

        assertEquals(listOf("FIRST_HAND_MP"), achievements.earned)
        assertTrue(wallet.applied.isEmpty())
    }

    @Test
    fun firstFinishedHand_recordsFirstHandMp_butGrantsNoCosmetic() = runTest {
        val achievements = CapturingAchievements()
        val inventory = CapturingInventory()
        val evaluator = build(
            handsCount = 1,
            achievements = achievements,
            inventory = inventory,
            catalog = FakeCatalog.with(stubProduct("emotes_grinder")),
        )

        evaluator.evaluate(userId)

        assertEquals(listOf("FIRST_HAND_MP"), achievements.earned)
        assertTrue(inventory.earnedGrants.isEmpty())
    }

    @Test
    fun noFinishedHands_grantsNothing() = runTest {
        val achievements = CapturingAchievements()
        val inventory = CapturingInventory()
        val evaluator = build(
            handsCount = 0,
            achievements = achievements,
            inventory = inventory,
            catalog = FakeCatalog.with(stubProduct("emotes_grinder")),
        )

        evaluator.evaluate(userId)

        assertTrue(achievements.earned.isEmpty())
        assertTrue(inventory.earnedGrants.isEmpty())
    }

    @Test
    fun alreadyEarned_isNotReGranted() = runTest {
        val achievements = CapturingAchievements(seeded = setOf("FIRST_HAND_MP", "HANDS_100_MP"))
        val inventory = CapturingInventory()
        val evaluator = build(
            handsCount = 250,
            achievements = achievements,
            inventory = inventory,
            catalog = FakeCatalog.with(stubProduct("emotes_grinder")),
        )

        evaluator.evaluate(userId)

        assertTrue(achievements.recordedThisCall.isEmpty())
        assertTrue(inventory.earnedGrants.isEmpty())
    }

    @Test
    fun missingCatalogProduct_stillRecordsAchievement_butGrantsNoCosmetic() = runTest {
        val achievements = CapturingAchievements()
        val inventory = CapturingInventory()
        val evaluator = build(
            handsCount = 100,
            achievements = achievements,
            inventory = inventory,
            catalog = FakeCatalog.empty(),
        )

        evaluator.evaluate(userId)

        assertEquals(listOf("FIRST_HAND_MP", "HANDS_100_MP"), achievements.earned)
        assertTrue(inventory.earnedGrants.isEmpty())
    }

    @Test
    fun doubleUp_recordsDoubleUpMp_andGrantsChips() = runTest {
        val achievements = CapturingAchievements()
        val wallet = CapturingWallet()
        val evaluator = build(handsCount = 0, achievements = achievements, inventory = CapturingInventory(), catalog = FakeCatalog.empty(), wallet = wallet)

        evaluator.evaluateHand(userId, outcome(stackMultiple = 2.0))

        assertEquals(listOf("DOUBLE_UP_MP"), achievements.earned)
        assertEquals(1, wallet.applied.size)
        assertEquals("achievement:DOUBLE_UP_MP", wallet.applied.single().idempotencyKey)
        assertEquals(1_000L, wallet.applied.single().delta)
    }

    @Test
    fun tripleUp_recordsBothDoubleAndTripleMp() = runTest {
        val achievements = CapturingAchievements()
        val wallet = CapturingWallet()
        val evaluator = build(handsCount = 0, achievements = achievements, inventory = CapturingInventory(), catalog = FakeCatalog.empty(), wallet = wallet)

        evaluator.evaluateHand(userId, outcome(stackMultiple = 3.0))

        // Tripling up crosses the double-up milestone too — both are earned.
        assertEquals(setOf("DOUBLE_UP_MP", "TRIPLE_UP_MP"), achievements.earned.toSet())
        assertEquals(2_000L, wallet.applied.single { it.idempotencyKey == "achievement:TRIPLE_UP_MP" }.delta)
    }

    @Test
    fun bustDealt_recordsFirstBustDealtMp() = runTest {
        val achievements = CapturingAchievements()
        val wallet = CapturingWallet()
        val evaluator = build(handsCount = 0, achievements = achievements, inventory = CapturingInventory(), catalog = FakeCatalog.empty(), wallet = wallet)

        evaluator.evaluateHand(userId, outcome(won = true, bustsDealt = 1))

        assertTrue("FIRST_BUST_DEALT_MP" in achievements.earned)
        assertEquals(1_000L, wallet.applied.single { it.idempotencyKey == "achievement:FIRST_BUST_DEALT_MP" }.delta)
    }

    @Test
    fun bigPotWin_recordsPot5000Mp_forTheWinner() = runTest {
        val achievements = CapturingAchievements()
        val wallet = CapturingWallet()
        buildWith(achievements, wallet).evaluateHand(userId, outcome(won = true, potTotal = 5_000))

        assertTrue("POT_5000_MP" in achievements.earned)
        assertEquals(1_500L, wallet.applied.single { it.idempotencyKey == "achievement:POT_5000_MP" }.delta)
    }

    @Test
    fun bigPot_loserDoesNotEarnPot5000Mp() = runTest {
        val achievements = CapturingAchievements()
        buildWith(achievements, CapturingWallet()).evaluateHand(userId, outcome(won = false, potTotal = 5_000))

        assertTrue("POT_5000_MP" !in achievements.earned, "a non-winner in a 5k pot doesn't earn it")
    }

    @Test
    fun ordinaryHand_grantsNothing() = runTest {
        val achievements = CapturingAchievements()
        val wallet = CapturingWallet()
        val evaluator = build(handsCount = 0, achievements = achievements, inventory = CapturingInventory(), catalog = FakeCatalog.empty(), wallet = wallet)

        evaluator.evaluateHand(userId, outcome(won = true, stackMultiple = 1.4, bustsDealt = 0, potTotal = 800))

        assertTrue(achievements.earned.isEmpty())
        assertTrue(wallet.applied.isEmpty())
    }

    @Test
    fun perHandAlreadyEarned_isNotReGranted() = runTest {
        val achievements = CapturingAchievements(
            seeded = setOf("FIRST_BUST_DEALT_MP", "DOUBLE_UP_MP", "TRIPLE_UP_MP", "POT_5000_MP"),
        )
        val wallet = CapturingWallet()
        val evaluator = build(handsCount = 0, achievements = achievements, inventory = CapturingInventory(), catalog = FakeCatalog.empty(), wallet = wallet)

        evaluator.evaluateHand(userId, outcome(won = true, stackMultiple = 3.0, bustsDealt = 2, potTotal = 9_000))

        assertTrue(achievements.recordedThisCall.isEmpty())
        assertTrue(wallet.applied.isEmpty())
    }

    private fun outcome(
        won: Boolean = false,
        stackMultiple: Double = 1.0,
        bustsDealt: Int = 0,
        potTotal: Long = 0,
    ): PlayerHandOutcome = PlayerHandOutcome(
        won = won,
        stackMultiple = stackMultiple,
        bustsDealt = bustsDealt,
        potTotal = potTotal,
    )

    private fun buildWith(
        achievements: AchievementRepository,
        wallet: WalletRepository,
    ): DefaultServerWitnessedAchievements = build(
        handsCount = 0,
        achievements = achievements,
        inventory = CapturingInventory(),
        catalog = FakeCatalog.empty(),
        wallet = wallet,
    )

    private fun build(
        handsCount: Long,
        achievements: AchievementRepository,
        inventory: InventoryRepository,
        catalog: ProductCatalogSource,
        wallet: WalletRepository = CapturingWallet(),
    ): DefaultServerWitnessedAchievements = DefaultServerWitnessedAchievements(
        handsFinished = FixedCountHandsFinished(handsCount),
        achievements = achievements,
        inventory = inventory,
        catalog = catalog,
        wallet = wallet,
        clock = Clock.System,
    )

    private fun stubProduct(id: String): Product.ChipOffer = Product.ChipOffer(
        id = id,
        titleByLocale = mapOf("en" to "Stub"),
        subtitleByLocale = mapOf("en" to "Stub"),
        iconEmoji = "🏆",
        costChips = 0L,
        grantsKey = id,
        isEquippable = true,
    )

    private class FixedCountHandsFinished(private val count: Long) :
        com.dangerfield.cards.server.domain.HandsFinishedRepository {
        override suspend fun recordHandFinished(
            userId: UserId,
            idempotencyKey: String,
            handSessionId: UUID,
            handNumber: Int,
        ) = Unit

        override suspend fun countForUser(userId: UserId): Long = count

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private class CapturingAchievements(seeded: Set<String> = emptySet()) : AchievementRepository {
        private val earnedSet = seeded.toMutableSet()
        val recordedThisCall = mutableListOf<String>()
        val earned: List<String> get() = recordedThisCall

        override suspend fun recordEarned(userId: UserId, achievementId: String, earnedAt: Instant): EarnedAchievement {
            if (earnedSet.add(achievementId)) recordedThisCall += achievementId
            return EarnedAchievement(achievementId = achievementId, earnedAt = earnedAt)
        }

        override suspend fun listEarned(userId: UserId): List<EarnedAchievement> =
            earnedSet.map { EarnedAchievement(achievementId = it, earnedAt = Instant.fromEpochMilliseconds(0)) }

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private class CapturingInventory : InventoryRepository {
        data class EarnedGrant(val userId: UserId, val productId: String)

        val earnedGrants = mutableListOf<EarnedGrant>()

        override suspend fun listOwned(userId: UserId): List<OwnedItem> = emptyList()

        override suspend fun recordPurchase(
            userId: UserId,
            productId: String,
            costChipsAtPurchase: Long,
            purchasedAt: Instant,
        ): OwnedItem = error("recordPurchase not used in this test")

        override suspend fun recordEarnedGrant(userId: UserId, productId: String, grantedAt: Instant): OwnedItem {
            earnedGrants += EarnedGrant(userId, productId)
            return OwnedItem(
                productId = productId,
                costChipsAtPurchase = 0L,
                purchasedAt = grantedAt,
                acquisitionSource = AcquisitionSource.Earned,
            )
        }

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private class CapturingWallet : WalletRepository {
        val applied = mutableListOf<WalletEvent>()
        private var balance = 0L

        override suspend fun findOrCreateResult(userId: UserId): FindOrCreateResult =
            error("findOrCreateResult not used in this test")

        override suspend fun find(userId: UserId): Wallet? = null

        override suspend fun apply(
            userId: UserId,
            idempotencyKey: String,
            delta: Long,
            reason: String,
        ): ApplyOutcome {
            val alreadyApplied = applied.any { it.idempotencyKey == idempotencyKey }
            if (!alreadyApplied) {
                applied += WalletEvent(
                    userId = userId,
                    idempotencyKey = idempotencyKey,
                    delta = delta,
                    reason = reason,
                    appliedAt = Instant.fromEpochMilliseconds(0),
                )
                balance += delta
            }
            return ApplyOutcome.Applied(balance = balance, wasAlreadyApplied = alreadyApplied)
        }

        override suspend fun recentEvents(userId: UserId, limit: Int): List<WalletEvent> = applied

        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    private class FakeCatalog private constructor(private val byId: Map<String, Product>) : ProductCatalogSource {
        override suspend fun read(context: ClientContext): ProductCatalog =
            ProductCatalog(chipPacks = emptyList(), chipOffers = emptyList())

        override suspend fun readById(id: String, context: ClientContext): Product? = byId[id]

        companion object {
            fun empty(): FakeCatalog = FakeCatalog(emptyMap())
            fun with(vararg products: Product): FakeCatalog = FakeCatalog(products.associateBy { it.id })
        }
    }
}
