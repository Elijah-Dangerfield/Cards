package com.dangerfield.cards.util

import com.github.gmazzo.buildconfig.BuildConfigExtension
import org.gradle.api.Project
import java.io.FileInputStream
import java.util.Properties

private const val DEFAULT_APPLICATION_ID = "com.dangerfield.cards"
private const val DEFAULT_VERSION_NAME = "0.0.1"
private const val DEFAULT_VERSION_CODE = 1
private const val DEFAULT_RELEASE_CHANNEL = "dev"
private const val DEFAULT_BUILD_NUMBER = 1

data class VersionMetadata(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val releaseChannel: String,
    val buildNumber: Int
) {
    val releaseDisplay: String = "$versionName ($buildNumber)"
}

data class ServerMetadata(
    /**
     * Per-dev toggle: when true, the client points at the dev's own
     * machine, choosing `http://localhost:8080` on iOS or
     * `http://10.0.2.2:8080` on Android at runtime. When false, the
     * client uses the [AppEnvironment] URL picked by build type (see
     * `DefaultNetworkConfig`).
     */
    val useLocal: Boolean,
    /**
     * Build-time environment override: `""` (default — pick by build type,
     * debug→dev / release→prod), `"dev"`, or `"prod"`. Emitted as
     * `CardsBuildConfig.TARGET_ENV` and read by `AppEnvironment.current`.
     * CI-guarded so an override can't land on a shared branch.
     */
    val targetEnv: String,
)

fun Project.loadVersionMetadata(): VersionMetadata {
    val properties = Properties()
    val metadataFile = rootProject.file("versions.properties")
    if (metadataFile.exists()) {
        FileInputStream(metadataFile).use(properties::load)
    }

    fun Properties.string(key: String, defaultValue: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() } ?: defaultValue

    fun Properties.int(key: String, defaultValue: Int): Int =
        string(key, defaultValue.toString()).toIntOrNull() ?: defaultValue

    val applicationId = properties.string("applicationId", DEFAULT_APPLICATION_ID)
    val versionName = properties.string("versionName", DEFAULT_VERSION_NAME)
    val versionCode = properties.int("versionCode", DEFAULT_VERSION_CODE)
    val releaseChannel = properties.string("releaseChannel", DEFAULT_RELEASE_CHANNEL)
    val buildNumber = properties.int("buildNumber", DEFAULT_BUILD_NUMBER)

    return VersionMetadata(
        applicationId = applicationId,
        versionName = versionName,
        versionCode = versionCode,
        releaseChannel = releaseChannel,
        buildNumber = buildNumber
    )
}

fun BuildConfigExtension.writeCommonMetadata(metadata: VersionMetadata) {
    buildConfigField("String", "APPLICATION_ID", "\"${metadata.applicationId}\"")
    buildConfigField("String", "VERSION_NAME", "\"${metadata.versionName}\"")
    buildConfigField("Int", "VERSION_CODE", metadata.versionCode.toString())
    buildConfigField("String", "RELEASE_CHANNEL", "\"${metadata.releaseChannel}\"")
    buildConfigField("Int", "BUILD_NUMBER", metadata.buildNumber.toString())
}

/**
 * Resolves the client's environment plumbing: the `server.useLocal` local
 * override and the `cards.targetEnv` dev/prod override. Both are read from
 * `local.properties` first (per-dev, uncommitted), falling through to
 * `gradle.properties` project properties.
 *
 * `server.useLocal` (boolean, default false) — when true the client points at
 * the dev's own machine (`localhost:8080` iOS / `10.0.2.2:8080` Android).
 *
 * `cards.targetEnv` (`""` | `"dev"` | `"prod"`, default `""`) — forces the
 * backend environment regardless of build type. Empty means "pick by build
 * type" (debug→dev, release→prod), which is what `AppEnvironment.current` does.
 *
 * CI guard: if `CI=true` and either override is set, the build fails
 * configuration immediately. This makes an accidentally-committed
 * `server.useLocal=true` or `cards.targetEnv=…` a loud, fast failure rather
 * than a silently mis-targeted shipped build.
 *
 * Typical dev flow: drop `server.useLocal=true` (or `cards.targetEnv=prod`)
 * into `local.properties`, resync Gradle. Remove the line to go back to the
 * build-type default.
 */
fun Project.loadServerMetadata(): ServerMetadata {
    val properties = Properties()
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        FileInputStream(localProperties).use(properties::load)
    }

    fun resolve(key: String): String? = properties.stringOrNull(key)
        ?: (findProperty(key) as? String)?.takeIf { it.isNotBlank() }

    val isCi = System.getenv("CI")?.equals("true", ignoreCase = true) == true

    val useLocal = resolve("server.useLocal")?.equals("true", ignoreCase = true) ?: false
    if (useLocal && isCi) {
        error(
            "server.useLocal=true is set but the build is running in CI " +
                "(CI=true). Local-server builds must never land on a shared " +
                "branch. Set server.useLocal=false in gradle.properties / " +
                "local.properties and rerun."
        )
    }

    val targetEnv = resolve("cards.targetEnv")?.trim()?.lowercase().orEmpty()
    if (targetEnv !in setOf("", "dev", "prod")) {
        error("cards.targetEnv must be unset, 'dev', or 'prod' (got '$targetEnv').")
    }
    if (targetEnv.isNotEmpty() && isCi) {
        error(
            "cards.targetEnv=$targetEnv is set but the build is running in CI " +
                "(CI=true). Env overrides must never land on a shared branch — a " +
                "release build already picks prod by build type. Unset " +
                "cards.targetEnv and rerun."
        )
    }

    return ServerMetadata(useLocal = useLocal, targetEnv = targetEnv)
}

fun BuildConfigExtension.writeServerMetadata(metadata: ServerMetadata) {
    buildConfigField("Boolean", "SERVER_USE_LOCAL", metadata.useLocal.toString())
    buildConfigField("String", "TARGET_ENV", "\"${metadata.targetEnv}\"")
}

private fun Properties.stringOrNull(key: String): String? =
    getProperty(key)?.takeIf { it.isNotBlank() }
