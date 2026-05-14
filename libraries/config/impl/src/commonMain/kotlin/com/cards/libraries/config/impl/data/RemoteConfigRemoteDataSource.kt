package com.dangerfield.cards.libraries.config.impl.data

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.impl.model.BasicMapAppConfig
import com.dangerfield.cards.libraries.config.impl.serialization.ConfigJsonConverter
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.flatMap
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Fetches the app config tree from the Cards server.
 *
 * Returns a sparse JSON object whose keys are the dotted [com.dangerfield.cards.libraries.config.ConfiguredValue.path]s
 * that the server wants to override. Anything missing falls back to the
 * client-side default declared on the [com.dangerfield.cards.libraries.config.ConfiguredValue].
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RemoteConfigRemoteDataSource @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val networkClient: NetworkClient,
    private val converter: ConfigJsonConverter,
) : RemoteConfigDataSource {

    private val logger = KLog.withTag("RemoteConfigDataSource")

    override suspend fun getConfig(): Catching<AppConfigMap> = withContext(dispatcherProvider.io) {
        Catching {
            networkClient.client.get("/v1/app-config").body<String>()
        }
            .flatMap { raw -> converter.decodeToMap(raw).map { BasicMapAppConfig(it) } }
            .onSuccess { logger.d { "Fetched remote app config" } }
            .onFailure { logger.w(it) { "Failed to fetch remote app config" } }
    }
}
