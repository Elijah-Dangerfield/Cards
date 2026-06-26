package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AchievementGrantApi
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.PlayerStats
import com.dangerfield.cards.libraries.cards.PlayerStatsRepository
import com.dangerfield.cards.libraries.cards.PlayerStatHandSummary
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.cards.winsVsBotKey
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.storage.db.AchievementDao
import com.dangerfield.cards.libraries.cards.storage.db.AchievementEarnedEntity
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The unlock engine over server-authoritative counters. Counter *derivation* is
 * tested in the shared fold (`libraries:achievements`); this pins the engine's
 * own job: given the effective counters, unlock the met-and-not-earned
 * achievements, cap per hand, grant rewards, dedup, and respect mode.
 */
class AchievementRepositoryImplTest : CoroutineTest() {

    @Test
    fun recordHand_unlocksAnAchievementWhoseCounterIsMet() = runUnitTest {
        val deps = Deps().preEarnAllExcept(AchievementId.POT_500)
        // POT_500 = Custom(max_pot_seen, 500).
        deps.stats.emitCounters(mapOf("max_pot_seen" to 500L))

        val earned = deps.build().recordHand(summary(mode = XpMode.BOTS), context())

        assertTrue(earned.any { it.achievement.id == AchievementId.POT_500 })
        assertTrue(AchievementId.POT_500.name in deps.dao.earnedIds())
    }

    @Test
    fun recordHand_doesNotUnlock_whenCounterBelowTarget() = runUnitTest {
        val deps = Deps().preEarnAllExcept(AchievementId.POT_1000)
        deps.stats.emitCounters(mapOf("max_pot_seen" to 999L)) // POT_1000 needs 1000

        val earned = deps.build().recordHand(summary(mode = XpMode.BOTS), context())

        assertFalse(earned.any { it.achievement.id == AchievementId.POT_1000 })
    }

    @Test
    fun recordHand_capsUnlocksPerHand_andDefersTheRest() = runUnitTest {
        // Leave several achievements unearned and meet all of them at once.
        val deps = Deps()
        deps.stats.emitCounters(
            mapOf(
                "max_pot_seen" to 100_000L,    // POT_500, POT_1000
                "all_in_hands" to 100L,        // FIRST_ALL_IN
                "win_by_fold" to 100L,         // FIRST_WIN_BY_FOLD, WIN_BY_FOLD_10
                "good_fold" to 100L,           // GOOD_FOLD_FIRST, GOOD_FOLD_25
            ),
        )

        val earned = deps.build().recordHand(summary(mode = XpMode.BOTS), context())

        assertEquals(2, earned.size, "no more than the per-hand cap unlock at once")
    }

    @Test
    fun recordHand_isIdempotent_doesNotReEarn() = runUnitTest {
        val deps = Deps().preEarnAllExcept(AchievementId.FIRST_ALL_IN)
        deps.stats.emitCounters(mapOf("all_in_hands" to 5L))
        val repo = deps.build()

        val first = repo.recordHand(summary(mode = XpMode.BOTS), context())
        val second = repo.recordHand(summary(mode = XpMode.BOTS), context())

        assertEquals(1, first.size)
        assertTrue(second.isEmpty(), "already-earned achievement doesn't re-fire")
    }

    @Test
    fun recordHand_grantsXpAndChips_forUnlocked() = runUnitTest {
        val deps = Deps().preEarnAllExcept(AchievementId.POT_500)
        deps.stats.emitCounters(mapOf("max_pot_seen" to 500L))

        deps.build().recordHand(summary(mode = XpMode.BOTS), context())

        val pot500 = AllAchievements.first { it.id == AchievementId.POT_500 }
        assertTrue(deps.progression.appliedAchievementXp.any { it.first == pot500.xpReward })
        if (pot500.chipReward > 0) assertEquals(10_000L + pot500.chipReward, deps.chips.balance())
    }

    @Test
    fun recordHand_respectsMode_botAchievementDoesNotUnlockInMp() = runUnitTest {
        // BEAT_JANE_10 is a BOTS-mode achievement; meeting it during an MP hand
        // must not unlock it.
        val deps = Deps().preEarnAllExcept(AchievementId.BEAT_JANE_10)
        deps.stats.emitCounters(mapOf(winsVsBotKey("Jane") to 10L))

        val earned = deps.build().recordHand(summary(mode = XpMode.MULTIPLAYER), context())

        assertFalse(earned.any { it.achievement.id == AchievementId.BEAT_JANE_10 })
    }

    @Test
    fun recordTutorialComplete_isIdempotent() = runUnitTest {
        val deps = Deps()
        val repo = deps.build()

        val first = repo.recordTutorialComplete()
        val second = repo.recordTutorialComplete()

        assertEquals(AchievementId.TUTORIAL_COMPLETE, first?.achievement?.id)
        assertEquals(null, second, "tutorial grant fires once")
    }

    // ---------- harness ----------

    private fun summary(mode: XpMode): HandResultSummary = HandResultSummary(
        handId = "h1",
        mode = mode,
        wasFold = false,
        reachedShowdown = false,
        wonPot = false,
        chipsCommitted = 0L,
        bigBlind = 10L,
        handCategory = null,
    )

