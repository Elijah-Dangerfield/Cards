package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.PasswordInput
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr

/** One environment's view of a flag, for the cross-env comparison. */
internal data class DiffSide(
    val base: JsonElement?,
    val inDb: Boolean,
    val rulesSummary: List<String>,
    val default: JsonElement?,
) {
    val serverLabel: String get() = if (inDb) base.inline() else "not set"
}

internal data class DiffRow(val path: String, val a: DiffSide, val b: DiffSide) {
    val serverDiffers: Boolean get() = a.serverLabel != b.serverLabel
    val rulesDiffer: Boolean get() = a.rulesSummary != b.rulesSummary
    val bakedDiffers: Boolean get() = a.default.inline() != b.default.inline()
    val differs: Boolean get() = serverDiffers || rulesDiffer
}

private fun side(path: String, flags: Map<String, ConfigFlagDto>, manifest: Map<String, ManifestEntryDto>): DiffSide {
    val flag = flags[path]
    return DiffSide(
        base = flag?.value,
        inDb = flag != null,
        rulesSummary = flag?.rules.orEmpty()
            .sortedBy { it.priority }
            .map { rule ->
                val state = if (rule.enabled) "" else " (disabled)"
                "when ${conditionsSentence(rule.conditions)} → ${rule.value.inline()}$state"
            },
        default = manifest[path]?.default,
    )
}

internal fun buildDiffRows(
    aFlags: List<ConfigFlagDto>,
    aManifest: List<ManifestEntryDto>,
    bFlags: List<ConfigFlagDto>,
    bManifest: List<ManifestEntryDto>,
): List<DiffRow> {
    val aByPath = aFlags.associateBy { it.path }
    val bByPath = bFlags.associateBy { it.path }
    val aManifestByPath = aManifest.associateBy { it.path }
    val bManifestByPath = bManifest.associateBy { it.path }
    val paths = (aByPath.keys + bByPath.keys + aManifestByPath.keys + bManifestByPath.keys).distinct().sorted()
    return paths.map { path ->
        DiffRow(path, side(path, aByPath, aManifestByPath), side(path, bByPath, bManifestByPath))
    }
}

private data class EnvSnapshot(val flags: List<ConfigFlagDto>, val manifest: List<ManifestEntryDto>)

/**
 * Side-by-side of every flag across this env and its sibling (dev ↔ prod) —
 * the pre-launch "did I remember to set this on prod?" check. Read-only by
 * design: writes belong in the sibling's own console, with its own prod
 * treatment. Needs the sibling's token once (read calls only).
 */
