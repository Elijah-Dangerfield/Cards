package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AchievementGrantApi
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.BOT_WHISPERER_BOTS_BEATEN
import com.dangerfield.cards.libraries.cards.BUSTS_DEALT_MP
import com.dangerfield.cards.libraries.cards.DOUBLED_UP_MP
import com.dangerfield.cards.libraries.cards.HANDS_PLAYED_MP
import com.dangerfield.cards.libraries.cards.MAX_POT_BB_RATIO_MP
import com.dangerfield.cards.libraries.cards.TRIPLED_UP_MP
import com.dangerfield.cards.libraries.cards.WIN_BY_FOLD_MP
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.MAX_POT_BB_RATIO
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.cards.SHORT_STACK_ARMED
import com.dangerfield.cards.libraries.cards.winsVsBotKey
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.storage.db.AchievementCounterEntity
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
 * Pins the V1.x BB-relative re-anchors for DONT_CALL_IT_COMEBACK and POT_5000.
 * Covers the tier-blind threshold breakage flagged in
 * `docs/achievements-spec.md` Summary §1 and §2.
 *
 * Tests pre-mark all other achievements as already-earned so the per-hand
 * `MAX_ACHIEVEMENTS_PER_HAND` cap can't crowd the targets out of the result.
 */
class AchievementRepositoryImplTest : CoroutineTest() {

