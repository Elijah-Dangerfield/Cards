package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.AuthTokenProvider
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
    private val authTokenProvider: AuthTokenProvider,
) : NetworkClient {

    override val client: HttpClient by lazy {
        HttpClient {
            applyCommonConfig(config)
        }
    }

    override val authenticatedClient: HttpClient by lazy {
        HttpClient {
            applyCommonConfig(config)
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
        }
    }
}

private fun HttpClientConfig<*>.applyCommonConfig(config: NetworkConfig) {
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
    }
    if (BuildInfo.isDebug) {
        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                private val log = KLog.withTag("Network")
                override fun log(message: String) = log.d { message }
            }
        }
    }
    expectSuccess = true
}
