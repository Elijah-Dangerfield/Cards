package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.networking.ClientHeaders
import com.dangerfield.cards.libraries.networking.ClientHeadersProvider
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.NetworkConfig
import com.dangerfield.cards.libraries.networking.NetworkJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
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
class NetworkClientImpl(
    private val config: NetworkConfig,
    private val authRepositoryProvider: () -> AuthRepository,
    private val headersProvider: ClientHeadersProvider,
) : NetworkClient {

    // Lazy provider rather than direct injection: [AuthRepository] (in
    // `:libraries:identity:impl`) depends on [ProfileApi] which depends
    // on [NetworkClient] for the `/v1/me`-flavored calls. That'd form a
    // construction-time DI cycle (network → auth → network). Both sides
    // are singletons; the lazy resolves the same instance, it just
    // defers when the graph walks through it.
    private val authRepository: AuthRepository by lazy { authRepositoryProvider() }

    override val client: HttpClient by lazy {
        HttpClient {
            applyCommonConfig(config, headersProvider)
        }
    }

    override val authenticatedClient: HttpClient by lazy {
        HttpClient {
            applyCommonConfig(config, headersProvider)
            install(Auth) {
                bearer {
                    loadTokens {
                        // AuthRepository.accessToken() suspends until auth
                        // resolves — internally it waits on the auth state
                        // flow's first emission. No more polling, no 5s
                        // ceiling here: the auth layer's resolve is the
                        // backstop.
                        val token = authRepository.accessToken()
                            ?: return@loadTokens null
                        BearerTokens(accessToken = token, refreshToken = "")
                    }
                    refreshTokens {
                        val token = authRepository.refreshAccessToken()
                            ?: return@refreshTokens null
                        BearerTokens(accessToken = token, refreshToken = "")
                    }
                    sendWithoutRequest { true }
                }
            }
            // WebSocket plugin so callers like :libraries:rooms:impl can
            // open room sockets via the same authenticated client (the
            // Auth bearer is attached on the WS handshake). The plugin
            // is additive — existing HTTP calls don't notice it.
            install(WebSockets) {
                pingIntervalMillis = 15.seconds.inWholeMilliseconds
            }
        }
    }
}

private fun HttpClientConfig<*>.applyCommonConfig(
    config: NetworkConfig,
    headersProvider: ClientHeadersProvider,
) {
    install(ContentNegotiation) {
        json(NetworkJson)
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
    }
    if (BuildInfo.isDebug) {
        // Debug-only by design: BODY-level logging dumps full request +
        // response payloads which we don't want in release builds
        // (privacy + log volume). KLog tag "Network" lets you filter
        // the device log for just HTTP traffic. Bumped to ALL so
        // headers + bodies are both visible; raised to INFO severity
        // so it stays in logcat at default filter levels rather than
        // hiding under DEBUG.
        install(Logging) {
            level = LogLevel.ALL
            logger = object : Logger {
                private val log = KLog.withTag("Network")
                override fun log(message: String) {
                    log.i { message }
                }
            }
        }
    }
    expectSuccess = true
}
