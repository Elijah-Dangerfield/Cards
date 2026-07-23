package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput

/**
 * The targeting axes the operator views/edits through: a synthetic client. Held
 * as observable state so the flag table re-resolves and the rule editor can
 * pre-fill conditions from whatever lens is currently set.
 */
internal class TargetState {
    var platform by mutableStateOf("android")
    var appVersion by mutableStateOf("")
    var buildNumber by mutableStateOf("")
    var country by mutableStateOf("")
    var locale by mutableStateOf("")
    var userId by mutableStateOf("")
    var installId by mutableStateOf("")

    fun toRequest(): ResolveRequest = ResolveRequest(
        platform = platform.ifBlank { null },
        appVersion = appVersion.trim().ifBlank { null },
        buildNumber = buildNumber.trim().toIntOrNull(),
        countryCode = country.trim().ifBlank { null },
        locale = locale.trim().ifBlank { null },
        userId = userId.trim().ifBlank { null },
        installId = installId.trim().ifBlank { null },
    )
}

private val PLATFORMS = listOf("android" to "Android", "ios" to "iOS", "other" to "Other")

/** "android · v0.1.0 (1) · US · en" — the current lens in one glance. */
private fun TargetState.summary(): String = listOfNotNull(
    platform.ifBlank { null },
    appVersion.trim().ifBlank { null }?.let { v ->
        buildNumber.trim().ifBlank { null }?.let { "v$v ($it)" } ?: "v$v"
    } ?: buildNumber.trim().ifBlank { null }?.let { "build $it" },
    country.trim().ifBlank { null },
    locale.trim().ifBlank { null },
    userId.trim().ifBlank { null }?.let { "user ${it.take(8)}…" },
).joinToString(" · ").ifBlank { "any client" }

/**
 * The lens controls, collapsed to a one-line summary by default. Pick a
 * platform, optionally snap to a captured version, and fill any axis.
 * "Resolve" re-evaluates every flag through this target.
 */
@Composable
internal fun TargetBar(
    target: TargetState,
    versions: List<ManifestVersionDto>,
    onResolve: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Div(attrs = { classes("panel", "target") }) {
        Div(attrs = { classes("row", "target-summary"); onClick { expanded = !expanded } }) {
            Span(attrs = { classes("muted") }) { Text("Previewing as") }
            Span { Text(target.summary()) }
            Span(attrs = { classes("muted") }) { Text(if (expanded) "▾" else "▸ edit") }
            Div(attrs = { classes("spacer") }) {}
            Button(attrs = {
                classes("primary")
                onClick { it.stopPropagation(); onResolve() }
            }) { Text("Resolve") }
        }

        if (!expanded) return@Div

        Div(attrs = { classes("row"); style { property("margin-top", "10px") } }) {
            Label { Text("Platform") }
            PLATFORMS.forEach { (value, label) ->
                Button(attrs = {
                    if (target.platform == value) classes("primary")
                    onClick { target.platform = value }
                }) { Text(label) }
            }
        }

        if (versions.isNotEmpty()) {
            Div(attrs = { classes("row"); style { property("margin-top", "8px") } }) {
                Label { Text("Version") }
                versions.forEach { v ->
                    val label = v.appVersion?.let { "$it (${v.versionCode})" } ?: "build ${v.versionCode}"
                    Button(attrs = {
                        if (target.buildNumber == v.versionCode.toString()) classes("primary")
                        onClick {
                            target.buildNumber = v.versionCode.toString()
                            target.appVersion = v.appVersion.orEmpty()
                        }
                    }) { Text(label) }
                }
            }
        }

        Div(attrs = { classes("grid"); style { property("margin-top", "8px") } }) {
            Label { Text("app version") }
            TextInput(target.appVersion) { onInput { target.appVersion = it.value }; placeholder("e.g. 1.0.1") }
            Label { Text("build number") }
            TextInput(target.buildNumber) { onInput { target.buildNumber = it.value }; placeholder("e.g. 42") }
            Label { Text("country") }
            TextInput(target.country) { onInput { target.country = it.value }; placeholder("US") }
            Label { Text("locale") }
            TextInput(target.locale) { onInput { target.locale = it.value }; placeholder("en") }
            Label { Text("user id") }
            TextInput(target.userId) { onInput { target.userId = it.value }; placeholder("uuid (for allow/deny + rollout)") }
            Label { Text("install id") }
            TextInput(target.installId) { onInput { target.installId = it.value }; placeholder("uuid (for rollout)") }
        }

        Div(attrs = { classes("row"); style { property("margin-top", "10px") } }) {
            Span(attrs = { classes("muted", "hint") }) { Text("Resolve shows what a client matching these axes receives.") }
        }
    }
}
