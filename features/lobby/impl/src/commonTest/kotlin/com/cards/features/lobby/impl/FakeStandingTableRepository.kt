package com.dangerfield.cards.features.lobby.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.rooms.CreateStandingTableOutcome
import com.dangerfield.cards.libraries.rooms.StandingTable
import com.dangerfield.cards.libraries.rooms.StandingTableDraft
import com.dangerfield.cards.libraries.rooms.StandingTableRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeStandingTableRepository(
    initialTables: List<StandingTable> = emptyList(),
) : StandingTableRepository {
    override val mine = MutableStateFlow(initialTables)
    var createCalls = 0
    var createGate: CompletableDeferred<Unit>? = null
    var createOutcome: CreateStandingTableOutcome = CreateStandingTableOutcome.NetworkError
    var getOutcome: Catching<StandingTable>? = null
    var updateOutcome: Catching<StandingTable>? = null
    var archiveOutcome: Catching<Unit> = Catching.success(Unit)
    var leaveOutcome: Catching<Unit> = Catching.success(Unit)
    var refreshOutcome: Catching<Unit> = Catching.success(Unit)
    var refreshCount = 0
    var lastDraft: StandingTableDraft? = null
    var archivedCode: String? = null
    var leftCode: String? = null

    override suspend fun refreshMine(): Catching<Unit> {
        refreshCount++
        return refreshOutcome
    }

    override suspend fun create(draft: StandingTableDraft): CreateStandingTableOutcome {
        createCalls++
        lastDraft = draft
        createGate?.await()
        return createOutcome
    }

    override suspend fun get(code: String): Catching<StandingTable> =
        getOutcome ?: Catching { mine.value.first { it.code == code } }

    override suspend fun update(code: String, draft: StandingTableDraft): Catching<StandingTable> {
        lastDraft = draft
        return updateOutcome ?: error("update not stubbed")
    }

    override suspend fun archive(code: String): Catching<Unit> {
        archivedCode = code
        return archiveOutcome
    }

    override suspend fun leave(code: String): Catching<Unit> {
        leftCode = code
        return leaveOutcome
    }
}
