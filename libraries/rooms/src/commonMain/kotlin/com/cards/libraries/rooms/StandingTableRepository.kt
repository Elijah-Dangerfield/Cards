package com.dangerfield.cards.libraries.rooms

import com.dangerfield.cards.libraries.core.Catching
import kotlinx.coroutines.flow.Flow

interface StandingTableRepository {
    val mine: Flow<List<StandingTable>>
    suspend fun refreshMine(): Catching<Unit>
    suspend fun create(draft: StandingTableDraft): CreateStandingTableOutcome
    suspend fun get(code: String): Catching<StandingTable>
    suspend fun update(code: String, draft: StandingTableDraft): Catching<StandingTable>
    suspend fun archive(code: String): Catching<Unit>
    suspend fun leave(code: String): Catching<Unit>
}

sealed interface CreateStandingTableOutcome {
    data class Created(val table: StandingTable) : CreateStandingTableOutcome
    data object TooMany : CreateStandingTableOutcome
    data class Invalid(val field: String) : CreateStandingTableOutcome
    data object NetworkError : CreateStandingTableOutcome
}
