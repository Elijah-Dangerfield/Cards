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

private fun Properties.stringOrNull(key: String): String? =
    getProperty(key)?.takeIf { it.isNotBlank() }
