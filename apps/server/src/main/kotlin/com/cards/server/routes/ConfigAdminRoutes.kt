package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.domain.AppConfigAdminRepository
import com.dangerfield.cards.server.domain.ConfigAuditRecord
import com.dangerfield.cards.server.domain.ConfigFlagRecord
import com.dangerfield.cards.server.domain.RuleConditions
import com.dangerfield.cards.server.domain.TargetingRule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
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
 *
 * The optional `X-Admin-Actor` header is recorded on every mutation's audit
 * row so a shared token can still attribute who made a change.
 */
fun Route.configAdminRoutes(
    config: AdminConfig,
    repository: AppConfigAdminRepository,
) {
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
            val record = repository.upsertFlag(path, body.value, call.actor())
            call.respond(HttpStatusCode.OK, record.toDto())
        }

        delete("/flags/{path}") {
            if (!call.requireAdmin(config)) return@delete
            val path = call.parameters["path"]?.takeUnless { it.isBlank() }
                ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "invalid_path", "path is required.")
            if (repository.deleteFlag(path, call.actor())) {
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
                    "Rule references a flag that doesn't exist: ${body.flagPath}",
                )
            call.respond(HttpStatusCode.OK, saved.toDto())
        }

        delete("/rules/{id}") {
            if (!call.requireAdmin(config)) return@delete
            val id = call.parameters["id"]?.toUuidOrNull()
                ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "invalid_id", "id must be a UUID.")
            if (repository.deleteRule(id, call.actor())) {
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
    }
}

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
