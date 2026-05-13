package com.dangerfield.cards.libraries.config.impl.repository

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.AppConfigRepository
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.config.impl.applyOverrides
import com.dangerfield.cards.libraries.config.impl.data.ConfigCache
import com.dangerfield.cards.libraries.config.impl.data.RemoteConfigDataSource
import com.dangerfield.cards.libraries.config.impl.model.BasicMapAppConfig
import com.dangerfield.cards.libraries.config.impl.model.FallbackConfigMap
import com.dangerfield.cards.libraries.config.impl.serialization.ConfigJsonConverter
import com.dangerfield.cards.libraries.core.ignoreValue
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.flowroutines.childSupervisorScope
import com.dangerfield.cards.libraries.flowroutines.tryWithTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal val ConfigExpiration = 60.minutes
private val ConfigRefreshTimeout = 5.seconds

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class OfflineFirstAppConfigRepository @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val remoteConfigDataSource: RemoteConfigDataSource,
    private val configCache: ConfigCache,
    private val converter: ConfigJsonConverter,
    private val fallbackConfig: FallbackConfigMap,
    private val configOverrideRepository: ConfigOverrideRepository,
    private val appScope: AppCoroutineScope,
) : AppConfigRepository {

    private val logger = KLog.withTag("AppConfigRepository")
    private var refreshPollingJob: Job? = null
    private var refreshJob: Job? = null
    private val refreshJobMutex = Mutex()

    init {
        startAppConfigRefresh()
    }

    private val cachedConfigFlow = configCache.updates
        .mapNotNull { snapshot -> snapshot.configJson?.let(::decodeConfig) }

    private val configStream: SharedFlow<AppConfigMap> = flow {
        withContext(dispatcherProvider.io) {
            refreshConfig().join()
        }
        val flow = combine(
            configOverrideRepository.getOverridesFlow(),
            cachedConfigFlow
        ) { overrides, config ->
            config.applyOverrides(overrides)
        }
        emitAll(flow)
    }
        .distinctUntilChanged()
        .onEach { logger.d { "Config emitted" } }
        .shareIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            replay = 1
        )

    override fun config(): AppConfigMap = LazyAppConfigMap()

    override fun configStream(): Flow<AppConfigMap> = configStream

    private fun startAppConfigRefresh() {
        if (refreshPollingJob?.isActive == true) return
        refreshPollingJob = appScope.childSupervisorScope(dispatcherProvider.io).launch {
            while (isActive) {
                logger.d { "Triggering scheduled config refresh" }
                refreshConfig().join()
                delay(ConfigExpiration)
            }
        }
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
