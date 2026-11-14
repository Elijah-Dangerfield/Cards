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
    private var requestId = 0L
    private val tables = MutableStateFlow<List<StandingTable>>(emptyList())
    val mine = tables.asStateFlow()

    suspend fun generation(): Long = mutex.withLock { generation }

    data class RefreshVersion(val account: Long, val request: Long)

    suspend fun beginRefresh(): RefreshVersion = mutex.withLock {
        RefreshVersion(generation, ++requestId)
    }

    suspend fun replaceIfCurrent(version: RefreshVersion, value: List<StandingTable>) = mutex.withLock {
        if (version.account == generation && version.request == requestId) tables.value = value
    }

    suspend fun removeIfCurrent(expectedGeneration: Long, code: String) = mutex.withLock {
        if (expectedGeneration == generation) {
            requestId++
            tables.value = tables.value.filterNot { it.code == code }
        }
    }

    override suspend fun clear(previousUserId: String) = mutex.withLock {
        generation++
        tables.value = emptyList()
    }
}
