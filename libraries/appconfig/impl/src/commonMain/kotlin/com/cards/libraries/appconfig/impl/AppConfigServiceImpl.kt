package com.dangerfield.cards.libraries.appconfig.impl

import com.dangerfield.cards.libraries.appconfig.AppConfig
import com.dangerfield.cards.libraries.appconfig.AppConfigService
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppConfigService::class)
@Inject
class AppConfigServiceImpl(
    private val networkClient: NetworkClient,
    private val appCache: AppCache,
) : AppConfigService {

    private val mutableState = MutableStateFlow(AppConfig.Defaults)
    override val state: StateFlow<AppConfig> get() = mutableState

    private val refreshMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        ioScope.launch {
            Catching {
                appCache.get().cachedAppConfig?.let { mutableState.value = it }
            }.logOnFailure("AppConfig: failed to load cached config")
        }
    }

    override suspend fun refresh() {
        refreshMutex.withLock {
            val result = Catching {
                val fresh: AppConfig = networkClient.client
                    .get("/v1/app-config")
                    .body()
                mutableState.value = fresh
                appCache.set(appCache.get().copy(cachedAppConfig = fresh))
            }
            result.logOnFailure("AppConfig: refresh failed; keeping current value")
        }
    }
}
