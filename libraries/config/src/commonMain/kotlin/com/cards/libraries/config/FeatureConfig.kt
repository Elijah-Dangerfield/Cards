package com.dangerfield.cards.libraries.config

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

abstract class FeatureConfig(
    val featureName: String,
    val configMap: AppConfigMap,
) {
    val values = mutableListOf<ConfiguredValue<*>>()

    inline fun <reified T : Any> featureValue(
        name: String? = null,
        description: String = "",
        default: T,
        path: String? = null,
    ): ReadOnlyProperty<FeatureConfig, T> = object : ReadOnlyProperty<FeatureConfig, T> {

        private var cachedValue: ConfiguredValue<T>? = null

        override fun getValue(thisRef: FeatureConfig, property: KProperty<*>): T {
            val descriptor = cachedValue ?: createValue(
                pathOverride = path,
                resolvedName = name ?: property.name,
                featureName = featureName,
                description = description,
                default = default
            ).also {
                cachedValue = it
                thisRef.values += it
            }
            return thisRef.configMap.value(descriptor)
        }

        private fun <T : Any> createValue(
            pathOverride: String?,
            resolvedName: String,
            featureName: String,
            description: String,
            default: T,
        ): ConfiguredValue<T> = object : ConfiguredValue<T>() {
            override val name: String = resolvedName
            override val description: String = description
            override val default: T = default
            override val path: String = (pathOverride ?: resolvedName.toPath()).let {
                if (it.startsWith(featureName)) it else "$featureName.$it"
            }
        }
    }
}