package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionDao
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionEntity
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventEntity
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ProgressionRepositoryImpl(
    private val progressionDao: ProgressionDao,
    private val xpEventDao: XpEventDao,
    private val clock: Clock,
) : ProgressionRepository {

    private val logger = KLog.withTag("ProgressionRepository")

    override fun observeProgression(): Flow<Progression> =
        progressionDao.observeProgression().map { it?.toDomain() ?: Progression.Empty }

    override suspend fun getProgression(): Progression =
        progressionDao.getProgression()?.toDomain() ?: Progression.Empty

    override suspend fun awardForHand(summary: HandResultSummary): List<XpEvent> {
        val awards = XpCalculator.calculate(summary)
        val totalDelta = awards.sumOf { it.amount }
        val now = clock.now().toEpochMilliseconds()

        val handsWonDelta = if (summary.wonPot) 1 else 0
        val handsFoldedDelta = if (summary.wasFold) 1 else 0
        val handsLostAtShowdownDelta =
            if (summary.reachedShowdown && !summary.wonPot) 1 else 0
        val botHandsPlayedDelta = if (summary.mode == XpMode.BOTS) 1 else 0

        progressionDao.ensureExistsAndApply(
            xpDelta = totalDelta,
            handsWonDelta = handsWonDelta,
            handsFoldedDelta = handsFoldedDelta,
            handsLostAtShowdownDelta = handsLostAtShowdownDelta,
            botHandsPlayedDelta = botHandsPlayedDelta,
            updatedAtEpochMs = now,
        )

        val ledgerRows = awards.map { award ->
            XpEventEntity(
                deltaXp = award.amount,
                source = award.source.name,
                mode = summary.mode.name,
                handId = summary.handId,
                createdAtEpochMs = now,
            )
        }
        if (ledgerRows.isNotEmpty()) {
            xpEventDao.insertAll(ledgerRows)
        }

        logger.d {
            "Awarded $totalDelta XP for hand ${summary.handId} (${summary.mode}, " +
                "showdown=${summary.reachedShowdown}, fold=${summary.wasFold}, " +
                "won=${summary.wonPot}); ${awards.size} ledger rows"
        }

        return ledgerRows.mapIndexed { index, row ->
            XpEvent(
                id = 0L, // ids assigned by Room — not surfaced for this return
                deltaXp = row.deltaXp,
                source = awards[index].source,
                mode = summary.mode,
                handId = row.handId,
                createdAtEpochMs = row.createdAtEpochMs,
            )
        }
    }

    override suspend fun deleteAll() {
        progressionDao.deleteAll()
        xpEventDao.deleteAll()
    }

    private fun ProgressionEntity.toDomain(): Progression = Progression(
        totalXp = totalXp,
        handsPlayed = handsPlayed,
        handsWon = handsWon,
        handsFolded = handsFolded,
        handsLostAtShowdown = handsLostAtShowdown,
        botHandsPlayed = botHandsPlayed,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
