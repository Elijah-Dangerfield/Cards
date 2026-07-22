package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput

/** One flag merged from the live DB rows, the resolve preview, and the manifest. */
internal data class FlagRow(
    val path: String,
    val type: String?,
    val default: JsonElement?,
    val allowedValues: List<String>?,
    val description: String?,
    val base: JsonElement?,
    val rules: List<ConfigRuleDto>,
    val matchedRule: MatchedRuleDto?,
    val resolved: JsonElement?,
    val inDb: Boolean,
)

private fun JsonElement?.allowedStrings(): List<String>? =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotEmpty() }

internal fun buildFlagRows(
    flags: List<ConfigFlagDto>,
    resolvedByPath: Map<String, ResolvedFlagDto>,
    manifestByPath: Map<String, ManifestEntryDto>,
): List<FlagRow> {
    val dbByPath = flags.associateBy { it.path }
    val paths = (dbByPath.keys + manifestByPath.keys + resolvedByPath.keys).distinct().sorted()
    return paths.map { path ->
        val flag = dbByPath[path]
        val entry = manifestByPath[path]
        val resolved = resolvedByPath[path]
        FlagRow(
            path = path,
            type = entry?.type ?: resolved?.type,
            default = entry?.default ?: resolved?.default,
            allowedValues = entry?.allowedValues.allowedStrings(),
            description = entry?.description,
            base = flag?.value ?: resolved?.base,
            rules = flag?.rules.orEmpty(),
            matchedRule = resolved?.matchedRule,
            resolved = resolved?.resolved ?: flag?.value ?: entry?.default,
            inDb = flag != null,
        )
    }
}

/**
 * "Baked into v0.1.0 (build 1)" — names the app version whose compiled-in
 * defaults we're showing. Null when no manifest is loaded (older server, or
 * none uploaded yet); callers fall back to a generic label.
 */
internal fun bakedVersionLabel(manifest: ManifestResponse?): String? {
    val code = manifest?.versionCode ?: return null
    val version = manifest.appVersion
    return if (version.isNullOrBlank()) "build $code" else "v$version (build $code)"
}

@Composable
internal fun FlagsView(
    flags: List<ConfigFlagDto>,
    resolvedByPath: Map<String, ResolvedFlagDto>,
    manifest: ManifestResponse?,
    target: TargetState,
    api: AdminApi,
    scope: CoroutineScope,
    setStatus: (Status) -> Unit,
    reload: () -> Unit,
) {
    val rows = buildFlagRows(flags, resolvedByPath, manifest?.entries.orEmpty().associateBy { it.path })
    H2 { Text("Flags · resolved for the target above") }
    if (rows.isEmpty()) {
        P(attrs = { classes("muted") }) { Text("No flags yet. Add one below, or upload a version manifest.") }
    }
    rows.forEach { row -> FlagCard(row, manifest, target, api, scope, setStatus, reload) }
    NewFlagForm(api, scope, setStatus, reload)
}

@Composable
private fun FlagCard(
    row: FlagRow,
    manifest: ManifestResponse?,
    target: TargetState,
    api: AdminApi,
    scope: CoroutineScope,
    setStatus: (Status) -> Unit,
    reload: () -> Unit,
) {
    var open by remember(row.path) { mutableStateOf(false) }
    val drift = row.resolved.inline() != row.default.inline()

    Div(attrs = { classes("panel", "flag") }) {
        Div(attrs = { classes("flag-head"); onClick { open = !open } }) {
            Span(attrs = { classes("flag-path") }) { Text(row.path) }
            row.type?.let { Span(attrs = { classes("tag") }) { Text(it) } }
            Span(attrs = { classes("muted") }) { Text(if (open) "▾" else "▸") }
            Div(attrs = { classes("spacer") }) {}
            Span(attrs = { classes("muted") }) { Text("this client gets") }
            Span(attrs = { classes(if (drift) "val-drift" else "val") }) { Text(row.resolved.inline()) }
            row.matchedRule?.let {
                Span(attrs = { classes("chip") }) { Text("rule #${it.priority}") }
            }
        }
        if (open) FlagDetail(row, manifest, target, api, scope, setStatus, reload)
    }
}

