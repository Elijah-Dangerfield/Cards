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
import io.sentry.kotlin.multiplatform.Attachment
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.protocol.User
import io.sentry.kotlin.multiplatform.protocol.UserFeedback
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

    // The planted Sentry tree, held so captureUserFeedback can dump its
    // in-memory log buffer as an attachment. Null until Sentry initializes.
    private var sentryLogTree: SentryLogTree? = null

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
                // Every feedback carrier event has an identical message
                // ("User feedback" / "Bug report") and no stacktrace, so Sentry
                // would group them all into one issue. Give each its own
                // fingerprint (keyed by a per-feedback id set in
                // captureUserFeedback) so every report is its own issue —
                // individually triageable and resolvable. Other events fall
                // through untouched.
                options.beforeSend = { event ->
                    event.getTag(FEEDBACK_EVENT_TAG)?.let { id ->
                        event.fingerprint = mutableListOf(FEEDBACK_FINGERPRINT, id)
                    }
                    event
                }
            }
        }.onFailure {
            logger.e(it) { scope ->
                scope.tag("environment", config.environment)
                scope.tag("platform", config.platformTag)
                scope.tag("build_type", config.buildTypeTag)
            }
        }.onSuccess {
            val tree = SentryLogTree(
                minBreadcrumbLevel = config.logPolicy.minBreadcrumbLevel,
                minEventLevel = config.logPolicy.minEventLevel,
                minBufferLevel = config.logPolicy.minBufferLevel,
            )
            sentryLogTree = tree
            KLog.plant(tree)
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

    override fun setSession(sessionId: String) {
        // Best-effort, same scope-persistence reasoning as setCurrentRoute:
        // writing the tag on the scope means a later native crash (turned into
        // an event on next launch) still carries the session it happened in.
        if (!Sentry.isEnabled()) return
        Sentry.configureScope { it.setTag(SESSION_ID_KEY, sessionId) }
    }

    override fun setInstallId(installId: String) {
        if (!Sentry.isEnabled()) return
        Sentry.configureScope { it.setTag(INSTALL_ID_KEY, installId) }
    }

    override fun setRoom(code: String?) {
        if (!Sentry.isEnabled()) return
        Sentry.configureScope {
            if (code.isNullOrBlank()) it.removeTag(ROOM_CODE_KEY) else it.setTag(ROOM_CODE_KEY, code)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
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
        // Mint a unique id for this report and stamp it on a LOCAL scope for
        // just the carrier event: beforeSend reads it to fingerprint the event
        // into its own issue (see init), and the in-memory log buffer rides
        // along as an attachment — the fine-grained Debug/Verbose we never ship
        // as breadcrumbs, captured only when the user actually files feedback.
        // Local scope means none of this leaks onto later events.
        val logDump = sentryLogTree?.snapshot()?.takeIf { it.isNotBlank() }
        val feedbackId = Uuid.random().toString()
        val sentryId = Sentry.captureMessage(if (isBugReport) "Bug report" else "User feedback") { scope ->
            scope.setTag(FEEDBACK_EVENT_TAG, feedbackId)
            if (logDump != null) {
                scope.addAttachment(Attachment(logDump.encodeToByteArray(), "session-log.txt", "text/plain"))
            }
        }

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

// Correlation keys mirrored on the backend (OTel span attributes + log
// fields), so the same value queries Sentry, Tempo, and Loki.
private const val SESSION_ID_KEY = "session_id"
private const val INSTALL_ID_KEY = "install_id"
// Mirrors the backend gameplay span attribute `room.code` so feedback during a
// game pivots to that room's server traces/logs.
private const val ROOM_CODE_KEY = "room_code"

// Per-feedback id stamped on the carrier event; `beforeSend` turns it into the
// event fingerprint so each feedback report is its own Sentry issue despite the
// shared "User feedback" / "Bug report" message.
private const val FEEDBACK_EVENT_TAG = "feedback_event"
private const val FEEDBACK_FINGERPRINT = "feedback"

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
        val minEventLevel: LogLevel,
        /**
         * Lowest level retained in the in-memory ring buffer dumped onto user
         * feedback (null = no buffer). Set below [minBreadcrumbLevel] to keep
         * the fine-grained detail we don't ship — debug builds buffer Verbose+,
         * release buffers Debug+ (skips per-frame Verbose churn).
         */
        val minBufferLevel: LogLevel? = null,
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
                    minEventLevel = LogLevel.Error,
                    minBufferLevel = if (buildInfo.isDebug) LogLevel.Verbose else LogLevel.Debug,
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
