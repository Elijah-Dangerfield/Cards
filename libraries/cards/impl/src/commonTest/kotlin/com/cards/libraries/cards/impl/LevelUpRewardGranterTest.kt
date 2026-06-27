package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.DefaultLevelRewards
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.LevelCosmeticGrantApi
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpBoostRepository
import com.dangerfield.cards.libraries.cards.XpBoostStatus
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.xpAtStartOfLevel
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [LevelUpRewardGranter] — the offline-first, idempotent grant on
 * level-cross. The watermark (`highestLevelRewarded`) gates re-grants and the
 * `0` sentinel seeds silently so existing levels are never retro-rewarded.
 */
class LevelUpRewardGranterTest : CoroutineTest() {

    @Test
    fun unsetWatermark_seedsToCurrentLevel_withoutGranting() = runUnitTest {
        val cache = FakeAppCache() // highestLevelRewarded = 0
        val chips = RecordingChipsRepository()
        // Level 5 account on a fresh install — must NOT retro-grant 3 & 5.
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(5)))

        build(cache = cache, chips = chips, progression = progression)

        assertEquals(5, cache.get().highestLevelRewarded, "seeds silently to the current level")
        assertTrue(chips.grants.isEmpty(), "no retro grants on first observation")
    }

    @Test
    fun unsetWatermark_alsoSeedsCelebrationWatermark_toTheSameLevel() = runUnitTest {
        // PROG-3: the granter is the single AutoInit observer that reliably sees
        // the user's pre-session level before any hand is played. It must anchor
        // BOTH watermarks there so a level-up earned this session can't be eaten
        // by HomeViewModel's own (racy, possibly-late) celebration seed. Without
        // this, a fresh account that levels up before Home's gate first emits
        // seeds lastCelebratedLevel straight to the new level and the celebration
        // is silently dropped.
        val cache = FakeAppCache() // both watermarks 0
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(5)))

        build(cache = cache, progression = progression)

        assertEquals(5, cache.get().highestLevelRewarded)
        assertEquals(5, cache.get().lastCelebratedLevel, "celebration watermark anchored alongside the reward one")
    }

    @Test
    fun unsetRewardWatermark_doesNotClobberAnAlreadySeededCelebrationWatermark() = runUnitTest {
        // If HomeViewModel's gate already seeded lastCelebratedLevel (won the
        // race), the granter's seed must leave it alone — only seed the
        // celebration watermark when it too is unset.
        val cache = FakeAppCache(AppData(lastCelebratedLevel = 1))
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(3)))

        build(cache = cache, progression = progression)

        assertEquals(3, cache.get().highestLevelRewarded)
        assertEquals(1, cache.get().lastCelebratedLevel, "existing celebration watermark untouched")
    }

    @Test
    fun crossingRewardedLevel_grantsChips_keyedPerLevel_andAdvancesWatermark() = runUnitTest {
        val cache = FakeAppCache(AppData(highestLevelRewarded = 2))
        val chips = RecordingChipsRepository()
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(3)))

        build(cache = cache, chips = chips, progression = progression)

        assertEquals(listOf("levelup_3" to 1_000L), chips.grants)
        assertEquals(3, cache.get().highestLevelRewarded)
    }

    @Test
    fun multiLevelJump_grantsEachCrossedRewardedLevel_once() = runUnitTest {
        val cache = FakeAppCache(AppData(highestLevelRewarded = 2))
        val chips = RecordingChipsRepository()
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(7)))

        build(cache = cache, chips = chips, progression = progression)

        // Levels 3, 5, 7 carry chips (4 & 6 carry nothing) — each once.
        assertEquals(
            listOf("levelup_3" to 1_000L, "levelup_5" to 2_500L, "levelup_7" to 4_000L),
            chips.grants,
        )
        assertEquals(7, cache.get().highestLevelRewarded)
    }

    @Test
    fun boostRewardLevel_grantsInactiveBoost_alongsideChips() = runUnitTest {
        val cache = FakeAppCache(AppData(highestLevelRewarded = 9))
        val chips = RecordingChipsRepository()
        val boost = RecordingXpBoostRepository()
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(10)))

        build(cache = cache, chips = chips, boost = boost, progression = progression)

        assertEquals(listOf("levelup_10" to 7_500L), chips.grants)
        assertEquals(1, boost.grants, "level 10 stashes an XP boost")
        assertEquals(0, boost.activations, "gifted boost must NOT auto-activate")
        assertEquals(10, cache.get().highestLevelRewarded)
    }

    @Test
    fun crossingCosmeticLevel_postsGrant_andResyncsInventory_onSuccess() = runUnitTest {
        val cache = FakeAppCache(AppData(highestLevelRewarded = 2))
        val cosmeticApi = RecordingCosmeticGrantApi(granted = true)
        val inventory = RecordingInventoryRepository()
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(3)))

        build(
            cache = cache,
            cosmeticApi = cosmeticApi,
            inventory = inventory,
            progression = progression,
            config = CosmeticAtLevel3Config,
        )

        assertEquals(listOf("cardback_level_three"), cosmeticApi.grants)
        assertEquals(1, inventory.syncs, "a granted cosmetic re-syncs inventory")
        assertEquals(3, cache.get().highestLevelRewarded)
    }

    @Test
    fun cosmeticGrantFailure_doesNotResync_butStillAdvancesWatermark() = runUnitTest {
        val cache = FakeAppCache(AppData(highestLevelRewarded = 2))
        val cosmeticApi = RecordingCosmeticGrantApi(granted = false)
        val inventory = RecordingInventoryRepository()
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(3)))

        build(
            cache = cache,
            cosmeticApi = cosmeticApi,
            inventory = inventory,
            progression = progression,
            config = CosmeticAtLevel3Config,
        )

        assertEquals(listOf("cardback_level_three"), cosmeticApi.grants, "still attempts the grant")
        assertEquals(0, inventory.syncs, "no re-sync when the grant didn't land")
        assertEquals(3, cache.get().highestLevelRewarded, "watermark advances; the next sync catches a missed grant")
    }

    @Test
    fun reEmittingSameLevel_doesNotRegrant() = runUnitTest {
        val cache = FakeAppCache(AppData(highestLevelRewarded = 2))
        val chips = RecordingChipsRepository()
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(3)))

        build(cache = cache, chips = chips, progression = progression)
        // Same level, more Xp within it → no new reward.
        progression.progression.value =
            progression.progression.value.copy(totalXp = xpAtStartOfLevel(3) + 50)

        assertEquals(listOf("levelup_3" to 1_000L), chips.grants, "still exactly one grant")
    }

    @Test
    fun derivesLevelFromTheConfiguredCurve_notTheBundledDefault() = runUnitTest {
        val cache = FakeAppCache(AppData(highestLevelRewarded = 1))
        val chips = RecordingChipsRepository()
        // 50 XP is still level 1 under the bundled 100×N² curve, but the
        // configured curve levels up at 50 — so this grant only fires if the
        // granter reads the curve off config, not the global default.
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = 50))

        build(cache = cache, chips = chips, progression = progression, config = FastCurveLevel2Config)

        assertEquals(listOf("levelup_2" to 777L), chips.grants)
        assertEquals(2, cache.get().highestLevelRewarded)
    }

    private fun build(
        cache: FakeAppCache = FakeAppCache(),
        chips: RecordingChipsRepository = RecordingChipsRepository(),
        boost: RecordingXpBoostRepository = RecordingXpBoostRepository(),
        cosmeticApi: RecordingCosmeticGrantApi = RecordingCosmeticGrantApi(),
        inventory: RecordingInventoryRepository = RecordingInventoryRepository(),
        progression: FakeProgressionRepository = FakeProgressionRepository(),
        config: ProgressionConfig = DefaultProgressionConfigFake(),
    ): LevelUpRewardGranter = LevelUpRewardGranter(
        progressionRepository = progression,
        chipsRepository = chips,
        xpBoostRepository = boost,
        cosmeticGrantApi = cosmeticApi,
        inventoryRepository = inventory,
        progressionConfig = config,
        appCache = cache,
        appScope = AppCoroutineScope(dispatchers),
    )

    private class DefaultProgressionConfigFake : ProgressionConfig {
        override fun rewardsForLevel(level: Int): List<LevelReward> =
            DefaultLevelRewards.rewardsForLevel(level)
        override fun levelCurve(): LevelCurve = DefaultLevelCurve
    }

    private object CosmeticAtLevel3Config : ProgressionConfig {
        override fun rewardsForLevel(level: Int): List<LevelReward> =
            if (level == 3) listOf(LevelReward.Cosmetic("cardback_level_three")) else emptyList()
        override fun levelCurve(): LevelCurve = DefaultLevelCurve
    }

    private object FastCurveLevel2Config : ProgressionConfig {
        override fun rewardsForLevel(level: Int): List<LevelReward> =
            if (level == 2) listOf(LevelReward.Chips(777)) else emptyList()
        // Levels up to 2 at 50 XP — half the bundled curve's threshold.
        override fun levelCurve(): LevelCurve = LevelCurve(xpPerLevel = listOf(50L))
    }

    private class RecordingCosmeticGrantApi(
        private val granted: Boolean = false,
    ) : LevelCosmeticGrantApi {
        val grants = mutableListOf<String>()
        override suspend fun grantLevelCosmetic(productId: String): Boolean {
            grants += productId
            return granted
        }
    }

    private class RecordingInventoryRepository : InventoryRepository {
        var syncs: Int = 0
            private set
        override fun observeInventory(): Flow<List<InventoryItem>> = MutableStateFlow(emptyList())
        override suspend fun getInventory(): List<InventoryItem> = emptyList()
        override suspend fun redeemChipOffer(productId: String, costChips: Long): RedeemResult =
            RedeemResult.Success
        override suspend fun markConfirmed(productIds: Collection<String>) = Unit
        override suspend fun revertPurchase(productId: String) = Unit
        override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun sync(): Result<Unit> {
            syncs += 1
            return Result.success(Unit)
        }
    }

    private class FakeProgressionRepository(
        initial: Progression = Progression.Empty,
    ) : ProgressionRepository {
        val progression = MutableStateFlow(initial)
        override fun observeProgression(): Flow<Progression> = progression
        override suspend fun getProgression(): Progression = progression.value
        override suspend fun awardForHand(summary: HandResultSummary): List<XpEvent> = error("unused")
        override suspend fun applyAchievementXp(delta: Int, description: String?): XpEvent = error("unused")
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAll() = Unit
        override suspend fun debugSetTotalXp(totalXp: Long) {
            progression.value = progression.value.copy(totalXp = totalXp)
        }
    }

    private class RecordingChipsRepository : ChipsRepository {
        val grants = mutableListOf<Pair<String, Long>>()
        private val balance = MutableStateFlow<Long?>(0L)
        override val walletJustCreated = MutableStateFlow(false)
        override fun observeBalance(): Flow<Long?> = balance
        override suspend fun getBalance(): Long? = balance.value
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
            grants += (idempotencyKey.orEmpty() to amount)
        }
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
        override suspend fun setBalance(authoritativeBalance: Long) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }

    private class RecordingXpBoostRepository : XpBoostRepository {
        var grants: Int = 0
            private set
        var activations: Int = 0
            private set
        override fun observe(): Flow<XpBoostStatus> = MutableStateFlow(XpBoostStatus.None)
        override suspend fun status(): XpBoostStatus = XpBoostStatus.None
        override suspend fun grant(count: Int) { grants += count }
        override suspend fun activate(durationMs: Long): Boolean { activations += 1; return true }
        override suspend fun multiplier(): Int = 1
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }
}
