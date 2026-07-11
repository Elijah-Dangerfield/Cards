package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.core.logging.EXTRA_APP_EVENT
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogId
import com.dangerfield.cards.libraries.core.logging.LogLevel
import com.dangerfield.cards.libraries.core.logging.LogTree
import com.dangerfield.cards.libraries.core.versionString
import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.createOpenTelemetry
import io.opentelemetry.kotlin.init.LogExportConfigDsl
import io.opentelemetry.kotlin.logging.SeverityNumber
import io.opentelemetry.kotlin.logging.export.LogRecordProcessor

/**
 * Forwards `logEvent` entries (those carrying [EXTRA_APP_EVENT]) to Grafana
 * Cloud as OTLP log records. Planted alongside KermitLogTree/SentryLogTree,
 * so the same entry reaches logcat and Sentry breadcrumbs through those
 * trees regardless of what this one does.
 *
 * Deliberately direct-to-Grafana rather than through our backend: the
 * reliability events (`net.backend_unreachable`, reconnect failures) must
 * survive a backend outage. See `docs/plans/client-app-events-otel.md`.
 *
 * `session_id` / `install_id` ride on every record (never as resource
 * attributes — the session rolls over mid-process on a 15-min background).
 * All OTel types stay confined to this class: if 0.5.0 misbehaves, the tree
 * gets re-backed without touching call sites.
 */
class GrafanaLogTree(
    private val exportEnabled: () -> Boolean,
    private val sampleRate: () -> Double,
    private val currentSessionId: () -> String?,
    private val currentInstallId: () -> String?,
    private val processorFactory: LogExportConfigDsl.() -> LogRecordProcessor,
) : LogTree() {

    private val openTelemetry: OpenTelemetry by lazy {
        createOpenTelemetry {
            loggerProvider {
                serviceName = SERVICE_NAME
                resource {
                    setStringAttribute("service.version", BuildInfo.versionString())
                    setStringAttribute("deployment.environment", if (BuildInfo.isDebug) "dev" else "prod")
                    setStringAttribute(
                        "platform",
                        when (BuildInfo.platform) {
                            Platform.Android -> "android"
                            Platform.iOS -> "ios"
                        },
                    )
                    setLongAttribute("build_number", BuildInfo.buildNumber.toLong())
                    setStringAttribute("commit_sha", BuildInfo.commitSha)
                    setStringAttribute("release_channel", BuildInfo.releaseChannel)
                }
                export { processorFactory() }
            }
        }
    }

    private val eventLogger by lazy {
        openTelemetry.loggerProvider.getLogger(name = SERVICE_NAME, version = BuildInfo.versionString())
    }

    override fun log(entry: LogEntry): LogId? {
        val eventName = entry.context.extras[EXTRA_APP_EVENT] as? String ?: return null
        Catching { forward(eventName, entry) }
        return null
    }

    private fun forward(eventName: String, entry: LogEntry) {
        if (!exportEnabled()) return
        val sessionId = currentSessionId()
        if (!isSessionSampledIn(sessionId)) return

        eventLogger.emit(
            body = entry.message,
            eventName = eventName,
            severityNumber = entry.level.toSeverityNumber(),
            attributes = {
                sessionId?.let { setStringAttribute(SESSION_ID_KEY, it) }
                currentInstallId()?.let { setStringAttribute(INSTALL_ID_KEY, it) }
                entry.context.tags.forEach { (key, value) -> setStringAttribute(key, value) }
                entry.context.extras.forEach { (key, value) ->
                    if (key == EXTRA_APP_EVENT) return@forEach
                    when (value) {
                        null -> Unit
                        is String -> setStringAttribute(key, value)
                        is Boolean -> setBooleanAttribute(key, value)
                        is Int -> setLongAttribute(key, value.toLong())
                        is Long -> setLongAttribute(key, value)
                        is Float -> setDoubleAttribute(key, value.toDouble())
                        is Double -> setDoubleAttribute(key, value)
                        else -> setStringAttribute(key, value.toString())
                    }
                }
            },
        )
    }

    /**
     * Stable per-session decision: hash the session id into [0, 1) and
     * compare against the rate, so one session's events are all-or-nothing
     * and a funnel never loses its middle step to per-event dice rolls.
     */
    private fun isSessionSampledIn(sessionId: String?): Boolean {
        val rate = sampleRate().coerceIn(0.0, 1.0)
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false
        val id = sessionId ?: return false
        val bucket = (id.hashCode().toLong() and 0x7FFFFFFFL).toDouble() / Int.MAX_VALUE.toDouble()
        return bucket < rate
    }

    private fun LogLevel.toSeverityNumber(): SeverityNumber = when (this) {
        LogLevel.Verbose -> SeverityNumber.TRACE
        LogLevel.Debug -> SeverityNumber.DEBUG
        LogLevel.Info -> SeverityNumber.INFO
        LogLevel.Warn -> SeverityNumber.WARN
        LogLevel.Error -> SeverityNumber.ERROR
        LogLevel.Assert, LogLevel.Fatal -> SeverityNumber.FATAL
    }

    companion object {
        const val SERVICE_NAME = "cards-client"
        private const val SESSION_ID_KEY = "session_id"
        private const val INSTALL_ID_KEY = "install_id"
    }
}
