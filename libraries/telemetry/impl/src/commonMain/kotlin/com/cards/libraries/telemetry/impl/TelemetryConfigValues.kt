package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.DoubleConfigValue
import com.dangerfield.cards.libraries.config.FlagConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Kill switch for shipping app events to Grafana Cloud. Exists for library
 * bugs (opentelemetry-kotlin is 0.5.0 experimental) and ingest-cost
 * incidents. Evaluated per-forward, so a server-side flip takes effect
 * without an app restart; when off, `logEvent` entries still reach logcat
 * and Sentry breadcrumbs through the other trees.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class AppEventsEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
    override val name = "App events to Grafana enabled"
    override val path = "telemetry.appEventsEnabled"
    override val default = true
}

/**
 * Forwards Warn+ KLog lines (no event extra required) to Grafana Cloud as
 * plain OTLP logs — the ops mirror of client errors into Loki without
 * waiting on a Sentry crash. Sits behind the same kill switch
 * ([AppEventsEnabled]) and per-session sampling as app events; events keep
 * flowing regardless of this flag. Default on at beta scale (owner decision
 * 2026-07-11); log volume becomes a deliberate second decision when the
 * user base grows.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class KlogForwardingEnabled(appConfigMap: AppConfigMap) : FlagConfigValue(appConfigMap) {
    override val name = "Warn+ logs to Grafana enabled"
    override val path = "telemetry.klogForwardingEnabled"
    override val default = true
}

/**
 * Per-session sampling rate for app events, 0.0..1.0. Sampling is keyed on a
 * stable hash of the session id, so a session's events are all-or-nothing
 * and funnels stay joinable. 1.0 until volume forces a cut.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class AppEventsSampleRate(appConfigMap: AppConfigMap) : DoubleConfigValue(appConfigMap) {
    override val name = "App events sample rate"
    override val path = "telemetry.appEventsSampleRate"
    override val default = 1.0
}
