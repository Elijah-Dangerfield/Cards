package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthUnready
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.networking.apiProblemOrNull
import com.dangerfield.cards.libraries.networking.isOfflineError
import com.dangerfield.cards.libraries.rooms.CreateStandingTableOutcome
import com.dangerfield.cards.libraries.rooms.StandingTable
import com.dangerfield.cards.libraries.rooms.StandingTableDraft
import com.dangerfield.cards.libraries.rooms.StandingTableRepository
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = StandingTableRepository::class)
class StandingTableRepositoryImpl(
    private val api: StandingTableApi,
    private val cache: StandingTableCache,
) : StandingTableRepository {
    private val logger = KLog.withTag("StandingTables")
    override val mine = cache.mine

    override suspend fun refreshMine(): Catching<Unit> {
        val generation = cache.beginRefresh()
        return operation("mine", request = api::mine) { response ->
            val tables = response.body<StandingTablesResponseDto>().tables.map { it.toDomain() }
            cache.replaceIfCurrent(generation, tables)
        }
    }

    override suspend fun create(draft: StandingTableDraft): CreateStandingTableOutcome {
        val result = operation("create", request = { api.create(draft.toCreateRequest()) }) { response ->
            response.body<StandingTableDto>().toDomain()
        }
        if (result.isSuccess) refreshMine()
        return result.fold(
            onSuccess = { CreateStandingTableOutcome.Created(it) },
            onFailure = { error ->
                val problem = (error as? ClientRequestException)?.apiProblemOrNull()
                when {
                    error is ClientRequestException && error.response.status == HttpStatusCode.Conflict &&
                        problem?.code == "too_many_standing_tables" -> CreateStandingTableOutcome.TooMany
                    error is ClientRequestException && error.response.status == HttpStatusCode.BadRequest ->
                        CreateStandingTableOutcome.Invalid(problem?.message?.substringBefore(' ') ?: "name")
                    else -> CreateStandingTableOutcome.NetworkError
                }
            },
        )
    }

    override suspend fun get(code: String): Catching<StandingTable> =
        operation("get", request = { api.get(code) }) { response ->
            response.body<StandingTableDto>().toDomain()
        }

    override suspend fun update(code: String, draft: StandingTableDraft): Catching<StandingTable> {
        val result = operation("update", request = { api.update(code, draft.toUpdateRequest()) }) { response ->
            response.body<StandingTableDto>().toDomain()
        }
        if (result.isSuccess) refreshMine()
        return result
    }

    override suspend fun archive(code: String): Catching<Unit> {
        val generation = cache.generation()
        val result = operation("archive", request = { api.archive(code) }) {
            cache.removeIfCurrent(generation, code)
        }
        if (result.isSuccess) refreshMine()
        return result
    }

    override suspend fun leave(code: String): Catching<Unit> {
        val generation = cache.generation()
        val result = operation("leave", request = { api.leave(code) }) {
            cache.removeIfCurrent(generation, code)
        }
        if (result.isSuccess) refreshMine()
        return result
    }

    private suspend fun <T> operation(
        name: String,
        request: suspend () -> HttpResponse,
        handleResponse: suspend (HttpResponse) -> T,
    ): Catching<T> {
        var handlingResponse = false
        val result = Catching {
            val response = request()
            handlingResponse = true
            handleResponse(response)
        }
        val error = result.exceptionOrNull()
        if (error != null && handlingResponse) {
            logger.e(error) { "Standing-table $name response handling failed" }
        }
        logger.logEvent(
            "standing_table.operation_completed",
            "operation" to name,
            "outcome" to if (result.isSuccess) "success" else "failure",
            "reason" to when {
                error == null -> "none"
                handlingResponse -> "decode"
                else -> error.operationFailureReason()
            },
        )
        return result
    }
}

private fun Throwable.operationFailureReason(): String = when {
    this is AuthUnready -> if (reason == AuthReason.Offline) "offline" else "auth"
    isOfflineError() -> "offline"
    this is ResponseException -> when (response.status) {
        HttpStatusCode.BadRequest -> "invalid"
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> "auth"
        HttpStatusCode.NotFound -> "not_found"
        HttpStatusCode.Conflict -> "conflict"
        HttpStatusCode.TooManyRequests -> "rate_limited"
        else -> if (response.status.value >= 500) "server" else "http"
    }
    else -> "network"
}
