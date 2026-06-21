package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.AuthTokenProvider
import com.dangerfield.cards.libraries.networking.ClientHeaders
import com.dangerfield.cards.libraries.networking.InternalNetworkingApi
import com.dangerfield.cards.libraries.networking.ClientHeadersProvider
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.NetworkConfig
import com.dangerfield.cards.libraries.networking.NetworkJson
import com.dangerfield.cards.libraries.networking.NetworkReachability
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.time.Duration.Companion.seconds
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
@OptIn(InternalNetworkingApi::class)
class NetworkClientImpl(
    private val config: NetworkConfig,
    private val tokenProvider: AuthTokenProvider,
    private val headersProvider: ClientHeadersProvider,
    private val reachability: NetworkReachability,
) : NetworkClient {

    override val client: HttpClient by lazy {
        HttpClient {
            applyCommonConfig(config, headersProvider, reachability)
        }
    }

    override val authenticatedClient: HttpClient by lazy {
        HttpClient {
            applyCommonConfig(config, headersProvider, reachability)
            install(Auth) {
                bearer {
                    // By the time loadTokens runs, authedCall has already
                    // called [awaitAuthReady], so the gateway peek is
                    // synchronous and instant. Null means there's no
                    // session (anon disabled, offline before first auth) —
                    // request goes unauthed and the server 401s cleanly.
                    loadTokens {
                        val token = tokenProvider.accessToken()
                            ?: return@loadTokens null
                        BearerTokens(accessToken = token, refreshToken = "")
                    }
                    refreshTokens {
                        val token = tokenProvider.refreshAccessToken()
                            ?: return@refreshTokens null
                        BearerTokens(accessToken = token, refreshToken = "")
                    }
                    sendWithoutRequest { true }
                }
            }
            // WebSocket plugin so callers can open room sockets via the
            // same authenticated client (the Auth bearer is attached on
            // the WS handshake). The plugin is additive — existing HTTP
            // calls don't notice it.
            install(WebSockets) {
                pingIntervalMillis = 15.seconds.inWholeMilliseconds
            }
        }
    }

    override suspend fun awaitAuthReady() {
        tokenProvider.awaitReady()
    }
}

private fun HttpClientConfig<*>.applyCommonConfig(
    config: NetworkConfig,
    headersProvider: ClientHeadersProvider,
    reachability: NetworkReachability,
) {
    install(ContentNegotiation) {
        json(NetworkJson)
    }
    // Witnessed reachability: a response (any status, even 4xx/5xx) means the
    // round-trip worked; a failure *without* a response (timeout / IO / DNS /
    // captive portal) means it didn't. This is what lets the offline banner
    // reflect "actually online" rather than just the OS's "there's a path."
    HttpResponseValidator {
        validateResponse { reachability.reportReachable() }
        handleResponseExceptionWithRequest { cause, _ ->
            // A ResponseException means the server answered (a 4xx/5xx) — the
            // network is fine. Anything else never reached the server.
            if (cause !is ResponseException) reachability.reportUnreachable()
        }
    }
    install(HttpTimeout) {
        requestTimeoutMillis = config.requestTimeoutMillis
        connectTimeoutMillis = config.requestTimeoutMillis
        socketTimeoutMillis = config.requestTimeoutMillis
    }
    install(DefaultRequest) {
        if (config.baseUrl.isNotBlank()) url(config.baseUrl)
        headers.append(HttpHeaders.Accept, "application/json")
        headers.append(HttpHeaders.ContentType, "application/json")
        // Per-request: re-read from the provider on every call so locale
        // changes flow through immediately. The provider caches its
        // build-info bits, so this is cheap.
        val h = headersProvider.current()
        headers.append(HttpHeaders.AcceptLanguage, h.acceptLanguage)
        headers.append(ClientHeaders.HEADER_PLATFORM, h.platform)
        headers.append(ClientHeaders.HEADER_APP_VERSION, h.appVersion)
        headers.append(ClientHeaders.HEADER_BUILD_NUMBER, h.buildNumber)
        h.countryCode?.let { headers.append(ClientHeaders.HEADER_COUNTRY_CODE, it) }
        h.installId?.let { headers.append(ClientHeaders.HEADER_INSTALL_ID, it) }
    }
    if (BuildInfo.isDebug) {
        // WiretapKMP — captures every request/response through this client
        // into the on-device inspector (shake → "Network inspector"). Debug
        // builds link the real plugin; release builds link the noop (and
        // never enter this branch anyway). Applied to both the plain and
        // authenticated clients since they share this config. Platform-gated
        // (see installNetworkInspector) so host-JVM unit tests, where
        // Wiretap's DI isn't bootstrapped, don't install + crash on it.
        installNetworkInspector()
        // Debug-only by design: bodies are valuable for debugging but
        // dumping them in release would blow up log volume and risk
        // leaking PII. Bodies stay LogLevel.BODY (covers headers + the
        // payload). We post-process Ktor's multi-line emit into a
        // single line per call via [CompactNetworkLogger] — filter the
        // device log for tag "Network" to scan API traffic at a glance.
        install(Logging) {
            level = LogLevel.BODY
            logger = CompactNetworkLogger
            // Belt-and-suspenders if we ever turn header logging back
            // on: bearer tokens never get echoed verbatim.
            sanitizeHeader { it.equals(HttpHeaders.Authorization, ignoreCase = true) }
        }
    }
    expectSuccess = true
}

