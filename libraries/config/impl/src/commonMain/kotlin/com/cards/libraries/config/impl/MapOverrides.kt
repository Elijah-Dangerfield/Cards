package com.dangerfield.cards.libraries.config.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.ConfigOverride
import com.dangerfield.cards.libraries.config.impl.model.BasicMapAppConfig

internal fun AppConfigMap.applyOverrides(overrides: List<ConfigOverride<Any>>): AppConfigMap {
    if (overrides.isEmpty()) return this
    val mutableMap = map.toMutableMap()
    overrides.forEach { override ->
        setValueForPath(mutableMap, override.path, override.value)
    }
    KLog.withTag("AppConfigOverrides").d {
        "Applied ${overrides.size} overrides"
    }
    return BasicMapAppConfig(mutableMap)
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Any> setValueForPath(
    outputMap: MutableMap<String, Any?>,
    fullPath: String,
    value: T
) {
    val segments = fullPath.split('.')
    setValueRecursive(outputMap, segments, value)
}

@Suppress("UNCHECKED_CAST")
private tailrec fun <T : Any> setValueRecursive(
    outMap: MutableMap<String, Any?>,
    path: List<String>,
    value: T
) {
    val key = path.first()
    if (path.size == 1) {
        outMap[key] = value
    } else {
        // Existing child maps may come from JSON deserialization as read-only
        // HashMaps on Kotlin/Native — calling put() on them throws
        // UnsupportedOperationException. Always copy into a fresh mutable map
        // on the way down and write it back into the parent.
        val existing = outMap[key] as? Map<String, Any?>
        val child = existing?.toMutableMap() ?: mutableMapOf()
        outMap[key] = child
        setValueRecursive(child, path.drop(1), value)
    }
}