@Composable
internal fun DiffView(ctx: AdminCtx, connectedEnv: AdminEnv) {
    val other = when (connectedEnv) {
        AdminEnv.Dev -> AdminEnv.Prod
        AdminEnv.Prod -> AdminEnv.Dev
        AdminEnv.Local -> null
    }
    if (other == null) {
        P(attrs = { classes("muted") }) { Text("The diff compares dev ↔ prod — connect to one of those to use it.") }
        return
    }

    var otherTokenDraft by remember { mutableStateOf("") }
    var haveOtherToken by remember { mutableStateOf(TokenStore.token(other) != null) }
    var snapshots by remember { mutableStateOf<Pair<EnvSnapshot, EnvSnapshot>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var diffsOnly by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableStateOf(0) }

    if (!haveOtherToken) {
        Div(attrs = { classes("panel") }) {
            P(attrs = { classes("muted") }) {
                Text("Comparing needs the ${other.displayName} admin token (read-only calls, stored in this browser only).")
            }
            Div(attrs = { classes("row") }) {
                PasswordInput(otherTokenDraft) {
                    onInput { otherTokenDraft = it.value }
                    placeholder("paste the ${other.displayName} admin token")
                }
                Button(attrs = {
                    classes("primary")
                    onClick {
                        val token = otherTokenDraft.trim()
                        if (token.isNotEmpty()) {
                            TokenStore.saveToken(other, token)
                            otherTokenDraft = ""
                            haveOtherToken = true
                        }
                    }
                }) { Text("Use token") }
            }
        }
        return
    }

    LaunchedEffect(reloadKey) {
        loadError = null
        snapshots = null
        Catching {
            val otherApi = AdminApi(other.baseUrl, TokenStore.token(other).orEmpty(), TokenStore.actor ?: "admin")
            val mine = EnvSnapshot(
                flags = ctx.api.listFlags(),
                manifest = Catching { ctx.api.getManifest(null).entries }.getOrNull().orEmpty(),
            )
            val theirs = EnvSnapshot(
                flags = otherApi.listFlags(),
                manifest = Catching { otherApi.getManifest(null).entries }.getOrNull().orEmpty(),
            )
            snapshots = mine to theirs
        }.onFailure { error ->
            // A rejected sibling token shouldn't wedge the tab — offer re-entry.
            loadError = error.message ?: "Failed to load"
            if ((error as? AdminApiException)?.status?.value == 401) {
                TokenStore.forgetToken(other)
                haveOtherToken = false
            }
        }
    }

    loadError?.let {
        Div(attrs = { classes("status", "err") }) { Text("Diff failed: $it") }
        Button(attrs = { onClick { reloadKey++ } }) { Text("Retry") }
        return
    }
    val (mine, theirs) = snapshots ?: run {
        P(attrs = { classes("muted") }) { Text("Loading both environments…") }
        return
    }

    val rows = buildDiffRows(mine.flags, mine.manifest, theirs.flags, theirs.manifest)
    val visible = if (diffsOnly) rows.filter { it.differs } else rows

    Div(attrs = { classes("row"); style { property("margin-bottom", "10px") } }) {
        Label {
            CheckboxInput(diffsOnly) { onInput { diffsOnly = !diffsOnly } }
            Text(" differences only")
        }
        Span(attrs = { classes("muted") }) {
            Text("${rows.count { it.differs }} of ${rows.size} flags differ")
        }
        Div(attrs = { classes("spacer") }) {}
        A(href = "${other.baseUrl}/admin/") { Text("open ${other.displayName} console →") }
        Button(attrs = { onClick { reloadKey++ } }) { Text("Refresh") }
    }

    if (visible.isEmpty()) {
        P(attrs = { classes("muted") }) {
            Text(if (diffsOnly) "No differences — ${connectedEnv.displayName} and ${other.displayName} serve the same config." else "No flags.")
        }
        return
    }

    Table(attrs = { classes("flags") }) {
        Thead {
            Tr {
                Th { Text("Flag") }
                Th { Text("${connectedEnv.displayName} server") }
                Th { Text("${other.displayName} server") }
                Th { Text("${connectedEnv.displayName} rules") }
                Th { Text("${other.displayName} rules") }
            }
        }
        Tbody {
            visible.forEach { row ->
                Tr {
                    Td {
                        Div { Span(attrs = { classes("flag-path") }) { Text(row.path) } }
                        if (row.bakedDiffers) {
                            Div(attrs = { classes("flag-desc") }) {
                                Text("baked: ${row.a.default.inline()} vs ${row.b.default.inline()}")
                            }
                        }
                    }
                    ServerCell(row.a, highlight = row.serverDiffers)
                    ServerCell(row.b, highlight = row.serverDiffers)
                    RulesCell(row.a.rulesSummary, highlight = row.rulesDiffer)
                    RulesCell(row.b.rulesSummary, highlight = row.rulesDiffer)
                }
            }
        }
    }
}

@Composable
private fun ServerCell(side: DiffSide, highlight: Boolean) {
    Td {
        Span(attrs = {
            classes(
                when {
                    !side.inDb -> "val-not-set"
                    highlight -> "val-drift"
                    else -> "val"
                },
            )
        }) { Text(side.serverLabel) }
    }
}

@Composable
private fun RulesCell(summaries: List<String>, highlight: Boolean) {
    Td {
        if (summaries.isEmpty()) {
            Span(attrs = { classes("val-not-set") }) { Text("—") }
        } else {
            summaries.forEach {
                Div(attrs = { classes(if (highlight) "val-drift" else "muted"); style { property("font-size", "12px") } }) {
                    Text(it)
                }
            }
        }
    }
}
