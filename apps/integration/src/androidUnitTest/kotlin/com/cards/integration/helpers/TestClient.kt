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
import com.dangerfield.cards.libraries.networking.impl.AccessDeniedBusImpl
import com.dangerfield.cards.libraries.networking.impl.NetworkClientImpl
import com.dangerfield.cards.libraries.networking.impl.NetworkReachabilityImpl
import com.dangerfield.cards.libraries.rooms.MatchmakingRepository
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.impl.HttpMatchmakingApi
import com.dangerfield.cards.libraries.rooms.impl.HttpRoomApi
import com.dangerfield.cards.libraries.rooms.impl.KtorRoomSocketTransport
import com.dangerfield.cards.libraries.rooms.impl.MatchmakingRepositoryImpl
import com.dangerfield.cards.libraries.rooms.impl.ReconnectingRoomSocket
import com.dangerfield.cards.libraries.rooms.impl.RoomRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/**
 * One end-to-end test user: the REAL client stack — `RoomRepositoryImpl` over the
 * real HTTP API + reconnecting WebSocket — pointed at [serverUrl]. Build a real
 * [LobbyViewModel] on demand with [lobbyVm].
 *
 * [userId] is the JWT `sub` AND the identity the fake [AuthRepository] reports, so
 * the VM's host/seat logic lines up with what the server sees.
 *
 * Pass [faulty] = true to route the socket through a [FaultInjectingTransport],
 * exposed as [faults], so a test can drop/block the connection and exercise the
 * reconnect + presence machinery.
 */
class TestClient(
    serverUrl: String,
    val userId: String = randomUserId(),
    faulty: Boolean = false,
) {
    /** Non-null only when constructed with `faulty = true`. */
    var faults: FaultInjectingTransport? = null
        private set

    private val config = object : NetworkConfig {
        override val baseUrl: String = serverUrl
    }
    private val networkClient = NetworkClientImpl(
        config,
        TokenProvider(userId),
        FixedHeaders,
        NetworkReachabilityImpl(AppCoroutineScope(DefaultDispatcherProvider())),
        AccessDeniedBusImpl(),
    )

    val repository: RoomRepository = run {
        val realTransport = KtorRoomSocketTransport(networkClient, config)
        val transport = if (faulty) {
            FaultInjectingTransport(realTransport).also { faults = it }
        } else {
            realTransport
        }
        val socket = ReconnectingRoomSocket(transport, AppCoroutineScope(DefaultDispatcherProvider()))
        RoomRepositoryImpl(HttpRoomApi(networkClient), socket, AppCoroutineScope(DefaultDispatcherProvider()))
    }

    /** The real matchmaking client surface — `find` / `play-bots` over HTTP. */
    val matchmaking: MatchmakingRepository = MatchmakingRepositoryImpl(HttpMatchmakingApi(networkClient))

    /** Build the real lobby VM for this user. Mirrors how the entry point constructs it. */
    fun lobbyVm(
        prefilledCode: String? = null,
        autoCreate: Boolean = false,
        maxSeats: Int? = null,
        buyIn: Long? = null,
        open: Boolean = false,
    ): LobbyViewModel =
        LobbyViewModel(
            prefilledCode = prefilledCode,
            autoCreate = autoCreate,
            maxSeats = maxSeats,
            buyIn = buyIn,
            open = open,
            rooms = repository,
            auth = FakeAuthRepository(userId),
            profile = NoProfileRepository,
            appScope = AppCoroutineScope(DefaultDispatcherProvider()),
        )

    /** Open a raw connection handle (real socket) — for gameplay/contract-level tests. */
    fun connect(code: String): RoomConnectionHandle = repository.connect(code)

    private class TokenProvider(private val userId: String) : AuthTokenProvider {
        override suspend fun awaitReady() = Unit
        override suspend fun accessToken(): String = IntegrationAuth.mintJwt(userId)
        override suspend fun refreshAccessToken(): String = IntegrationAuth.mintJwt(userId)
    }

    private object FixedHeaders : ClientHeadersProvider {
        override fun current(): ClientHeaders = ClientHeaders(
            platform = "android",
            appVersion = "0.0.0",
            buildNumber = "0",
            acceptLanguage = "en-US",
            countryCode = null,
            installId = null,
            sessionId = "test-session",
        )
    }

    /** Avatar isn't exercised by these flows; never emits a profile. */
    private object NoProfileRepository : com.dangerfield.cards.libraries.identity.profile.ProfileRepository {
        override suspend fun current() = error("unused")
        override fun observe() =
            kotlinx.coroutines.flow.emptyFlow<com.dangerfield.cards.libraries.identity.profile.Profile>()
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ) = error("unused")
        override suspend fun fetchAvatarPack() = error("unused")
    }

    /** Always signed in as [userId]; the rest is unused by the lobby flow. */
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
}

internal fun randomUserId(): String = UUID.randomUUID().toString()
