package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The change log, newest first, with before/after diffs. Loaded on demand. */
@Composable
internal fun AuditView(api: AdminApi, setStatus: (Status) -> Unit) {
    var entries by remember { mutableStateOf<List<ConfigAuditDto>?>(null) }
    val scope = rememberCoroutineScope()

    Button(attrs = {
        classes("primary")
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
