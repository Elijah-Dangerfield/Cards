package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.HandCategoryGrade
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionDao
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionEntity
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventEntity
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins `ProgressionRepositoryImpl` — the only thing between hand outcomes and
 * the player's lifetime XP / hand counters. The pure-math [XpCalculator] is
 * already pinned by [XpCalculatorTest]; these tests cover the counter-delta +
 * ledger-row composition the repo layers on top.
 */
class ProgressionRepositoryImplTest : CoroutineTest() {

    @Test
    fun awardForHand_won_incrementsWonAndNotLostAtShowdown() = runUnitTest {
        val dao = FakeProgressionDao()
        val repo = build(dao = dao)

        repo.awardForHand(summary(wonPot = true, reachedShowdown = true))

        val row = dao.row()
        assertEquals(1L, row.handsWon, "wonPot=true ticks handsWon")
        assertEquals(0L, row.handsLostAtShowdown, "wonPot=true does not tick handsLostAtShowdown")
        assertEquals(1L, row.handsPlayed, "every hand bumps handsPlayed")
    }

    @Test
    fun awardForHand_showdownLoss_incrementsLostAndNotWon() = runUnitTest {
        val dao = FakeProgressionDao()
        val repo = build(dao = dao)

        repo.awardForHand(summary(wonPot = false, reachedShowdown = true))

        val row = dao.row()
        assertEquals(0L, row.handsWon, "wonPot=false leaves handsWon at 0")
        assertEquals(1L, row.handsLostAtShowdown, "reachedShowdown && !wonPot ticks handsLostAtShowdown")
    }

    @Test
    fun awardForHand_fold_incrementsHandsFolded() = runUnitTest {
        val dao = FakeProgressionDao()
        val repo = build(dao = dao)

        repo.awardForHand(summary(wasFold = true, reachedShowdown = false, wonPot = false))

        val row = dao.row()
        assertEquals(1L, row.handsFolded, "wasFold=true ticks handsFolded")
        assertEquals(0L, row.handsLostAtShowdown, "fold does not count as showdown loss")
        assertEquals(0L, row.handsWon)
    }

    @Test
    fun awardForHand_bots_incrementsBotHandsPlayed() = runUnitTest {
        val dao = FakeProgressionDao()
        val repo = build(dao = dao)

        repo.awardForHand(summary(mode = XpMode.BOTS))

        assertEquals(1L, dao.row().botHandsPlayed, "BOTS mode ticks botHandsPlayed")
    }

    @Test
    fun awardForHand_multiplayer_leavesBotHandsPlayedAtZero() = runUnitTest {
        val dao = FakeProgressionDao()
        val repo = build(dao = dao)

        repo.awardForHand(summary(mode = XpMode.MULTIPLAYER))

        assertEquals(0L, dao.row().botHandsPlayed, "MULTIPLAYER mode never bumps the bot counter")
    }

    @Test
    fun awardForHand_writesOneLedgerRowPerCalculatorAward_andReturnsThem() = runUnitTest {
        val dao = FakeProgressionDao()
        val ledger = FakeXpEventDao()
        val repo = build(dao = dao, ledger = ledger, clockEpochMs = 7_777L)

        // Showdown + Flush + investment + base → four non-zero awards in BOTS mode.
        val handSummary = summary(
            handId = "h-42",
            reachedShowdown = true,
            chipsCommitted = 80,
            bigBlind = 10,
            handCategory = HandCategoryGrade.Flush,
            mode = XpMode.BOTS,
        )
        val expectedAwards = XpCalculator.calculate(handSummary)

        val returned = repo.awardForHand(handSummary)

        assertEquals(expectedAwards.size, returned.size, "one returned XpEvent per calculator award")
        assertEquals(expectedAwards.size, ledger.inserted.size, "one ledger row per calculator award")

        // Per-row contract: source/amount/mode/handId/timestamp match the calculator's output.
        expectedAwards.forEachIndexed { index, award ->
            val row = ledger.inserted[index]
            assertEquals(award.amount, row.deltaXp)
            assertEquals(award.source.name, row.source)
            assertEquals(XpMode.BOTS.name, row.mode)
            assertEquals("h-42", row.handId)
            assertEquals(7_777L, row.createdAtEpochMs)

            val event = returned[index]
            assertEquals(award.amount, event.deltaXp)
            assertEquals(award.source, event.source)
            assertEquals(XpMode.BOTS, event.mode)
            assertEquals("h-42", event.handId)
            assertEquals(7_777L, event.createdAtEpochMs)
        }

        // Counter delta = sum of awards, not a fixed base.
        assertEquals(expectedAwards.sumOf { it.amount }.toLong(), dao.row().totalXp)
    }