@Composable
private fun FlagDetail(
    row: FlagRow,
    manifest: ManifestResponse?,
    target: TargetState,
    api: AdminApi,
    scope: CoroutineScope,
    setStatus: (Status) -> Unit,
    reload: () -> Unit,
) {
    row.description?.takeIf { it.isNotBlank() }?.let {
        P(attrs = { classes("muted"); style { property("margin", "4px 0 8px") } }) { Text(it) }
    }

    // The three layers of every flag, spelled out: what the client build ships
    // with, what (if anything) we've set on the server, and what a client
    // matching the target lens actually receives.
    Div(attrs = { classes("layers") }) {
        val bakedLabel = bakedVersionLabel(manifest)?.let { "baked into $it" } ?: "baked into app"
        when {
            row.default != null -> Layer(bakedLabel, row.default.inline(), "compiled into the app — read-only here")
            manifest == null -> Layer(bakedLabel, "unknown", "no manifest on this server")
            else -> Layer(bakedLabel, "—", "not in this version's manifest")
        }
        if (row.inDb) {
            Layer("set on server", row.base.inline(), "replaces the baked value for everyone, unless a rule matches")
        } else {
            Layer("set on server", "not set", "no server value — clients use the baked value", notSet = true)
        }
        Layer("this client gets", row.resolved.inline(), "resolved for the target above")
    }

    // Server-value editor. Without one the baked value is served; setting one
    // overrides it for every client that no rule matches — the remote retune
    // layer, no release needed. The baked value above stays read-only.
    var baseDraft by remember(row.path, row.base) {
        mutableStateOf((row.base ?: row.default).inline().takeUnless { it == "—" } ?: "null")
    }
    Div(attrs = { style { property("margin-top", "8px") } }) {
        Span(attrs = { classes("muted") }) {
            Text(
                if (row.inDb) {
                    "Set on server — edit, or remove so clients fall back to the baked value"
                } else {
                    "Nothing set on server — clients use the baked value. Set one to change it remotely without a release."
                },
            )
        }
        Div(attrs = { classes("row"); style { property("margin-top", "6px") } }) {
            Label { Text("server value") }
            TextInput(baseDraft) { onInput { baseDraft = it.value } }
            Button(attrs = {
                classes("primary")
                onClick {
                    val element = parseJsonOrNull(baseDraft)
                    if (element == null) { setStatus(Status(false, "Server value must be valid JSON")); return@onClick }
                    dangerousWarning(row.path, baseDraft)?.let { if (!confirmDialog(it)) return@onClick }
                    scope.launchOp(setStatus, reload, "Saved server value for ${row.path}") {
                        api.upsertFlag(row.path, element)
                    }
                }
            }) { Text(if (row.inDb) "Save server value" else "Set on server") }
            if (row.inDb) {
                Button(attrs = {
                    classes("danger")
                    onClick { scope.launchOp(setStatus, reload, "Removed server value for ${row.path}") { api.deleteFlag(row.path) } }
                }) { Text("Remove server value") }
            }
        }
    }

    // Targeting rules.
    Div(attrs = { style { property("margin-top", "12px") } }) {
        Span(attrs = { classes("muted") }) { Text("Targeting rules — first match wins, in priority order") }
        if (row.rules.isEmpty()) {
            P(attrs = { classes("muted") }) { Text("No rules. Every client gets the server value, or the baked value if nothing is set.") }
        }
        row.rules.forEach { rule -> RuleRow(rule, api, scope, setStatus, reload) }
    }

    RuleEditor(row, target, api, scope, setStatus, reload)
}

@Composable
private fun Layer(label: String, value: String, hint: String, notSet: Boolean = false) {
    Div(attrs = { classes("layer") }) {
        Span(attrs = { classes("muted") }) { Text(label) }
        Span(attrs = { classes(if (notSet) "val-not-set" else "val") }) { Text(value) }
        Span(attrs = { classes("muted", "hint") }) { Text(hint) }
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
    Div(attrs = { classes("rule", if (rule.enabled) "on" else "off") }) {
        Div(attrs = { classes("row") }) {
            Span(attrs = { classes("muted") }) { Text("#${rule.priority}") }
            Span { Text("When "); Code { Text(conditionsSentence(rule.conditions)) }; Text(" → "); Code { Text(rule.value.inline()) } }
            Div(attrs = { classes("spacer") }) {}
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
                onClick { scope.launchOp(setStatus, reload, "Deleted rule") { api.deleteRule(rule.id) } }
            }) { Text("Delete") }
        }
        rule.description?.takeIf { it.isNotBlank() }?.let {
            Div(attrs = { classes("muted") }) { Text(it) }
        }
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

    H2 { Text("Add a brand-new flag") }
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("row") }) {
            TextInput(path) { onInput { path = it.value }; placeholder("dotted.path e.g. social.enabled") }
            TextInput(value) { onInput { value = it.value }; placeholder("JSON value e.g. false, 6, \"off\"") }
            Button(attrs = {
                classes("primary")
                onClick {
                    val element = parseJsonOrNull(value)
                    if (path.isBlank() || element == null) {
                        setStatus(Status(false, "Path required and value must be valid JSON")); return@onClick
                    }
                    val newPath = path.trim()
                    scope.launchOp(setStatus, reload, "Saved $newPath") { api.upsertFlag(newPath, element) }
                    path = ""
                }
            }) { Text("Add") }
        }
    }
}
