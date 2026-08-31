package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.AuthGate
import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.core.AuthVerdict
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.networking.AccessDeniedBus
import com.dangerfield.cards.libraries.networking.BannedState
import com.dangerfield.cards.libraries.networking.SessionRejectionBus
import com.dangerfield.cards.libraries.networking.AuthTokenProvider
import com.dangerfield.cards.libraries.networking.ClientHeaders
import com.dangerfield.cards.libraries.networking.InternalNetworkingApi
import com.dangerfield.cards.libraries.networking.ClientHeadersProvider
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.NetworkConfig
import com.dangerfield.cards.libraries.networking.NetworkJson
import com.dangerfield.cards.libraries.networking.indicatesBackendUnreachable
import com.dangerfield.cards.libraries.networking.NetworkReachability
import com.dangerfield.cards.libraries.networking.platformHttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
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
    private val accessDeniedBus: AccessDeniedBus,
    private val bannedState: BannedState,
    private val sessionRejectionBus: SessionRejectionBus,
    // Lazy: AuthGate's impl (identity) reaches NetworkClient through its own
    // deps, so a direct injection would cycle at construction time.
    private val authGate: () -> AuthGate,
) : NetworkClient {

    override val client: HttpClient by lazy {
        HttpClient(platformHttpEngineFactory) {
            applyCommonConfig(
                config,
                headersProvider,
                reachability,
                accessDeniedBus,
                bannedState,
                ::onAccountMissing,
            )
        }
    }

    override val authenticatedClient: HttpClient by lazy {
        HttpClient(platformHttpEngineFactory) {
            applyCommonConfig(
                config,
                headersProvider,
                reachability,
                accessDeniedBus,
                bannedState,
                ::onAccountMissing,
            )
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
            // calls don't notice it. Keepalive is per-engine; see
            // installWebSocketKeepalive for the OkHttp trap (MP-32).
            installWebSocketKeepalive()
            if (BuildInfo.isDebug) {
                // Wiretap's WS capture (ENG-8) — the gameplay room socket runs
                // through this authenticated client, so this surfaces every
                // sent/received frame + connect/close in the same inspector as
                // HTTP. Installed AFTER WebSockets so it can wrap the raw session
                // before WebSockets transforms it. Debug-only + noop in release,
                // same as the HTTP capture.
                installWebSocketInspector()
            }
        }
    }

    override suspend fun awaitAuthReady() {
        tokenProvider.awaitReady()
    }

    override suspend fun authVerdict(requirement: AuthRequirement): AuthVerdict =
        authGate().awaitVerdict(requirement)

    override val sessionRejectionEpoch: Long
        get() = sessionRejectionBus.rejectionEpoch

    /**
     * Our server answered a verified token with "that account doesn't exist"
     * (AUTH-29). Supabase still honours the token — it's our `auth.users` row
     * that's gone — so the bearer plugin's refresh path will never notice, and
     * without this every sync would keep firing doomed writes forever. Route it
     * into the same rejection bus a server-rejected refresh uses so the session
     * tears down once and the auth gate short-circuits the rest.
     */
    private suspend fun onAccountMissing() {
        sessionRejectionBus.signalRejected(wasAnonymous = tokenProvider.isAnonymousSession())
    }
}

