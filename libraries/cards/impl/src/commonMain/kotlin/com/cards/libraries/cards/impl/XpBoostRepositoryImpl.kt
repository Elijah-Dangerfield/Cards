package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.XpBoostRepository
import com.dangerfield.cards.libraries.cards.XpBoostStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * Persists the 2× XP boost window in [AppCache] (`xpBoostExpiresAtEpochMs`).
 * No server involvement — the boost only affects local XP math, so it works
 * offline and survives a restart for free via the persisted cache.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class XpBoostRepositoryImpl(
    private val appCache: AppCache,
    private val clock: Clock,
) : XpBoostRepository {

    override fun observe(): Flow<XpBoostStatus> =
        appCache.updates.map { XpBoostStatus(it.xpBoostExpiresAtEpochMs) }

    override suspend fun status(): XpBoostStatus =
        XpBoostStatus(appCache.get().xpBoostExpiresAtEpochMs)

    override suspend fun activate(durationMs: Long) {
        val now = clock.now().toEpochMilliseconds()
        appCache.update { data ->
            // Stack from the current expiry while active so a re-buy adds time;
            // otherwise start fresh from now.
            val base = maxOf(now, data.xpBoostExpiresAtEpochMs ?: now)
            data.copy(xpBoostExpiresAtEpochMs = base + durationMs)
        }
    }

    override suspend fun multiplier(): Int =
        status().multiplierAt(clock.now().toEpochMilliseconds())
}
