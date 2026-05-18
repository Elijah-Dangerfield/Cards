package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.server.config.SentryConfig
import io.sentry.Sentry
import io.sentry.SentryOptions
import org.slf4j.LoggerFactory

/**
 * Centralised Sentry setup. Called once at startup BEFORE any other plugin
 * so subsequent code paths can rely on `Sentry.captureException(...)` and
 * `Sentry.captureMessage(...)` being live.
 *
 * No-op when [SentryConfig.dsn] is null/blank: the SDK stays uninitialized,
 * every `Sentry.captureXxx` call becomes a cheap no-op, and we log a single
 * boot-time warning. This is the V1 deploy mode — the DSN gets set per
 * environment via `fly secrets set SENTRY_DSN=...` once the Sentry project
 * exists.
 *
 * `environment` and `release` give us per-env filtering ("issues in dev"
 * vs "issues in prod") and "this issue was fixed in release X" attribution.
 * V1 reads them straight from the env so the host owns the values; in
 * production, Fly sets `FLY_APP_NAME=cards-server-dev|cards-server` and
 * the deploy workflow can derive `SENTRY_RELEASE` from the git SHA.
 *
 * **Never log [SentryConfig.dsn] verbatim.** It encodes a project secret
 * suffix and pushing it to logs is the same as leaking it.
 */
fun installSentry(config: SentryConfig) {
    val logger = LoggerFactory.getLogger("Sentry")
    val dsn = config.dsn?.takeUnless { it.isBlank() }
    if (dsn == null) {
        logger.warn(
            "SENTRY_DSN not set — server error reporting disabled. " +
                "Set the DSN via `fly secrets set SENTRY_DSN=https://…@sentry.io/…` to enable.",
        )
        return
    }

    Sentry.init { options: SentryOptions ->
        options.dsn = dsn
        options.environment = config.environment
        options.release = config.release
        // Production tracing is disabled by default — sampling adds cost +
        // PII risk that should be a separate, deliberate decision once we
        // have real traffic. Errors and breadcrumbs are still captured.
        options.tracesSampleRate = 0.0
        options.isAttachServerName = true
        options.isAttachThreads = false
        options.isEnableUncaughtExceptionHandler = true
    }
    logger.info(
        "Sentry initialised (environment={}, release={})",
        config.environment,
        config.release ?: "<unset>",
    )
}

/**
 * Forwards an exception to Sentry if it's been initialised; no-op otherwise.
 * Use this from places that want to add server-side breadcrumbs without
 * relying on the StatusPages-driven unhandled-exception path (e.g. when
 * a 503 is intentionally returned but still worth investigating).
 */
fun captureToSentry(throwable: Throwable, context: String? = null) {
    if (!Sentry.isEnabled()) return
    if (context != null) {
        Sentry.configureScope { scope -> scope.setTag("context", context) }
    }
    Sentry.captureException(throwable)
}
