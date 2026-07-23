package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr

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
    rows: List<FlagRow>,
    manifest: ManifestResponse?,
    target: TargetState,
    ctx: AdminCtx,
) {
    H2 { Text("Flags") }
    if (rows.isEmpty()) {
        P(attrs = { classes("muted") }) { Text("No flags yet. Add one below, or upload a version manifest.") }
    } else {
        Table(attrs = { classes("flags") }) {
            Thead {
                Tr {
                    Th { Text("Flag") }
                    Th { Text("Type") }
                    Th { Text(bakedVersionLabel(manifest)?.let { "Baked · $it" } ?: "Baked in app") }
                    Th { Text("Set on server") }
                    Th { Text("This client gets") }
                    Th { Text("Rules") }
                }
            }
            Tbody {
                rows.forEach { row -> FlagTableRows(row, manifest, target, ctx) }
            }
        }
    }
    NewFlagForm(ctx)
}

/** One flag: its scannable table line, plus the expanded detail row. */
@Composable
private fun FlagTableRows(
    row: FlagRow,
    manifest: ManifestResponse?,
    target: TargetState,
    ctx: AdminCtx,
) {
    var open by remember(row.path) { mutableStateOf(false) }
    val drift = row.resolved.inline() != row.default.inline()

    Tr(attrs = { classes("flag-line"); onClick { open = !open } }) {
        Td {
            Div { Span(attrs = { classes("flag-path") }) { Text(row.path) } }
            row.description?.takeIf { it.isNotBlank() }?.let {
                Div(attrs = { classes("flag-desc") }) { Text(it) }
            }
        }
        Td { row.type?.let { Span(attrs = { classes("tag") }) { Text(it) } } }
        Td {
            Span(attrs = { classes(if (row.default != null) "val" else "val-not-set") }) {
                Text(row.default?.inline() ?: "—")
            }
        }
        Td {
            val effective = (parseJsonOrNull((row.base ?: row.default).inline()) as? JsonPrimitive)?.booleanOrNull
            if (row.type == "boolean" && effective != null) {
                // LaunchDarkly-style quick toggle: flips what the server serves
                // (creating the server value if only the baked one exists) —
                // still through the confirm sheet.
                Div(attrs = { classes("row") }) {
                    Switch(checked = effective) {
                        val flipped = JsonPrimitive(!effective)
                        ctx.confirmWrite(
                            title = "Set server value",
                            flagPath = row.path,
                            before = if (row.inDb) row.base.inline() else "not set (baked ${row.default.inline()})",
                            after = flipped.inline(),
                            success = "Set ${row.path} to ${flipped.inline()}",
                            warning = dangerousWarning(row.path, flipped.inline()),
                        ) { ctx.api.upsertFlag(row.path, flipped) }
                    }
                    if (!row.inDb) Span(attrs = { classes("val-not-set") }) { Text("not set") }
                }
            } else if (row.inDb) {
                Span(attrs = { classes("val") }) { Text(row.base.inline()) }
            } else {
                Span(attrs = { classes("val-not-set") }) { Text("not set") }
            }
        }
        Td {
            Span(attrs = { classes(if (drift) "val-drift" else "val") }) { Text(row.resolved.inline()) }
            row.matchedRule?.let {
                Text(" ")
                Span(attrs = { classes("chip") }) { Text("rule #${it.priority}") }
            }
        }
        Td {
            val enabled = row.rules.count { it.enabled }
            if (row.rules.isEmpty()) {
                Span(attrs = { classes("val-not-set") }) { Text("—") }
            } else {
                Span(attrs = { classes("val") }) { Text("$enabled/${row.rules.size}") }
            }
        }
    }
    if (open) {
        Tr {
            Td(attrs = { attr("colspan", "6"); classes("detail-cell") }) {
                FlagDetail(row, manifest, target, ctx)
            }
        }
    }
}

/** A CSS toggle switch. Stops propagation so the table row doesn't expand. */
@Composable
private fun Switch(checked: Boolean, onToggle: () -> Unit) {
    Label(attrs = { classes("switch"); onClick { it.stopPropagation() } }) {
        CheckboxInput(checked) {
            onClick { it.stopPropagation() }
            onInput { onToggle() }
        }
        Span(attrs = { classes("slider") }) {}
    }
}

