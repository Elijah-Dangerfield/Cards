package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.social.Rivalry
import com.dangerfield.cards.libraries.social.RivalryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Resolves the head-to-head records: fetch the scorelines, batch-resolve their
 * opponents to public profiles, and project the result most-recent-first.
 *
 * The two-step mirrors [RecentOpponentsRepositoryImpl] and the server contract
 * it shares: the lists deal in bare ids and `/v1/profiles` is the one place that
 * turns an id into something renderable, so the client never holds a directory
 * of strangers.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = RivalryRepository::class)
@Inject
class RivalryRepositoryImpl(
    private val api: SocialApi,
) : RivalryRepository {

    private val logger = KLog.withTag("Rivalries")
    private val rivalries = MutableStateFlow<List<Rivalry>>(emptyList())

    override fun observe(): Flow<List<Rivalry>> = rivalries.asStateFlow()

    override suspend fun refresh(limit: Int) {
        Catching {
            val records = api.rivalries(limit)
            if (records.isEmpty()) {
                rivalries.value = emptyList()
                return@Catching
            }
            val byId = api.resolveProfiles(records.map { it.opponentId }).associateBy { it.id }
            // Keep the server's ordering; drop an opponent we cannot name rather
            // than render a scoreline against a blank.
            rivalries.value = records.mapNotNull { record ->
                byId[record.opponentId]?.let { profile ->
                    Rivalry(
                        opponentId = record.opponentId,
                        displayName = profile.displayName,
                        avatarEmoji = profile.avatarEmoji,
                        avatarBackgroundColorHex = profile.avatarBackgroundColor,
                        handsTogether = record.handsTogether,
                        handsWon = record.handsWon,
                        opponentHandsWon = record.opponentHandsWon,
                        biggestPotWon = record.biggestPotWon,
                        sessionsTogether = record.sessionsTogether,
                    )
                }
            }
        }.onFailure { logger.w(it) { "rivalries refresh failed" } }
    }
}
