package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput

/** Mutable, observable form state for one targeting rule. */
internal class RuleDraft {
    var priority by mutableStateOf("0")
    var value by mutableStateOf("true")
    var platforms by mutableStateOf("")
    var countries by mutableStateOf("")
    var locales by mutableStateOf("")
    var userAllow by mutableStateOf("")
    var userDeny by mutableStateOf("")
    var minVersionCode by mutableStateOf("")
    var maxVersionCode by mutableStateOf("")
    var minAppVersion by mutableStateOf("")
    var minAppVersionInclusive by mutableStateOf(true)
    var maxAppVersion by mutableStateOf("")
    var maxAppVersionInclusive by mutableStateOf(true)
    var rolloutPercent by mutableStateOf("")
    var description by mutableStateOf("")

    fun toConditions() = RuleConditions(
        platforms = csvSet(platforms),
        countries = csvSet(countries)?.map { it.uppercase() }?.toSet(),
        locales = csvSet(locales)?.map { it.lowercase() }?.toSet(),
        userAllow = csvSet(userAllow),
        userDeny = csvSet(userDeny),
        minVersionCode = minVersionCode.trim().toIntOrNull(),
        maxVersionCode = maxVersionCode.trim().toIntOrNull(),
        minAppVersion = minAppVersion.trim().ifBlank { null },
        minAppVersionInclusive = minAppVersionInclusive,
        maxAppVersion = maxAppVersion.trim().ifBlank { null },
        maxAppVersionInclusive = maxAppVersionInclusive,
        rolloutPercent = rolloutPercent.trim().toIntOrNull(),
    )
}

/** Seed a draft from the current lens so "for this target" is one click. */
private fun draftForTarget(row: FlagRow, target: TargetState): RuleDraft = RuleDraft().apply {
    if (target.platform.isNotBlank()) platforms = target.platform
    if (target.country.isNotBlank()) countries = target.country
    if (target.locale.isNotBlank()) locales = target.locale
    if (target.userId.isNotBlank()) userAllow = target.userId
    // A version lens defaults to "this version and up".
    if (target.appVersion.isNotBlank()) minAppVersion = target.appVersion
    // Default the value to the opposite of the in-code default, the common intent.
    value = when (row.default.inline()) {
        "false" -> "true"
        "true" -> "false"
        else -> row.default.inline().takeUnless { it == "—" } ?: "true"
    }
}

