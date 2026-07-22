package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.PasswordInput
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.renderComposable

fun main() {
    renderComposable(rootElementId = "root") { App() }
}

private enum class Tab(val label: String) { Flags("Flags"), Versions("Versions"), Audit("Audit") }

/** The env whose server is serving this console, when hosted at `/admin`. */
private fun hostedEnv(): AdminEnv? =
    AdminEnv.entries.firstOrNull { it.baseUrl == window.location.origin }

@Composable
private fun App() {
    val hosted = remember { hostedEnv() }
    var selectedEnv by remember { mutableStateOf(hosted ?: AdminEnv.Dev) }
    var connectedEnv by remember { mutableStateOf<AdminEnv?>(null) }
    var actor by remember { mutableStateOf(TokenStore.actor ?: "admin") }
    var tokenDraft by remember { mutableStateOf("") }
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

    val activeApi = api
    val env = connectedEnv

    // ── top app bar: who am I, where am I pointed, is it live ──
    Div(attrs = { classes("appbar") }) {
        Div(attrs = { classes("appbar-inner") }) {
            Span(attrs = { classes("wordmark") }) {
                Text("Downcard ")
                Span(attrs = { classes("dim") }) { Text("Config") }
            }
            env?.let {
                Span(attrs = { classes("env-chip", if (it == AdminEnv.Prod) "env-chip-prod" else "env-chip-dev") }) {
                    Text(it.displayName.uppercase())
                }
            }
            Span(attrs = {
                classes("conn-dot")
                if (env != null) classes("on")
            }) {}
            Span(attrs = { classes("muted") }) { Text(env?.baseUrl ?: "not connected") }
            Div(attrs = { classes("spacer") }) {}
            if (env != null) {
                Span(attrs = { classes("muted") }) { Text("as ${actor.trim().ifBlank { "admin" }}") }
                Button(attrs = { onClick { reloadAll() } }) { Text("Reload") }
                Button(attrs = {
                    onClick {
                        api = null
                        connectedEnv = null
                    }
                }) { Text("Disconnect") }
            }
        }
        if (env == AdminEnv.Prod) {
            Div(attrs = { classes("env-banner-prod") }) {
                Text("PRODUCTION — changes affect live users")
            }
        }
    }

    // Make prod unmistakable beyond the banner: a page border via body class.
    DisposableEffect(env) {
        if (env == AdminEnv.Prod) document.body?.classList?.add("body-prod")
        onDispose { document.body?.classList?.remove("body-prod") }
    }

    Div(attrs = { classes("page") }) {
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

        if (activeApi == null || env == null) {
            P(attrs = { classes("sub") }) {
                Text("Connect to an environment to view and edit its config.")
            }
            ConnectionPanel(
                selectedEnv = selectedEnv,
                hosted = hosted,
                onSelectEnv = { selectedEnv = it },
                actor = actor,
                onActor = { actor = it },
                tokenDraft = tokenDraft,
                onTokenDraft = { tokenDraft = it },
                onForgetToken = {
                    TokenStore.forgetToken(selectedEnv)
                    setStatus(Status(true, "Forgot the ${selectedEnv.displayName} token."))
                },
                onConnect = {
                    val envToConnect = selectedEnv
                    val actorName = actor.trim().ifBlank { "admin" }
                    val token = tokenDraft.trim().ifBlank { TokenStore.token(envToConnect).orEmpty() }
                    if (token.isBlank()) {
                        setStatus(Status(false, "Paste the ${envToConnect.displayName} admin token to connect."))
                    } else {
                        // Validate the token with a real call before storing it,
                        // so a typo'd token never sticks.
                        val candidate = AdminApi(envToConnect.baseUrl, token, actorName)
                        scope.launch {
                            Catching { candidate.listFlags() }
                                .onSuccess {
                                    TokenStore.saveToken(envToConnect, token)
                                    TokenStore.actor = actorName
                                    tokenDraft = ""
                                    api = candidate
                                    connectedEnv = envToConnect
                                    reloadAll()
                                }
                                .onFailure {
                                    setStatus(Status(false, "Could not connect to ${envToConnect.displayName}: ${it.message}"))
                                }
                        }
                    }
                },
            )
        } else {
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
    }
}

@Composable
private fun ConnectionPanel(
    selectedEnv: AdminEnv,
    hosted: AdminEnv?,
    onSelectEnv: (AdminEnv) -> Unit,
    actor: String,
    onActor: (String) -> Unit,
    tokenDraft: String,
    onTokenDraft: (String) -> Unit,
    onForgetToken: () -> Unit,
    onConnect: () -> Unit,
) {
    val savedToken = TokenStore.token(selectedEnv) != null

    Div(attrs = { classes("panel") }) {
        Div(attrs = { classes("row") }) {
            Label { Text("Environment") }
            if (hosted != null) {
                // Hosted mode: this console manages the server that serves it.
                // The sibling envs are links to their own consoles — each env's
                // tokens stay in that origin's storage.
                Span(attrs = { classes("env-chip", if (hosted == AdminEnv.Prod) "env-chip-prod" else "env-chip-dev") }) {
                    Text(hosted.displayName.uppercase())
                }
                Span(attrs = { classes("muted") }) { Text("served by this console") }
                AdminEnv.entries.filter { it != hosted && it != AdminEnv.Local }.forEach { other ->
                    A(href = "${other.baseUrl}/admin/") { Text("open ${other.displayName} console →") }
                }
            } else {
                AdminEnv.entries.forEach { env ->
                    Button(attrs = {
                        if (env == selectedEnv) classes("primary")
                        onClick { onSelectEnv(env) }
                    }) { Text(env.displayName.uppercase()) }
                }
                Span(attrs = { classes("muted") }) { Text(selectedEnv.baseUrl) }
            }
        }
        Div(attrs = { classes("grid"); style { property("margin-top", "8px") } }) {
            Label { Text("Token") }
            Div(attrs = { classes("row") }) {
                PasswordInput(tokenDraft) {
                    onInput { onTokenDraft(it.value) }
                    placeholder(
                        if (savedToken) "saved for ${selectedEnv.displayName} — paste to replace" else "paste the admin token",
                    )
                }
                if (savedToken) {
                    Button(attrs = { onClick { onForgetToken() } }) { Text("Forget token") }
                }
            }
            Label { Text("Actor") }
            TextInput(actor) { onInput { onActor(it.value) }; placeholder("your name (audited)") }
        }
        Div(attrs = { classes("row"); style { property("margin-top", "10px") } }) {
            Button(attrs = { classes("primary"); onClick { onConnect() } }) { Text("Connect / Reload") }
        }
    }
}
