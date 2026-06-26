package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.renderComposable

fun main() {
    renderComposable(rootElementId = "root") { App() }
}

private data class Status(val ok: Boolean, val message: String)

@Composable
private fun App() {
    var selectedEnv by remember { mutableStateOf(AdminEnv.Dev) }
    var actor by remember { mutableStateOf("admin") }
    var api by remember { mutableStateOf<AdminApi?>(null) }
    var flags by remember { mutableStateOf<List<ConfigFlagDto>>(emptyList()) }
    var status by remember { mutableStateOf<Status?>(null) }
    val scope = rememberCoroutineScope()

    fun setStatus(s: Status) { status = s }

    fun reload() {
        val current = api ?: return
        scope.launch {
            runCatching { flags = current.listFlags() }
                .onSuccess { setStatus(Status(true, "Loaded ${flags.size} flag(s)")) }
                .onFailure { setStatus(Status(false, it.message ?: "Failed to load")) }
        }
    }

    H1 { Text("Cards · Remote Config Admin") }
    P(attrs = { classes("sub") }) {
        Text("Pick an environment, then view and edit its flags.")
    }

    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("row") }) {
            Label { Text("Environment") }
            AdminEnv.entries.forEach { env ->
                Button(attrs = {
                    classes(if (env == selectedEnv) "primary" else "")
                    onClick { selectedEnv = env }
                }) { Text(env.displayName.uppercase()) }
            }
            Span(attrs = { classes("muted") }) { Text(selectedEnv.baseUrl) }
        }
        if (selectedEnv.token.isBlank()) {
            Div(attrs = { classes("status", "err") }) {
                Text("No token set for ${selectedEnv.displayName}. Add it to admin-tokens.local.properties and rebuild.")
            }
        }
        Div(attrs = { classes("grid"); style { property("margin-top", "8px") } }) {
            Label { Text("Actor") }
            TextInput(actor) { onInput { actor = it.value }; placeholder("your name (audited)") }
        }
        Div(attrs = { classes("row"); style { property("margin-top", "10px") } }) {
            Button(attrs = {
                classes("primary")
                onClick {
                    val env = selectedEnv
                    if (env.token.isBlank()) {
                        setStatus(Status(false, "No token configured for ${env.displayName}."))
                        return@onClick
                    }
                    api = AdminApi(env.baseUrl, env.token, actor.trim().ifBlank { "admin" })
                    reload()
                }
            }) { Text("Connect / Reload") }
        }
    }

    status?.let { s ->
        Div(attrs = { classes("status", if (s.ok) "ok" else "err") }) { Text(s.message) }
    }

    val activeApi = api
    if (activeApi != null) {
        NewFlagForm(activeApi, scope, ::setStatus, ::reload)

        H2 { Text("Flags") }
        if (flags.isEmpty()) {
            P(attrs = { classes("muted") }) { Text("No flags yet. Add one above.") }
        }
        flags.forEach { flag ->
            FlagCard(flag, activeApi, scope, ::setStatus, ::reload)
        }

        AuditPanel(activeApi, scope, ::setStatus)
    }
}

@Composable
private fun NewFlagForm(
    api: AdminApi,
    scope: CoroutineScope,
    setStatus: (Status) -> Unit,
    reload: () -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("false") }

    H2 { Text("Add flag") }
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("row") }) {
            TextInput(path) { onInput { path = it.value }; placeholder("dotted.path e.g. social.enabled") }
            TextInput(value) { onInput { value = it.value }; placeholder("JSON value e.g. false, 6, \"off\"") }
            Button(attrs = {
                classes("primary")
                onClick {
                    val element = parseJsonOrNull(value)
                    if (path.isBlank() || element == null) {
                        setStatus(Status(false, "Path required and value must be valid JSON"))
                        return@onClick
                    }
                    scope.launchOp(setStatus, reload, "Saved ${path.trim()}") { api.upsertFlag(path.trim(), element) }
                    path = ""
                }
            }) { Text("Add") }
        }
    }
}

