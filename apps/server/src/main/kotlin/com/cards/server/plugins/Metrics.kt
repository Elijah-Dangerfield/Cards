package com.dangerfield.cards.server.plugins

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableLongGauge
import java.util.concurrent.atomic.AtomicLong

/**
 * Single meter name for the whole server — same convention as
 * [serverTracer]. Resolved through [GlobalOpenTelemetry] so feature code
 * never threads the SDK through constructors.
 */
internal val serverMeter: Meter
    get() = GlobalOpenTelemetry.getMeter(SERVER_METER_NAME)

internal const val SERVER_METER_NAME = "com.dangerfield.cards.server"

/**
 * Metric attribute keys shared across the server. Lifted to one place so a
 * rename touches one file regardless of which gauge / counter set it.
 */
internal object MetricAttrs {
    val OrphanSweepTtlDays: AttributeKey<Long> = AttributeKey.longKey("orphan_sweep.ttl_days")
}

/**
 * Process-wide registry for synchronous-set / observable-callback custom
 * metrics. The OTel SDK holds the gauge alive only as long as we retain
 * the [ObservableLongGauge] handle, so the [register] call binds them on
 * the singleton.
 *
 * Why an object instead of an injectable singleton: mirrors the existing
 * [serverTracer] / [withSpan] pattern (top-level accessor, no DI), so
 * call sites — currently just the anon sweep — set values without taking
 * a constructor dependency. If the surface grows past a couple of
 * gauges, lift to DI; today the parallel-to-tracing shape keeps the
 * indirection cost off the metric path.
 *
 * Tests that build their own SDK via [buildOpenTelemetrySdk] should call
 * [register] after `GlobalOpenTelemetry.set(sdk)` so the callback binds
 * to the test SDK's meter rather than the noop default. [reset] is the
 * teardown counterpart.
 */
internal object ServerMetrics {

    /**
     * Last-observed candidate count from the anonymous-user sweep. The
     * gauge callback re-emits this value on each OTel export interval
     * (every [PeriodicMetricReader] tick) so the metric line is
     * continuous between sweep runs — the daily cron drives the level,
     * the export interval drives the resolution.
     *
     * Sentinel -1 means the sweep hasn't run since the JVM started; the
     * callback skips emission until a real value lands so a freshly
     * booted server doesn't post a misleading "0 orphans" sample before
     * the first sweep.
     */
    private val anonOrphansOverTtl = AtomicLong(-1L)
    private val anonOrphansTtlDays = AtomicLong(0L)

    private var anonOrphansGauge: ObservableLongGauge? = null

    /**
     * Registers the observable gauges against the live [GlobalOpenTelemetry]
     * meter. Idempotent: a second call is a no-op so test harnesses can
     * call it freely without leaking duplicate callbacks.
     */
    @Synchronized
    fun register() {
        if (anonOrphansGauge != null) return
        anonOrphansGauge = serverMeter
            .gaugeBuilder("cards.anon_orphans.over_ttl")
            .ofLongs()
            .setDescription(
                "Orphan anonymous Supabase users older than the configured " +
                    "sweep TTL, observed by the most recent sweep run. A " +
                    "sustained low/flat line confirms the daily sweep is " +
                    "keeping up; a rising trend means orphans are " +
                    "accumulating faster than they're being deleted.",
            )
            .setUnit("{user}")
            .buildWithCallback { measurement ->
                val value = anonOrphansOverTtl.get()
                if (value < 0L) return@buildWithCallback
                measurement.record(
                    value,
                    Attributes.of(MetricAttrs.OrphanSweepTtlDays, anonOrphansTtlDays.get()),
                )
            }
    }

    /**
     * Set the latest candidate count + the TTL the sweep used to find
     * them. Called by [com.dangerfield.cards.server.data.DefaultOrphanAnonymousSweep]
     * after the upstream listing returns; safe from any thread.
     */
    fun recordAnonOrphanCandidates(count: Long, ttlDays: Long) {
        anonOrphansTtlDays.set(ttlDays)
        anonOrphansOverTtl.set(count)
    }

    /** Tear down the registered callbacks. Tests only. */
    @Synchronized
    internal fun reset() {
        anonOrphansGauge?.close()
        anonOrphansGauge = null
        anonOrphansOverTtl.set(-1L)
        anonOrphansTtlDays.set(0L)
    }

    /**
     * Last-recorded candidate count, or -1 if the sweep hasn't run yet.
     * Test-only: lets sweep-side tests verify the recording happened
     * without binding to a live meter.
     */
    internal fun debugLastAnonOrphanCount(): Long = anonOrphansOverTtl.get()

    /**
     * Last-recorded TTL the sweep used to find the candidates. Test-only.
     */
    internal fun debugLastAnonOrphanTtlDays(): Long = anonOrphansTtlDays.get()
}
