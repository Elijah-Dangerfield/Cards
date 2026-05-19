package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.AuthTokenProvider
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
    private val authTokenProviderProvider: () -> AuthTokenProvider,
    private val headersProvider: ClientHeadersProvider,
) : NetworkClient {

    // Lazy provider rather than direct injection: the real
    // [AuthTokenProvider] (in `:libraries:identity:impl`) depends on the
    // unauthenticated HTTP client to call `/v1/auth/refresh`, which would
    // create a constructor-time DI cycle (network → auth → network). Both
    // sides are already singletons (`SingleIn(AppScope::class)`), so this
    // resolves to the same instance — we just defer when the graph walks
    // through it.
    private val authTokenProvider: AuthTokenProvider by lazy { authTokenProviderProvider() }

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
                        val token = authTokenProvider.getAccessToken() ?: return@loadTokens null
                        BearerTokens(accessToken = token, refreshToken = "")
                    }
                    refreshTokens {
                        val token = authTokenProvider.refreshAccessToken() ?: return@refreshTokens null
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
        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                private val log = KLog.withTag("Network")
                override fun log(message: String) {
                    log.d { message }
                }
            }
        }
    }
    expectSuccess = true
}
