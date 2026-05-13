package com.dangerfield.cards.libraries.config.impl.data

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.config.ConfigOverride
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.config.impl.serialization.ConfigJsonConverter
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ConfigOverrideRepositoryImpl @Inject constructor(
    private val configCache: ConfigCache,
    private val converter: ConfigJsonConverter,
    appScope: AppCoroutineScope
) : ConfigOverrideRepository {

    private val logger = KLog.withTag("ConfigOverrideRepository")
    private val mutex = Mutex()

    private val overridesState = configCache.updates
        .map { snapshot -> decodeOverrides(snapshot.overridesJson) }
        .stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = runBlocking { decodeOverrides(configCache.get().overridesJson) }
        )

    override fun getOverrides(): List<ConfigOverride<Any>> = overridesState.value.toList()

    override fun getOverridesFlow(): Flow<List<ConfigOverride<Any>>> =
        overridesState.map { it.toList() }

    override suspend fun addOverride(override: ConfigOverride<Any>) {
        mutex.withLock {
            val updated = overridesState.value + override
            converter.encodeOverrides(updated)
                .onSuccess { json ->
                    logger.d { "Persisting ${updated.size} overrides" }
                    configCache.update { snapshot -> snapshot.copy(overridesJson = json) }
                }
                .onFailure { error ->
                    logger.e(error) { "Unable to persist overrides" }
                }
        }
    }

    private fun decodeOverrides(raw: String?): Set<ConfigOverride<Any>> {
        if (raw.isNullOrBlank()) return emptySet()
        return converter.decodeOverrides(raw)
            .onFailure { error -> logger.e(error) { "Unable to decode overrides" } }
            .getOrNull()
            ?: emptySet()
    }
}