/**
 * One-line-per-call network log. Ktor's default `Logging` plugin emits
 * ~20 lines per request (headers, body markers, etc.); this collapses
 * each emit into:
 *
 *  - `→ POST /v1/inventory/sync  ⇢ {"purchases":[]}`
 *  - `← 200 OK POST /v1/inventory/sync (412ms)  ⇠ {"schemaVersion":...}`
 *  - `✗ GET /v1/app-config — Timed out waiting for 5000 ms`
 *
 * The plugin emits one log() call per multi-line block, so parsing
 * happens here rather than via a custom Ktor interceptor. Unknown
 * formats fall through verbatim so future Ktor changes don't silently
 * eat output.
 */
private object CompactNetworkLogger : Logger {
    private val log = KLog.withTag("Network")
    private const val BODY_PREVIEW_MAX = 500

    override fun log(message: String) {
        log.i { formatCompact(message) }
    }

    private fun formatCompact(raw: String): String {
        val trimmed = raw.trimEnd()
        if (trimmed.isEmpty()) return trimmed
        val lines = trimmed.lines()
        val first = lines.first()
        return when {
            first.startsWith("REQUEST:") -> formatRequest(lines)
            first.startsWith("REQUEST ") && first.contains(" failed ") -> formatFailure(first)
            first.startsWith("RESPONSE:") -> formatResponse(lines)
            else -> trimmed
        }
    }

    private fun formatRequest(lines: List<String>): String {
        val url = lines.first().substringAfter("REQUEST:").trim()
        val method = lines.lookupOneLine("METHOD:") ?: "?"
        val body = extractBody(lines)
        return buildString {
            append("→ $method ${extractPath(url)}")
            if (body.isNotBlank()) append("  ⇢ $body")
        }
    }

    private fun formatResponse(lines: List<String>): String {
        val status = lines.first().substringAfter("RESPONSE:").trim()
        val method = lines.lookupOneLine("METHOD:") ?: "?"
        val url = lines.lookupOneLine("FROM:") ?: ""
        val body = extractBody(lines)
        return buildString {
            append("← $status $method ${extractPath(url)}")
            if (body.isNotBlank()) append("  ⇠ $body")
        }
    }

    private fun formatFailure(line: String): String {
        // Shape: "REQUEST <url> failed with exception: <message...>"
        val afterRequest = line.substringAfter("REQUEST").trim()
        val url = afterRequest.substringBefore(" failed").trim()
        val reason = afterRequest.substringAfter("failed with exception:", missingDelimiterValue = afterRequest).trim()
        return "✗ ${extractPath(url)} — $reason"
    }

    private fun List<String>.lookupOneLine(prefix: String): String? =
        firstOrNull { it.startsWith(prefix) }?.substringAfter(prefix)?.trim()

    /**
     * Pull the lines between `BODY START` and `BODY END`. Joins multi-
     * line bodies with a space (JSON serializes on one line for the
     * routes we care about, so this is mostly cosmetic for stray
     * pretty-printed responses) and caps the preview length.
     */
    private fun extractBody(lines: List<String>): String {
        val start = lines.indexOfFirst { it == "BODY START" }
        val end = lines.indexOfFirst { it == "BODY END" }
        if (start < 0 || end < 0 || end <= start + 1) return ""
        val body = lines.subList(start + 1, end).joinToString(" ").trim()
        if (body.isEmpty()) return ""
        return if (body.length > BODY_PREVIEW_MAX) {
            body.substring(0, BODY_PREVIEW_MAX) + "… (${body.length} chars)"
        } else {
            body
        }
    }

    private fun extractPath(url: String): String {
        if (url.isBlank()) return ""
        val withoutScheme = url.substringAfter("://", url)
        val firstSlash = withoutScheme.indexOf('/')
        return if (firstSlash >= 0) withoutScheme.substring(firstSlash) else withoutScheme
    }
}
