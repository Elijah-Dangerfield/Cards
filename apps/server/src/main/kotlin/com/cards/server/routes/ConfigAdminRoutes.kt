package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.data.AppConfigTargetingEngine
import com.dangerfield.cards.server.data.ConfigSchema
import com.dangerfield.cards.server.data.validateRuleConditions
import com.dangerfield.cards.server.domain.AppConfigAdminRepository
import com.dangerfield.cards.server.domain.AppConfigManifestRepository
import com.dangerfield.cards.server.domain.ConfigAuditRecord
import com.dangerfield.cards.server.domain.ConfigChangeEvent
import com.dangerfield.cards.server.domain.ConfigChangeNotifier
import com.dangerfield.cards.server.domain.ConfigFlagRecord
import com.dangerfield.cards.server.domain.ManifestEntry
import com.dangerfield.cards.server.domain.ManifestVersion
import com.dangerfield.cards.server.domain.RuleConditions
import com.dangerfield.cards.server.domain.TargetingRule
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.http.ClientContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * Token-gated CRUD for remote config, consumed by the local admin web GUI (and
 * usable from any script). Same `X-Admin-Token` gate as the rest of `/v1/admin`.
 *
 * Surface:
 *  - `GET    /v1/admin/config`              — every flag + its rules
 *  - `PUT    /v1/admin/config/flags/{path}` — upsert a flag's base value
 *  - `DELETE /v1/admin/config/flags/{path}` — delete a flag (+ its rules)
 *  - `PUT    /v1/admin/config/rules/{id}`   — upsert a targeting rule
 *  - `DELETE /v1/admin/config/rules/{id}`   — delete a rule
 *  - `GET    /v1/admin/config/audit`        — change log (newest first)
 *  - `PUT    /v1/admin/config/manifest`     — upload a build's in-code registry
 *  - `GET    /v1/admin/config/manifest/versions` — captured versions
 *  - `GET    /v1/admin/config/manifest`     — one version's defaults (latest if unset)
 *  - `POST   /v1/admin/config/resolve`      — preview how flags resolve for a target
 *
 * The optional `X-Admin-Actor` header is recorded on every mutation's audit
 * row so a shared token can still attribute who made a change.
 */