@Composable
private fun FlagCard(
    flag: ConfigFlagDto,
    api: AdminApi,
    scope: CoroutineScope,
    setStatus: (Status) -> Unit,
    reload: () -> Unit,
) {
    var draft by remember(flag.value.toString()) { mutableStateOf(flag.value.toString()) }

    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("row") }) {
            Span(attrs = { classes("flag-path") }) { Text(flag.path) }
            Span(attrs = { classes("muted") }) { Text("base value:") }
            TextInput(draft) { onInput { draft = it.value } }
            Button(attrs = {
                onClick {
                    val element = parseJsonOrNull(draft)
                    if (element == null) {
                        setStatus(Status(false, "Value must be valid JSON")); return@onClick
                    }
                    scope.launchOp(setStatus, reload, "Updated ${flag.path}") { api.upsertFlag(flag.path, element) }
                }
            }) { Text("Save") }
            Button(attrs = {
                classes("danger")
                onClick {
                    scope.launchOp(setStatus, reload, "Deleted ${flag.path}") { api.deleteFlag(flag.path) }
                }
            }) { Text("Delete flag") }
        }

        if (flag.rules.isNotEmpty()) {
            Div(attrs = { style { property("margin-top", "8px") } }) {
                flag.rules.forEach { rule -> RuleRow(rule, api, scope, setStatus, reload) }
            }
        }
        AddRuleForm(flag.path, api, scope, setStatus, reload)
    }
}

@Composable
private fun RuleRow(
    rule: ConfigRuleDto,
    api: AdminApi,
    scope: CoroutineScope,
    setStatus: (Status) -> Unit,
    reload: () -> Unit,
) {
    Div(attrs = { classes("rule", if (rule.enabled) "" else "off") }) {
        Div(attrs = { classes("row") }) {
            Span(attrs = { classes("muted") }) { Text("#${rule.priority}") }
            Span { Text("→ "); Code { Text(rule.value.toString()) } }
            Span(attrs = { classes("muted") }) { Text(summarize(rule.conditions)) }
            Button(attrs = {
                onClick {
                    val request = UpsertRuleRequest(
                        flagPath = rule.flagPath,
                        priority = rule.priority,
                        value = rule.value,
                        conditions = rule.conditions,
                        enabled = !rule.enabled,
                        description = rule.description,
                    )
                    scope.launchOp(setStatus, reload, if (rule.enabled) "Disabled rule" else "Enabled rule") {
                        api.upsertRule(rule.id, request)
                    }
                }
            }) { Text(if (rule.enabled) "Disable" else "Enable") }
            Button(attrs = {
                classes("danger")
                onClick {
                    scope.launchOp(setStatus, reload, "Deleted rule") { api.deleteRule(rule.id) }
                }
            }) { Text("Delete") }
        }
        rule.description?.takeIf { it.isNotBlank() }?.let {
            Div(attrs = { classes("muted") }) { Text(it) }
        }
    }
}

@Composable
private fun AddRuleForm(
    flagPath: String,
    api: AdminApi,
    scope: CoroutineScope,
    setStatus: (Status) -> Unit,
    reload: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var priority by remember { mutableStateOf("0") }
    var value by remember { mutableStateOf("true") }
    var platforms by remember { mutableStateOf("") }
    var countries by remember { mutableStateOf("") }
    var locales by remember { mutableStateOf("") }
    var userAllow by remember { mutableStateOf("") }
    var userDeny by remember { mutableStateOf("") }
    var minVersion by remember { mutableStateOf("") }
    var maxVersion by remember { mutableStateOf("") }
    var rollout by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    if (!open) {
        Button(attrs = { onClick { open = true } }) { Text("+ Add rule") }
        return
    }

    Div(attrs = { classes("rule") }) {
        Div(attrs = { classes("grid") }) {
            Label { Text("priority") }
            TextInput(priority) { onInput { priority = it.value } }
            Label { Text("value (JSON)") }
            TextInput(value) { onInput { value = it.value } }
            Label { Text("platforms") }
            TextInput(platforms) { onInput { platforms = it.value }; placeholder("android,ios,other") }
            Label { Text("countries") }
            TextInput(countries) { onInput { countries = it.value }; placeholder("US,CA") }
            Label { Text("locales") }
            TextInput(locales) { onInput { locales = it.value }; placeholder("en,es") }
            Label { Text("user allow") }
            TextInput(userAllow) { onInput { userAllow = it.value }; placeholder("uuid,uuid") }
            Label { Text("user deny") }
            TextInput(userDeny) { onInput { userDeny = it.value }; placeholder("uuid,uuid") }
            Label { Text("min version code") }
            TextInput(minVersion) { onInput { minVersion = it.value } }
            Label { Text("max version code") }
            TextInput(maxVersion) { onInput { maxVersion = it.value } }
            Label { Text("rollout %") }
            TextInput(rollout) { onInput { rollout = it.value }; placeholder("0-100") }
            Label { Text("description") }
            TextInput(description) { onInput { description = it.value } }
        }
        Div(attrs = { classes("row"); style { property("margin-top", "8px") } }) {
            Button(attrs = {
                classes("primary")
                onClick {
                    val element = parseJsonOrNull(value)
                    val priorityInt = priority.trim().toIntOrNull()
                    if (element == null || priorityInt == null) {
                        setStatus(Status(false, "Priority must be an int and value valid JSON")); return@onClick
                    }
                    val conditions = RuleConditions(
                        platforms = csvSet(platforms),
                        countries = csvSet(countries)?.map { it.uppercase() }?.toSet(),
                        locales = csvSet(locales)?.map { it.lowercase() }?.toSet(),
                        userAllow = csvSet(userAllow),
                        userDeny = csvSet(userDeny),
                        minVersionCode = minVersion.trim().toIntOrNull(),
                        maxVersionCode = maxVersion.trim().toIntOrNull(),
                        rolloutPercent = rollout.trim().toIntOrNull(),
                    )
                    val request = UpsertRuleRequest(
                        flagPath = flagPath,
                        priority = priorityInt,
                        value = element,
                        conditions = conditions,
                        enabled = true,
                        description = description.trim().ifBlank { null },
                    )
                    scope.launchOp(setStatus, reload, "Added rule to $flagPath") {
                        api.upsertRule(randomUuid(), request)
                    }
                    open = false
                }
            }) { Text("Save rule") }
            Button(attrs = { onClick { open = false } }) { Text("Cancel") }
        }
    }
}

