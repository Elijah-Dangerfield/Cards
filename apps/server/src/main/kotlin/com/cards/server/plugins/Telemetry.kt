package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.server.config.ObservabilityConfig
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
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
 * Exporter strategy:
 *  - [ObservabilityConfig.otlpEndpoint] null/blank → [LoggingSpanExporter]
 *    (stdout, one line per span). Lets dev runs and Fly deploys without
 *    a sink configured still surface span data in `flyctl logs`.
 *  - Otherwise → [OtlpHttpSpanExporter] pointed at the URL with any
 *    headers from `OTEL_EXPORTER_OTLP_HEADERS` (`Key=Value,Key=Value`).
 *
 * Processor: stdout uses [SimpleSpanProcessor] (one-at-a-time, no
 * buffering — the point is to see spans interleaved with the rest of
 * the log stream). OTLP uses [BatchSpanProcessor] so the exporter HTTP
 * cost amortises across spans.
 *
 * Multiple calls within one JVM (e.g. tests) are guarded:
 * `GlobalOpenTelemetry` is set-once. If already initialised, this is a
 * no-op that returns the existing instance. Tests that need a fresh SDK
 * should bypass this function and call [buildOpenTelemetrySdk] directly
 * with their own exporter, then plug it into the routing via DI.
 */
fun Application.installOpenTelemetry(config: ObservabilityConfig): OpenTelemetry {
    val log = LoggerFactory.getLogger("OpenTelemetry")

    val existing = runCatching { GlobalOpenTelemetry.get() }.getOrNull()
    if (existing != null && existing != OpenTelemetry.noop()) {
        log.info("OpenTelemetry already initialised; reusing the global SDK")
        return existing
    }

    val sdk = buildOpenTelemetrySdk(config)
    GlobalOpenTelemetry.set(sdk)

    monitor.subscribe(ApplicationStopPreparing) {
        runCatching { sdk.close() }
            .onFailure { log.warn("OpenTelemetry SDK shutdown failed", it) }
    }

    log.info(
        "OpenTelemetry initialised (service={}, env={}, exporter={})",
        config.serviceName,
        config.environment,
        if (config.otlpEndpoint.isNullOrBlank()) "stdout" else "otlp-http",
    )
    return sdk
}

/**
 * Builds (but does not register) an [OpenTelemetrySdk] for [config].
 * Test-friendly seam: tests that swap in [io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter]
 * build their SDK directly and inject it where needed without touching
 * the global slot.
 */
fun buildOpenTelemetrySdk(
    config: ObservabilityConfig,
    spanExporter: SpanExporter? = null,
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

    val exporter = spanExporter ?: defaultSpanExporter(config)
    val processor = if (spanExporter == null && !config.otlpEndpoint.isNullOrBlank()) {
        BatchSpanProcessor.builder(exporter).build()
    } else {
        SimpleSpanProcessor.create(exporter)
    }

    val tracerProvider = SdkTracerProvider.builder()
        .setResource(resource)
        .addSpanProcessor(processor)
        .build()

    return OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build()
}

private fun defaultSpanExporter(config: ObservabilityConfig): SpanExporter {
    val endpoint = config.otlpEndpoint?.takeUnless { it.isBlank() }
        ?: return LoggingSpanExporter.create()
    val builder = OtlpHttpSpanExporter.builder()
        .setEndpoint(endpoint.trimEnd('/') + "/v1/traces")
    config.otlpHeaders
        ?.takeUnless { it.isBlank() }
        ?.split(',')
        ?.forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                builder.addHeader(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim())
            }
        }
    return builder.build()
}

