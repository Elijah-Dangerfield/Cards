package com.dangerfield.cards.libraries.config

import com.dangerfield.cards.libraries.core.BuildInfo

/**
 * Snapshot of the merged configuration tree (defaults + remote + overrides). Implementations provide
 * the [map] data and consumers rely on the typed helpers to retrieve values safely.
 */
abstract class AppConfigMap {

    abstract val map: Map<String, *>

    /**
     * Returns an `Int` by delegating to [value] and handling numeric conversions.
     */
    fun intValue(value: ConfiguredValue<Int>): Int =
        value<Number>(value).toInt()

    /**
     * Returns a `Long` by delegating to [value] and handling numeric conversions.
     */
    fun longValue(value: ConfiguredValue<Long>): Long =
        value<Number>(value).toLong()

    /**
     * Returns a `Double` by delegating to [value] and handling numeric conversions.
     */
    fun doubleValue(value: ConfiguredValue<Double>): Double =
        value<Number>(value).toDouble()

    /**
     * Resolves the configured value, honoring debug overrides and falling back to [ConfiguredValue.default].
     */
    inline fun <reified T : Any> value(value: ConfiguredValue<T>): T =
        value.debugOverride?.takeIf { BuildInfo.isDebug }
            ?: map.getValueForPath<T>(fullPath = value.path) ?: value.default

}