fun Route.configAdminRoutes(
    config: AdminConfig,
    repository: AppConfigAdminRepository,
    manifestRepository: AppConfigManifestRepository,
    notifier: ConfigChangeNotifier = ConfigChangeNotifier {},
) {
    val engine = AppConfigTargetingEngine()

    route("/v1/admin/config") {

        get {
            if (!call.requireAdmin(config)) return@get
            call.respond(
                HttpStatusCode.OK,
                ConfigListResponse(repository.listFlags().map { it.toDto() }),
            )
        }

        put("/flags/{path}") {
            if (!call.requireAdmin(config)) return@put
            val path = call.parameters["path"]?.takeUnless { it.isBlank() }
                ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "invalid_path", "path is required.")
            val body = call.receiveOrNull<UpsertFlagRequest>()
                ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "invalid_body", "Malformed flag body.")
            schema(manifestRepository).validateValue(path, body.value)?.let {
                return@put call.respondProblem(HttpStatusCode.BadRequest, "invalid_value", it)
            }
            val record = repository.upsertFlag(path, body.value, call.actor())
            notifier.changed(ConfigChangeEvent(call.actor(), "update_flag", path, body.value.toString()))
            call.respond(HttpStatusCode.OK, record.toDto())
        }

        delete("/flags/{path}") {
            if (!call.requireAdmin(config)) return@delete
            val path = call.parameters["path"]?.takeUnless { it.isBlank() }
                ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "invalid_path", "path is required.")
            if (repository.deleteFlag(path, call.actor())) {
                notifier.changed(ConfigChangeEvent(call.actor(), "delete_flag", path, null))
                call.respond(HttpStatusCode.OK, OkResponse())
            } else {
                call.respondProblem(HttpStatusCode.NotFound, "not_found", "No such flag.")
            }
        }

        put("/rules/{id}") {
            if (!call.requireAdmin(config)) return@put
            val id = call.parameters["id"]?.toUuidOrNull()
                ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "invalid_id", "id must be a UUID.")
            val body = call.receiveOrNull<UpsertRuleRequest>()
                ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "invalid_body", "Malformed rule body.")
            validateRuleConditions(body.conditions)?.let {
                return@put call.respondProblem(HttpStatusCode.BadRequest, "invalid_conditions", it)
            }
            schema(manifestRepository).validateValue(body.flagPath, body.value)?.let {
                return@put call.respondProblem(HttpStatusCode.BadRequest, "invalid_value", it)
            }
            // A rule needs a flag row to attach to (FK). Rather than make the admin
            // mint a base override by hand, lazily materialize the flag from its
            // shipped manifest default the first time it carries a rule. Only the
            // truly-unknown flag (no DB row, no manifest entry) stays a 409.
            seedFlagFromManifestIfMissing(body.flagPath, repository, manifestRepository, call.actor())
            val rule = TargetingRule(
                id = id,
                flagPath = body.flagPath,
                priority = body.priority,
                value = body.value,
                conditions = body.conditions,
                enabled = body.enabled,
                description = body.description?.takeUnless { it.isBlank() },
            )
            val saved = repository.upsertRule(rule, call.actor())
                ?: return@put call.respondProblem(
                    HttpStatusCode.Conflict,
                    "unknown_flag",
                    "Rule references a flag that doesn't exist and isn't in any manifest: ${body.flagPath}",
                )
            notifier.changed(ConfigChangeEvent(call.actor(), "update_rule", body.flagPath, body.value.toString()))
            call.respond(HttpStatusCode.OK, saved.toDto())
        }

        delete("/rules/{id}") {
            if (!call.requireAdmin(config)) return@delete
            val id = call.parameters["id"]?.toUuidOrNull()
                ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "invalid_id", "id must be a UUID.")
            if (repository.deleteRule(id, call.actor())) {
                notifier.changed(ConfigChangeEvent(call.actor(), "delete_rule", null, "rule $id"))
                call.respond(HttpStatusCode.OK, OkResponse())
            } else {
                call.respondProblem(HttpStatusCode.NotFound, "not_found", "No such rule.")
            }
        }

        get("/audit") {
            if (!call.requireAdmin(config)) return@get
            val flag = call.parameters["flag"]?.takeUnless { it.isBlank() }
            val limit = call.parameters["limit"]?.toIntOrNull() ?: DEFAULT_AUDIT_LIMIT
            call.respond(
                HttpStatusCode.OK,
                AuditListResponse(repository.listAudit(flag, limit).map { it.toDto() }),
            )
        }

        put("/manifest") {
            if (!call.requireAdmin(config)) return@put
            val body = call.receiveOrNull<UploadManifestRequest>()
                ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "invalid_body", "Malformed manifest body.")
            val count = manifestRepository.upsertManifest(
                versionCode = body.versionCode,
                appVersion = body.appVersion,
                entries = body.entries.map { it.toDomain() },
            )
            call.respond(HttpStatusCode.OK, UploadManifestResponse(versionCode = body.versionCode, flagCount = count))
        }

        get("/manifest/versions") {
            if (!call.requireAdmin(config)) return@get
            call.respond(
                HttpStatusCode.OK,
                ManifestVersionsResponse(manifestRepository.listVersions().map { it.toDto() }),
            )
        }

        get("/manifest") {
            if (!call.requireAdmin(config)) return@get
            val requested = call.parameters["version"]?.toIntOrNull()
            val versions = manifestRepository.listVersions()
            val target = requested ?: versions.firstOrNull()?.versionCode
            val meta = versions.firstOrNull { it.versionCode == target }
            val entries = manifestRepository.getManifest(target)
            call.respond(
                HttpStatusCode.OK,
                ManifestResponse(
                    versionCode = target,
                    appVersion = meta?.appVersion,
                    entries = entries.map { it.toDto() },
                ),
            )
        }

        post("/resolve") {
            if (!call.requireAdmin(config)) return@post
            val body = call.receiveOrNull<ResolveRequest>()
                ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "invalid_body", "Malformed resolve body.")
            call.respond(HttpStatusCode.OK, resolveFlags(body, repository, manifestRepository, engine))
        }
    }
}

