package com.dangerfield.cards.admin

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.engine.js.Js
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Wire shapes mirroring the server's `ConfigAdminRoutes` DTOs. Kept as a small
 * standalone copy (this module shares no code with the server) so the admin
 * tool stays a single, dependency-light JS target.
 */
@Serializable
data class RuleConditions(
    val platforms: Set<String>? = null,
    val minVersionCode: Int? = null,
    val maxVersionCode: Int? = null,
    val minAppVersion: String? = null,
    val minAppVersionInclusive: Boolean = true,
    val maxAppVersion: String? = null,
    val maxAppVersionInclusive: Boolean = true,
    val countries: Set<String>? = null,
    val locales: Set<String>? = null,
    val userAllow: Set<String>? = null,
    val userDeny: Set<String>? = null,
    val rolloutPercent: Int? = null,
)

@Serializable
data class ConfigRuleDto(
    val id: String,
    val flagPath: String,
    val priority: Int,
    val value: JsonElement,
    val conditions: RuleConditions,
    val enabled: Boolean,
    val description: String? = null,
)

@Serializable
data class ConfigFlagDto(
    val path: String,
    val value: JsonElement,
    val updatedAtEpochMs: Long,
    val rules: List<ConfigRuleDto>,
)

@Serializable
private data class ConfigListResponse(val flags: List<ConfigFlagDto>)

@Serializable
private data class UpsertFlagRequest(val value: JsonElement)

@Serializable
data class UpsertRuleRequest(
    val flagPath: String,
    val priority: Int,
    val value: JsonElement,
    val conditions: RuleConditions = RuleConditions(),
    val enabled: Boolean = true,
    val description: String? = null,
)

@Serializable
data class ConfigAuditDto(
    val id: String,
    val atEpochMs: Long,
    val actor: String,
    val action: String,
    val flagPath: String? = null,
    val before: JsonElement? = null,
    val after: JsonElement? = null,
)

@Serializable
private data class AuditListResponse(val entries: List<ConfigAuditDto>)

// ---------- Manifest (per-version in-code defaults) ----------

@Serializable
data class ManifestEntryDto(
    val path: String,
    val type: String,
    val default: JsonElement,
    val description: String? = null,
    val allowedValues: JsonElement? = null,
)

@Serializable
data class ManifestVersionDto(
    val versionCode: Int,
    val appVersion: String? = null,
    val capturedAtEpochMs: Long,
    val flagCount: Int,
)

@Serializable
private data class ManifestVersionsResponse(val versions: List<ManifestVersionDto>)

@Serializable
data class ManifestResponse(
    val versionCode: Int? = null,
    val appVersion: String? = null,
    val entries: List<ManifestEntryDto>,
)

// ---------- Resolve (per-target preview) ----------

@Serializable
data class ResolveRequest(
    val platform: String? = null,
    val appVersion: String? = null,
    val buildNumber: Int? = null,
    val countryCode: String? = null,
    val locale: String? = null,
    val userId: String? = null,
    val installId: String? = null,
)

@Serializable
data class MatchedRuleDto(val id: String, val priority: Int, val description: String? = null)

@Serializable
data class ResolvedFlagDto(
    val path: String,
    val type: String? = null,
    val default: JsonElement? = null,
    val base: JsonElement? = null,
    val matchedRule: MatchedRuleDto? = null,
    val resolved: JsonElement? = null,
)

@Serializable
private data class ResolveResponse(val flags: List<ResolvedFlagDto>)

val adminJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/**
 * Thin client over `/v1/admin/config`. One instance per connection (a given
 * base URL + token + actor). Throws [AdminApiException] with the server's
 * problem message on a non-2xx so the UI can show why a write was refused.
 */
class AdminApi(
    baseUrl: String,
    private val token: String,
    private val actor: String,
) {
    private val base = baseUrl.trimEnd('/')
    private val client = HttpClient(Js) {
        install(ContentNegotiation) { json(adminJson) }
    }

    suspend fun listFlags(): List<ConfigFlagDto> =
        client.get("$base/v1/admin/config") { auth() }
            .require<ConfigListResponse>().flags

    suspend fun upsertFlag(path: String, value: JsonElement) {
        client.put("$base/v1/admin/config/flags/${encode(path)}") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(UpsertFlagRequest(value))
        }.requireOk()
    }

    suspend fun deleteFlag(path: String) {
        client.delete("$base/v1/admin/config/flags/${encode(path)}") { auth() }.requireOk()
    }

    suspend fun upsertRule(id: String, request: UpsertRuleRequest) {
        client.put("$base/v1/admin/config/rules/$id") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireOk()
    }

    suspend fun deleteRule(id: String) {
        client.delete("$base/v1/admin/config/rules/$id") { auth() }.requireOk()
    }

    suspend fun listAudit(flag: String?): List<ConfigAuditDto> {
        val suffix = if (flag.isNullOrBlank()) "" else "?flag=${encode(flag)}"
        return client.get("$base/v1/admin/config/audit$suffix") { auth() }
            .require<AuditListResponse>().entries
    }

    suspend fun listManifestVersions(): List<ManifestVersionDto> =
        client.get("$base/v1/admin/config/manifest/versions") { auth() }
            .require<ManifestVersionsResponse>().versions

    suspend fun getManifest(versionCode: Int?): ManifestResponse {
        val suffix = if (versionCode == null) "" else "?version=$versionCode"
        return client.get("$base/v1/admin/config/manifest$suffix") { auth() }.require()
    }

    suspend fun resolve(request: ResolveRequest): List<ResolvedFlagDto> =
        client.post("$base/v1/admin/config/resolve") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.require<ResolveResponse>().flags

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header("X-Admin-Token", token)
        header("X-Admin-Actor", actor)
    }

    private suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.require(): T {
        requireOk()
        return body()
    }

    private suspend fun io.ktor.client.statement.HttpResponse.requireOk() {
        if (status.isSuccess()) return
        val message = Catching {
            adminJson.parseToJsonElement(bodyAsText())
        }.getOrNull()?.let { describeError(it) } ?: "HTTP ${status.value}"
        throw AdminApiException(status, message)
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    private fun encode(segment: String): String = segment.encodeURIComponentSafe()
}

class AdminApiException(val status: HttpStatusCode, message: String) :
    RuntimeException("${status.value}: $message")

private fun describeError(body: JsonElement): String? = Catching {
    val error = (body as? kotlinx.serialization.json.JsonObject)?.get("error")
        as? kotlinx.serialization.json.JsonObject
    error?.get("message")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
}.getOrNull()

/** Browser-native URI component encoding (path segments can hold dots/etc.). */
private fun String.encodeURIComponentSafe(): String = encodeURIComponent(this)

private fun encodeURIComponent(value: String): String =
    js("encodeURIComponent(value)") as String
