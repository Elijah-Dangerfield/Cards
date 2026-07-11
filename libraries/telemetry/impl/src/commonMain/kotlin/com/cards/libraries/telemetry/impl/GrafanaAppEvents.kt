package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.networking.InstallIdProvider
import com.dangerfield.cards.libraries.networking.SessionIdProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlin.io.encoding.Base64
import io.opentelemetry.kotlin.logging.export.batchLogRecordProcessor
import io.opentelemetry.kotlin.logging.export.otlpHttpLogRecordExporter
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Boot-time wiring for client app events: constructs [GrafanaLogTree],
 * plants it, and emits `app.launched` — which both warms the SDK and proves
 * the whole pipe (extension → KLog → tree → exporter) on every cold start.
 *
 * [AutoInit] because planting must happen before the user can produce
 * events; a lazily-constructed tree would silently drop everything until
 * something injected it.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class GrafanaAppEvents(
    sessionIdProvider: SessionIdProvider,
    installIdProvider: InstallIdProvider,
    appEventsEnabled: AppEventsEnabled,
    appEventsSampleRate: AppEventsSampleRate,
) : AutoInit {

    private val logger = KLog.withTag("GrafanaAppEvents")

    init {
        if (GrafanaCloud.isConfigured) {
            val tree = GrafanaLogTree(
                exportEnabled = { appEventsEnabled() },
                sampleRate = { appEventsSampleRate() },
                currentSessionId = { sessionIdProvider.current() },
                currentInstallId = { installIdProvider.current() },
                processorFactory = {
                    batchLogRecordProcessor(
                        otlpHttpLogRecordExporter(GrafanaCloud.OTLP_BASE_URL, grafanaHttpClient()),
                    )
                },
            )
            KLog.plant(tree)
            KLog.logEvent("app.launched", "cold_start" to true)
        } else {
            logger.i { "Grafana app events disabled: no OTLP credentials in this build" }
        }
    }
}

/**
 * Grafana Cloud OTLP gateway credentials, hard-coded like `CARDS_SENTRY_DSN`
 * (AppTelemetry.kt precedent): the token is scoped to logs:write only, so it
 * can't read or modify anything. Deliberately not routed through our backend
 * — these events must survive backend outages.
 *
 * Owner action (tracked in docs/developer-todo.md): mint a Cloud Access
 * Policy token scoped to logs:write in the Grafana Cloud console (do NOT
 * reuse the server's token) and paste the three values. Until then
 * [isConfigured] is false and the tree is never planted — events still
 * reach logcat + Sentry through the other trees.
 */
internal object GrafanaCloud {
    /** `https://otlp-gateway-<region>.grafana.net/otlp` */
    const val OTLP_BASE_URL: String = ""
    const val INSTANCE_ID: String = ""
    const val LOGS_WRITE_TOKEN: String = ""

    val isConfigured: Boolean
        get() = OTLP_BASE_URL.isNotBlank() && INSTANCE_ID.isNotBlank() && LOGS_WRITE_TOKEN.isNotBlank()

    val basicAuthHeader: String
        get() = "Basic " + Base64.encode("$INSTANCE_ID:$LOGS_WRITE_TOKEN".encodeToByteArray())
}

/**
 * Matches the plugin set of the exporter's internal default client
 * (HttpTimeout + ContentNegotiation + gzip ContentEncoding), which the
 * library requires when a custom client is supplied — plus the Grafana
 * basic-auth header, which is the whole reason we supply one:
 * `otlpHttpLogRecordExporter` has no headers parameter.
 */
private fun grafanaHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
    }
    install(ContentNegotiation)
    install(ContentEncoding) {
        gzip()
    }
    defaultRequest {
        header(HttpHeaders.Authorization, GrafanaCloud.basicAuthHeader)
    }
}
