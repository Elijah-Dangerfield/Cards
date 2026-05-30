package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.config.ObservabilityConfig
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.logging.SystemOutLogRecordExporter
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.slf4j.LoggerFactory

/**
 * Initialises the OpenTelemetry SDK and registers it as
 * [GlobalOpenTelemetry]. Returns the live SDK so callers (mostly
 * [Application.installOpenTelemetry]) can hand it to a shutdown hook;
 * production code reads the tracer via `GlobalOpenTelemetry.getTracer(...)`
 * so feature code never needs to thread the SDK through.
 *
 * Exporter strategy is mirrored across traces and logs so a single
 * env var (`OTEL_EXPORTER_OTLP_ENDPOINT`) flips both pipes at once:
 *  - endpoint unset → stdout (`LoggingSpanExporter` +
 *    `SystemOutLogRecordExporter`). Spans + log records interleave with
 *    the rest of `flyctl logs` so dev runs without an external sink stay
 *    debuggable.
 *  - endpoint set → OTLP/HTTP for both signals, with any headers from
 *    `OTEL_EXPORTER_OTLP_HEADERS` (`Key=Value,Key=Value`).
 *
 * Processors mirror the same split: `Simple*Processor` in stdout mode
 * (one-at-a-time, no buffering), `Batch*Processor` in OTLP mode so
 * exporter HTTP cost amortises.
 *
 * After SDK construction, [OpenTelemetryAppender.install] wires the
 * Logback appender's pending log records into the live SDK. The
 * appender itself is declared in `logback.xml` and starts buffering on
 * JVM boot; if `installOpenTelemetry` isn't called the records age out
 * of its bounded queue without crashing.
 *
 * Multiple calls within one JVM (e.g. tests) are guarded:
 * `GlobalOpenTelemetry` is set-once. If something else already set the
 * global (or a stray `get()` from a static initialiser locked it to noop
 * — logback's `OpenTelemetryAppender` is the culprit on this server),
 * `set()` throws `IllegalStateException`; we catch it, log, and reuse
 * whatever's there. Tests that need a fresh SDK bypass this function
 * and call [buildOpenTelemetrySdk] directly, plugging the result into
 * the routing via DI.
 *
 * Critically, we do **not** probe with `GlobalOpenTelemetry.get()`
 * before calling `set()`. The probe itself registers a placeholder and
 * locks the slot, which then makes our own `set()` throw. Try-set-catch
 * is the only safe order.
 */
fun Application.installOpenTelemetry(config: ObservabilityConfig): OpenTelemetry {
    val log = LoggerFactory.getLogger("OpenTelemetry")

    val sdk = buildOpenTelemetrySdk(config)
    val installed: OpenTelemetry = try {
        GlobalOpenTelemetry.set(sdk)
        sdk
    } catch (e: IllegalStateException) {
        log.warn("OpenTelemetry global already set; reusing the live SDK", e)
        GlobalOpenTelemetry.get()
    }
    OpenTelemetryAppender.install(installed)

    monitor.subscribe(ApplicationStopPreparing) {
        Catching { sdk.close() }
            .onFailure { log.warn("OpenTelemetry SDK shutdown failed", it) }
    }

    log.info(
        "OpenTelemetry initialised (service={}, env={}, exporter={})",
        config.serviceName,
        config.environment,
        if (config.otlpEndpoint.isNullOrBlank()) "stdout" else "otlp-http",
    )
    return installed
}

/**
 * Builds (but does not register) an [OpenTelemetrySdk] for [config].
 * Test-friendly seam: tests that swap in
 * [io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter] (or its
 * log counterpart) build their SDK directly and inject it where needed
 * without touching the global slot.
 */
fun buildOpenTelemetrySdk(
    config: ObservabilityConfig,
    spanExporter: SpanExporter? = null,
    logRecordExporter: LogRecordExporter? = null,
): OpenTelemetrySdk {
    val resource = Resource.getDefault().merge(
        Resource.create(
            Attributes.builder()
                .put(AttributeKey.stringKey("service.name"), config.serviceName)
                .put(AttributeKey.stringKey("deployment.environment"), config.environment)
                .apply {
                    config.release?.let { put(AttributeKey.stringKey("service.version"), it) }
                }
                .build(),
        ),
    )

    val resolvedSpanExporter = spanExporter ?: defaultSpanExporter(config)
    val spanProcessor = if (spanExporter == null && !config.otlpEndpoint.isNullOrBlank()) {
        BatchSpanProcessor.builder(resolvedSpanExporter).build()
    } else {
        SimpleSpanProcessor.create(resolvedSpanExporter)
    }

    val tracerProvider = SdkTracerProvider.builder()
        .setResource(resource)
        .addSpanProcessor(spanProcessor)
        .build()

    val resolvedLogExporter = logRecordExporter ?: defaultLogRecordExporter(config)
    val logProcessor = if (logRecordExporter == null && !config.otlpEndpoint.isNullOrBlank()) {
        BatchLogRecordProcessor.builder(resolvedLogExporter).build()
    } else {
        SimpleLogRecordProcessor.create(resolvedLogExporter)
    }

    val loggerProvider = SdkLoggerProvider.builder()
        .setResource(resource)
        .addLogRecordProcessor(logProcessor)
        .build()

    return OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setLoggerProvider(loggerProvider)
        .build()
}

private fun defaultSpanExporter(config: ObservabilityConfig): SpanExporter {
    val endpoint = config.otlpEndpoint?.takeUnless { it.isBlank() }
        ?: return LoggingSpanExporter.create()
    val builder = OtlpHttpSpanExporter.builder()
        .setEndpoint(endpoint.trimEnd('/') + "/v1/traces")
    applyOtlpHeaders(config.otlpHeaders) { name, value -> builder.addHeader(name, value) }
    return builder.build()
}

private fun defaultLogRecordExporter(config: ObservabilityConfig): LogRecordExporter {
    val endpoint = config.otlpEndpoint?.takeUnless { it.isBlank() }
        ?: return SystemOutLogRecordExporter.create()
    val builder = OtlpHttpLogRecordExporter.builder()
        .setEndpoint(endpoint.trimEnd('/') + "/v1/logs")
    applyOtlpHeaders(config.otlpHeaders) { name, value -> builder.addHeader(name, value) }
    return builder.build()
}

/**
 * Parses an `OTEL_EXPORTER_OTLP_HEADERS`-shaped string (`Key=Value,Key=Value`)
 * and forwards each entry to [add]. Lifted out so trace + log exporter
 * builders share one parser instead of drifting apart.
 *
 * Values are percent-decoded per the OTel env-var spec — vendors (Grafana
 * Cloud included) hand you headers like `Authorization=Basic%20<base64>`
 * with the space URL-encoded. Without decoding, the outgoing header is
 * literally `Basic%20<base64>` and the gateway rejects every request.
 */
private fun applyOtlpHeaders(raw: String?, add: (String, String) -> Unit) {
    parseOtlpHeaders(raw).forEach { (name, value) -> add(name, value) }
}

internal fun parseOtlpHeaders(raw: String?): List<Pair<String, String>> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim().percentDecode()
        name to value
    }
}

private fun String.percentDecode(): String =
    java.net.URLDecoder.decode(this, Charsets.UTF_8)

