package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput

private const val MODE_PATH = "upgrade.maintenanceMode"
private const val MESSAGE_PATH = "upgrade.maintenanceMessage"
private const val MIN_VERSION_PATH = "upgrade.minSupportedVersionCode"

/**
 * The emergency flags, pinned above the tabs so incident response never
 * involves scrolling a flag list: maintenance mode (lockout/banner), the
 * maintenance message, and the force-upgrade floor. Big obvious state, and
 * every flip still goes through the confirm sheet.
 */
@Composable
internal fun KillSwitchPanel(rows: List<FlagRow>, manifest: ManifestResponse?, ctx: AdminCtx) {
    val mode = rows.find { it.path == MODE_PATH }
    val message = rows.find { it.path == MESSAGE_PATH }
    val minVersion = rows.find { it.path == MIN_VERSION_PATH }
    if (mode == null && message == null && minVersion == null) return

    val modeValue = mode?.resolved.stringContent() ?: "off"
    val stateClass = when (modeValue) {
        "blocking" -> "kill-blocking"
        "banner" -> "kill-banner"
        else -> "kill-clear"
    }
    val stateLabel = when (modeValue) {
        "blocking" -> "MAINTENANCE: BLOCKING — all users are locked out"
        "banner" -> "MAINTENANCE: BANNER — all users see the maintenance banner"
        else -> "ALL CLEAR — no maintenance, app is open"
    }

    Div(attrs = { classes("panel", "kill-panel", stateClass) }) {
        Div(attrs = { classes("kill-state") }) { Text(stateLabel) }

        mode?.let { row ->
            KillRow(row, "maintenance mode") {
                val options = row.allowedValues ?: listOf("off", "banner", "blocking")
                options.forEach { option ->
                    Button(attrs = {
                        if (option == modeValue) classes("primary")
                        onClick {
                            if (option == modeValue) return@onClick
                            val json = JsonPrimitive(option).toString()
                            ctx.confirmWrite(
                                title = "Set maintenance mode",
                                flagPath = row.path,
                                before = serverValueLabel(row),
                                after = json,
                                success = "Maintenance mode set to $option",
                                warning = dangerousWarning(row.path, json),
                            ) { ctx.api.upsertFlag(row.path, JsonPrimitive(option)) }
                        }
                    }) { Text(option) }
                }
            }
        }

        message?.let { row ->
            var draft by remember(row.path, row.base) {
                mutableStateOf(row.resolved.stringContent() ?: "")
            }
            KillRow(row, "maintenance message") {
                TextInput(draft) { onInput { draft = it.value }; placeholder("shown to users during maintenance") }
                Button(attrs = {
                    onClick {
                        ctx.confirmWrite(
                            title = "Set maintenance message",
                            flagPath = row.path,
                            before = serverValueLabel(row),
                            after = JsonPrimitive(draft).toString(),
                            success = "Maintenance message saved",
                        ) { ctx.api.upsertFlag(row.path, JsonPrimitive(draft)) }
                    }
                }) { Text("Save") }
            }
        }

        minVersion?.let { row ->
            var draft by remember(row.path, row.base) { mutableStateOf(row.resolved.inline()) }
            KillRow(row, "min supported build") {
                TextInput(draft) { onInput { draft = it.value }; placeholder("e.g. 1") }
                Button(attrs = {
                    onClick {
                        val parsed = parseTypedValue("int", draft)
                        val element = parsed.element
                        if (element == null) {
                            ctx.setStatus(Status(false, parsed.problem ?: "Must be a whole number"))
                            return@onClick
                        }
                        ctx.confirmWrite(
                            title = "Set min supported build",
                            flagPath = row.path,
                            before = serverValueLabel(row),
                            after = element.inline(),
                            success = "Min supported build set to ${element.inline()}",
                            warning = dangerousWarning(row.path, draft),
                        ) { ctx.api.upsertFlag(row.path, element) }
                    }
                }) { Text("Save") }
                manifest?.versionCode?.let {
                    Span(attrs = { classes("muted", "hint") }) {
                        Text("current build is $it — raising above it force-upgrades everyone below")
                    }
                }
            }
        }
    }
}

/** One kill-switch line: label, the three layers at a glance, then controls. */
@Composable
private fun KillRow(row: FlagRow, label: String, controls: @Composable () -> Unit) {
    Div(attrs = { classes("row", "kill-row") }) {
        Span(attrs = { classes("muted", "kill-label") }) { Text(label) }
        Span(attrs = { classes("muted", "hint") }) {
            Text("baked ${row.default.inline()} · server ${if (row.inDb) row.base.inline() else "not set"} · clients get ${row.resolved.inline()}")
        }
        Div(attrs = { classes("spacer") }) {}
        controls()
    }
}

private fun serverValueLabel(row: FlagRow): String =
    if (row.inDb) row.base.inline() else "not set (baked ${row.default.inline()})"

private fun kotlinx.serialization.json.JsonElement?.stringContent(): String? =
    (this as? JsonPrimitive)?.contentOrNull