    @Test
    fun dontCallItComeback_armsAtTenBb_andFiresAtHundredBb_onCasualTier() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.DONT_CALL_IT_COMEBACK) }
        val repo = deps.build()

        // Casual: BB=10 → arm ceiling 100 chips, recovery floor 1000 chips.
        // Hand 1: dip to 50 chips (= 5 BB). Armed.
        repo.recordHand(
            summary = summary(bigBlind = 10),
            context = context(humanStartingStack = 200, humanEndingStack = 50, bigBlind = 10),
        )
        assertEquals(1, deps.dao.getCounter(SHORT_STACK_ARMED), "arms on dip below 10 BB")

        // Hand 2: climb to 1000 chips (= 100 BB). Fires + disarms.
        val earned = repo.recordHand(
            summary = summary(bigBlind = 10),
            context = context(humanStartingStack = 800, humanEndingStack = 1000, bigBlind = 10),
        )
        assertTrue(
            earned.any { it.achievement.id == AchievementId.DONT_CALL_IT_COMEBACK },
            "fires when armed && ending >= 100 BB",
        )
        assertEquals(0, deps.dao.getCounter(SHORT_STACK_ARMED), "disarms after recovery")
    }

    @Test
    fun dontCallItComeback_armsAtTenBb_andFiresAtHundredBb_onHighTier() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.DONT_CALL_IT_COMEBACK) }
        val repo = deps.build()

        // High: BB=200 → arm ceiling 2000 chips, recovery floor 20_000 chips.
        // The old absolute thresholds (100 / 1000) would never fire on this
        // tier — the BB-relative re-anchor is what this test pins.
        repo.recordHand(
            summary = summary(bigBlind = 200),
            context = context(humanStartingStack = 4_000, humanEndingStack = 1_500, bigBlind = 200),
        )
        assertEquals(1, deps.dao.getCounter(SHORT_STACK_ARMED), "arms at 7.5 BB on High tier")

        val earned = repo.recordHand(
            summary = summary(bigBlind = 200),
            context = context(humanStartingStack = 15_000, humanEndingStack = 20_000, bigBlind = 200),
        )
        assertTrue(
            earned.any { it.achievement.id == AchievementId.DONT_CALL_IT_COMEBACK },
            "fires at 100 BB on High tier",
        )
    }

    @Test
    fun dontCallItComeback_doesNotFire_whenRecoveringWithoutPriorDip() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.DONT_CALL_IT_COMEBACK) }
        val repo = deps.build()

        val earned = repo.recordHand(
            summary = summary(bigBlind = 10),
            context = context(humanStartingStack = 500, humanEndingStack = 1_500, bigBlind = 10),
        )
        assertFalse(
            earned.any { it.achievement.id == AchievementId.DONT_CALL_IT_COMEBACK },
            "no fire without prior arm",
        )
    }

    @Test
    fun dontCallItComeback_doesNotFire_atBustEvenIfBelowTenBb() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.DONT_CALL_IT_COMEBACK) }
        val repo = deps.build()

        // Ending at 0 = bust. Arm-range is (0, 10 BB] — bust is excluded so
        // the bust-modal / rebuy story owns that surface.
        repo.recordHand(
            summary = summary(bigBlind = 10),
            context = context(humanStartingStack = 200, humanEndingStack = 0, bigBlind = 10),
        )
        assertEquals(0, deps.dao.getCounter(SHORT_STACK_ARMED) ?: 0, "bust does not arm")
    }

    @Test
    fun pot5000_firesAt25xBb_onCasualTier() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.POT_5000) }
        val repo = deps.build()

        // Casual: BB=10. Pot 300 chips = 30× BB → fires.
        val earned = repo.recordHand(
            summary = summary(bigBlind = 10, totalPot = 300),
            context = context(bigBlind = 10),
        )
        assertTrue(
            earned.any { it.achievement.id == AchievementId.POT_5000 },
            "30× BB ≥ 25× threshold on Casual tier",
        )
        assertEquals(30, deps.dao.getCounter(MAX_POT_BB_RATIO))
    }

    @Test
    fun pot5000_doesNotFire_belowThreshold() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.POT_5000) }
        val repo = deps.build()

        // High: BB=200. Pot 4000 chips = 20× BB → below 25 threshold.
        // The old 5000-chip absolute threshold would've fired here — that's
        // the spec's "trivial on Challenging" complaint this test guards against.
        val earned = repo.recordHand(
            summary = summary(bigBlind = 200, totalPot = 4_000),
            context = context(bigBlind = 200),
        )
        assertFalse(
            earned.any { it.achievement.id == AchievementId.POT_5000 },
            "20× BB is below the 25× threshold",
        )
    }

    @Test
    fun pot5000_firesAt25xBb_onHighTier() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.POT_5000) }
        val repo = deps.build()

        // High: BB=200. Pot 6000 chips = 30× BB → fires.
        val earned = repo.recordHand(
            summary = summary(bigBlind = 200, totalPot = 6_000),
            context = context(bigBlind = 200),
        )
        assertTrue(
            earned.any { it.achievement.id == AchievementId.POT_5000 },
            "30× BB fires on High tier",
        )
    }

    @Test
    fun maxPotBbRatio_isStickyHighWaterMark() = runUnitTest {
        val deps = Deps()
        val repo = deps.build()

        repo.recordHand(
            summary = summary(bigBlind = 10, totalPot = 500),
            context = context(bigBlind = 10),
        )
        assertEquals(50, deps.dao.getCounter(MAX_POT_BB_RATIO))

        // Smaller pot must not drop the high-water mark.
        repo.recordHand(
            summary = summary(bigBlind = 10, totalPot = 100),
            context = context(bigBlind = 10),
        )
        assertEquals(50, deps.dao.getCounter(MAX_POT_BB_RATIO), "ratchets up, never down")
    }

    @Test
    fun botWhisperer_ticksOnce_eachTimeAPerBotCounterCrosses10() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.BOT_WHISPERER) }
        // Three bots already at 9 wins each, one at 5 wins, last untouched —
        // the next won hand against all five should tick the capstone
        // counter exactly +3 (the three crossing 9→10), not +4 or +5.
        deps.dao.counters[winsVsBotKey("Jane")] = 9
        deps.dao.counters[winsVsBotKey("David")] = 9
        deps.dao.counters[winsVsBotKey("Gina")] = 9
        deps.dao.counters[winsVsBotKey("Steve")] = 5
        // Mike untouched (0).
        val repo = deps.build()

        repo.recordHand(
            summary = summary(bigBlind = 10, wonPot = true),
            context = context(
                bigBlind = 10,
                opponentBotNames = listOf("Jane", "David", "Gina", "Steve", "Mike"),
            ),
        )

        assertEquals(3, deps.dao.getCounter(BOT_WHISPERER_BOTS_BEATEN))
    }

    @Test
    fun botWhisperer_doesNotDoubleTick_whenSameBotAppearsTwice() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.BOT_WHISPERER) }
        deps.dao.counters[winsVsBotKey("Jane")] = 9
        val repo = deps.build()

        // Same bot listed twice — capstone counter must still tick exactly once.
        repo.recordHand(
            summary = summary(bigBlind = 10, wonPot = true),
            context = context(bigBlind = 10, opponentBotNames = listOf("Jane", "Jane")),
        )

        assertEquals(1, deps.dao.getCounter(BOT_WHISPERER_BOTS_BEATEN))
    }

    @Test
    fun botWhisperer_doesNotTick_afterPerBotCounterAlreadyCrossed10() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.BOT_WHISPERER) }
        // Counter already past 10 — additional wins must not double-tick.
        deps.dao.counters[winsVsBotKey("Jane")] = 15
        val repo = deps.build()

        repo.recordHand(
            summary = summary(bigBlind = 10, wonPot = true),
            context = context(bigBlind = 10, opponentBotNames = listOf("Jane")),
        )

        assertEquals(0, deps.dao.getCounter(BOT_WHISPERER_BOTS_BEATEN) ?: 0)
    }

    @Test
    fun tutorialComplete_unlocksFirstTime_andIsIdempotent() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.TUTORIAL_COMPLETE) }
        val repo = deps.build()

        val first = repo.recordTutorialComplete()
        assertTrue(
            first?.achievement?.id == AchievementId.TUTORIAL_COMPLETE,
            "first completion fires the achievement",
        )
        assertTrue(
            deps.dao.earned.containsKey(AchievementId.TUTORIAL_COMPLETE.name),
            "earned row written",
        )

        // Replay (e.g. user opens "How to play" again from Settings).
        val second = repo.recordTutorialComplete()
        assertTrue(second == null, "replay returns null — no double-grant")
        // No double XP — first call awarded once, second call short-circuited.
        assertTrue(
            deps.progression.appliedAchievementXp.size == 1,
            "XP awarded exactly once across two completions",
        )
    }

    @Test
    fun botWhisperer_unlocks_whenCounterReaches5() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.BOT_WHISPERER) }
        // Four bots already counted toward the capstone; this hand is the
        // fifth bot's first cross at 10.
        deps.dao.counters[BOT_WHISPERER_BOTS_BEATEN] = 4
        deps.dao.counters[winsVsBotKey("Mike")] = 9
        val repo = deps.build()

        val earned = repo.recordHand(
            summary = summary(bigBlind = 10, wonPot = true),
            context = context(bigBlind = 10, opponentBotNames = listOf("Mike")),
        )

        assertTrue(
            earned.any { it.achievement.id == AchievementId.BOT_WHISPERER },
            "fires once the fifth bot's per-bot counter crosses 10",
        )
    }

    @Test
    fun mpAchievement_doesNotFire_inBotsModeHand_evenIfCriterionMet() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.FIRST_BUST_DEALT_MP) }
        deps.dao.counters[BUSTS_DEALT_MP] = 1
        val repo = deps.build()

        val earned = repo.recordHand(
            summary = summary(bigBlind = 10, mode = XpMode.BOTS),
            context = context(bigBlind = 10),
        )

        assertFalse(
            earned.any { it.achievement.id == AchievementId.FIRST_BUST_DEALT_MP },
            "MP-mode achievement cannot unlock from a bots-mode hand",
        )
    }

    @Test
    fun mpAchievement_fires_inMultiplayerHand_whenCriterionMet() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.FIRST_BUST_DEALT_MP) }
        deps.dao.counters[BUSTS_DEALT_MP] = 1
        val repo = deps.build()

        val earned = repo.recordHand(
            summary = summary(bigBlind = 10, mode = XpMode.MULTIPLAYER),
            context = context(bigBlind = 10),
        )

        assertTrue(
            earned.any { it.achievement.id == AchievementId.FIRST_BUST_DEALT_MP },
            "MP-mode achievement unlocks from a multiplayer hand",
        )
    }

    @Test
    fun botsAchievement_doesNotFire_inMultiplayerHand_evenIfCriterionMet() = runUnitTest {
        val deps = Deps().also { it.preEarnAllExcept(AchievementId.BEAT_JANE_10) }
        deps.dao.counters[winsVsBotKey("Jane")] = 10
        val repo = deps.build()

        val earned = repo.recordHand(
            summary = summary(bigBlind = 10, mode = XpMode.MULTIPLAYER),
            context = context(bigBlind = 10),
        )

        assertFalse(
            earned.any { it.achievement.id == AchievementId.BEAT_JANE_10 },
            "bots-mode achievement cannot unlock from a multiplayer hand",
        )
    }

    @Test
    fun newMpVariants_fireInMultiplayerHand_whenCounterMeetsTarget() = runUnitTest {
        // One MP-only registry entry per new sibling — each one only fires
        // when its custom counter reaches the threshold AND the hand was
        // a multiplayer hand. Counter values are server-driven once Phase
        // 4.2 lands; here we plant them directly to pin the registry
        // wiring (mode-gate + counter + custom key) end to end.
        val cases = listOf(
            AchievementId.HANDS_100_MP to (HANDS_PLAYED_MP to 100),
            AchievementId.WIN_BY_FOLD_10_MP to (WIN_BY_FOLD_MP to 10),
            AchievementId.DOUBLE_UP_MP to (DOUBLED_UP_MP to 1),
            AchievementId.TRIPLE_UP_MP to (TRIPLED_UP_MP to 1),
            AchievementId.POT_5000_MP to (MAX_POT_BB_RATIO_MP to 25),
        )
        for ((id, counter) in cases) {
            val (key, value) = counter
            val deps = Deps().also { it.preEarnAllExcept(id) }
            deps.dao.counters[key] = value
            val repo = deps.build()

            val earned = repo.recordHand(
                summary = summary(bigBlind = 10, mode = XpMode.MULTIPLAYER),
                context = context(bigBlind = 10),
            )

            assertTrue(
                earned.any { it.achievement.id == id },
                "MP variant $id should unlock when its custom counter ($key=$value) " +
                    "meets target on a multiplayer hand",
            )
        }
    }

    @Test
    fun newMpVariants_doNotFireInBotsHand_evenIfCounterMet() = runUnitTest {
        val cases = listOf(
            AchievementId.HANDS_100_MP to (HANDS_PLAYED_MP to 100),
            AchievementId.WIN_BY_FOLD_10_MP to (WIN_BY_FOLD_MP to 10),
            AchievementId.DOUBLE_UP_MP to (DOUBLED_UP_MP to 1),
            AchievementId.TRIPLE_UP_MP to (TRIPLED_UP_MP to 1),
            AchievementId.POT_5000_MP to (MAX_POT_BB_RATIO_MP to 25),
        )
        for ((id, counter) in cases) {
            val (key, value) = counter
            val deps = Deps().also { it.preEarnAllExcept(id) }
            deps.dao.counters[key] = value
            val repo = deps.build()

            val earned = repo.recordHand(
                summary = summary(bigBlind = 10, mode = XpMode.BOTS),
                context = context(bigBlind = 10),
            )

            assertFalse(
                earned.any { it.achievement.id == id },
                "MP variant $id must not unlock from a bots-mode hand even if its counter is satisfied",
            )
        }
    }

    // ---------- Test scaffolding ----------

    private fun summary(
        bigBlind: Long,
        totalPot: Long = 0L,
        wonPot: Boolean = false,
        mode: XpMode = XpMode.BOTS,
    ): HandResultSummary = HandResultSummary(
        handId = "h-${bigBlind}-${totalPot}",
        mode = mode,
        wasFold = false,
        reachedShowdown = false,
        wonPot = wonPot,
        chipsCommitted = 0L,
        bigBlind = bigBlind,
        handCategory = null,
        totalPot = totalPot,
    )

    private fun context(
        bigBlind: Long,
        humanStartingStack: Long = 1_000L,
        humanEndingStack: Long = 1_000L,
        opponentBotNames: List<String> = emptyList(),
    ): AchievementHandContext = AchievementHandContext(
        opponentBotNames = opponentBotNames,
        botDifficulty = null,
        humanStartingStack = humanStartingStack,
        humanEndingStack = humanEndingStack,
        bigBlind = bigBlind,
        bustedOpponentCount = 0,
    )

    private inner class Deps {
        val dao: FakeAchievementDao = FakeAchievementDao()
        val progression: FakeProgressionRepository = FakeProgressionRepository()
        val chips: FakeChipsRepository = FakeChipsRepository()
        val grantApi: FakeAchievementGrantApi = FakeAchievementGrantApi()
        val inventory: FakeInventoryRepository = FakeInventoryRepository()
        val clock: Clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(1_000L)
        }

        fun preEarnAllExcept(keep: AchievementId) {
            for (ach in AllAchievements) {
                if (ach.id != keep) {
                    dao.earned[ach.id.name] = AchievementEarnedEntity(
                        achievementId = ach.id.name,
                        earnedAtEpochMs = 1L,
                    )
                }
            }
        }

        fun build(): AchievementRepositoryImpl = AchievementRepositoryImpl(
            achievementDao = dao,
            progressionRepository = progression,
            chipsRepository = chips,
            grantApi = grantApi,
            inventoryRepository = inventory,
            appScope = AppCoroutineScope(dispatchers),
            clock = clock,
        )
    }

    private class FakeAchievementDao : AchievementDao {
        val earned = mutableMapOf<String, AchievementEarnedEntity>()
        val counters = mutableMapOf<String, Int>()
        private val earnedFlow = MutableStateFlow<List<AchievementEarnedEntity>>(emptyList())
        private val countersFlow = MutableStateFlow<List<AchievementCounterEntity>>(emptyList())

        override fun observeEarned(): Flow<List<AchievementEarnedEntity>> = earnedFlow.asStateFlow()
        override suspend fun getEarned(): List<AchievementEarnedEntity> = earned.values.toList()
        override suspend fun insertEarned(entity: AchievementEarnedEntity) {
            earned.putIfAbsent(entity.achievementId, entity)
            earnedFlow.value = earned.values.toList()
        }
        override suspend fun deleteAllEarned() {
            earned.clear()
            earnedFlow.value = emptyList()
        }

        override fun observeCounters(): Flow<List<AchievementCounterEntity>> = countersFlow.asStateFlow()
        override suspend fun getCounters(): List<AchievementCounterEntity> =
            counters.map { (k, v) -> AchievementCounterEntity(key = k, value = v) }
        override suspend fun getCounter(key: String): Int? = counters[key]
        override suspend fun incrementCounter(key: String, delta: Int) {
            counters[key] = (counters[key] ?: 0) + delta
            countersFlow.value = getCounters()
        }
        override suspend fun setCounter(entity: AchievementCounterEntity) {
            counters[entity.key] = entity.value
            countersFlow.value = getCounters()
        }
        override suspend fun deleteAllCounters() {
            counters.clear()
            countersFlow.value = emptyList()
        }

        override suspend fun deleteAll() {
            deleteAllEarned()
            deleteAllCounters()
        }
    }

    private class FakeProgressionRepository : ProgressionRepository {
        private val state = MutableStateFlow(Progression.Empty)
        /** Each successful [applyAchievementXp] call recorded as
         *  (delta, description). Lets tests assert idempotency without
         *  reaching into the production XpLedger fake. */
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
        override suspend fun deleteAll() {}
        override suspend fun debugSetTotalXp(totalXp: Long) {
            state.value = state.value.copy(totalXp = totalXp)
        }
    }

    private class FakeChipsRepository : ChipsRepository {
        private val state = MutableStateFlow<Long?>(10_000L)
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

    private class FakeAchievementGrantApi : AchievementGrantApi {
        override suspend fun grantAchievement(achievementId: AchievementId): Boolean = false
    }

    private class FakeInventoryRepository : InventoryRepository {
        private val state = MutableStateFlow<List<InventoryItem>>(emptyList())
        override fun observeInventory(): Flow<List<InventoryItem>> = state.asStateFlow()
        override suspend fun getInventory(): List<InventoryItem> = state.value
        override suspend fun redeemChipOffer(productId: String, costChips: Long): RedeemResult =
            RedeemResult.Success
        override suspend fun markConfirmed(productIds: Collection<String>) {}
        override suspend fun revertPurchase(productId: String) {}
        override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) {}
        override suspend fun deleteAll() {}
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }
}
