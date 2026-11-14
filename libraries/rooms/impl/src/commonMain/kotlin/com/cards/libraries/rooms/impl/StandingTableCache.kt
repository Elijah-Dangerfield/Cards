package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.cards.UserScopedClearer
import com.dangerfield.cards.libraries.rooms.StandingTable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = UserScopedClearer::class, multibinding = true)
class StandingTableCache : UserScopedClearer {
    private val mutex = Mutex()
    private var generation = 0L
    private val tables = MutableStateFlow<List<StandingTable>>(emptyList())
    val mine = tables.asStateFlow()

    suspend fun generation(): Long = mutex.withLock { generation }

    suspend fun replaceIfCurrent(expectedGeneration: Long, value: List<StandingTable>) = mutex.withLock {
        if (expectedGeneration == generation) tables.value = value
    }

    suspend fun removeIfCurrent(expectedGeneration: Long, code: String) = mutex.withLock {
        if (expectedGeneration == generation) tables.value = tables.value.filterNot { it.code == code }
    }

    override suspend fun clear(previousUserId: String) = mutex.withLock {
        generation++
        tables.value = emptyList()
    }
}
