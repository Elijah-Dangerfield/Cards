package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ApplyXpOutcome
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.RewardChips
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.plugins.PROGRESSION_WRITE_LIMIT
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlin.time.ExperimentalTime

/**
 * Server-authoritative XP / progression endpoints (Model 2 — see
 * docs/wiki/state-authority-and-sync.md). The client computes XP per hand
 * offline and flushes the deltas here; the server stores + caps them and
 * returns the authoritative `totalXp`.
 *
 * `GET /v1/me/progression` returns the caller's current authoritative XP,
 * lazy-creating the row at zero on first contact.
 *
 * `POST /v1/me/progression/sync` accepts a batch of locally-applied XP
 * events and reconciles them. Each event is idempotent on its
 * `idempotencyKey` (`hand_<handId>` / `achievement_<id>`), so retries and
 * reinstall re-flushes never double-count.
 *
 * Level-up chip rewards are credited here (ENG-9): when a sync moves the
 * authoritative XP total across a rewarded level, the server applies the
 * chip prize from [RewardChips.LEVEL_CHIPS] to the wallet ledger,
 * idempotent per level — the client's own `levelup.*` credit is refused
 * at wallet sync, so this is the only path that mints them. Replays are
 * safe twice over: an already-applied XP batch doesn't move the total
 * (no crossing detected), and the per-level ledger key dedupes anyway.
 *
 * Both require a valid Supabase JWT; the userId comes from the `sub` claim.
 * Per-IP rate limit on sync mirrors the wallet endpoint.
 */
/** Cap on rows echoed back for client re-hydration of the recent-XP feed. */
private const val RECENT_EVENTS_LIMIT = 50

@OptIn(ExperimentalTime::class)
fun Route.progressionRoutes(
    repository: ProgressionRepository,
    wallet: WalletRepository,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me/progression") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val result = repository.findOrCreateResult(userId)
            call.respond(
                HttpStatusCode.OK,
                ProgressionResponse(
                    totalXp = result.progression.totalXp,
                    progressionCreated = result.created,
                ),
            )
        }

        rateLimit(RateLimitName(PROGRESSION_WRITE_LIMIT)) {
            post("/v1/me/progression/sync") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<ProgressionSyncRequest>()

                val initial = repository.findOrCreateResult(userId)
                var lastTotal = initial.progression.totalXp
                val results = body.events.map { event ->
                    val outcome = repository.applyXp(
                        userId = userId,
                        idempotencyKey = event.idempotencyKey,
                        deltaXp = event.deltaXp,
                        source = event.source,
                        mode = event.mode,
                        handId = event.handId,
                        wasBoosted = event.wasBoosted,
                    )
                    lastTotal = outcome.totalXp
                    when (outcome) {
                        is ApplyXpOutcome.Applied -> XpEventResultDto(
                            idempotencyKey = event.idempotencyKey,
                            outcome = if (outcome.wasAlreadyApplied) {
                                XpEventOutcomeDto.AlreadyApplied
                            } else {
                                XpEventOutcomeDto.Applied
                            },
                            totalXp = outcome.totalXp,
                        )
                    }
                }

                // When a crossing mints, the response carries the post-mint
                // wallet balance so the client knows to re-pull its wallet
                // now instead of showing the reward stale until the next
                // sync trigger (PROG-12).
                var mintedWalletBalance: Long? = null
                RewardChips.rewardedLevelsCrossed(
                    beforeXp = initial.progression.totalXp,
                    afterXp = lastTotal,
                ).forEach { level ->
                    val outcome = wallet.apply(
                        userId = userId,
                        idempotencyKey = RewardChips.levelLedgerKey(level),
                        delta = RewardChips.LEVEL_CHIPS.getValue(level),
                        reason = RewardChips.levelLedgerReason(level),
                    )
                    mintedWalletBalance = outcome.balance
                }

                // Re-hydration: an event-less sync is the client asking "what
                // do I have?" — the cold-boot / account-switch / reinstall
                // pulse, where the local ledger was just wiped. Echo back the
                // recent server rows so the client can repopulate its feed.
                // A sync that carried events is steady-state (the client still
                // holds those rows locally), so skip the extra read + payload.
                val recentEvents = if (body.events.isEmpty()) {
                    repository.recentEvents(userId, RECENT_EVENTS_LIMIT).map { event ->
                        XpEventSnapshotDto(
                            idempotencyKey = event.idempotencyKey,
                            deltaXp = event.deltaXp,
                            source = event.source,
                            mode = event.mode,
                            handId = event.handId,
                            wasBoosted = event.wasBoosted,
                            appliedAtEpochMs = event.appliedAt.toEpochMilliseconds(),
                        )
                    }
                } else {
                    emptyList()
                }

                call.respond(
                    HttpStatusCode.OK,
                    ProgressionSyncResponse(
                        totalXp = lastTotal,
                        results = results,
                        progressionCreated = initial.created,
                        recentEvents = recentEvents,
                        walletBalance = mintedWalletBalance,
                    ),
                )
            }
        }
    }
}
