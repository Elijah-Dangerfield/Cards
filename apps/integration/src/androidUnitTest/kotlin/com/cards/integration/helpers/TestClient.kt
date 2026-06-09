package com.cards.integration.helpers

import com.dangerfield.cards.features.lobby.impl.LobbyViewModel
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.DefaultDispatcherProvider
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.networking.AuthTokenProvider
import com.dangerfield.cards.libraries.networking.ClientHeaders
import com.dangerfield.cards.libraries.networking.ClientHeadersProvider
import com.dangerfield.cards.libraries.networking.NetworkConfig
import com.dangerfield.cards.libraries.networking.impl.NetworkClientImpl
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.impl.HttpRoomApi
import com.dangerfield.cards.libraries.rooms.impl.KtorRoomSocketTransport
import com.dangerfield.cards.libraries.rooms.impl.ReconnectingRoomSocket
import com.dangerfield.cards.libraries.rooms.impl.RoomRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/**
 * One end-to-end test user: the REAL client stack — `RoomRepositoryImpl` over the
 * real HTTP API + reconnecting WebSocket — pointed at [serverUrl], wrapped in the
 * REAL [LobbyViewModel]. This is the production code path from the lobby down to
 * the wire; only auth and per-request headers are stubbed.
 *
 * [userId] is the JWT `sub` AND the identity the fake [AuthRepository] reports, so
 * the VM's host/seat logic lines up with what the server sees.
 */
class TestClient(
    serverUrl: String,
    val userId: String = UUID.randomUUID().toString(),
    prefilledCode: String? = null,
    autoCreate: Boolean = false,
) {
    val repository: RoomRepository = buildRepository(serverUrl, userId)

    val vm: LobbyViewModel = LobbyViewModel(
        prefilledCode = prefilledCode,
        autoCreate = autoCreate,
        rooms = repository,
        auth = FakeAuthRepository(userId),
        appScope = AppCoroutineScope(DefaultDispatcherProvider()),
    )
}

private fun buildRepository(serverUrl: String, userId: String): RoomRepository {
    val config = object : NetworkConfig {
        override val baseUrl: String = serverUrl
    }
    val tokens = object : AuthTokenProvider {
        override suspend fun awaitReady() = Unit
        override suspend fun accessToken(): String = IntegrationAuth.mintJwt(userId)
        override suspend fun refreshAccessToken(): String = IntegrationAuth.mintJwt(userId)
    }
    val headers = object : ClientHeadersProvider {
        override fun current(): ClientHeaders = ClientHeaders(
            platform = "android",
            appVersion = "0.0.0",
            buildNumber = "0",
            acceptLanguage = "en-US",
            countryCode = null,
            installId = null,
        )
    }
    val networkClient = NetworkClientImpl(config, tokens, headers)
    val transport = KtorRoomSocketTransport(networkClient, config)
    val socket = ReconnectingRoomSocket(transport, AppCoroutineScope(DefaultDispatcherProvider()))
    return RoomRepositoryImpl(HttpRoomApi(networkClient), socket)
}

/** Always signed in as [userId]; everything else is unused by the lobby flow. */
private class FakeAuthRepository(userId: String) : AuthRepository {
    private val state: AuthState =
        AuthState.Authenticated(userId = userId, isAnonymous = true, email = null)

    override suspend fun current(): AuthState = state
    override fun observe(): Flow<AuthState> = flowOf(state)
    override suspend fun retry(): AuthState = state
    override suspend fun signInWithEmail(email: String, password: String) = error("unused")
    override suspend fun signUpWithEmail(email: String, password: String) = error("unused")
    override suspend fun refreshSession() = error("unused")
    override suspend fun resendVerificationEmail(email: String) = error("unused")
    override suspend fun sendPasswordResetEmail(email: String) = error("unused")
    override suspend fun signOut() = Unit
    override suspend fun deleteAccount() = error("unused")
    override suspend fun linkOAuthIdentity(provider: OAuthProvider) = error("unused")
    override suspend fun signInWithOAuth(provider: OAuthProvider) = error("unused")
    override suspend fun linkEmailIdentity(email: String, password: String) = error("unused")
}
