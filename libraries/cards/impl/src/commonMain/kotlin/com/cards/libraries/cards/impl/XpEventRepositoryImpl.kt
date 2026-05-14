package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpEventRepository
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.storage.db.XpEventDao
import com.dangerfield.cards.libraries.cards.storage.db.XpEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class XpEventRepositoryImpl(
    private val xpEventDao: XpEventDao,
) : XpEventRepository {

    override fun observeRecent(limit: Int): Flow<List<XpEvent>> =
        xpEventDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeSince(sinceEpochMs: Long): Flow<List<XpEvent>> =
        xpEventDao.observeSince(sinceEpochMs).map { rows -> rows.map { it.toDomain() } }

    private fun XpEventEntity.toDomain(): XpEvent = XpEvent(
        id = id,
        deltaXp = deltaXp,
        source = parseSource(source),
        mode = parseMode(mode),
        handId = handId,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun parseSource(raw: String): XpSource = runCatching { XpSource.valueOf(raw) }
        .getOrDefault(XpSource.BASE)

    private fun parseMode(raw: String): XpMode = runCatching { XpMode.valueOf(raw) }
        .getOrDefault(XpMode.BOTS)
}
