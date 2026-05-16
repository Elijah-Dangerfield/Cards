package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.core.buildType
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.LogLevel
import com.dangerfield.cards.libraries.core.logging.Logger
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.cards.impl.logging.KermitLogTree
import com.dangerfield.cards.libraries.cards.impl.logging.SentryLogTree
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.protocol.SentryId
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
                options.debug = config.isDebugLoggingEnabled
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

    override fun captureUserFeedback(
        message: String,
        isBugReport: Boolean,
        eventId: String?,
        errorCode: Int?
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
        val sentryId = eventId?.let { Catching { SentryId(it) }.getOrNull() } ?: SentryId.EMPTY_ID
        val feedback = UserFeedback(sentryId).apply {
            comments = buildString {
                if (isBugReport && errorCode != null) {
                    append("Error code: $errorCode\n\n")
                }
                append(payload)
            }
        }

        Sentry.captureUserFeedback(feedback)

        logger.i { scope ->
            scope.tag("feedback_type", typeTag)
            scope.extra("event_id", sentryId.toString())
            if (isBugReport) {
                errorCode?.let { scope.extra("error_code", it) }
            }
            scope.extra("payload_length", payload.length)
            "Feedback forwarded to Sentry ($typeTag)"
        }
    }
}

private const val ANDROID_DEBUG_DSN_PLACEHOLDER = "https://527ff39be0de65d7d96cfc0976f34393@o4510490770079744.ingest.us.sentry.io/4510494550589440"
private const val ANDROID_RELEASE_DSN_PLACEHOLDER = "https://0bc66272a354ec2a2c550ef435782ee4@o4510490770079744.ingest.us.sentry.io/4510494555832320"
private const val IOS_DEBUG_DSN_PLACEHOLDER = "https://59d54ea15ef9826ecfe6e50835e1fa35@o4510490770079744.ingest.us.sentry.io/4510494554259456"
private const val IOS_RELEASE_DSN_PLACEHOLDER = "https://af45ba8caf0a26ae529f08ba375df917@o4510490770079744.ingest.us.sentry.io/4510494557798400"

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
    val isDebugLoggingEnabled: Boolean,
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
            val dsn = when (buildInfo.platform) {
                Platform.Android -> if (buildInfo.isDebug) ANDROID_DEBUG_DSN_PLACEHOLDER else ANDROID_RELEASE_DSN_PLACEHOLDER
                Platform.iOS -> if (buildInfo.isDebug) IOS_DEBUG_DSN_PLACEHOLDER else IOS_RELEASE_DSN_PLACEHOLDER
            }
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
                isDebugLoggingEnabled = false, // TODO link this to a QA option
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
