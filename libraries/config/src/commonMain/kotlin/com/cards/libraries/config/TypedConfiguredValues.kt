package com.dangerfield.cards.libraries.config

/**
 * Typed [ConfiguredValue] bases that fill in [resolveValue] for the common
 * scalar types so each concrete value only declares `name` / `path` / `default`.
 *
 * `resolveValue` lives here (at the concrete scalar type), not on
 * [ConfiguredValue], because [AppConfigMap.value] is `inline fun <reified T>` —
 * it needs the value type known at the call site, which it is once `T` is
 * pinned to `Boolean` / `Int` / `String` / etc.
 *
 * For composite values (maps, lists, `@Serializable` objects) subclass
 * `ConfiguredValue<T>` directly and implement `resolveValue()` =
 * `appConfigMap.value(this)`.
 */

abstract class FlagConfigValue(
    protected val appConfigMap: AppConfigMap,
) : ConfiguredValue<Boolean>() {
    override fun resolveValue(): Boolean = appConfigMap.value(this)
}

abstract class IntConfigValue(
    protected val appConfigMap: AppConfigMap,
) : ConfiguredValue<Int>() {
    override fun resolveValue(): Int = appConfigMap.intValue(this)
}

abstract class LongConfigValue(
    protected val appConfigMap: AppConfigMap,
) : ConfiguredValue<Long>() {
    override fun resolveValue(): Long = appConfigMap.longValue(this)
}

abstract class DoubleConfigValue(
    protected val appConfigMap: AppConfigMap,
) : ConfiguredValue<Double>() {
    override fun resolveValue(): Double = appConfigMap.doubleValue(this)
}

abstract class StringConfigValue(
    protected val appConfigMap: AppConfigMap,
) : ConfiguredValue<String>() {
    override fun resolveValue(): String = appConfigMap.value(this)
}