/**
 * Evaluate every known flag against a synthetic target. The flag set is the
 * union of the live DB flags and the requested version's manifest, so a flag
 * that only exists in code (no DB row yet) still shows up with its in-code
 * default. For each flag we report the in-code default, the DB base, which rule
 * (if any) won, and the resolved value.
 */
private suspend fun resolveFlags(
    request: ResolveRequest,
    repository: AppConfigAdminRepository,
    manifestRepository: AppConfigManifestRepository,
    engine: AppConfigTargetingEngine,
): ResolveResponse {
    val context = ClientContext(
        platform = parsePlatform(request.platform),
        appVersion = request.appVersion?.takeUnless { it.isBlank() },
        buildNumber = request.buildNumber,
        // An empty locale list means "no locale preference", so locale-scoped
        // rules simply won't match — the honest preview when none is supplied.
        preferredLocales = request.locale?.takeUnless { it.isBlank() }?.let { listOf(it) }.orEmpty(),
        countryCode = request.countryCode?.takeUnless { it.isBlank() }?.uppercase(),
        installId = request.installId?.takeUnless { it.isBlank() },
    )
    val userId = request.userId?.takeUnless { it.isBlank() }
        ?.let { runCatching { UserId(UUID.fromString(it)) }.getOrNull() }

    val dbByPath = repository.listFlags().associateBy { it.path }
    val manifestByPath = manifestRepository.getManifest(request.buildNumber).associateBy { it.path }

    val paths = (dbByPath.keys + manifestByPath.keys).toSortedSet()
    val flags = paths.map { path ->
        val flag = dbByPath[path]
        val entry = manifestByPath[path]
        val rules = flag?.rules.orEmpty()
        val match = engine.firstMatchingRule(rules, context, userId, path)
        val base = flag?.value
        ResolvedFlagDto(
            path = path,
            type = entry?.type,
            default = entry?.default,
            base = base,
            matchedRule = match?.let { MatchedRuleDto(it.id.toString(), it.priority, it.description) },
            resolved = match?.value ?: base ?: entry?.default,
        )
    }
    return ResolveResponse(flags)
}

private fun parsePlatform(raw: String?): ClientContext.Platform = when (raw?.lowercase()) {
    "ios" -> ClientContext.Platform.iOS
    "android" -> ClientContext.Platform.Android
    else -> ClientContext.Platform.Other
}

/**
 * Materialize a flag's DB row from its shipped manifest default when it doesn't
 * exist yet, so a targeting rule can attach without the operator first minting a
 * base override. No-op when the flag already has a DB row; skipped when no
 * manifest entry exists (the rule write then fails its FK check, surfacing the
 * honest "unknown flag" conflict).
 */
private suspend fun seedFlagFromManifestIfMissing(
    flagPath: String,
    repository: AppConfigAdminRepository,
    manifestRepository: AppConfigManifestRepository,
    actor: String,
) {
    if (repository.listFlags().any { it.path == flagPath }) return
    val default = manifestRepository.getManifest(null).firstOrNull { it.path == flagPath }?.default ?: return
    repository.upsertFlag(flagPath, default, actor)
}

/** Build the type/allowed-values schema from the latest captured manifest. */
private suspend fun schema(manifestRepository: AppConfigManifestRepository): ConfigSchema =
    ConfigSchema.from(manifestRepository.getManifest(null))

private const val DEFAULT_AUDIT_LIMIT = 100

private suspend fun ApplicationCall.requireAdmin(config: AdminConfig): Boolean {
    if (authenticatedAsAdmin(config)) return true
    respondProblem(HttpStatusCode.Unauthorized, "unauthorized", "Missing or invalid admin token.")
    return false
}

private fun ApplicationCall.actor(): String =
    request.header("X-Admin-Actor")?.takeUnless { it.isBlank() } ?: "admin"

private fun String.toUuidOrNull(): UUID? = try {
    UUID.fromString(this)
} catch (_: IllegalArgumentException) {
    null
}

private suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, code: String, message: String) =
    respond(status, problemEnvelope(code, message))

