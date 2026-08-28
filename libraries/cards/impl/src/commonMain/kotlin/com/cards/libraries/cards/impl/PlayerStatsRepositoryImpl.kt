package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.achievements.AchievementCounters
import com.dangerfield.cards.libraries.achievements.HandFacts
import com.dangerfield.cards.libraries.cards.PlayerStatHandSummary
import com.dangerfield.cards.libraries.cards.PlayerStats
import com.dangerfield.cards.libraries.cards.PlayerStatsRepository
import com.dangerfield.cards.libraries.cards.impl.dto.PlayerStatHandDto
import com.dangerfield.cards.libraries.cards.impl.dto.PlayerStatEventOutcomeDto
import com.dangerfield.cards.libraries.cards.impl.dto.PlayerStatsDto
import com.dangerfield.cards.libraries.cards.impl.dto.PlayerStatsSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.PlayerStatsSyncResponseDto
import com.dangerfield.cards.libraries.cards.storage.db.PlayerStatEventDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayerStatEventEntity
import com.dangerfield.cards.libraries.cards.storage.db.PlayerStatsDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayerStatsEntity
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Optimistic-local player stats, server-reconciled (Model 2 — mirrors
 * [PlayStyleRepositoryImpl]).
 *
 * [recordHand] appends one `player_stat_events` outbox row per finished hand
 * (`synced = false`). [sync] flushes the unsynced rows through `POST
 * /v1/me/player-stats/sync` a bounded page at a time, marks each page synced
 * as it lands, and caches the server's authoritative snapshot into the
 * singleton `player_stats` row for instant + offline display.
 *
 * The counters are server-authoritative — the client never accumulates them
 * locally; it only sends the per-hand signals and renders whatever the server
 * folds back. That's what lets a second device / reinstall pick up the right
 * totals after a single hydrate sync.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = PlayerStatsRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = UserScopedSyncer::class)
