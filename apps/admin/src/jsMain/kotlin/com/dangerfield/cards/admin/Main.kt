package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.renderComposable

fun main() {
    renderComposable(rootElementId = "root") { App() }
}

private enum class Tab(val label: String) { Flags("Flags"), Versions("Versions"), Audit("Audit") }

@Composable
private fun App() {
    var selectedEnv by remember { mutableStateOf(AdminEnv.Dev) }
    var connectedEnv by remember { mutableStateOf<AdminEnv?>(null) }
    var actor by remember { mutableStateOf("admin") }
    var api by remember { mutableStateOf<AdminApi?>(null) }
    var status by remember { mutableStateOf<Status?>(null) }
    var tab by remember { mutableStateOf(Tab.Flags) }
    var pendingConfirm by remember { mutableStateOf<PendingWrite?>(null) }
    var errorLog by remember { mutableStateOf<List<ErrorEntry>>(emptyList()) }

    var flags by remember { mutableStateOf<List<ConfigFlagDto>>(emptyList()) }
    var resolvedByPath by remember { mutableStateOf<Map<String, ResolvedFlagDto>>(emptyMap()) }
    // The whole manifest response, not just its entries — the views label the
    // baked-default layer with the version it came from ("Baked into v0.1.0").
    var manifest by remember { mutableStateOf<ManifestResponse?>(null) }
    var manifestVersions by remember { mutableStateOf<List<ManifestVersionDto>>(emptyList()) }
    val target = remember { TargetState() }

    var versionsSelected by remember { mutableStateOf<Int?>(null) }
    var versionsEntries by remember { mutableStateOf<List<ManifestEntryDto>>(emptyList()) }

    val scope = rememberCoroutineScope()
    fun setStatus(s: Status) { status = s }

    fun resolveNow() {
        val a = api ?: return
        scope.launch {
            // Resolve/manifest are best-effort: an older server without these
            // endpoints shouldn't error the whole screen.
            Catching {
                val req = target.toRequest()
                resolvedByPath = a.resolve(req).associateBy { it.path }
                manifest = a.getManifest(req.buildNumber)
            }.onFailure { setStatus(Status(false, "Resolve unavailable: ${it.message}")) }
        }
    }

    fun reloadAll() {
        val a = api ?: return
        scope.launch {
            // The flag list is the core capability — it must succeed.
            val loaded = Catching { a.listFlags() }
                .onFailure { setStatus(Status(false, it.message ?: "Failed to load flags")) }
                .getOrNull() ?: return@launch
            flags = loaded

            // The version manifest + per-target resolve are newer endpoints; if
            // the connected server predates them, keep the flag editor working
            // and just note the degraded mode rather than blanking the screen.
            val enriched = Catching {
                manifestVersions = a.listManifestVersions()
                if (target.buildNumber.isBlank()) {
                    manifestVersions.firstOrNull()?.let {
                        target.buildNumber = it.versionCode.toString()
                        target.appVersion = it.appVersion.orEmpty()
                    }
                }
                val req = target.toRequest()
                resolvedByPath = a.resolve(req).associateBy { it.path }
                manifest = a.getManifest(req.buildNumber)
            }.isSuccess
            setStatus(
                Status(true, "Loaded ${flags.size} flag(s)" + if (enriched) "" else " — resolve/manifest unavailable on this server"),
            )
        }
    }

    fun selectVersion(versionCode: Int) {
        val a = api ?: return
        versionsSelected = versionCode
        scope.launch {
            Catching { versionsEntries = a.getManifest(versionCode).entries }
                .onFailure { setStatus(Status(false, it.message ?: "Failed to load version")) }
        }
    }

    H1 { Text("Cards · Remote Config Admin") }
    P(attrs = { classes("sub") }) { Text("Pick an environment, set a target lens, then view and edit flags.") }

    ConnectionPanel(
        selectedEnv = selectedEnv,
        onSelectEnv = { selectedEnv = it },
        actor = actor,
        onActor = { actor = it },
        onConnect = {
            val env = selectedEnv
            if (env.token.isBlank()) {
                setStatus(Status(false, "No token configured for ${env.displayName}."))
            } else {
                api = AdminApi(env.baseUrl, env.token, actor.trim().ifBlank { "admin" })
                connectedEnv = env
                reloadAll()
            }
        },
    )

    status?.let { s ->
        Div(attrs = { classes("status", if (s.ok) "ok" else "err") }) { Text(s.message) }
    }

    // Failed writes stay visible until dismissed — the one-line status above
    // gets overwritten by the next action; these don't.
    if (errorLog.isNotEmpty()) {
        Div(attrs = { classes("panel", "error-log") }) {
            Div(attrs = { classes("row") }) {
                Span(attrs = { classes("err") }) { Text("Failed writes") }
                Div(attrs = { classes("spacer") }) {}
                Button(attrs = { onClick { errorLog = emptyList() } }) { Text("Dismiss all") }
            }
            errorLog.forEach { entry ->
                Div(attrs = { classes("row", "error-entry") }) {
                    Span(attrs = { classes("muted") }) { Text(entry.time) }
                    Span { Text(entry.operation) }
                    entry.attempted?.let { Span(attrs = { classes("muted") }) { Text("attempted: $it") } }
                    Span(attrs = { classes("err") }) { Text(entry.message) }
                    Div(attrs = { classes("spacer") }) {}
                    Button(attrs = { onClick { errorLog = errorLog - entry } }) { Text("✕") }
                }
            }
        }
    }

    val activeApi = api ?: return
    val env = connectedEnv ?: return

    val ctx = remember(activeApi, env) {
        AdminCtx(
            api = activeApi,
            scope = scope,
            envName = env.displayName,
            isProd = env == AdminEnv.Prod,
            setStatus = ::setStatus,
            reload = ::reloadAll,
            requestConfirm = { pendingConfirm = it },
            reportError = { errorLog = errorLog + it },
        )
    }

    // Make prod unmistakable: a page border (body class) + a sticky banner.
    DisposableEffect(env) {
        if (env == AdminEnv.Prod) document.body?.classList?.add("body-prod")
        onDispose { document.body?.classList?.remove("body-prod") }
    }
    if (env == AdminEnv.Prod) {
        Div(attrs = { classes("env-banner-prod") }) {
            Text("PRODUCTION — changes affect live users")
        }
    }

    ConfirmModal(pendingConfirm) { pendingConfirm = null }

    val rows = buildFlagRows(flags, resolvedByPath, manifest?.entries.orEmpty().associateBy { it.path })
    KillSwitchPanel(rows, manifest, ctx)

    Div(attrs = { classes("tabs") }) {
        Tab.entries.forEach { t ->
            Button(attrs = {
                if (t == tab) classes("active")
                onClick {
                    tab = t
                    if (t == Tab.Versions && versionsSelected == null) {
                        manifestVersions.firstOrNull()?.let { selectVersion(it.versionCode) }
                    }
                }
            }) { Text(t.label) }
        }
    }

    when (tab) {
        Tab.Flags -> {
            TargetBar(target = target, versions = manifestVersions, onResolve = { resolveNow() })
            FlagsView(
                rows = rows,
                manifest = manifest,
                target = target,
                ctx = ctx,
            )
        }

        Tab.Versions -> VersionsView(
            versions = manifestVersions,
            selectedVersion = versionsSelected,
            entries = versionsEntries,
            onSelect = { selectVersion(it) },
        )

        Tab.Audit -> AuditView(api = activeApi, setStatus = ::setStatus)
    }
}

@Composable
private fun ConnectionPanel(
    selectedEnv: AdminEnv,
    onSelectEnv: (AdminEnv) -> Unit,
    actor: String,
    onActor: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("row") }) {
            Label { Text("Environment") }
            AdminEnv.entries.forEach { env ->
                Button(attrs = {
                    if (env == selectedEnv) classes("primary")
                    onClick { onSelectEnv(env) }
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
            TextInput(actor) { onInput { onActor(it.value) }; placeholder("your name (audited)") }
        }
        Div(attrs = { classes("row"); style { property("margin-top", "10px") } }) {
            Button(attrs = { classes("primary"); onClick { onConnect() } }) { Text("Connect / Reload") }
        }
    }
}
