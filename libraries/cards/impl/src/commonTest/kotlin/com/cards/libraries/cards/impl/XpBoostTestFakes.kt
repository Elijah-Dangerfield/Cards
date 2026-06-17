package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.XP_BOOST_MULTIPLIER
import com.dangerfield.cards.libraries.cards.XpBoostRepository
import com.dangerfield.cards.libraries.cards.XpBoostStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Always-off boost — the default for tests that don't care about boosting. */
internal object InactiveXpBoostRepository : XpBoostRepository {
    override fun observe(): Flow<XpBoostStatus> = MutableStateFlow(XpBoostStatus.None)
    override suspend fun status(): XpBoostStatus = XpBoostStatus.None
    override suspend fun grant(count: Int) = Unit
    override suspend fun activate(durationMs: Long): Boolean = false
    override suspend fun multiplier(): Int = 1
}

/** Boost whose multiplier is fixed for the test. */
internal class FixedXpBoostRepository(
    private val active: Boolean,
) : XpBoostRepository {
    override fun observe(): Flow<XpBoostStatus> = MutableStateFlow(XpBoostStatus.None)
    override suspend fun status(): XpBoostStatus = XpBoostStatus.None
    override suspend fun grant(count: Int) = Unit
    override suspend fun activate(durationMs: Long): Boolean = false
    override suspend fun multiplier(): Int = if (active) XP_BOOST_MULTIPLIER else 1
}
