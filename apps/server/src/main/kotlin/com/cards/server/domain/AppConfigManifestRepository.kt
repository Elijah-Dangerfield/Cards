package com.dangerfield.cards.server.domain

import kotlinx.serialization.json.JsonElement

/**
 * Read/write surface for the **in-code config registry** captured per app
 * version (`app_config_manifest`). CI uploads a build's manifest at release
 * time; the admin tool reads it to show what each version shipped with and the
 * in-code default a remote override would replace.
 *
 * Distinct from [AppConfigAdminRepository] (the live, editable DB overrides):
 * the manifest is a historical record of what the *code* shipped, not what the
 * server is serving.
 */
interface AppConfigManifestRepository {

    /**
     * Replace the captured registry for [versionCode] with [entries] (one row
     * per flag). Re-uploading a version is idempotent. Returns the row count.
     */
    suspend fun upsertManifest(versionCode: Int, appVersion: String?, entries: List<ManifestEntry>): Int

    /** Captured versions, newest first, with their flag counts. */
    suspend fun listVersions(): List<ManifestVersion>

    /** The registry for [versionCode], or the latest captured version when null. Empty if none. */
    suspend fun getManifest(versionCode: Int?): List<ManifestEntry>
}

/** One flag in a captured build: its type, in-code default, and optional allowed values. */
data class ManifestEntry(
    val path: String,
    val type: String,
    val default: JsonElement,
    val description: String?,
    val allowedValues: JsonElement?,
)

/** A captured app version and how many flags it recorded. */
data class ManifestVersion(
    val versionCode: Int,
    val appVersion: String?,
    val capturedAtEpochMs: Long,
    val flagCount: Int,
)
