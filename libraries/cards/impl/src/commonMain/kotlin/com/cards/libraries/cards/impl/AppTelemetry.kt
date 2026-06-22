package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.core.buildType
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.LogLevel
import com.dangerfield.cards.libraries.core.logging.Logger
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.cards.impl.logging.DevConsoleWriter
import com.dangerfield.cards.libraries.cards.impl.logging.KermitLogTree
import com.dangerfield.cards.libraries.cards.impl.logging.SentryLogTree
import co.touchlab.kermit.Logger as KermitLogger
import co.touchlab.kermit.Severity as KermitSeverity
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.protocol.User
import io.sentry.kotlin.multiplatform.protocol.UserFeedback
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AppTelemetry : Telemetry by ConfiguredTelemetry(
    configProvider = { SentryRuntimeConfig.forApp(BuildInfo) }
)

class IosExtensionTelemetry(
    private val configProvider: () -> SentryRuntimeConfig = { SentryRuntimeConfig.forIosExtension(BuildInfo) }
) : Telemetry by ConfiguredTelemetry(configProvider)

private class ConfiguredTelemetry(
    private val configProvider: () -> SentryRuntimeConfig
) : Telemetry {

    private val logger: Logger = KLog.withTag("Telemetry")
    private var initialized = false

    override fun initialize() {
        if (initialized) return
        initialized = true

        KLog.plant(KermitLogTree())

        // Debug-only: drop Kermit's global min-severity to Verbose so nothing
        // is pre-filtered before reaching any writer. The platform writers
        // (OSLogWriter on iOS, LogcatWriter on Android) handle Info+ natively.
        // [DevConsoleWriter] adds a pretty stdout-only path for Debug-and-
        // below entries, because Android Studio's KMM plugin filters those
        // out of its Run window when running iOS apps. See the writer's
        // header for the full reasoning.
        if (BuildInfo.isDebug) {
            KermitLogger.setMinSeverity(KermitSeverity.Verbose)
            KermitLogger.addLogWriter(DevConsoleWriter())
        }

        val config = configProvider()

        if (!config.isEnabled) {
            logger.i { scope ->
                scope.tag("environment", config.environment)
                scope.tag("platform", config.platformTag)
                "Sentry disabled for ${config.environment}"
            }
            return
        }

        Catching {
            Sentry.init { options ->
                options.dsn = config.dsn
                options.environment = config.environment
                options.release = config.release
                options.sendDefaultPii = config.sendDefaultPii
                options.attachStackTrace = config.attachStacktrace
                options.enableAutoSessionTracking = config.enableAutoSessionTracking
                config.tracesSampleRate?.let { options.tracesSampleRate = it }
                config.profilesSampleRate?.let { options.sampleRate = it }
            }
        }.onFailure {
            logger.e(it) { scope ->
                scope.tag("environment", config.environment)
                scope.tag("platform", config.platformTag)
                scope.tag("build_type", config.buildTypeTag)
            }
        }.onSuccess {
            KLog.plant(
                SentryLogTree(
                    minBreadcrumbLevel = config.logPolicy.minBreadcrumbLevel,
                    minEventLevel = config.logPolicy.minEventLevel
                )
            )
            Sentry.configureScope {
                it.setExtra("platform", config.platformTag)
                it.setExtra("build_type", config.buildTypeTag)
                it.setExtra("release_channel", BuildInfo.releaseChannel)
            }
            logger.i { scope ->
                scope.extra("environment", config.environment)
                scope.extra("platform", config.platformTag)
                scope.extra("build_type", config.buildTypeTag)
                "Sentry initialized for ${config.environment}"
            }
        }
    }

    override fun setUser(
        email: String?,
        name: String?,
        id: String?
    ) {
        Sentry.setUser(
            User(
                id = id,
                email = email,
                username = name
            )
        )
    }

    override fun setCurrentRoute(route: String) {
        // Best-effort: when Sentry isn't initialized (e.g. disabled
        // environment) configureScope has no scope to mutate, so skip quietly
        // rather than logging on every navigation.
        if (!Sentry.isEnabled()) return
        Sentry.configureScope {
            // Tag = searchable/filterable in the issues list; extra = shown on
            // the event detail. The user asked for both.
            it.setTag(ROUTE_KEY, route)
            it.setExtra(ROUTE_KEY, route)
        }
    }

    override fun captureUserFeedback(
        message: String,
        isBugReport: Boolean,
        eventId: String?,
        errorCode: Int?,
        email: String?,
    ) {
        val payload = message.trim()
        if (payload.isBlank()) {
            logger.w {
                it.tag("feedback_type", if (isBugReport) "bug_report" else "feedback")
                "Ignoring empty feedback payload"
            }
            return
        }

        if (!Sentry.isEnabled()) {
            logger.w {
                it.tag("feedback_type", if (isBugReport) "bug_report" else "feedback")
                "Sentry disabled, feedback dropped"
            }
            return
        }

        val typeTag = if (isBugReport) "bug_report" else "feedback"
        val sanitizedEmail = email?.trim()?.takeIf { it.isNotBlank() }

        // The legacy User Feedback API only persists feedback attached to an
        // event Sentry has already ingested — an empty or unknown event id is
        // silently dropped on ingest, which is why feedback never surfaced.
        // `eventId` here is our internal KLog id (or null for general feedback),
        // never a real Sentry id, so mint a carrier event via captureMessage and
        // attach the feedback to that. Mirrors Sentry's documented
        // captureMessage → captureUserFeedback flow. The KLog id / error code
        // ride along in the comment for correlation back to the logs.
        val sentryId = Sentry.captureMessage(if (isBugReport) "Bug report" else "User feedback")

        val feedback = UserFeedback(sentryId).apply {
            comments = buildString {
                if (isBugReport) {
                    errorCode?.let { append("Error code: $it\n") }
                    eventId?.let { append("Log ID: $it\n") }
                    if (errorCode != null || eventId != null) append('\n')
                }
                append(payload)
            }
            sanitizedEmail?.let { this.email = it }
        }

        Sentry.captureUserFeedback(feedback)

        logger.i { scope ->
            scope.tag("feedback_type", typeTag)
            scope.extra("event_id", sentryId.toString())
            if (isBugReport) {
                errorCode?.let { scope.extra("error_code", it) }
            }
            scope.extra("payload_length", payload.length)
            scope.extra("has_email", sanitizedEmail != null)
            "Feedback forwarded to Sentry ($typeTag)"
        }
    }
}

