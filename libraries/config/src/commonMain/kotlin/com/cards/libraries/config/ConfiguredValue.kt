package com.dangerfield.cards.libraries.config

/**
 * A single strongly-typed value that resolves from the merged [AppConfigMap]
 * (defaults + remote + QA overrides). Each value is its own injectable class,
 * contributed into the app graph's `Set<ConfiguredValue<*>>` so it shows up in
 * the QA menu automatically — see the typed bases in `TypedConfiguredValues.kt`
 * ([FlagConfigValue], [IntConfigValue], [StringConfigValue], …) which fill in
 * [resolveValue] for the common scalar types.
 *
 * Example — a flag:
 *
 * ```kotlin
 * @Inject
 * @SingleIn(AppScope::class)
 * @ContributesBinding(AppScope::class, ConfiguredValue::class, multibinding = true)
 * class GoogleSignInEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
 *     override val name = "Google sign-in enabled"
 *     override val path = "identity.googleSignInEnabled"
 *     override val default = false
 * }
 *
 * @Inject
 * class SignInViewModel(googleSignInEnabled: GoogleSignInEnabled) {
 *     val showGoogle = googleSignInEnabled()   // resolves the value
 * }
 * ```
 *
 * Values don't have to be a single scalar — a [ConfiguredValue] can hold a map,
 * list, or `@Serializable` object. Subclass `ConfiguredValue<T>` directly and
 * implement [resolveValue] (`appConfigMap.value(this)`) for those.
 */
/**
 * Non-generic supertype every [ConfiguredValue] implements. Exists so all values
 * can be collected into a single DI multibinding `Set<QaConfigValue>` — anvil/KSP
 * can't key a multibinding on a generic type (`ConfiguredValue<*>` fails ClassName
 * conversion). Consumers that need the typed value still inject the concrete class
 * and read `ConfiguredValue<T>.value`; the QA menu iterates the set and narrows
 * back to `ConfiguredValue<*>` for display.
 */
interface QaConfigValue

abstract class ConfiguredValue<out T : Any> : QaConfigValue {
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
     * Whether this value should be shown in the QA dashboard, allowing for a
     * local [ConfigOverride]. Defaults to `true` so every value is discoverable;
     * override to `false` to hide a value from QA.
     */
    open val showInQADashboard: Boolean = true

    /**
     * QA-menu section this value is grouped under. Defaults to the first path
     * segment (e.g. `upgrade.minSupportedVersionCode` → `"upgrade"`).
     */
    open val group: String
        get() = path.substringBefore(".")

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

    /**
     * Resolves the current value from the backing [AppConfigMap]. Implemented
     * by the typed bases for scalars; composite values implement it directly.
     */
    protected abstract fun resolveValue(): T

    /** The resolved value. */
    val value: T get() = resolveValue()

    /** Sugar so a value reads like `googleSignInEnabled()`. */
    operator fun invoke(): T = value
}
