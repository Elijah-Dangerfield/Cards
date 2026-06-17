package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpBoostRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.impl.dto.ProgressionSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.ProgressionSyncResponseDto
import com.dangerfield.cards.libraries.cards.impl.dto.XpEventDto
import com.dangerfield.cards.libraries.cards.impl.dto.XpEventOutcomeDto
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionDao
import com.dangerfield.cards.libraries.cards.storage.db.ProgressionEntity
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventEntity
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Optimistic-local XP, write-through to the server's authoritative
 * `user_progression` total (Model 2 — mirrors [ChipsRepositoryImpl]).
 *
 * [awardForHand] / [applyAchievementXp] bump the local `total_xp` and append
 * `xp_events` rows tagged with a per-row idempotency key (`synced = false`).
 * [sync] flushes the unsynced rows through `POST /v1/me/progression/sync`,
 * marks them synced, and reconciles the local total to the server's
 * authoritative value. Lifetime hand counters stay client-local for now.
 *
 * `level` is never stored or synced — the client derives it from `total_xp`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ProgressionRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class ProgressionRepositoryImpl(
    private val progressionDao: ProgressionDao,
    private val xpEventDao: XpEventDao,
    private val networkClient: NetworkClient,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
    private val xpBoostRepository: XpBoostRepository,
) : ProgressionRepository, AppEventListener {

    private val logger = KLog.withTag("ProgressionRepository")
    private val syncLogger = KLog.withTag("ProgressionSync")
    private val syncMutex = Mutex()

    override fun observeProgression(): Flow<Progression> =
        progressionDao.observeProgression().map { it?.toDomain() ?: Progression.Empty }

    override suspend fun getProgression(): Progression =
        progressionDao.getProgression()?.toDomain() ?: Progression.Empty

    override suspend fun awardForHand(summary: HandResultSummary): List<XpEvent> {
        // A 2× XP boost (if active) multiplies every award before it hits the
        // ledger, so the boosted XP is what the user keeps + what syncs to the
        // server. The boost is purely local math — see [XpBoostRepository].
        val multiplier = xpBoostRepository.multiplier()
        val awards = XpCalculator.calculate(summary).let { base ->
            if (multiplier > 1) base.map { it.copy(amount = it.amount * multiplier) } else base
        }
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
                idempotencyKey = Uuid.random().toString(),
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

    override suspend fun applyAchievementXp(delta: Int, description: String?): XpEvent {
        require(delta > 0) { "Achievement XP delta must be positive (got $delta)" }
        val now = clock.now().toEpochMilliseconds()
        // Achievement XP bumps total_xp only — never touches hand counters.
        progressionDao.ensureExistsAndAddXp(xpDelta = delta, updatedAtEpochMs = now)

        val entity = XpEventEntity(
            idempotencyKey = Uuid.random().toString(),
            deltaXp = delta,
            source = XpSource.ACHIEVEMENT.name,
            // V1 tagging — achievement XP isn't really mode-specific. When MP
            // lands and a multiplayer-only achievement unlocks, swap this to
            // a sensible mode.
            mode = XpMode.BOTS.name,
            handId = null,
            description = description,
            createdAtEpochMs = now,
        )
        xpEventDao.insertAll(listOf(entity))
        return XpEvent(
            id = 0L,
            deltaXp = delta,
            source = XpSource.ACHIEVEMENT,
            mode = XpMode.BOTS,
            handId = null,
            description = description,
            createdAtEpochMs = now,
        )
    }

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
        // Always POST — an empty events list is a valid "hydrate total" call,
        // which is how a reinstall / second device picks up XP earned elsewhere.
        networkClient.authedCall("progression.sync", retry = RetryPolicy.idempotent()) { client ->
            val pending = xpEventDao.getUnsynced()

            val request = ProgressionSyncRequestDto(events = pending.map { it.toDto() })
            val response: ProgressionSyncResponseDto = client
                .post("/v1/me/progression/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                .body()

            // Applied + AlreadyApplied → server has it; mark the local row
            // synced (kept for the history feed, not deleted). Unknown → leave
            // it unsynced so a newer client retries.
            val resolvedKeys = response.results
                .filter { it.outcome in RESOLVED_OUTCOMES }
                .map { it.idempotencyKey }
            if (resolvedKeys.isNotEmpty()) {
                xpEventDao.markSynced(resolvedKeys)
            }

            // Reconcile the local total to the server's authoritative value
            // (the setBalance analog). After the just-posted events that's the
            // correct sum; a cross-device gain is also picked up here.
            progressionDao.ensureExistsAndSetTotalXp(
                totalXp = response.totalXp,
                updatedAtEpochMs = clock.now().toEpochMilliseconds(),
            )

            syncLogger.d {
                "Sync complete: ${pending.size} sent, ${resolvedKeys.size} resolved, " +
                    "total now ${response.totalXp}."
            }
            Unit
        }
    }

    override fun onUserChanged(event: AppEvent.UserChanged) {
        // A user just became active (cold-boot resolve, sign-in, or account
        // switch). On a switch the prior user's XP was just wiped, so
        // re-hydrate the new user's authoritative total now rather than waiting
        // for a foreground. Sign-out (current == null) has nothing to fetch.
        if (event.current == null) return
        appScope.launch { sync() }
    }

    override fun onAccountClaimed(event: AppEvent.AccountClaimed) {
        // A guest just claimed their account (same user id, no UserChanged), so
        // flush pending XP + reconcile the authoritative total now instead of
        // waiting for the next foreground.
        appScope.launch { sync() }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        // Cold-boot's initial sync is owned by [onUserChanged]; this handles
        // the warm-resume reconcile only.
        if (event.isColdBoot) return
        appScope.launch { sync() }
    }

    override suspend fun deleteAll() {
        progressionDao.deleteAll()
        xpEventDao.deleteAll()
    }

    override suspend fun debugSetTotalXp(totalXp: Long) {
        val now = clock.now().toEpochMilliseconds()
        progressionDao.ensureExistsAndSetTotalXp(
            totalXp = totalXp.coerceAtLeast(0L),
            updatedAtEpochMs = now,
        )
    }

    private fun XpEventEntity.toDto(): XpEventDto = XpEventDto(
        idempotencyKey = idempotencyKey,
        deltaXp = deltaXp.toLong(),
        source = source,
        mode = mode,
        handId = handId,
    )

    private fun ProgressionEntity.toDomain(): Progression = Progression(
        totalXp = totalXp,
        handsPlayed = handsPlayed,
        handsWon = handsWon,
        handsFolded = handsFolded,
        handsLostAtShowdown = handsLostAtShowdown,
        botHandsPlayed = botHandsPlayed,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private companion object {
        // Outcomes meaning "the server has this event" — mark the local row
        // synced. Unknown stays unsynced for a newer client to retry.
        val RESOLVED_OUTCOMES = setOf(
            XpEventOutcomeDto.Applied,
            XpEventOutcomeDto.AlreadyApplied,
        )
    }
}
