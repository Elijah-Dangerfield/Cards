package com.dangerfield.cards.libraries.config

/**
 * Describes a single strongly typed value that can be read from [AppConfigMap].
 * If the value is missing from the config, the default value is used
 *
 * Example:
 *
 * ```kotlin
 * object MinSupportedVersion : ConfiguredValue<Int>() {
 *     override val displayName = "Min Supported Version"
 *     override val path = "app.minSupportedVersion"
 *     override val default = 1
 *
 *     override fun resolveValue(): Int = default
 * }
 *
 * class LoginViewModel @Inject constructor(
 *     private val config: AppConfigMap
 * ) {
 *     fun shouldBlockUser(appVersion: Int) =
 *         appVersion < config.value(MinSupportedVersion)
 * }
 * ```
 */
abstract class ConfiguredValue<out T : Any> {
    abstract val name: String
    open val description: String? = null

    /**
     * The path to the value in the config json
     * ex:
     *  myfeature.myfeaturesConfigThingy
     */
    open val path: String
        get() = name.toPath()

    fun String.toPath() = this
        .replaceFirstChar { it.lowercase() }
        .replace(" ", "_")
        .apply {
            if (this.contains("_")) {
                this.lowercase()
            }
        }



    /**
     * Value to be used when the config does not specify a value
     */
    abstract val default: T

    /**
     * Weather this value should be shown in the QA dashboard, allowing for a local [ConfigOverride]
     */
    open val showInQADashboard: Boolean = false

    /**
     * Value to be used specifically for debug builds.
     */
    open val debugOverride: T? = null

    /**
     * Optional list of valid values for this config. When provided, the QA
     * menu renders a chip-style selector instead of a free-text input — useful
     * for enum-like strings (e.g. `"off" | "banner" | "blocking"`).
     *
     * Leave `null` for open-ended values (numbers, free-form text, etc.).
     */
    open val allowedValues: List<@UnsafeVariance T>? = null
}