@Composable
internal fun RuleEditor(
    row: FlagRow,
    target: TargetState,
    api: AdminApi,
    scope: CoroutineScope,
    setStatus: (Status) -> Unit,
    reload: () -> Unit,
) {
    // null = closed; otherwise the draft being edited.
    var draft by remember(row.path) { mutableStateOf<RuleDraft?>(null) }

    val current = draft
    if (current == null) {
        Div(attrs = { classes("row"); style { property("margin-top", "8px") } }) {
            Button(attrs = { classes("primary"); onClick { draft = draftForTarget(row, target) } }) {
                Text("+ Add rule for this target")
            }
            Button(attrs = { onClick { draft = RuleDraft() } }) { Text("+ Add blank rule") }
        }
        return
    }

    Div(attrs = { classes("rule", "editing") }) {
        Div(attrs = { classes("grid") }) {
            Label { Text("priority") }
            TextInput(current.priority) { onInput { current.priority = it.value } }

            Label { Text("value to set") }
            ValueField(row, current.value) { current.value = it }

            Label { Text("platforms") }
            TextInput(current.platforms) { onInput { current.platforms = it.value }; placeholder("android,ios,other") }

            Label { Text("app version min") }
            VersionBound(
                version = current.minAppVersion,
                inclusive = current.minAppVersionInclusive,
                inclusiveLabel = "≥",
                exclusiveLabel = ">",
                onVersion = { current.minAppVersion = it },
                onInclusive = { current.minAppVersionInclusive = it },
            )

            Label { Text("app version max") }
            VersionBound(
                version = current.maxAppVersion,
                inclusive = current.maxAppVersionInclusive,
                inclusiveLabel = "≤",
                exclusiveLabel = "<",
                onVersion = { current.maxAppVersion = it },
                onInclusive = { current.maxAppVersionInclusive = it },
            )

            Label { Text("countries") }
            TextInput(current.countries) { onInput { current.countries = it.value }; placeholder("US,CA") }
            Label { Text("locales") }
            TextInput(current.locales) { onInput { current.locales = it.value }; placeholder("en,es") }
            Label { Text("user allow") }
            TextInput(current.userAllow) { onInput { current.userAllow = it.value }; placeholder("uuid,uuid") }
            Label { Text("user deny") }
            TextInput(current.userDeny) { onInput { current.userDeny = it.value }; placeholder("uuid,uuid") }
            Label { Text("build code min") }
            TextInput(current.minVersionCode) { onInput { current.minVersionCode = it.value }; placeholder("e.g. 42") }
            Label { Text("build code max") }
            TextInput(current.maxVersionCode) { onInput { current.maxVersionCode = it.value } }
            Label { Text("rollout %") }
            TextInput(current.rolloutPercent) { onInput { current.rolloutPercent = it.value }; placeholder("0-100") }
            Label { Text("description") }
            TextInput(current.description) { onInput { current.description = it.value } }
        }

        // Live preview of the clause the operator is building.
        Div(attrs = { classes("muted"); style { property("margin", "6px 0") } }) {
            Text("When ")
            Span(attrs = { classes("val") }) { Text(conditionsSentence(current.toConditions())) }
            Text(" → ")
            Span(attrs = { classes("val") }) { Text(current.value.ifBlank { "?" }) }
        }

        Div(attrs = { classes("row") }) {
            Button(attrs = {
                classes("primary")
                onClick {
                    val element = parseJsonOrNull(current.value)
                    val priorityInt = current.priority.trim().toIntOrNull()
                    if (element == null || priorityInt == null) {
                        setStatus(Status(false, "Priority must be an int and value valid JSON")); return@onClick
                    }
                    dangerousWarning(row.path, current.value)?.let { if (!confirmDialog(it)) return@onClick }
                    val request = UpsertRuleRequest(
                        flagPath = row.path,
                        priority = priorityInt,
                        value = element,
                        conditions = current.toConditions(),
                        enabled = true,
                        description = current.description.trim().ifBlank { null },
                    )
                    // A rule needs its flag to exist (FK). Create the override
                    // from the in-code default first if there's no DB row yet.
                    scope.launchOp(setStatus, reload, "Added rule to ${row.path}") {
                        if (!row.inDb) {
                            val seed = row.default ?: parseJsonOrNull("null")!!
                            api.upsertFlag(row.path, seed)
                        }
                        api.upsertRule(randomUuid(), request)
                    }
                    draft = null
                }
            }) { Text("Save rule") }
            Button(attrs = { onClick { draft = null } }) { Text("Cancel") }
        }
    }
}

/** A semver bound: "[≥|>] [1.0.1]". The checkbox flips inclusive/exclusive. */
@Composable
private fun VersionBound(
    version: String,
    inclusive: Boolean,
    inclusiveLabel: String,
    exclusiveLabel: String,
    onVersion: (String) -> Unit,
    onInclusive: (Boolean) -> Unit,
) {
    Div(attrs = { classes("row") }) {
        Button(attrs = {
            classes("op")
            onClick { onInclusive(!inclusive) }
        }) { Text(if (inclusive) inclusiveLabel else exclusiveLabel) }
        TextInput(version) { onInput { onVersion(it.value) }; placeholder("e.g. 1.0.1") }
    }
}

/** Type-aware value picker: booleans and enum-like flags get buttons; else JSON text. */
@Composable
private fun ValueField(row: FlagRow, value: String, onValue: (String) -> Unit) {
    when {
        row.type == "boolean" -> Div(attrs = { classes("row") }) {
            listOf("true", "false").forEach { v ->
                Button(attrs = {
                    if (value.trim() == v) classes("primary")
                    onClick { onValue(v) }
                }) { Text(v) }
            }
        }

        row.allowedValues != null -> Div(attrs = { classes("row") }) {
            row.allowedValues.forEach { v ->
                val json = "\"$v\""
                Button(attrs = {
                    if (value.trim() == json) classes("primary")
                    onClick { onValue(json) }
                }) { Text(v) }
            }
        }

        else -> TextInput(value) { onInput { onValue(it.value) }; placeholder("JSON e.g. 6, \"off\"") }
    }
}