// Scope key for the current navigation route (set via [Telemetry.setCurrentRoute]).
// Shared by the tag and the extra so they read identically in Sentry.
private const val ROUTE_KEY = "route"

// All platforms / build types report to the single `cards` Sentry project.
// The `environment` tag (releaseChannel-platform-buildType) and the
// `platform` extra separate debug vs release and iOS vs Android within it,
// so one DSN is enough. If debug noise floods the project, gate the debug
// branch off in [SentryRuntimeConfig.forApp] rather than minting new DSNs.
private const val CARDS_SENTRY_DSN = "https://2010decd1b11057a4038b99bcd75878b@o327796.ingest.us.sentry.io/4511478399565824"

data class SentryRuntimeConfig(
    val dsn: String,
    val environment: String,
    val release: String,
    val sendDefaultPii: Boolean,
    val attachStacktrace: Boolean,
    val tracesSampleRate: Double?,
    val profilesSampleRate: Double?,
    val platformTag: String,
    val buildTypeTag: String,
    val logPolicy: LogPolicy,
    val enableAutoSessionTracking: Boolean
) {
    val isEnabled: Boolean get() = dsn.isNotBlank()

    data class LogPolicy(
        val minBreadcrumbLevel: LogLevel,
        val minEventLevel: LogLevel
    )

    companion object {
        fun forApp(buildInfo: BuildInfo): SentryRuntimeConfig {
            val platformTag = when (buildInfo.platform) {
                Platform.Android -> "android"
                Platform.iOS -> "ios"
            }
            val buildTypeTag = buildInfo.buildType
            val dsn = CARDS_SENTRY_DSN
            val environment = "${buildInfo.releaseChannel}-$platformTag-$buildTypeTag"
            val release = "cardse@${buildInfo.versionName}+${buildInfo.buildNumber}"
            val tracesSampleRate = if (buildInfo.isDebug) 1.0 else 0.15
            val profilesSampleRate = if (buildInfo.isDebug) 1.0 else 0.05
            val breadcrumbLevel = if (buildInfo.isDebug) LogLevel.Debug else LogLevel.Info
            return SentryRuntimeConfig(
                dsn = dsn,
                environment = environment,
                release = release,
                sendDefaultPii = false,
                attachStacktrace = true,
                tracesSampleRate = tracesSampleRate,
                profilesSampleRate = profilesSampleRate,
                platformTag = platformTag,
                buildTypeTag = buildTypeTag,
                logPolicy = LogPolicy(
                    minBreadcrumbLevel = breadcrumbLevel,
                    minEventLevel = LogLevel.Error
                ),
                enableAutoSessionTracking = true
            )
        }

        fun forIosExtension(buildInfo: BuildInfo): SentryRuntimeConfig {
            val base = forApp(buildInfo)
            val environment = "${buildInfo.releaseChannel}-ios-extension-${buildInfo.buildType}"
            return base.copy(
                environment = environment,
                release = base.release + "-extension",
                tracesSampleRate = if (buildInfo.isDebug) 0.25 else 0.05,
                profilesSampleRate = null,
                logPolicy = LogPolicy(
                    minBreadcrumbLevel = LogLevel.Info,
                    minEventLevel = LogLevel.Error
                ),
                enableAutoSessionTracking = false
            )
        }
    }
}
