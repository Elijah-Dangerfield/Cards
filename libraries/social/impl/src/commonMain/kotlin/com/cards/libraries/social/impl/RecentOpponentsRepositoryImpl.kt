package com.dangerfield.cards.libraries.social.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.social.RecentOpponentProfile
import com.dangerfield.cards.libraries.social.RecentOpponentsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Resolves the recently-played-with shelf: fetch the bare id list, batch-resolve
 * it to public profiles, and project the result newest-first into [observe].
 *
 * The two-step (ids → profiles) mirrors the server contract: the social lists
 * deal in bare ids and `/v1/profiles` is the one place that turns them into
 * something renderable, so the client never holds a directory of strangers.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = RecentOpponentsRepository::class)
@Inject
class RecentOpponentsRepositoryImpl(
    private val api: SocialApi,
) : RecentOpponentsRepository {

    private val logger = KLog.withTag("RecentOpponents")
    private val recent = MutableStateFlow<List<RecentOpponentProfile>>(emptyList())

    override fun observe(): Flow<List<RecentOpponentProfile>> = recent.asStateFlow()

    override suspend fun refresh(limit: Int) {
        Catching {
            val ids = api.recentOpponentIds(limit)
            if (ids.isEmpty()) {
                recent.value = emptyList()
                return@Catching
            }
            val byId = api.resolveProfiles(ids).associateBy { it.id }
            // Preserve the server's newest-first order; drop any id that didn't
            // resolve to a profile rather than render a blank tile.
            recent.value = ids.mapNotNull { id ->
                byId[id]?.let { dto ->
                    RecentOpponentProfile(
                        id = dto.id,
                        displayName = dto.displayName,
                        avatarEmoji = dto.avatarEmoji,
                        avatarBackgroundColorHex = dto.avatarBackgroundColor,
                    )
                }
            }
        }.onFailure { logger.w(it) { "recent opponents refresh failed" } }
    }
}