@Composable
private fun AuditPanel(
    api: AdminApi,
    scope: CoroutineScope,
    setStatus: (Status) -> Unit,
) {
    var entries by remember { mutableStateOf<List<ConfigAuditDto>?>(null) }

    H2 { Text("Audit log") }
    Button(attrs = {
        onClick {
            scope.launch {
                runCatching { entries = api.listAudit(flag = null) }
                    .onFailure { setStatus(Status(false, it.message ?: "Failed to load audit")) }
            }
        }
    }) { Text("Load audit log") }

    entries?.let { list ->
        if (list.isEmpty()) {
            P(attrs = { classes("muted") }) { Text("No changes recorded yet.") }
        }
        list.forEach { entry ->
            Div(attrs = { classes("rule") }) {
                Div(attrs = { classes("row") }) {
                    Span(attrs = { classes("muted") }) { Text(entry.action) }
                    entry.flagPath?.let { Span(attrs = { classes("flag-path") }) { Text(it) } }
                    Span(attrs = { classes("muted") }) { Text("by ${entry.actor}") }
                }
                val before = entry.before?.toString()
                val after = entry.after?.toString()
                if (before != null || after != null) {
                    Pre { Text(listOfNotNull(before?.let { "− $it" }, after?.let { "+ $it" }).joinToString("\n")) }
                }
            }
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private fun parseJsonOrNull(raw: String): JsonElement? =
    runCatching { adminJson.parseToJsonElement(raw.trim()) }.getOrNull()

private fun csvSet(raw: String): Set<String>? =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet().ifEmpty { null }

private fun summarize(c: RuleConditions): String {
    val parts = buildList {
        c.platforms?.let { add("platform∈${it.joinToString("/")}") }
        c.countries?.let { add("country∈${it.joinToString("/")}") }
        c.locales?.let { add("locale∈${it.joinToString("/")}") }
        c.minVersionCode?.let { add("vc≥$it") }
        c.maxVersionCode?.let { add("vc≤$it") }
        c.userAllow?.let { add("allow:${it.size}") }
        c.userDeny?.let { add("deny:${it.size}") }
        c.rolloutPercent?.let { add("rollout:$it%") }
    }
    return if (parts.isEmpty()) "(always)" else parts.joinToString(", ")
}

/** Run a suspend mutation, then reload + report. Centralizes the try/status dance. */
private fun CoroutineScope.launchOp(
    setStatus: (Status) -> Unit,
    reload: () -> Unit,
    success: String,
    block: suspend () -> Unit,
) {
    launch {
        runCatching { block() }
            .onSuccess { setStatus(Status(true, success)); reload() }
            .onFailure { setStatus(Status(false, it.message ?: "Request failed")) }
    }
}

private fun randomUuid(): String = js("crypto.randomUUID()") as String