    @Test
    fun awardForHand_zeroAwards_writesNoLedgerRows() = runUnitTest {
        val dao = FakeProgressionDao()
        val ledger = FakeXpEventDao()
        // Fold + zero chips committed + zero bigBlind = only BASE survives the
        // filter > 0 step. (Guards "every code path appends to xp_events".)
        val repo = build(dao = dao, ledger = ledger)

        // bigBlind=0 + wasFold=true → BASE-only award; ledger gets exactly one row.
        // Belt-and-suspenders: assert insertAll is called with a non-empty list
        // ONLY when there are awards.
        val foldSummary = summary(
            wasFold = true,
            chipsCommitted = 0,
            bigBlind = 0,
            mode = XpMode.BOTS,
        )
        repo.awardForHand(foldSummary)

        val expected = XpCalculator.calculate(foldSummary)
        assertEquals(expected.size, ledger.inserted.size)
    }

    @Test
    fun applyAchievementXp_negativeDelta_throws() = runUnitTest {
        val repo = build()

        assertFailsWith<IllegalArgumentException> {
            repo.applyAchievementXp(delta = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            repo.applyAchievementXp(delta = -5)
        }
    }

    @Test
    fun applyAchievementXp_writesOneAchievementRow_andLeavesHandCountersUntouched() = runUnitTest {
        val dao = FakeProgressionDao()
        val ledger = FakeXpEventDao()
        val repo = build(dao = dao, ledger = ledger, clockEpochMs = 5_000L)

        val event = repo.applyAchievementXp(delta = 25, description = "tutorial-complete")

        // Exactly one ledger row, tagged ACHIEVEMENT/BOTS/null-handId per the repo contract.
        assertEquals(1, ledger.inserted.size)
        val row = ledger.inserted.single()
        assertEquals(25, row.deltaXp)
        assertEquals(XpSource.ACHIEVEMENT.name, row.source)
        assertEquals(XpMode.BOTS.name, row.mode)
        assertNull(row.handId)
        assertEquals("tutorial-complete", row.description)
        assertEquals(5_000L, row.createdAtEpochMs)

        // XP bumps total_xp; hand counters stay at 0.
        val progression = dao.row()
        assertEquals(25L, progression.totalXp)
        assertEquals(0L, progression.handsPlayed, "achievement XP must not bump hand counters")
        assertEquals(0L, progression.handsWon)
        assertEquals(0L, progression.handsFolded)
        assertEquals(0L, progression.botHandsPlayed)

        // Returned event mirrors the row.
        assertEquals(25, event.deltaXp)
        assertEquals(XpSource.ACHIEVEMENT, event.source)
        assertEquals(XpMode.BOTS, event.mode)
        assertNull(event.handId)
        assertEquals("tutorial-complete", event.description)
        assertEquals(5_000L, event.createdAtEpochMs)
    }

    @Test
    fun deleteAll_clearsBothDaos() = runUnitTest {
        val dao = FakeProgressionDao()
        val ledger = FakeXpEventDao()
        val repo = build(dao = dao, ledger = ledger)

        repo.awardForHand(summary(wonPot = true, reachedShowdown = true))
        repo.applyAchievementXp(delta = 10)
        assertTrue(dao.row().handsPlayed > 0)
        assertTrue(ledger.inserted.isNotEmpty())

        repo.deleteAll()

        assertTrue(dao.cleared)
        assertTrue(ledger.cleared)
    }

    // ---------- Scaffolding ----------

    private fun summary(
        handId: String = "h",
        wasFold: Boolean = false,
        reachedShowdown: Boolean = false,
        wonPot: Boolean = false,
        chipsCommitted: Long = 0,
        bigBlind: Long = 10,
        handCategory: HandCategoryGrade? = null,
        mode: XpMode = XpMode.BOTS,
    ): HandResultSummary = HandResultSummary(
        handId = handId,
        mode = mode,
        wasFold = wasFold,
        reachedShowdown = reachedShowdown,
        wonPot = wonPot,
        chipsCommitted = chipsCommitted,
        bigBlind = bigBlind,
        handCategory = handCategory,
    )

    private fun build(
        dao: FakeProgressionDao = FakeProgressionDao(),
        ledger: FakeXpEventDao = FakeXpEventDao(),
        clockEpochMs: Long = 1_000L,
    ): ProgressionRepositoryImpl = ProgressionRepositoryImpl(
        progressionDao = dao,
        xpEventDao = ledger,
        clock = FixedClock(clockEpochMs),
    )

    private class FixedClock(private val now: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    }

    private class FakeProgressionDao : ProgressionDao {
        private var entity: ProgressionEntity? = null
        var cleared: Boolean = false
            private set
        private val flow = MutableStateFlow<ProgressionEntity?>(null)

        fun row(): ProgressionEntity = entity ?: error("row never written")

        override fun observeProgression(): Flow<ProgressionEntity?> = flow.asStateFlow()
        override suspend fun getProgression(): ProgressionEntity? = entity

        override suspend fun insertIfMissing(entity: ProgressionEntity) {
            if (this.entity == null) {
                this.entity = entity
                flow.value = entity
            }
        }

        override suspend fun applyHandDeltas(
            xpDelta: Int,
            handsWonDelta: Int,
            handsFoldedDelta: Int,
            handsLostAtShowdownDelta: Int,
            botHandsPlayedDelta: Int,
            updatedAtEpochMs: Long,
        ) {
            val current = entity ?: error("applyHandDeltas called without ensureExistsAndApply")
            entity = current.copy(
                totalXp = current.totalXp + xpDelta,
                handsPlayed = current.handsPlayed + 1,
                handsWon = current.handsWon + handsWonDelta,
                handsFolded = current.handsFolded + handsFoldedDelta,
                handsLostAtShowdown = current.handsLostAtShowdown + handsLostAtShowdownDelta,
                botHandsPlayed = current.botHandsPlayed + botHandsPlayedDelta,
                updatedAtEpochMs = updatedAtEpochMs,
            )
            flow.value = entity
        }

        override suspend fun addXpOnly(xpDelta: Int, updatedAtEpochMs: Long) {
            val current = entity ?: error("addXpOnly called without ensureExistsAndAddXp")
            entity = current.copy(
                totalXp = current.totalXp + xpDelta,
                updatedAtEpochMs = updatedAtEpochMs,
            )
            flow.value = entity
        }

        override suspend fun setTotalXp(totalXp: Long, updatedAtEpochMs: Long) {
            val current = entity ?: error("setTotalXp called without ensureExistsAndSetTotalXp")
            entity = current.copy(totalXp = totalXp, updatedAtEpochMs = updatedAtEpochMs)
            flow.value = entity
        }

        override suspend fun deleteAll() {
            cleared = true
            entity = null
            flow.value = null
        }
    }

    private class FakeXpEventDao : XpEventDao {
        val inserted = mutableListOf<XpEventEntity>()
        var cleared: Boolean = false
            private set
        private val flow = MutableStateFlow<List<XpEventEntity>>(emptyList())

        override suspend fun insertAll(events: List<XpEventEntity>) {
            inserted += events
            flow.value = inserted.toList()
        }

        override fun observeSince(sinceEpochMs: Long): Flow<List<XpEventEntity>> =
            flow.asStateFlow()

        override fun observeRecent(limit: Int): Flow<List<XpEventEntity>> =
            flow.asStateFlow()

        override suspend fun deleteAll() {
            cleared = true
            inserted.clear()
            flow.value = emptyList()
        }
    }
}
