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
 * Persists the XP boost stash + window in [AppCache] (`xpBoostOwnedCount` +
 * `xpBoostExpiresAtEpochMs`). No server involvement — the boost only affects
 * local XP math, so it works offline and survives a restart for free via the
 * persisted cache.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class XpBoostRepositoryImpl(
    private val appCache: AppCache,
    private val clock: Clock,
) : XpBoostRepository {

    override fun observe(): Flow<XpBoostStatus> =
        appCache.updates.map { XpBoostStatus(it.xpBoostExpiresAtEpochMs, it.xpBoostOwnedCount) }

    override suspend fun status(): XpBoostStatus =
        appCache.get().let { XpBoostStatus(it.xpBoostExpiresAtEpochMs, it.xpBoostOwnedCount) }

    override suspend fun grant(count: Int) {
        if (count <= 0) return
        appCache.update { it.copy(xpBoostOwnedCount = it.xpBoostOwnedCount + count) }
    }

    override suspend fun activate(durationMs: Long): Boolean {
        val now = clock.now().toEpochMilliseconds()
        var activated = false
        appCache.update { data ->
            // A boost has to be in the stash to light — empty stash is a no-op.
            if (data.xpBoostOwnedCount <= 0) return@update data
            activated = true
            // Stack from the current expiry while active so a re-light adds time;
            // otherwise start fresh from now.
            val base = maxOf(now, data.xpBoostExpiresAtEpochMs ?: now)
            data.copy(
                xpBoostOwnedCount = data.xpBoostOwnedCount - 1,
                xpBoostExpiresAtEpochMs = base + durationMs,
            )
        }
        return activated
    }

    override suspend fun multiplier(): Int =
        status().multiplierAt(clock.now().toEpochMilliseconds())
}
