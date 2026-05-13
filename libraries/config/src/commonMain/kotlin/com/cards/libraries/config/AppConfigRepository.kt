package com.dangerfield.cards.libraries.config

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the source of configuration data (remote service, bundled file, etc.).
 */
interface AppConfigRepository {
    /** Returns the latest known configuration snapshot synchronously. */
    fun config(): AppConfigMap

    /** Emits configuration updates so callers can react to changes at runtime. */
    fun configStream(): Flow<AppConfigMap>
}