    private fun context(): AchievementHandContext = AchievementHandContext(
        opponentBotNames = emptyList(),
        botDifficulty = null,
        humanStartingStack = 1_000L,
        humanEndingStack = 1_000L,
        bigBlind = 10L,
    )

    private inner class Deps {
        val dao = FakeAchievementDao()
        val stats = FakeStats()
        val progression = FakeProgressionRepository()
        val chips = FakeChipsRepository()

        fun preEarnAllExcept(keep: AchievementId): Deps {
            for (ach in AllAchievements) if (ach.id != keep) {
                dao.earned[ach.id.name] = AchievementEarnedEntity(ach.id.name, earnedAtEpochMs = 1L)
            }
            return this
        }

        fun build() = AchievementRepositoryImpl(
            achievementDao = dao,
            playerStatsRepository = stats,
            progressionRepository = progression,
            chipsRepository = chips,
            grantApi = object : AchievementGrantApi {
                override suspend fun grantAchievement(achievementId: AchievementId) = false
            },
            inventoryRepository = FakeInventoryRepository(),
            networkClient = NeverCalledNetworkClient,
            progressionConfig = FakeProgressionConfig(),
            appScope = AppCoroutineScope(dispatchers),
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(1_000L)
            },
        )
    }

    @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
    private object NeverCalledNetworkClient : com.dangerfield.cards.libraries.networking.NetworkClient {
        private val engine = io.ktor.client.engine.mock.MockEngine { error("unexpected network call") }
        override val client = io.ktor.client.HttpClient(engine)
        override val authenticatedClient = client
        override suspend fun awaitAuthReady() = Unit
    }

    private class FakeStats : PlayerStatsRepository {
        private val counters = MutableStateFlow<Map<String, Long>>(emptyMap())
        fun emitCounters(c: Map<String, Long>) { counters.value = c }
        override fun observeStats(): Flow<PlayerStats?> = MutableStateFlow(null)
        override suspend fun getStats(): PlayerStats? = null
        override fun observeEffectiveCounters(): Flow<Map<String, Long>> = counters.asStateFlow()
        override suspend fun effectiveCounters(): Map<String, Long> = counters.value
        override suspend fun recordHand(summary: PlayerStatHandSummary) {}
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAll() {}
    }

    private class FakeAchievementDao : AchievementDao {
        val earned = mutableMapOf<String, AchievementEarnedEntity>()
        private val earnedFlow = MutableStateFlow<List<AchievementEarnedEntity>>(emptyList())
        fun earnedIds() = earned.keys
        override fun observeEarned(): Flow<List<AchievementEarnedEntity>> = earnedFlow.asStateFlow()
        override suspend fun getEarned(): List<AchievementEarnedEntity> = earned.values.toList()
        override suspend fun insertEarned(entity: AchievementEarnedEntity) {
            if (earned.putIfAbsent(entity.achievementId, entity) == null) earnedFlow.value = earned.values.toList()
        }
        override suspend fun getUnsyncedEarned() = earned.values.filter { !it.synced }
        override suspend fun markEarnedSynced(ids: List<String>) {
            ids.forEach { id -> earned[id]?.let { earned[id] = it.copy(synced = true) } }
        }
        override suspend fun deleteAllEarned() { earned.clear() }
    }

    private class FakeProgressionRepository : ProgressionRepository {
        private val state = MutableStateFlow(Progression.Empty)
        val appliedAchievementXp = mutableListOf<Pair<Int, String?>>()
        override fun observeProgression(): Flow<Progression> = state.asStateFlow()
        override suspend fun getProgression(): Progression = state.value
        override suspend fun awardForHand(summary: HandResultSummary): List<XpEvent> = emptyList()
        override suspend fun applyAchievementXp(delta: Int, description: String?): XpEvent {
            appliedAchievementXp += delta to description
            return XpEvent(
                id = 0L,
                deltaXp = delta,
                source = XpSource.ACHIEVEMENT,
                mode = XpMode.BOTS,
                handId = null,
                description = description,
                createdAtEpochMs = 0L,
            )
        }
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAll() {}
        override suspend fun debugSetTotalXp(totalXp: Long) { state.value = state.value.copy(totalXp = totalXp) }
    }

    private class FakeChipsRepository : ChipsRepository {
        private val state = MutableStateFlow<Long?>(10_000L)
        fun balance() = state.value
        override val walletJustCreated = MutableStateFlow(false)
        override fun observeBalance(): Flow<Long?> = state.asStateFlow()
        override suspend fun getBalance(): Long? = state.value
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
            state.value = (state.value ?: 0L) + amount
        }
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) {
            state.value = (state.value ?: 0L) - amount
        }
        override suspend fun setBalance(authoritativeBalance: Long) { state.value = authoritativeBalance }
        override suspend fun deleteAll() { state.value = 0L }
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }

    private class FakeInventoryRepository : InventoryRepository {
        private val state = MutableStateFlow<List<InventoryItem>>(emptyList())
        override fun observeInventory(): Flow<List<InventoryItem>> = state.asStateFlow()
        override suspend fun getInventory(): List<InventoryItem> = state.value
        override suspend fun redeemChipOffer(productId: String, costChips: Long) = RedeemResult.Success
        override suspend fun markConfirmed(productIds: Collection<String>) {}
        override suspend fun revertPurchase(productId: String) {}
        override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) {}
        override suspend fun deleteAll() {}
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }
}
