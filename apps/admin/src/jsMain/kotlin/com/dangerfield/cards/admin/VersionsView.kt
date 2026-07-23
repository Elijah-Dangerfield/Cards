package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * "What did version X ship with." Pick a captured version; see its baked
 * defaults — the baseline a server value replaces.
 */
@Composable
internal fun VersionsView(
    versions: List<ManifestVersionDto>,
    selectedVersion: Int?,
    entries: List<ManifestEntryDto>,
    onSelect: (Int) -> Unit,
) {
    if (versions.isEmpty()) {
        P(attrs = { classes("muted") }) {
            Text("No version manifests uploaded yet. Run :apps:admin:exportConfigManifest and PUT it (see README).")
        }
        return
    }

    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("row") }) {
            Span(attrs = { classes("muted") }) { Text("Version") }
            versions.forEach { v ->
                val label = v.appVersion?.let { "$it (${v.versionCode})" } ?: "build ${v.versionCode}"
                Button(attrs = {
                    if (selectedVersion == v.versionCode) classes("primary")
                    onClick { onSelect(v.versionCode) }
                }) { Text(label) }
            }
        }
    }

    if (entries.isEmpty()) {
        P(attrs = { classes("muted") }) { Text("Pick a version to see the defaults baked into that build.") }
        return
    }

    Div(attrs = { classes("panel") }) {
        entries.forEach { entry ->
            Div(attrs = { classes("manifest-row") }) {
                Span(attrs = { classes("flag-path") }) { Text(entry.path) }
                Span(attrs = { classes("tag") }) { Text(entry.type) }
                Span(attrs = { classes("muted") }) { Text("baked default") }
                Code { Text(entry.default.inline()) }
                entry.description?.takeIf { it.isNotBlank() }?.let {
                    Span(attrs = { classes("muted", "hint") }) { Text(it) }
                }
            }
        }
    }
}
