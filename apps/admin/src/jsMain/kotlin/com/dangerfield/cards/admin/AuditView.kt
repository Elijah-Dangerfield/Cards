package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput

/**
 * The change log, newest first, as readable sentences with expandable
 * before/after and a one-click Revert (through the confirm sheet). Loads on
 * tab open; the filter narrows to one flag path via the server-side filter.
 */
@Composable
internal fun AuditView(ctx: AdminCtx) {
    var entries by remember { mutableStateOf<List<ConfigAuditDto>?>(null) }
    var filter by remember { mutableStateOf("") }
    var loadedFilter by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(loadedFilter, reloadKey) {
        Catching { entries = ctx.api.listAudit(flag = loadedFilter) }
            .onFailure { ctx.setStatus(Status(false, it.message ?: "Failed to load audit")) }
    }

    Div(attrs = { classes("row"); style { property("margin-bottom", "10px") } }) {
        TextInput(filter) { onInput { filter = it.value }; placeholder("filter by flag path e.g. social.enabled") }
        Button(attrs = { onClick { loadedFilter = filter.trim().ifBlank { null } } }) { Text("Filter") }
        if (loadedFilter != null) {
            Button(attrs = {
                onClick {
                    filter = ""
                    loadedFilter = null
                }
            }) { Text("Clear") }
        }
        Div(attrs = { classes("spacer") }) {}
        Button(attrs = { onClick { reloadKey++ } }) { Text("Refresh") }
    }

    val list = entries ?: run {
        P(attrs = { classes("muted") }) { Text("Loading audit log…") }
        return
    }
    if (list.isEmpty()) {
        P(attrs = { classes("muted") }) { Text("No changes recorded yet.") }
    }
    list.forEach { entry -> AuditRow(entry, ctx, onReverted = { reloadKey++ }) }
}

@Composable
private fun AuditRow(entry: ConfigAuditDto, ctx: AdminCtx, onReverted: () -> Unit) {
    var open by remember(entry.id) { mutableStateOf(false) }
    val plan = remember(entry.id) { revertPlanFor(entry) }

    Div(attrs = { classes("rule") }) {
        Div(attrs = { classes("row") }) {
            Span(attrs = { classes("muted") }) { Text(relativeTime(entry.atEpochMs)) }
            Span { Text(auditSentence(entry)) }
            Span(attrs = { classes("muted"); style { property("cursor", "pointer") }; onClick { open = !open } }) {
                Text(if (open) "hide detail ▾" else "detail ▸")
            }
            Div(attrs = { classes("spacer") }) {}
            plan?.let { p ->
                Button(attrs = {
                    onClick {
                        ctx.confirmWrite(
                            title = "Revert",
                            flagPath = entry.flagPath,
                            before = "now: ${entry.after.inline()}",
                            after = "revert will ${p.summary}",
                            success = "Reverted: ${p.summary}",
                            warning = p.caveat ?: entry.flagPath?.let { path ->
                                entry.before?.let { dangerousWarning(path, it.inline()) }
                            },
                        ) {
                            p.execute(ctx.api)
                            // Refresh the audit list only after the revert landed.
                            onReverted()
                        }
                    }
                }) { Text("Revert") }
            }
        }
        if (open) {
            Div(attrs = { classes("layers") }) {
                Div(attrs = { classes("layer") }) {
                    Span(attrs = { classes("muted") }) { Text("before") }
                    Span(attrs = { classes(if (entry.before == null) "val-not-set" else "val") }) {
                        Text(entry.before?.inline() ?: "not set")
                    }
                }
                Div(attrs = { classes("layer") }) {
                    Span(attrs = { classes("muted") }) { Text("after") }
                    Span(attrs = { classes(if (entry.after == null) "val-not-set" else "val") }) {
                        Text(entry.after?.inline() ?: "removed")
                    }
                }
            }
        }
    }
}
