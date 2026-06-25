package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.rooms.CandidatesOutcome
import com.dangerfield.cards.libraries.rooms.FindTableOutcome
import com.dangerfield.cards.libraries.rooms.MatchmakingRepository
import com.dangerfield.cards.libraries.rooms.PlayBotsOutcome
import com.dangerfield.cards.libraries.rooms.SubsidyBudgetOutcome
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpStatusCode
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Maps [MatchmakingApi]'s raw HTTP responses onto the sealed outcomes the
 * Searching ViewModel reasons about. Mirrors [RoomRepositoryImpl]'s status-code
 * mapping discipline; the matched room is handed back as a domain [com.dangerfield.cards.libraries.rooms.Room]
 * so the caller opens its socket via [com.dangerfield.cards.libraries.rooms.RoomRepository.connect].
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class MatchmakingRepositoryImpl(
    private val api: MatchmakingApi,
) : MatchmakingRepository {

    override suspend fun findTable(minBuyIn: Long, maxBuyIn: Long): FindTableOutcome = try {
        val response = api.findTable(MatchmakingFindRequestDto(minBuyIn = minBuyIn, maxBuyIn = maxBuyIn))
        val body = response.body<MatchmakingFindResponseDto>()
        FindTableOutcome.Success(room = body.room.toDomain(), created = body.created)
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.BadRequest ->
                if (extractCode(e) == "insufficient_balance") {
                    FindTableOutcome.InsufficientBalance(extractMessage(e) ?: "Not enough chips for that buy-in")
                } else {
                    FindTableOutcome.InvalidRange(extractMessage(e) ?: "Invalid buy-in range")
                }
            HttpStatusCode.Unauthorized -> FindTableOutcome.NotSignedIn(e)
            HttpStatusCode.TooManyRequests -> FindTableOutcome.RateLimited(e)
            else -> FindTableOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        FindTableOutcome.NetworkError(e)
    } catch (e: ServerResponseException) {
        FindTableOutcome.Unknown(e)
    } catch (e: Throwable) {
        FindTableOutcome.NetworkError(e)
    }

    override suspend fun findCandidates(minBuyIn: Long, maxBuyIn: Long): CandidatesOutcome = try {
        val response = api.candidates(minBuyIn = minBuyIn, maxBuyIn = maxBuyIn)
        val body = response.body<MatchmakingCandidatesResponseDto>()
        CandidatesOutcome.Success(rooms = body.rooms.map { it.toDomain() })
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.BadRequest ->
                if (extractCode(e) == "insufficient_balance") {
                    CandidatesOutcome.InsufficientBalance(extractMessage(e) ?: "Not enough chips for that buy-in")
                } else {
                    CandidatesOutcome.InvalidRange(extractMessage(e) ?: "Invalid buy-in range")
                }
            HttpStatusCode.Unauthorized -> CandidatesOutcome.NotSignedIn(e)
            HttpStatusCode.TooManyRequests -> CandidatesOutcome.RateLimited(e)
            else -> CandidatesOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        CandidatesOutcome.NetworkError(e)
    } catch (e: ServerResponseException) {
        CandidatesOutcome.Unknown(e)
    } catch (e: Throwable) {
        CandidatesOutcome.NetworkError(e)
    }

    override suspend fun playBots(code: String): PlayBotsOutcome = try {
        val response = api.playBots(code)
        PlayBotsOutcome.Success(room = response.body<MatchmakingFindResponseDto>().room.toDomain())
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.NotFound -> PlayBotsOutcome.RoomNotFound
            // The server refuses to bot-fill a table a human already joined; for
            // us that's the win condition — keep the real game.
            HttpStatusCode.Conflict -> when (extractCode(e)) {
                "real_player_present" -> PlayBotsOutcome.RealPlayerJoined
                else -> PlayBotsOutcome.Unknown(e)
            }
            HttpStatusCode.Unauthorized -> PlayBotsOutcome.NotSignedIn(e)
            else -> PlayBotsOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        PlayBotsOutcome.NetworkError(e)
    } catch (e: ServerResponseException) {
        PlayBotsOutcome.Unknown(e)
    } catch (e: Throwable) {
        PlayBotsOutcome.NetworkError(e)
    }

    override suspend fun subsidyBudget(): SubsidyBudgetOutcome = try {
        val body = api.subsidyBudget().body<SubsidyBudgetResponseDto>()
        SubsidyBudgetOutcome.Success(
            grantedToday = body.grantedToday,
            cap = body.cap,
            remaining = body.remaining,
        )
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.Unauthorized -> SubsidyBudgetOutcome.NotSignedIn(e)
            else -> SubsidyBudgetOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        SubsidyBudgetOutcome.NetworkError(e)
    } catch (e: ServerResponseException) {
        SubsidyBudgetOutcome.Unknown(e)
    } catch (e: Throwable) {
        SubsidyBudgetOutcome.NetworkError(e)
    }

    private suspend fun extractMessage(e: ClientRequestException): String? = try {
        e.response.body<ProblemEnvelopeDto>().error.message
    } catch (_: Throwable) {
        null
    }

    private suspend fun extractCode(e: ClientRequestException): String? = try {
        e.response.body<ProblemEnvelopeDto>().error.code
    } catch (_: Throwable) {
        null
    }
}
