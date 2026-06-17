package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.Progression
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
    fun boostRewardLevel_activatesBoost_alongsideChips() = runUnitTest {
        val cache = FakeAppCache(AppData(highestLevelRewarded = 9))
        val chips = RecordingChipsRepository()
        val boost = RecordingXpBoostRepository()
        val progression = FakeProgressionRepository(Progression.Empty.copy(totalXp = xpAtStartOfLevel(10)))

        build(cache = cache, chips = chips, boost = boost, progression = progression)

        assertEquals(listOf("levelup_10" to 7_500L), chips.grants)
        assertEquals(1, boost.activations, "level 10 also gifts an XP boost")
        assertEquals(10, cache.get().highestLevelRewarded)
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

    private fun build(
        cache: FakeAppCache = FakeAppCache(),
        chips: RecordingChipsRepository = RecordingChipsRepository(),
        boost: RecordingXpBoostRepository = RecordingXpBoostRepository(),
        progression: FakeProgressionRepository = FakeProgressionRepository(),
    ): LevelUpRewardGranter = LevelUpRewardGranter(
        progressionRepository = progression,
        chipsRepository = chips,
        xpBoostRepository = boost,
        appCache = cache,
        appScope = AppCoroutineScope(dispatchers),
    )

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
        var activations: Int = 0
            private set
        override fun observe(): Flow<XpBoostStatus> = MutableStateFlow(XpBoostStatus.None)
        override suspend fun status(): XpBoostStatus = XpBoostStatus.None
        override suspend fun activate(durationMs: Long) { activations += 1 }
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
