package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.InstallIdProvider
import kotlin.concurrent.Volatile
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Production [InstallIdProvider]. On construction, kicks off a one-shot
 * read of [AppCache.installId]; if the stored value is null, generates a
 * fresh UUID and persists it. The in-memory ref is populated as soon as
 * the read completes — typically within a few ms of process start,
 * since the [AppCache] is hydrated eagerly via [AutoInit] elsewhere.
 *
 * Calls to [current] before hydration completes return `null`; the header
 * is then simply omitted from the outgoing request. This is the intended
 * fallback shape — the server treats absence the same as "client too old
 * to send the header." A small window where the header is missing on the
 * very first /me of a cold boot is acceptable; the next request after
 * hydration sends it, and the server tags the profile then.
 *
 * Implements [AutoInit] so DI's boot-time iterator forces construction
 * at app start. Without that, the hydrate-on-init path wouldn't run
 * until a network call asked for the header — exactly the latency we
 * want to avoid.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = InstallIdProvider::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
@OptIn(ExperimentalUuidApi::class)
class CachedInstallIdProvider(
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
) : InstallIdProvider, AutoInit {

    private val logger = KLog.withTag("InstallIdProvider")

    @Volatile
    private var cached: String? = null

    init {
        appScope.launch {
            Catching {
                val data = appCache.get()
                val resolved = data.installId
                    ?: Uuid.random().toString().also { fresh ->
                        appCache.update { it.copy(installId = fresh) }
                        logger.i { "Minted fresh install_id on first launch" }
                    }
                cached = resolved
            }.onFailure { t ->
                logger.e(t) { "Failed to hydrate install_id from AppCache" }
            }
        }
    }

    override fun current(): String? = cached
}
