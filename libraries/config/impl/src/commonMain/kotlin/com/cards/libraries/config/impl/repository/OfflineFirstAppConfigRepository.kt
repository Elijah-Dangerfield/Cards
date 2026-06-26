package com.dangerfield.cards.libraries.config.impl.repository

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEvents
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.AppConfigRepository
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.config.impl.applyOverrides
import com.dangerfield.cards.libraries.config.impl.data.ConfigCache
import com.dangerfield.cards.libraries.config.impl.data.RemoteConfigDataSource
import com.dangerfield.cards.libraries.config.impl.model.BasicMapAppConfig
import com.dangerfield.cards.libraries.config.impl.model.FallbackConfigMap
import com.dangerfield.cards.libraries.config.impl.serialization.ConfigJsonConverter
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.ignoreValue
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.flowroutines.childSupervisorScope
import com.dangerfield.cards.libraries.flowroutines.tryWithTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val ConfigRefreshTimeout = 5.seconds

/**
 * How long must pass since the last config fetch before a foreground triggers
 * another one. Short enough that the kill-switch / maintenance / forced-upgrade
 * flags propagate to a returning user promptly; long enough that quick
 * app-switching doesn't fetch on every resume.
 */
private val ConfigRefreshThrottle = 5.minutes

/**
 * Disk-backed, **throttled-foreground** app config.
 *
 * Config carries the time-sensitive levers (kill switch, maintenance, forced
 * upgrade), so unlike the other session-aware caches it refreshes on **every app
 * foreground** — gated only by [ConfigRefreshThrottle] so a quick app-switch
 * doesn't refetch. Cold boot always fetches (no prior fetch this process, and
 * [AppEvent.OnForeground] fires on cold boot too). A user who returns after a few
 * minutes gets fresh flags instead of waiting for a session rollover.
 *
 * Note this is a *transition* trigger: a user who keeps the app foregrounded
 * uninterrupted never re-fetches mid-session — that would need polling, which we
 * deliberately don't do. The cached snapshot survives launches so the first frame
 * never blocks on the network.
 *
 * Failure path: if the network fetch fails AND there's no prior cached snapshot,
 * the bundled fallback config is persisted so future `configStream` subscribers
 * get a usable map. With a prior snapshot, the failure is logged and the prior
 * snapshot stays in place — the next foreground past the throttle tries again.
 */
@ContributesBinding(AppScope::class, boundType = AppConfigRepository::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@SingleIn(AppScope::class)
class OfflineFirstAppConfigRepository @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val remoteConfigDataSource: RemoteConfigDataSource,
    private val configCache: ConfigCache,
    private val converter: ConfigJsonConverter,
    private val fallbackConfig: FallbackConfigMap,
    private val configOverrideRepository: ConfigOverrideRepository,
    private val appEvents: AppEvents,
    private val clock: Clock,
    private val appScope: AppCoroutineScope,
) : AppConfigRepository, AutoInit {

    private val logger = KLog.withTag("AppConfigRepository")
    private var refreshJob: Job? = null
    private val refreshJobMutex = Mutex()
    private var lastFetchAtMs: Long? = null

    private val cachedConfigFlow = configCache.updates
        .mapNotNull { snapshot -> snapshot.configJson?.let(::decodeConfig) }

    private val configStream: SharedFlow<AppConfigMap> = combine(
        configOverrideRepository.getOverridesFlow(),
        cachedConfigFlow,
    ) { overrides, config -> config.applyOverrides(overrides) }
        .distinctUntilChanged()
        .onEach { logger.d { "Config emitted" } }
        .shareIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    init {
        observeForegroundForRefresh()
    }

    override fun config(): AppConfigMap = LazyAppConfigMap()

    override fun configStream(): Flow<AppConfigMap> = configStream

    private fun observeForegroundForRefresh() {
        appEvents
            .filterIsInstance<AppEvent.OnForeground>()
            .onEach { maybeRefreshOnForeground() }
            .launchIn(appScope.childSupervisorScope(dispatcherProvider.io))
    }

    /**
     * Refresh on app foreground, at most once per [ConfigRefreshThrottle]. The
     * timestamp is stamped at the *attempt*, so a failed fetch doesn't retry on
     * every rapid resume — the next foreground past the window does.
     */
    private suspend fun maybeRefreshOnForeground() {
        val now = clock.now().toEpochMilliseconds()
        val last = lastFetchAtMs
        if (last != null && now - last < ConfigRefreshThrottle.inWholeMilliseconds) {
            logger.d { "Foreground within throttle window — skipping config refresh" }
            return
        }
        lastFetchAtMs = now
        refreshConfig().join()
    }

    private suspend fun refreshConfig(): Job = refreshJobMutex.withLock {
        val currentJob = refreshJob
        if (currentJob != null && currentJob.isActive) {
            currentJob
        } else {
            appScope.childSupervisorScope(dispatcherProvider.io).launch {
                tryWithTimeout(ConfigRefreshTimeout) {
                    remoteConfigDataSource.getConfig()
                }
                    .onSuccess { config ->
                        logger.d { "Config refresh succeeded" }
                        persistConfig(config)
                    }
                    .onFailure { throwable ->
                        if (!hasCachedConfig()) {
                            logger.w(throwable) { "No cached config available, using fallback" }
                            persistConfig(fallbackConfig)
                        } else {
                            logger.w(throwable) { "Falling back to cached config" }
                        }
                    }
                    .ignoreValue()
            }.also { job -> refreshJob = job }
        }
    }

    private suspend fun persistConfig(config: AppConfigMap) {
        converter.encodeMap(config.map)
            .onSuccess { json ->
                configCache.update { snapshot -> snapshot.copy(configJson = json) }
            }
            .onFailure { error ->
                logger.e(error) { "Unable to persist config" }
            }
    }

    private suspend fun hasCachedConfig(): Boolean = configCache.get().configJson != null

    private fun decodeConfig(raw: String): AppConfigMap? =
        converter.decodeToMap(raw)
            .onFailure { error -> logger.e(error) { "Unable to decode cached config" } }
            .getOrNull()
            ?.let { BasicMapAppConfig(it) }

    private inner class LazyAppConfigMap : AppConfigMap() {
        override val map: Map<String, *>
            get() = configStream.replayCache.firstOrNull()?.map ?: runBlocking {
                configStream.first().map
            }
    }
}
