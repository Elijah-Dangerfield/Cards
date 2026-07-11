package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.buildinfo.CardsBuildConfig
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
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
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Boot-time wiring for client app events: constructs [GrafanaLogTree] and
 * plants it at DI init, so the pipe (extension → KLog → tree → exporter) is
 * live before the user can produce events. `app.launched` itself is emitted
 * by [AppLaunchedEmitter] on the cold-boot foreground — see its doc for why
 * emitting from this init orphaned the event's session_id (ENG-24).
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
    klogForwardingEnabled: KlogForwardingEnabled,
) : AutoInit {

    private val logger = KLog.withTag("GrafanaAppEvents")

    init {
        if (GrafanaCloud.isConfigured) {
            val tree = GrafanaLogTree(
                exportEnabled = { appEventsEnabled() },
                sampleRate = { appEventsSampleRate() },
                klogForwardingEnabled = { klogForwardingEnabled() },
                currentSessionId = { sessionIdProvider.current() },
                currentInstallId = { installIdProvider.current() },
                processorFactory = {
                    batchLogRecordProcessor(
                        FailSafeLogRecordExporter(
                            OtlpJsonLogRecordExporter(GrafanaCloud.OTLP_BASE_URL, grafanaHttpClient()),
                        ),
                    )
                },
            )
            KLog.plant(tree)
        } else {
            logger.i { "Grafana app events disabled: no OTLP credentials in this build" }
        }
    }
}

/**
 * Emits `app.launched` once per cold start — on the boot foreground, NOT at
 * DI init (ENG-24): init runs before the first foreground, which is when
 * [AppEvent.ColdBoot] makes the session tracker roll session #1, so an
 * init-time emission carries the tracker's pre-boot sentinel uuid and every
 * cold start's launch event lands orphaned from the rest of its session.
 * [onForeground] with `isColdBoot` is guaranteed to run after every ColdBoot
 * listener (the dispatcher notifies all listeners per event, in event order),
 * so the session_id is settled by then.
 *
 * A separate class from [GrafanaAppEvents] on purpose: joining the listener
 * set from a class that (transitively, via the config values → AppConfigMap →
 * config repository → AppEvents) depends on the dispatcher is a DI cycle.
 * This emitter depends only on [PreviousExitProvider], which keeps it out of
 * the loop.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppEventListener::class, multibinding = true)
@Inject
class AppLaunchedEmitter(
    private val previousExitProvider: PreviousExitProvider,
) : AppEventListener {

    override fun onForeground(event: AppEvent.OnForeground) {
        if (event.isColdBoot) {
            logAppLaunched(previousExitProvider.previousExit())
        }
    }
}

/**
 * `app.launched` — once per cold start, first event through the freshly
 * planted tree (warms the SDK and proves the whole pipe). `previous_exit`
 * says how the last run ended; iOS is always `unknown` for now (see
 * [PreviousExitProvider]).
 */
internal fun logAppLaunched(previousExit: PreviousExit) {
    KLog.logEvent(
        "app.launched",
        "cold_start" to true,
        "previous_exit" to previousExit.value,
    )
}

/**
 * Grafana Cloud OTLP gateway credentials, injected at build time into
 * `CardsBuildConfig` (see `loadTelemetryMetadata` in build-logic): CI reads
 * GitHub secrets, local builds read `local.properties` `grafana.*` keys. The
 * repo is public, so the token never lives in source — even though it is
 * write-only and ships in the binary regardless (any client credential is
 * extractable; this one can only append logs). Deliberately not routed
 * through our backend — these events must survive backend outages.
 *
 * Unconfigured builds leave [isConfigured] false and the tree is never
 * planted — events still reach logcat + Sentry through the other trees.
 */
internal object GrafanaCloud {
    val OTLP_BASE_URL: String = CardsBuildConfig.GRAFANA_OTLP_BASE_URL
    val INSTANCE_ID: String = CardsBuildConfig.GRAFANA_OTLP_INSTANCE_ID
    val LOGS_WRITE_TOKEN: String = CardsBuildConfig.GRAFANA_LOGS_WRITE_TOKEN

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
