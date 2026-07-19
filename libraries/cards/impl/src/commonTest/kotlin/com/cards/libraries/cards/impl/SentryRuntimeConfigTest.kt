package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.logging.LogLevel
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.SentryOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the config → [SentryOptions] mapping. The load-bearing assertion is
 * that **error events are never sampled**: `options.sampleRate` must stay at
 * the SDK default (null = 100%) no matter what the config says about traces
 * or profiling. The first prod feedback (2026-07-09) was silently dropped
 * because the release profiling rate (0.05) was wired into `sampleRate`,
 * discarding 95% of error events — feedback carriers and crashes included.
 */
class SentryRuntimeConfigTest {

    private fun config(tracesSampleRate: Double? = 0.15) = SentryRuntimeConfig(
        dsn = "https://key@o1.ingest.sentry.io/1",
        environment = "store-ios-release",
        release = "cards@0.1.0+2",
        sendDefaultPii = false,
        attachStacktrace = true,
        tracesSampleRate = tracesSampleRate,
        platformTag = "ios",
        buildTypeTag = "release",
        logPolicy = SentryRuntimeConfig.LogPolicy(
            minBreadcrumbLevel = LogLevel.Info,
            minEventLevel = LogLevel.Error,
        ),
        enableAutoSessionTracking = true,
    )

    @Test
    fun errorEvents_areNeverSampled() {
        val options = SentryOptions()

        config().applyTo(options)

        assertNull(options.sampleRate, "error-event sampleRate must stay at the SDK default (100%)")
    }

    @Test
    fun tracesSampleRate_mapsToTracesOption_only() {
        val options = SentryOptions()

        config(tracesSampleRate = 0.15).applyTo(options)

        assertEquals(0.15, options.tracesSampleRate)
        assertNull(options.sampleRate)
    }

    @Test
    fun nullTracesSampleRate_leavesSdkDefault() {
        val options = SentryOptions()

        config(tracesSampleRate = null).applyTo(options)

        assertNull(options.tracesSampleRate)
    }

    @Test
    fun beforeSend_fingerprintsFeedbackCarriersIndividually() {
        val options = SentryOptions()
        config().applyTo(options)

        val carrier = SentryEvent().apply { setTag("feedback_event", "abc-123") }
        val passedThrough = options.beforeSend!!(carrier)

        assertEquals<List<String>?>(listOf("feedback", "abc-123"), passedThrough?.fingerprint)
    }

    @Test
    fun beforeSend_leavesOrdinaryEventsUntouched() {
        val options = SentryOptions()
        config().applyTo(options)

        val event = SentryEvent()
        val passedThrough = options.beforeSend!!(event)

        assertEquals<List<String>?>(emptyList(), passedThrough?.fingerprint)
    }
}
