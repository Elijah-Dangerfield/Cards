package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.AchievementRepository
import com.dangerfield.cards.server.domain.RewardChips
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.plugins.ACHIEVEMENTS_WRITE_LIMIT
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
import kotlin.time.Instant

/**
 * Server-authoritative earned-achievement endpoints (Model 2). The client
 * evaluates criteria offline and flushes newly-earned ids here; the server
 * persists them idempotently and returns the authoritative earned set so the
 * client can reconcile (cross-device / reinstall).
 *
 * `GET /v1/me/achievements` returns the caller's earned set.
 * `POST /v1/me/achievements/sync` records the posted earned ids (first-write-
 * wins on `earnedAt`) and returns the full set.
 *
 * The cosmetic-reward mapping stays in `GrantsRoutes`. Chip rewards for the
 * single-player achievements are credited here (ENG-9): recording an earned
 * id whose reward includes chips applies the server-owned amount from
 * [RewardChips.ACHIEVEMENT_CHIPS] to the wallet ledger, idempotent on the
 * achievement's stable key — the client's own `achievement.*` credit is
 * refused at wallet sync, so this is the only path that mints them.
 */
@OptIn(ExperimentalTime::class)
fun Route.achievementsRoutes(
    repository: AchievementRepository,
    wallet: WalletRepository,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me/achievements") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(HttpStatusCode.OK, AchievementsSyncResponse(earned = repository.listEarned(userId).toDto()))
        }

        rateLimit(RateLimitName(ACHIEVEMENTS_WRITE_LIMIT)) {
            post("/v1/me/achievements/sync") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<AchievementsSyncRequest>()

                body.earned.forEach { earned ->
                    repository.recordEarned(
                        userId = userId,
                        achievementId = earned.achievementId,
                        earnedAt = Instant.fromEpochMilliseconds(earned.earnedAtEpochMs),
                    )
                    RewardChips.ACHIEVEMENT_CHIPS[earned.achievementId]?.let { chips ->
                        wallet.apply(
                            userId = userId,
                            idempotencyKey = RewardChips.achievementLedgerKey(earned.achievementId),
                            delta = chips,
                            reason = RewardChips.achievementLedgerReason(earned.achievementId),
                        )
                    }
                }

                call.respond(
                    HttpStatusCode.OK,
                    AchievementsSyncResponse(earned = repository.listEarned(userId).toDto()),
                )
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun List<com.dangerfield.cards.server.domain.EarnedAchievement>.toDto(): List<EarnedAchievementDto> =
    map { EarnedAchievementDto(achievementId = it.achievementId, earnedAtEpochMs = it.earnedAt.toEpochMilliseconds()) }
