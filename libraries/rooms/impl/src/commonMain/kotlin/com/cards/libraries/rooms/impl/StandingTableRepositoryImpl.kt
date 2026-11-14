package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.networking.apiProblemOrNull
import com.dangerfield.cards.libraries.rooms.CreateStandingTableOutcome
import com.dangerfield.cards.libraries.rooms.StandingTable
import com.dangerfield.cards.libraries.rooms.StandingTableDraft
import com.dangerfield.cards.libraries.rooms.StandingTableRepository
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
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
    override val mine = cache.mine

    override suspend fun refreshMine(): Catching<Unit> = Catching {
        val generation = cache.generation()
        val tables = api.mine().body<StandingTablesResponseDto>().tables.map { it.toDomain() }
        cache.replaceIfCurrent(generation, tables)
    }

    override suspend fun create(draft: StandingTableDraft): CreateStandingTableOutcome =
        Catching {
            val table = api.create(draft.toCreateRequest()).body<StandingTableDto>().toDomain()
            refreshMine().logOnFailure { "Standing tables refresh after creation failed" }
            CreateStandingTableOutcome.Created(table)
        }.getOrElse { error ->
            val problem = (error as? ClientRequestException)?.apiProblemOrNull()
            when {
                error is ClientRequestException && error.response.status == HttpStatusCode.Conflict &&
                    problem?.code == "too_many_standing_tables" -> CreateStandingTableOutcome.TooMany
                error is ClientRequestException && error.response.status == HttpStatusCode.BadRequest ->
                    CreateStandingTableOutcome.Invalid(problem?.message?.substringBefore(' ') ?: "name")
                else -> CreateStandingTableOutcome.NetworkError
            }
        }

    override suspend fun get(code: String): Catching<StandingTable> = Catching {
        api.get(code).body<StandingTableDto>().toDomain()
    }

    override suspend fun update(code: String, draft: StandingTableDraft): Catching<StandingTable> = Catching {
        val table = api.update(code, draft.toUpdateRequest()).body<StandingTableDto>().toDomain()
        refreshMine().logOnFailure { "Standing tables refresh after editing failed" }
        table
    }

    override suspend fun archive(code: String): Catching<Unit> = Catching {
        val generation = cache.generation()
        api.archive(code)
        cache.removeIfCurrent(generation, code)
        refreshMine().logOnFailure { "Standing tables refresh after archiving failed" }
        Unit
    }

    override suspend fun leave(code: String): Catching<Unit> = Catching {
        val generation = cache.generation()
        api.leave(code)
        cache.removeIfCurrent(generation, code)
        refreshMine().logOnFailure { "Standing tables refresh after leaving failed" }
        Unit
    }
}