private fun problemEnvelope(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = try {
    receive<T>()
} catch (_: BadRequestException) {
    null
}

// ---------- DTOs ----------

@Serializable
private data class ConfigListResponse(val flags: List<ConfigFlagDto>)

@Serializable
private data class ConfigFlagDto(
    val path: String,
    val value: JsonElement,
    val updatedAtEpochMs: Long,
    val rules: List<ConfigRuleDto>,
)

@Serializable
private data class ConfigRuleDto(
    val id: String,
    val flagPath: String,
    val priority: Int,
    val value: JsonElement,
    val conditions: RuleConditions,
    val enabled: Boolean,
    val description: String?,
)

@Serializable
private data class UpsertFlagRequest(val value: JsonElement)

@Serializable
private data class UpsertRuleRequest(
    val flagPath: String,
    val priority: Int,
    val value: JsonElement,
    val conditions: RuleConditions = RuleConditions(),
    val enabled: Boolean = true,
    val description: String? = null,
)

@Serializable
private data class AuditListResponse(val entries: List<ConfigAuditDto>)

@Serializable
private data class ConfigAuditDto(
    val id: String,
    val atEpochMs: Long,
    val actor: String,
    val action: String,
    val flagPath: String?,
    val before: JsonElement?,
    val after: JsonElement?,
)

@Serializable
private data class OkResponse(val ok: Boolean = true)

// ---------- Manifest DTOs ----------

@Serializable
private data class ManifestEntryDto(
    val path: String,
    val type: String,
    val default: JsonElement,
    val description: String? = null,
    val allowedValues: JsonElement? = null,
)

@Serializable
private data class UploadManifestRequest(
    val versionCode: Int,
    val appVersion: String? = null,
    val entries: List<ManifestEntryDto>,
)

@Serializable
private data class UploadManifestResponse(val versionCode: Int, val flagCount: Int)

@Serializable
private data class ManifestVersionDto(
    val versionCode: Int,
    val appVersion: String?,
    val capturedAtEpochMs: Long,
    val flagCount: Int,
)

@Serializable
private data class ManifestVersionsResponse(val versions: List<ManifestVersionDto>)

@Serializable
private data class ManifestResponse(
    val versionCode: Int?,
    val appVersion: String?,
    val entries: List<ManifestEntryDto>,
)

// ---------- Resolve DTOs ----------

@Serializable
private data class ResolveRequest(
    val platform: String? = null,
    val appVersion: String? = null,
    val buildNumber: Int? = null,
    val countryCode: String? = null,
    val locale: String? = null,
    val userId: String? = null,
    val installId: String? = null,
)

@Serializable
private data class MatchedRuleDto(val id: String, val priority: Int, val description: String?)

@Serializable
private data class ResolvedFlagDto(
    val path: String,
    val type: String?,
    val default: JsonElement?,
    val base: JsonElement?,
    val matchedRule: MatchedRuleDto?,
    val resolved: JsonElement?,
)

@Serializable
private data class ResolveResponse(val flags: List<ResolvedFlagDto>)

private fun ManifestEntryDto.toDomain() = ManifestEntry(
    path = path,
    type = type,
    default = default,
    description = description,
    allowedValues = allowedValues,
)

private fun ManifestEntry.toDto() = ManifestEntryDto(
    path = path,
    type = type,
    default = default,
    description = description,
    allowedValues = allowedValues,
)

private fun ManifestVersion.toDto() = ManifestVersionDto(
    versionCode = versionCode,
    appVersion = appVersion,
    capturedAtEpochMs = capturedAtEpochMs,
    flagCount = flagCount,
)

private fun ConfigFlagRecord.toDto() = ConfigFlagDto(
    path = path,
    value = value,
    updatedAtEpochMs = updatedAtEpochMs,
    rules = rules.map { it.toDto() },
)

private fun TargetingRule.toDto() = ConfigRuleDto(
    id = id.toString(),
    flagPath = flagPath,
    priority = priority,
    value = value,
    conditions = conditions,
    enabled = enabled,
    description = description,
)

private fun ConfigAuditRecord.toDto() = ConfigAuditDto(
    id = id.toString(),
    atEpochMs = atEpochMs,
    actor = actor,
    action = action,
    flagPath = flagPath,
    before = before,
    after = after,
)
