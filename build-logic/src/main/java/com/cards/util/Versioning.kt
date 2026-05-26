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

data class SupabaseMetadata(
    val projectId: String,
    val anonKey: String
) {
    val url: String = projectId.takeIf { it.isNotBlank() }
        ?.let { "https://$it.supabase.co" }
        ?: ""
}

data class ServerMetadata(
    /**
     * Per-dev toggle: when true, the client points at the dev's own
     * machine, choosing `http://localhost:8080` on iOS or
     * `http://10.0.2.2:8080` on Android at runtime. When false, the
     * client uses hardcoded dev/prod URLs picked by build type (see
     * `DefaultNetworkConfig`).
     */
    val useLocal: Boolean,
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

fun Project.loadSupabaseMetadata(): SupabaseMetadata {
    val properties = Properties()
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        FileInputStream(localProperties).use(properties::load)
    }

    fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

    val projectId = properties.stringOrNull("supabase.projectId")
        ?: env("SUPABASE_PROJECT_ID")
        ?: "mfozvowjsxdwrslyoyrf"
    val anonKey = properties.stringOrNull("supabase.anonKey")
        ?: env("SUPABASE_ANON_KEY")
        ?: ""

    return SupabaseMetadata(
        projectId = projectId,
        anonKey = anonKey
    )
}

fun BuildConfigExtension.writeSupabaseMetadata(metadata: SupabaseMetadata) {
    buildConfigField("String", "SUPABASE_PROJECT_ID", "\"${metadata.projectId}\"")
    buildConfigField("String", "SUPABASE_URL", "\"${metadata.url}\"")
    buildConfigField("String", "SUPABASE_ANON_KEY", "\"${metadata.anonKey}\"")
}

/**
 * Resolves whether the client should point at the dev's local Ktor
 * server instead of the baked-in dev/prod URLs.
 *
 * `server.useLocal` (boolean) — checked in to `gradle.properties` as
 * `false` (the documented default), overridable per-dev via
 * `local.properties` for ad-hoc local backend work. When true, the
 * client picks `http://localhost:8080` on iOS or `http://10.0.2.2:8080`
 * on Android at runtime based on `BuildInfo.platform`.
 *
 * CI guard: if `CI=true` in the environment and `useLocal` is true, the
 * build fails configuration immediately. This makes accidentally
 * committing `server.useLocal=true` to gradle.properties a loud, fast
 * failure rather than a silent shipped-bug.
 *
 * Typical dev flow: drop `server.useLocal=true` into `local.properties`,
 * resync Gradle, `./gradlew :apps:server:run`. Remove the line to go
 * back to the dev/prod URL.
 */
fun Project.loadServerMetadata(): ServerMetadata {
    val properties = Properties()
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        FileInputStream(localProperties).use(properties::load)
    }

    // local.properties wins; otherwise fall through to gradle.properties
    // (auto-loaded as project properties by Gradle).
    val useLocalRaw = properties.stringOrNull("server.useLocal")
        ?: (findProperty("server.useLocal") as? String)?.takeIf { it.isNotBlank() }
    val useLocal = useLocalRaw?.equals("true", ignoreCase = true) ?: false

    if (useLocal && System.getenv("CI")?.equals("true", ignoreCase = true) == true) {
        error(
            "server.useLocal=true is set but the build is running in CI " +
                "(CI=true). Local-server builds must never land on a shared " +
                "branch. Set server.useLocal=false in gradle.properties / " +
                "local.properties and rerun."
        )
    }

    return ServerMetadata(useLocal = useLocal)
}

fun BuildConfigExtension.writeServerMetadata(metadata: ServerMetadata) {
    buildConfigField("Boolean", "SERVER_USE_LOCAL", metadata.useLocal.toString())
}

private fun Properties.stringOrNull(key: String): String? =
    getProperty(key)?.takeIf { it.isNotBlank() }