@Composable
private fun FlagDetail(
    row: FlagRow,
    manifest: ManifestResponse?,
    target: TargetState,
    ctx: AdminCtx,
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
            TypedValueEditor(row.type, row.allowedValues, baseDraft) { baseDraft = it }
            Button(attrs = {
                classes("primary")
                onClick {
                    val parsed = parseTypedValue(row.type, baseDraft)
                    val element = parsed.element
                    if (element == null) {
                        ctx.setStatus(Status(false, parsed.problem ?: "Server value must be valid JSON"))
                        return@onClick
                    }
                    ctx.confirmWrite(
                        title = if (row.inDb) "Change server value" else "Set server value",
                        flagPath = row.path,
                        before = row.base.inline().takeUnless { !row.inDb } ?: "not set",
                        after = element.inline(),
                        success = "Saved server value for ${row.path}",
                        warning = dangerousWarning(row.path, baseDraft),
                    ) { ctx.api.upsertFlag(row.path, element) }
                }
            }) { Text(if (row.inDb) "Save server value" else "Set on server") }
            if (row.inDb) {
                Button(attrs = {
                    classes("danger")
                    onClick {
                        ctx.confirmWrite(
                            title = "Remove server value",
                            flagPath = row.path,
                            before = row.base.inline(),
                            after = "not set",
                            success = "Removed server value for ${row.path} — clients fall back to the baked value",
                        ) { ctx.api.deleteFlag(row.path) }
                    }
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
        row.rules.forEach { rule -> RuleRow(rule, ctx) }
    }

    RuleEditor(row, target, ctx)
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
private fun RuleRow(rule: ConfigRuleDto, ctx: AdminCtx) {
    val sentence = "When ${conditionsSentence(rule.conditions)} → ${rule.value.inline()}"
    Div(attrs = { classes("rule", if (rule.enabled) "on" else "off") }) {
        Div(attrs = { classes("row") }) {
            Span(attrs = { classes("muted") }) { Text("#${rule.priority}") }
            Span { Text("When "); Code { Text(conditionsSentence(rule.conditions)) }; Text(" → "); Code { Text(rule.value.inline()) } }
            Div(attrs = { classes("spacer") }) {}
            Button(attrs = {
                onClick {
                    val enabling = !rule.enabled
                    val request = UpsertRuleRequest(
                        flagPath = rule.flagPath,
                        priority = rule.priority,
                        value = rule.value,
                        conditions = rule.conditions,
                        enabled = enabling,
                        description = rule.description,
                    )
                    ctx.confirmWrite(
                        title = if (enabling) "Enable rule #${rule.priority}" else "Disable rule #${rule.priority}",
                        flagPath = rule.flagPath,
                        before = "$sentence (${if (rule.enabled) "enabled" else "disabled"})",
                        after = "$sentence (${if (enabling) "enabled" else "disabled"})",
                        success = if (enabling) "Enabled rule on ${rule.flagPath}" else "Disabled rule on ${rule.flagPath}",
                        warning = if (enabling) dangerousWarning(rule.flagPath, rule.value.inline()) else null,
                    ) { ctx.api.upsertRule(rule.id, request) }
                }
            }) { Text(if (rule.enabled) "Disable" else "Enable") }
            Button(attrs = {
                classes("danger")
                onClick {
                    ctx.confirmWrite(
                        title = "Delete rule #${rule.priority}",
                        flagPath = rule.flagPath,
                        before = sentence,
                        after = "rule deleted",
                        success = "Deleted rule on ${rule.flagPath}",
                    ) { ctx.api.deleteRule(rule.id) }
                }
            }) { Text("Delete") }
        }
        rule.description?.takeIf { it.isNotBlank() }?.let {
            Div(attrs = { classes("muted") }) { Text(it) }
        }
    }
}

@Composable
private fun NewFlagForm(ctx: AdminCtx) {
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
                        ctx.setStatus(Status(false, "Path required and value must be valid JSON")); return@onClick
                    }
                    val newPath = path.trim()
                    ctx.confirmWrite(
                        title = "Create flag",
                        flagPath = newPath,
                        before = "not set",
                        after = element.inline(),
                        success = "Saved $newPath",
                        warning = dangerousWarning(newPath, value),
                    ) { ctx.api.upsertFlag(newPath, element) }
                    path = ""
                }
            }) { Text("Add") }
        }
    }
}
