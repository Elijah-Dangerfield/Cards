package com.dangerfield.cards.libraries.config.impl.model

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.impl.serialization.ConfigJsonConverter
import kotlinx.coroutines.runBlocking
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import cards.libraries.config.impl.generated.resources.Res

@SingleIn(AppScope::class)
open class FallbackConfigMap @Inject constructor(
    private val converter: ConfigJsonConverter,
) : AppConfigMap() {
    private val logger = KLog.withTag("FallbackConfigMap")

    suspend fun load(): String = Res.readBytes("files/fallback_app_config.json").decodeToString()

    override val map: Map<String, *> by lazy {
        val rawJson = runBlocking { load() }
        converter.decodeToMap(rawJson)
            .onFailure { error -> logger.e(error) { "Unable to parse fallback config" } }
            .getOrDefault(emptyMap())
    }
}