@Inject
class PlayerStatsRepositoryImpl(
    private val playerStatsDao: PlayerStatsDao,
    private val playerStatEventDao: PlayerStatEventDao,
    private val networkClient: NetworkClient,
    private val clock: Clock,
) : PlayerStatsRepository, UserScopedSyncer {

    private val logger = KLog.withTag("PlayerStatsRepository")
    private val syncMutex = Mutex()

    override fun observeStats(): Flow<PlayerStats?> =
        playerStatsDao.observe().map { it?.toDomain() }

    override suspend fun getStats(): PlayerStats? = playerStatsDao.get()?.toDomain()

    override fun observeEffectiveCounters(): Flow<Map<String, Long>> =
        combine(
            playerStatsDao.observe(),
            playerStatEventDao.observeUnsynced(),
        ) { snapshot, unsynced -> effective(snapshot, unsynced) }

    override suspend fun effectiveCounters(): Map<String, Long> =
        effective(playerStatsDao.get(), playerStatEventDao.getAllUnsynced())

    /**
     * The server snapshot's counter projection with the unsynced outbox folded on
     * top, in apply order — the same `AchievementCounters.fold` the server runs.
     * A fresh client (empty snapshot, empty outbox) yields empty; after a hydrate
     * sync it's exact server truth; offline it's server-last-known + this
     * session's hands.
     */
    private fun effective(
        snapshot: PlayerStatsEntity?,
        unsynced: List<PlayerStatEventEntity>,
    ): Map<String, Long> {
        val base = AchievementCounters(decodePerBotWins(snapshot?.achievementCountersJson ?: "{}"))
        return unsynced.fold(base) { acc, row -> acc.fold(row.toHandFacts()) }.values
    }

    private fun PlayerStatEventEntity.toHandFacts(): HandFacts = HandFacts(
        idempotencyKey = idempotencyKey,
        mode = mode,
        won = won,
        folded = folded,
        lostAtShowdown = lostAtShowdown,
        vsBot = vsBot,
        beatenBotId = beatenBotId,
        botDifficulty = botDifficulty,
        busted = busted,
        startStack = startStack,
        endStack = endStack,
        bigBlind = bigBlind,
        potTotal = potTotal,
        wasAllIn = wasAllIn,
        wonByFold = wonByFold,
        bustsDealt = bustsDealt,
        foldedWouldHaveLost = foldedWouldHaveLost,
        handStrengthShown = handStrengthShown,
    )

    override suspend fun recordHand(summary: PlayerStatHandSummary) {
        val now = clock.now().toEpochMilliseconds()
        playerStatEventDao.insertAll(
            listOf(
                PlayerStatEventEntity(
                    idempotencyKey = "stat_${summary.handId}_${Uuid.random()}",
                    mode = summary.mode.name,
                    won = summary.won,
                    folded = summary.folded,
                    lostAtShowdown = summary.lostAtShowdown,
                    vsBot = summary.vsBot,
                    beatenBotId = summary.beatenBotId,
                    noBustStreak = summary.noBustStreak,
                    busted = summary.busted,
                    startStack = summary.startStack,
                    endStack = summary.endStack,
                    bigBlind = summary.bigBlind,
                    potTotal = summary.potTotal,
                    wasAllIn = summary.wasAllIn,
                    wonByFold = summary.wonByFold,
                    bustsDealt = summary.bustsDealt,
                    foldedWouldHaveLost = summary.foldedWouldHaveLost,
                    handStrengthShown = summary.handStrengthShown,
                    botDifficulty = summary.botDifficulty,
                    createdAtEpochMs = now,
                ),
            ),
        )
        logger.d {
            "Recorded stat hand ${summary.handId} (${summary.mode}, won=${summary.won}, " +
                "folded=${summary.folded}, vsBot=${summary.vsBot}, streak=${summary.noBustStreak})"
        }
    }

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
        // Callers gate *when* to sync (see the event hooks below); this just
        // does the work, one bounded page at a time until the outbox drains.
        networkClient.authedCall("player-stats.sync", retry = RetryPolicy.idempotent()) { client ->
            drainOutbox(
                loadPage = { limit -> playerStatEventDao.getUnsynced(limit) },
                flushPage = { page -> flushPage(client, page) },
            )
        }
    }

    /**
     * Posts one page of the outbox. An empty [page] is a valid "hydrate
     * snapshot" call — how a reinstall or a second device picks up stats
     * accumulated elsewhere — so this never short-circuits on an empty batch.
     * Returns how many rows the server resolved.
     */
    private suspend fun flushPage(client: HttpClient, page: List<PlayerStatEventEntity>): Int {
        val request = PlayerStatsSyncRequestDto(events = page.map { it.toDto() })
        val response: PlayerStatsSyncResponseDto = client
            .post("/v1/me/player-stats/sync") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            .body()

        val resolvedKeys = response.results
            .filter { it.outcome in RESOLVED_OUTCOMES }
            .map { it.idempotencyKey }
        if (resolvedKeys.isNotEmpty()) {
            playerStatEventDao.markSynced(resolvedKeys)
        }

        cacheSnapshot(response.stats)

        logger.d {
            "Sync page complete: ${page.size} sent, ${resolvedKeys.size} resolved, " +
                "handsPlayed now ${response.stats.handsPlayed}."
        }
        return resolvedKeys.size
    }

    override suspend fun deleteAll() {
        playerStatsDao.deleteAll()
        playerStatEventDao.deleteAll()
    }

    private suspend fun cacheSnapshot(stats: PlayerStatsDto) {
        playerStatsDao.set(
            PlayerStatsEntity(
                handsPlayed = stats.handsPlayed,
                handsWon = stats.handsWon,
                handsFolded = stats.handsFolded,
                handsLostAtShowdown = stats.handsLostAtShowdown,
                botHandsPlayed = stats.botHandsPlayed,
                currentNoBustStreak = stats.currentNoBustStreak,
                bestNoBustStreak = stats.bestNoBustStreak,
                perBotWinsJson = statsJson.encodeToString(PER_BOT_WINS_SERIALIZER, stats.perBotWins),
                achievementCountersJson = statsJson.encodeToString(PER_BOT_WINS_SERIALIZER, stats.achievementCounters),
                updatedAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun PlayerStatEventEntity.toDto(): PlayerStatHandDto = PlayerStatHandDto(
        idempotencyKey = idempotencyKey,
        mode = mode,
        won = won,
        folded = folded,
        lostAtShowdown = lostAtShowdown,
        vsBot = vsBot,
        beatenBotId = beatenBotId,
        noBustStreak = noBustStreak,
        busted = busted,
        botDifficulty = botDifficulty,
        startStack = startStack,
        endStack = endStack,
        bigBlind = bigBlind,
        potTotal = potTotal,
        wasAllIn = wasAllIn,
        wonByFold = wonByFold,
        bustsDealt = bustsDealt,
        foldedWouldHaveLost = foldedWouldHaveLost,
        handStrengthShown = handStrengthShown,
    )

    private fun PlayerStatsEntity.toDomain(): PlayerStats = PlayerStats(
        handsPlayed = handsPlayed,
        handsWon = handsWon,
        handsFolded = handsFolded,
        handsLostAtShowdown = handsLostAtShowdown,
        botHandsPlayed = botHandsPlayed,
        currentNoBustStreak = currentNoBustStreak,
        bestNoBustStreak = bestNoBustStreak,
        perBotWins = decodePerBotWins(perBotWinsJson),
        achievementCounters = decodePerBotWins(achievementCountersJson),
    )

    private fun decodePerBotWins(json: String): Map<String, Long> =
        Catching {
            statsJson.decodeFromString(PER_BOT_WINS_SERIALIZER, json)
        }.getOrDefault(emptyMap())

    private companion object {
        val RESOLVED_OUTCOMES = setOf(
            PlayerStatEventOutcomeDto.Applied,
            PlayerStatEventOutcomeDto.AlreadyApplied,
        )
        val statsJson = Json { ignoreUnknownKeys = true }
        val PER_BOT_WINS_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
    }
}
