package com.cards.integration.helpers

import com.dangerfield.cards.features.lobby.impl.LobbyViewModel
import com.dangerfield.cards.libraries.core.AuthGate
import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.core.AuthVerdict
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
import com.dangerfield.cards.libraries.networking.impl.SessionRejectionBusImpl
import com.dangerfield.cards.libraries.rooms.MatchmakingRepository
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.impl.HttpMatchmakingApi
import com.dangerfield.cards.libraries.rooms.impl.HttpRoomApi
import com.dangerfield.cards.libraries.rooms.impl.KtorRoomSocketTransport
import com.dangerfield.cards.libraries.rooms.impl.MatchmakingRepositoryImpl
import com.dangerfield.cards.libraries.rooms.impl.ReconnectingRoomSocket
import com.dangerfield.cards.libraries.rooms.impl.RoomRepositoryImpl
import kotlinx.coroutines.cancel
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
 *
 * Pass [latencyMs] to route the socket through a [LatencyTransport] that adds
 * that one-way delay to every send and inbound frame (a slow-RTT network), so a
 * test can prove time-sensitive client behaviour holds under latency.
 */
class TestClient(
    serverUrl: String,
    val userId: String = randomUserId(),
    faulty: Boolean = false,
    latencyMs: Long? = null,
    // Mimic a debug build: wrap the raw engine WS session in a plain
    // pass-through delegator at the same pipeline phase the Wiretap WS
    // inspector uses. Defeats Ktor's "engine session is already a
    // DefaultWebSocketSession" fast path — the config every dev build runs
    // and the trigger for MP-32. See [wiretapAlikeRawSessionWrapper].
    wiretapWrapped: Boolean = false,
    // Ungated by default (this suite tests the server contract, not the client
    // auth gate); pass a SettableAuthGate to exercise short-circuit behavior.
    private val authGate: AuthGate = AlwaysReadyAuthGate,
) {
    /** Non-null only when constructed with `faulty = true`. */
    var faults: FaultInjectingTransport? = null
        private set

    // One app-lifetime scope per client. The socket's reconnect loop + the
    // repository's fire-and-forget work all live here, so [close] can quiesce a
    // client's background reconnect attempts — important for a test that bounces
    // the server (a still-retrying client otherwise hammers the recycled port and
    // destabilises later tests in the same JVM).
    private val appScope = AppCoroutineScope(DefaultDispatcherProvider())

    private val config = object : NetworkConfig {
        override val baseUrl: String = serverUrl
    }
    private val networkClient = NetworkClientImpl(
        config,
        TokenProvider(userId),
        FixedHeaders,
        NetworkReachabilityImpl(appScope),
        AccessDeniedBusImpl(),
        SessionRejectionBusImpl(),
        { authGate },
    )

    init {
        if (wiretapWrapped) {
            @OptIn(com.dangerfield.cards.libraries.networking.InternalNetworkingApi::class)
            networkClient.authenticatedClient.wiretapAlikeRawSessionWrapper()
        }
    }

    val repository: RoomRepository = run {
        val realTransport = KtorRoomSocketTransport(networkClient, config)
        val faultable = if (faulty) {
            FaultInjectingTransport(realTransport).also { faults = it }
        } else {
            realTransport
        }
        val transport = latencyMs?.let { LatencyTransport(faultable, it) } ?: faultable
        val socket = ReconnectingRoomSocket(transport, appScope)
        RoomRepositoryImpl(HttpRoomApi(networkClient), socket, appScope)
    }

    /** Cancel this client's background work (reconnect loop, fire-and-forget calls). */
    fun close() = appScope.cancel()

    /** The real matchmaking client surface — `find` / `play-bots` over HTTP. */
    val matchmaking: MatchmakingRepository = MatchmakingRepositoryImpl(HttpMatchmakingApi(networkClient))

    /** Build the real lobby VM for this user. Mirrors how the entry point constructs it. */
    fun lobbyVm(
        prefilledCode: String? = null,
        autoCreate: Boolean = false,
        maxSeats: Int? = null,
        buyIn: Long? = null,
        open: Boolean = false,
        pickedFeltProductId: String? = null,
        pickedCardBackProductId: String? = null,
    ): LobbyViewModel =
        LobbyViewModel(
            prefilledCode = prefilledCode,
            autoCreate = autoCreate,
            maxSeats = maxSeats,
            buyIn = buyIn,
            open = open,
            pickedFeltProductId = pickedFeltProductId,
            pickedCardBackProductId = pickedCardBackProductId,
            rooms = repository,
            auth = FakeAuthRepository(userId),
            profile = NoProfileRepository,
            equipment = NoEquipmentRepository,
            chips = NoChipsRepository,
            appScope = AppCoroutineScope(DefaultDispatcherProvider()),
        )

    /** Open a raw connection handle (real socket) — for gameplay/contract-level tests. */
    fun connect(code: String): RoomConnectionHandle = repository.connect(code)

    private class TokenProvider(private val userId: String) : AuthTokenProvider {
        override suspend fun awaitReady() = Unit
        override suspend fun accessToken(): String = IntegrationAuth.mintJwt(userId)
        override suspend fun refreshAccessToken(): String = IntegrationAuth.mintJwt(userId)
        override suspend fun isAnonymousSession(): Boolean = false
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

    /**
     * No equipped cosmetics — the host creates rooms with no felt/card-back
     * override (SHOP-3), so the table falls back to each player's own cosmetic.
     */
    private object NoEquipmentRepository : com.dangerfield.cards.libraries.cards.EquipmentRepository {
        override fun observeEquipped() =
            flowOf(emptyList<com.dangerfield.cards.libraries.cards.EquipmentEntry>())
        override suspend fun getAll() = emptyList<com.dangerfield.cards.libraries.cards.EquipmentEntry>()
        override suspend fun equip(productId: String) =
            com.dangerfield.cards.libraries.cards.EquipmentToggleResult.NoChange
        override suspend fun unequip(productId: String) =
            com.dangerfield.cards.libraries.cards.EquipmentToggleResult.NoChange
        override suspend fun applyServerSnapshot(
            authoritative: List<com.dangerfield.cards.libraries.cards.EquipmentEntry>,
        ) = Unit
        override suspend fun dropOrphanEquipment(ownedProductIds: Set<String>) = emptyList<String>()
        override suspend fun deleteAll() = Unit
        override suspend fun sync() = Result.success(Unit)
    }

    /**
     * No wallet — the lobby flow's only chip touch is the MP-27 reconcile sync
     * fired on leave, which a no-op satisfies (the harness asserts on room state,
     * not balances).
     */
    private object NoChipsRepository : com.dangerfield.cards.libraries.cards.ChipsRepository {
        override fun observeBalance() = flowOf<Long?>(null)
        override suspend fun getBalance(): Long? = null
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
        override suspend fun setBalance(authoritativeBalance: Long) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun sync() = Result.success(Unit)
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

/** The suite's default: never gates — every authed call fires against the real server. */
internal object AlwaysReadyAuthGate : AuthGate {
    override fun verdict(requirement: AuthRequirement): AuthVerdict = AuthVerdict.Ready
    override suspend fun awaitVerdict(requirement: AuthRequirement): AuthVerdict = AuthVerdict.Ready
}

/** Flip [next] to Blocked to prove a short-circuited call never reaches the wire. */
internal class SettableAuthGate(
    var next: AuthVerdict = AuthVerdict.Ready,
) : AuthGate {
    override fun verdict(requirement: AuthRequirement): AuthVerdict = next
    override suspend fun awaitVerdict(requirement: AuthRequirement): AuthVerdict = next
}