private fun HttpClientConfig<*>.applyCommonConfig(
    config: NetworkConfig,
    headersProvider: ClientHeadersProvider,
    reachability: NetworkReachability,
    accessDeniedBus: AccessDeniedBus,
    bannedState: BannedState,
    onAccountMissing: suspend () -> Unit,
) {
    install(ContentNegotiation) {
        json(NetworkJson)
    }
    // Ahead of Auth on purpose: a banned request should be cut before the bearer
    // plugin spends a token load or refresh on it.
    install(bannedCircuitBreaker(bannedState))
    // Witnessed reachability: a response (any status, even 4xx/5xx) means the
    // round-trip worked; a failure *without* a response (timeout / IO / DNS /
    // captive portal) means it didn't. This is what lets the offline banner
    // reflect "actually online" rather than just the OS's "there's a path."
    HttpResponseValidator {
        validateResponse { response ->
            reachability.reportReachable()
            clearBanIfStatusProbeSucceeded(response, bannedState)
        }
        handleResponseExceptionWithRequest { cause, _ ->
            // A ResponseException means the server answered (a 4xx/5xx) — the
            // network is fine. Anything else never reached the server, unless we
            // are the reason it didn't leave.
            if (cause !is ResponseException) {
                if (cause.indicatesBackendUnreachable()) reachability.reportUnreachable()
                return@handleResponseExceptionWithRequest
            }
            when (cause.response.status) {
                HttpStatusCode.Forbidden ->
                    signalAccessDeniedIfEnveloped(cause.response, accessDeniedBus, bannedState)
                HttpStatusCode.Unauthorized ->
                    signalAccountMissingIfEnveloped(cause.response, onAccountMissing)
                else -> Unit
            }
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
        headers.append(ClientHeaders.HEADER_SESSION_ID, h.sessionId)
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
    }
    expectSuccess = true
}

/**
 * Server's `403 AccessDeniedResponse` wire shape — duplicated here (not shared)
 * because the server class lives in `:apps:server` which the client must not
 * depend on. Locked machine-readable data only, no copy: the client localizes
 * off [reason]. `internal` so the impl module's test can construct an
 * envelope-shaped response without re-deriving the shape; the public surface
 * outside the module is [AccessDeniedBus.Denial].
 */
@Serializable
internal data class AccessDeniedWire(
    val reason: String,
    val until: String? = null,
    val appealUrl: String? = null,
)

/**
 * On a `403`, attempt to read the response body as the locked [AccessDeniedWire]
 * envelope and signal the access-denied bus. We don't throw or swallow — the
 * caller's existing failure flow gets the same `ClientRequestException` it
 * always did. A 403 *without* our envelope (a route-level deny, an upstream
 * proxy 403) doesn't decode and is skipped silently — the [Catching] wrap is
 * the safety net there.
 *
 * Run from `handleResponseExceptionWithRequest` rather than `validateResponse`
 * because the `ResponseException` Ktor throws after validation holds a *saved*
 * copy of the response — reading the body here doesn't race the caller's own
 * body decoding (callers like `RoomRepositoryImpl` map 403 → `NotHost` off the
 * status alone and never re-read the body anyway).
 */
internal suspend fun signalAccessDeniedIfEnveloped(
    response: HttpResponse,
    accessDeniedBus: AccessDeniedBus,
    bannedState: BannedState,
) {
    Catching { response.body<AccessDeniedWire>() }
        .onSuccess { wire ->
            val denial = AccessDeniedBus.Denial(
                reason = wire.reason,
                until = wire.until,
                appealUrl = wire.appealUrl,
            )
            bannedState.setBanned(denial)
            accessDeniedBus.signalDenied(denial)
        }
}

/**
 * A `200` from the allowlisted account-status probe is the only thing that lifts
 * a local ban. Gated on the exact path so an unrelated success — a public
 * app-config fetch, say — can't clear a block the server still enforces.
 */
internal fun clearBanIfStatusProbeSucceeded(response: HttpResponse, bannedState: BannedState) {
    if (!bannedState.isBanned) return
    if (!response.status.isSuccess()) return
    if (response.request.url.encodedPath != ACCOUNT_STATUS_PATH) return
    bannedState.clearBanned()
}

/**
 * Server's error envelope. Only [ProblemWire.error]'s `code` is read — the
 * message is server-authored English we never show.
 */
@Serializable
internal data class ProblemWire(val error: Problem) {
    @Serializable
    internal data class Problem(val code: String)
}

/**
 * `account_not_found` is our server saying the token verified but its
 * `auth.users` row is gone (AUTH-29): the session can't be saved, only replaced.
 * Every other 401 stays untouched, including the ordinary expired-token one the
 * bearer plugin refreshes through — misreading one of those as account death
 * would sign people out over a hiccup.
 */
internal suspend fun signalAccountMissingIfEnveloped(
    response: HttpResponse,
    onAccountMissing: suspend () -> Unit,
) {
    val code = Catching { response.body<ProblemWire>().error.code }.getOrNull()
    if (code == ACCOUNT_NOT_FOUND_CODE) onAccountMissing()
}

private const val ACCOUNT_NOT_FOUND_CODE = "account_not_found"
