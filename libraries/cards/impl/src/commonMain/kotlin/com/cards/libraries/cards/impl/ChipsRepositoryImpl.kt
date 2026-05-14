package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.ChipsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ChipsRepositoryImpl(
    private val chipsDao: ChipsDao,
    private val clock: Clock,
) : ChipsRepository {

    override fun observeBalance(): Flow<Long> = chipsDao.observeChips()
        .onStart { ensureSeeded() }
        .map { it?.balance ?: ChipsRepository.STARTING_GRANT }

    override suspend fun getBalance(): Long {
        ensureSeeded()
        return chipsDao.getChips()?.balance ?: ChipsRepository.STARTING_GRANT
    }

    override suspend fun applyDelta(delta: Long) {
        ensureSeeded()
        chipsDao.applyDelta(delta = delta, updatedAtEpochMs = clock.now().toEpochMilliseconds())
    }

    override suspend fun deleteAll() {
        chipsDao.deleteAll()
    }

    private suspend fun ensureSeeded() {
        chipsDao.insertIfMissing(
            ChipsEntity(
                balance = ChipsRepository.STARTING_GRANT,
                updatedAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }
}
