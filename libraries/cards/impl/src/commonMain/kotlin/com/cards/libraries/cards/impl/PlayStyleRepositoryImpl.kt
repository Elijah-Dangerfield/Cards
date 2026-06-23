package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.PlayStyleHandSummary
import com.dangerfield.cards.libraries.cards.PlayStyleRepository
import com.dangerfield.cards.libraries.cards.impl.dto.PlayStyleAxesDto
import com.dangerfield.cards.libraries.cards.impl.dto.PlayStyleEventOutcomeDto
import com.dangerfield.cards.libraries.cards.impl.dto.PlayStyleHandDto
import com.dangerfield.cards.libraries.cards.impl.dto.PlayStyleResponseDto
import com.dangerfield.cards.libraries.cards.impl.dto.PlayStyleSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.PlayStyleSyncResponseDto
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleEntity
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleEventDao
import com.dangerfield.cards.libraries.cards.storage.db.PlayStyleEventEntity
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.request.get
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
 * Optimistic-local play-style, server-reconciled (Model 2 — mirrors
 * [ProgressionRepositoryImpl]).
 *
 * [recordHand] appends one `play_style_events` outbox row per finished hand
 * (`synced = false`). [sync] flushes the unsynced rows through `POST
 * /v1/me/play-style/sync`, marks them synced, and caches the server's computed
 * axes into the singleton `play_style` row for instant + offline display. The
 * axes are never derived on-device — the count → axis formula stays server-side
 * and tunable.
 *
 * [getStyleFor] reads another player's public axes for the Opponent Style
 * Reader; results are cached in-session so re-tapping a seat doesn't refetch.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = PlayStyleRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class PlayStyleRepositoryImpl(
    private val playStyleDao: PlayStyleDao,
    private val playStyleEventDao: PlayStyleEventDao,
    private val networkClient: NetworkClient,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
) : PlayStyleRepository, AppEventListener {

    private val logger = KLog.withTag("PlayStyleRepository")
    private val syncMutex = Mutex()

    private val opponentCacheMutex = Mutex()
    private val opponentCache = mutableMapOf<String, PlayStyleAxes>()

    override fun observeOwnStyle(): Flow<PlayStyleAxes?> =
        playStyleDao.observe().map { it?.toDomain() }

    override suspend fun getOwnStyle(): PlayStyleAxes? = playStyleDao.get()?.toDomain()

    override suspend fun recordHand(summary: PlayStyleHandSummary) {
        val now = clock.now().toEpochMilliseconds()
        playStyleEventDao.insertAll(
            listOf(
                PlayStyleEventEntity(
                    idempotencyKey = Uuid.random().toString(),
                    mode = summary.mode.name,
                    inBlind = summary.inBlind,
                    vpip = summary.vpip,
                    pfr = summary.pfr,
                    preflopFold = summary.preflopFold,
                    aggressiveActionCount = summary.aggressiveActionCount,
                    callActionCount = summary.callActionCount,
                    wentToShowdown = summary.wentToShowdown,
                    showdownBluff = summary.showdownBluff,
                    createdAtEpochMs = now,
                ),
            ),
        )
        logger.d {
            "Recorded play-style hand ${summary.handId} (${summary.mode}, vpip=${summary.vpip}, " +
                "pfr=${summary.pfr}, fold=${summary.preflopFold}, showdownBluff=${summary.showdownBluff})"
        }
    }

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
        // Always POST — an empty events list is a valid "hydrate axes" call,
        // which is how a reinstall / second device picks up a style computed
        // elsewhere.
        networkClient.authedCall("play-style.sync", retry = RetryPolicy.idempotent()) { client ->
            val pending = playStyleEventDao.getUnsynced()

            val request = PlayStyleSyncRequestDto(events = pending.map { it.toDto() })
            val response: PlayStyleSyncResponseDto = client
                .post("/v1/me/play-style/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                .body()

            val resolvedKeys = response.results
                .filter { it.outcome in RESOLVED_OUTCOMES }
                .map { it.idempotencyKey }
            if (resolvedKeys.isNotEmpty()) {
                playStyleEventDao.markSynced(resolvedKeys)
            }

            cacheOwnAxes(response.axes)

            logger.d {
                "Sync complete: ${pending.size} sent, ${resolvedKeys.size} resolved, " +
                    "sample now ${response.axes.sampleSize}."
            }
            Unit
        }
    }

    override suspend fun getStyleFor(userId: String): Result<PlayStyleAxes?> {
        opponentCacheMutex.withLock { opponentCache[userId] }?.let { return Result.success(it) }
        return networkClient.authedCall("play-style.opponent", retry = RetryPolicy.idempotent()) { client ->
            val response: PlayStyleResponseDto = client
                .get("/v1/users/$userId/play-style")
                .body()
            response.axes.toDomain().also { style ->
                opponentCacheMutex.withLock { opponentCache[userId] = style }
            }
        }
    }

    override suspend fun deleteAll() {
        playStyleDao.deleteAll()
        playStyleEventDao.deleteAll()
        opponentCacheMutex.withLock { opponentCache.clear() }
    }

    override fun onUserChanged(event: AppEvent.UserChanged) {
        // A user just became active. On an account switch the prior user's
        // cached axes were just wiped, so drop the opponent cache (under its
        // lock — it's a plain map shared with in-flight getStyleFor) and
        // re-hydrate the new user's own style now.
        if (event.current == null) return
        appScope.launch {
            opponentCacheMutex.withLock { opponentCache.clear() }
            sync()
        }
    }

    override fun onAccountClaimed(event: AppEvent.AccountClaimed) {
        appScope.launch { sync() }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        // Cold-boot's initial sync is owned by [onUserChanged]; this handles
        // the warm-resume reconcile only.
        if (event.isColdBoot) return
        appScope.launch { sync() }
    }

    private suspend fun cacheOwnAxes(axes: PlayStyleAxesDto) {
        playStyleDao.set(
            PlayStyleEntity(
                tight = axes.tight,
                aggro = axes.aggro,
                bluff = axes.bluff,
                patient = axes.patient,
                sampleSize = axes.sampleSize,
                updatedAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun PlayStyleEventEntity.toDto(): PlayStyleHandDto = PlayStyleHandDto(
        idempotencyKey = idempotencyKey,
        mode = mode,
        inBlind = inBlind,
        vpip = vpip,
        pfr = pfr,
        preflopFold = preflopFold,
        aggressiveActionCount = aggressiveActionCount,
        callActionCount = callActionCount,
        wentToShowdown = wentToShowdown,
        showdownBluff = showdownBluff,
    )

    private fun PlayStyleEntity.toDomain(): PlayStyleAxes = PlayStyleAxes(
        tight = tight.toFloat(),
        aggro = aggro.toFloat(),
        bluff = bluff.toFloat(),
        patient = patient.toFloat(),
        sampleSize = sampleSize,
    )

    private fun PlayStyleAxesDto.toDomain(): PlayStyleAxes = PlayStyleAxes(
        tight = tight.toFloat(),
        aggro = aggro.toFloat(),
        bluff = bluff.toFloat(),
        patient = patient.toFloat(),
        sampleSize = sampleSize,
    )

    private companion object {
        val RESOLVED_OUTCOMES = setOf(
            PlayStyleEventOutcomeDto.Applied,
            PlayStyleEventOutcomeDto.AlreadyApplied,
        )
    }
}
