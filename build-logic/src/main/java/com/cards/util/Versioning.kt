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
     * The explicit base URL the client should use when `useLocal` is
     * false. Always populated (gradle.properties carries the team default).
     */
    val baseUrl: String,
    /**
     * Per-dev toggle: when true, the client ignores [baseUrl] and resolves
     * to `http://localhost:8080` on iOS or `http://10.0.2.2:8080` on
     * Android at runtime, so a single flag works for both targets.
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
 * Resolves Cards server connection settings from gradle properties, with
 * two independent knobs:
 *
 * `server.useLocal` (boolean, per-dev) — when true, the client targets
 *   the dev's own machine at runtime: `http://localhost:8080` on iOS,
 *   `http://10.0.2.2:8080` on Android. One flag, both platforms.
 *   Resolution: `local.properties` only. (Doesn't belong in
 *   gradle.properties — this is a per-dev convenience.)
 *
 * `server.baseUrl` (string) — explicit URL for when `useLocal` is false
 *   or the local presets don't fit (staging, ngrok, teammate's IP).
 *   Resolution precedence:
 *     1. `server.baseUrl` in `local.properties` (per-dev override)
 *     2. `CARDS_SERVER_BASE_URL` env var (CI / shell override)
 *     3. `server.baseUrl` in `gradle.properties` (team default, checked in)
 *     4. Hardcoded fallback — only hit if gradle.properties is deleted.
 *
 * Typical dev flow: drop `server.useLocal=true` into `local.properties`,
 * resync Gradle, run `./gradlew :apps:server:run`. Remove the line to go
 * back to Fly.
 */
fun Project.loadServerMetadata(): ServerMetadata {
    val properties = Properties()
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        FileInputStream(localProperties).use(properties::load)
    }

    fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

    val useLocal = properties.stringOrNull("server.useLocal")
        ?.equals("true", ignoreCase = true)
        ?: false

    val baseUrl = properties.stringOrNull("server.baseUrl")
        ?: env("CARDS_SERVER_BASE_URL")
        ?: (findProperty("server.baseUrl") as? String)?.takeIf { it.isNotBlank() }
        ?: "https://cards-server-dev.fly.dev"

    return ServerMetadata(baseUrl = baseUrl, useLocal = useLocal)
}

fun BuildConfigExtension.writeServerMetadata(metadata: ServerMetadata) {
    buildConfigField("String", "SERVER_BASE_URL", "\"${metadata.baseUrl}\"")
    buildConfigField("Boolean", "SERVER_USE_LOCAL", metadata.useLocal.toString())
}

private fun Properties.stringOrNull(key: String): String? =
    getProperty(key)?.takeIf { it.isNotBlank() }
