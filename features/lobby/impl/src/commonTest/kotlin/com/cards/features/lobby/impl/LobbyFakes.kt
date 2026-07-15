package com.dangerfield.cards.features.lobby.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.rooms.AddBotOutcome
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.RemoveBotOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * Base [RoomRepository] stub: every call fails loudly. Fakes extend this and
 * override only the calls their test exercises, so an unexpected repo call
 * surfaces as a test failure instead of a silently-wrong default.
 */
internal open class StubRoomRepository : RoomRepository {
    override suspend fun createRoom(
        maxSeats: Int?,
        buyIn: Long?,
        open: Boolean,
        feltProductId: String?,
        cardBackProductId: String?,
    ): CreateRoomOutcome = error("createRoom not stubbed")

    override suspend fun joinRoom(code: String): JoinRoomOutcome = error("joinRoom not stubbed")
    override suspend fun leaveRoom(code: String): LeaveRoomOutcome = error("leaveRoom not stubbed")
    override suspend fun addBot(code: String, seatIndex: Int?): AddBotOutcome = error("addBot not stubbed")
    override suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome =
        error("removeBot not stubbed")

    override suspend fun getActiveRooms(): GetActiveRoomsOutcome = error("getActiveRooms not stubbed")
    override fun observeActiveRooms(): Flow<List<Room>> = error("observeActiveRooms not stubbed")
    override fun connect(code: String): RoomConnectionHandle = error("connect not stubbed")
}

/**
 * The workhorse fake: canned outcomes per call, a join counter, and a
 * [connect] whose connection flow is supplied by [observe] so tests can
 * drive socket frames.
 */
internal class FakeRoomRepository(
    private val createOutcome: CreateRoomOutcome =
        CreateRoomOutcome.NetworkError(RuntimeException("simulated network error")),
    private val joinOutcome: JoinRoomOutcome =
        JoinRoomOutcome.NetworkError(RuntimeException("simulated network error")),
    private val leaveOutcome: LeaveRoomOutcome = LeaveRoomOutcome.Success(),
    private val observe: (String) -> Flow<RoomConnection> = { flow {} },
) : StubRoomRepository() {
    var joinCalls: Int = 0
        private set
    var createdFeltProductId: String? = null
        private set
    var createdCardBackProductId: String? = null
        private set

    override suspend fun createRoom(
        maxSeats: Int?,
        buyIn: Long?,
        open: Boolean,
        feltProductId: String?,
        cardBackProductId: String?,
    ): CreateRoomOutcome {
        createdFeltProductId = feltProductId
        createdCardBackProductId = cardBackProductId
        return createOutcome
    }

    override suspend fun joinRoom(code: String): JoinRoomOutcome {
        joinCalls += 1
        return joinOutcome
    }

    override suspend fun leaveRoom(code: String): LeaveRoomOutcome = leaveOutcome

    override fun connect(code: String): RoomConnectionHandle = object : RoomConnectionHandle {
        override val connection: Flow<RoomConnection> = observe(code)
        override val gameplayFrames: Flow<GameplayFrame> = flow { }
        override suspend fun send(frame: ClientFrame) = Unit
    }
}

/**
 * [RoomRepository] whose [connect] hands back a [RecordingHandle] so the
 * StartGame tests can assert the outbound [ClientFrame.StartHand] actually
 * reached the socket. Also records bot add/remove calls.
 */
internal class RecordingRoomRepository(
    private val createOutcome: CreateRoomOutcome,
    val handle: RecordingHandle = RecordingHandle(),
    private val addBotOutcome: AddBotOutcome? = null,
    /** When set, [addBot] parks on this after recording the call so a test can
     *  inspect the in-flight state before the request resolves. */
    private val addBotGate: CompletableDeferred<AddBotOutcome>? = null,
) : StubRoomRepository() {
    val addBotSeatIndexes: MutableList<Int?> = mutableListOf()
    val removedBotUserIds: MutableList<String> = mutableListOf()

    override suspend fun createRoom(
        maxSeats: Int?,
        buyIn: Long?,
        open: Boolean,
        feltProductId: String?,
        cardBackProductId: String?,
    ): CreateRoomOutcome = createOutcome

    override suspend fun addBot(code: String, seatIndex: Int?): AddBotOutcome {
        addBotSeatIndexes += seatIndex
        addBotGate?.let { return it.await() }
        return addBotOutcome ?: AddBotOutcome.Success(
            Room(
                code = code,
                hostUserId = "host",
                createdAtEpochMs = 0,
                maxSeats = 4,
                status = RoomStatus.Lobby,
                members = emptyList(),
            ),
        )
    }

    override suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome {
        removedBotUserIds += botUserId
        return RemoveBotOutcome.Success
    }

    override fun connect(code: String): RoomConnectionHandle = handle
}

internal class RecordingHandle : RoomConnectionHandle {
    val sent: MutableList<ClientFrame> = mutableListOf()
    override val connection: Flow<RoomConnection> = flow { }
    override val gameplayFrames: Flow<GameplayFrame> = flow { }
    override suspend fun send(frame: ClientFrame) {
        sent += frame
    }
}

/**
 * Lets a test gate `leaveRoom` on an external [CompletableDeferred] and
 * observe whether the call actually completed (vs. being cancelled mid-flight).
 */
internal class ControllableRoomRepository(
    private val createOutcome: CreateRoomOutcome,
    private val leaveGate: CompletableDeferred<LeaveRoomOutcome>,
) : StubRoomRepository() {
    var leaveStarted: Int = 0
        private set
    var leaveFinished: Int = 0
        private set

    override suspend fun createRoom(
        maxSeats: Int?,
        buyIn: Long?,
        open: Boolean,
        feltProductId: String?,
        cardBackProductId: String?,
    ): CreateRoomOutcome = createOutcome

    override suspend fun leaveRoom(code: String): LeaveRoomOutcome {
        leaveStarted += 1
        val outcome = leaveGate.await()
        leaveFinished += 1
        return outcome
    }

    override fun connect(code: String): RoomConnectionHandle = EmptyHandle
}

internal object EmptyHandle : RoomConnectionHandle {
    override val connection: Flow<RoomConnection> = flow { }
    override val gameplayFrames: Flow<GameplayFrame> = flow { }
    override suspend fun send(frame: ClientFrame) = Unit
}

/**
 * Equipment fake seeded with whatever the host has "equipped" — newest-first,
 * mirroring the real DAO order. Only [observeEquipped] is exercised by the
 * create path; the rest are no-ops.
 */
internal class FakeEquipmentRepository(
    equipped: List<String> = emptyList(),
) : EquipmentRepository {
    private val state = MutableStateFlow(
        equipped.mapIndexed { index, productId ->
            EquipmentEntry(
                productId = productId,
                isEquipped = true,
                syncState = EquipmentSyncState.Synced,
                updatedAtEpochMs = (equipped.size - index).toLong(),
            )
        },
    )

    override fun observeEquipped(): Flow<List<EquipmentEntry>> = state.asStateFlow()
    override suspend fun getAll(): List<EquipmentEntry> = state.value
    override suspend fun equip(productId: String): EquipmentToggleResult = EquipmentToggleResult.NoChange
    override suspend fun unequip(productId: String): EquipmentToggleResult = EquipmentToggleResult.NoChange
    override suspend fun applyServerSnapshot(authoritative: List<EquipmentEntry>) = Unit
    override suspend fun dropOrphanEquipment(ownedProductIds: Set<String>): List<String> = emptyList()
    override suspend fun deleteAll() = Unit
    override suspend fun sync(): Result<Unit> = Result.success(Unit)
}

/**
 * Records [sync] / [setBalance] calls so the MP-27 / MP-29 tests can assert
 * how the lobby Leave reconciles the wallet for a real-chip room.
 */
internal class FakeChipsRepository : ChipsRepository {
    var syncCalls: Int = 0
        private set
    var lastSetBalance: Long? = null
        private set
    private val balance = MutableStateFlow<Long?>(1000L)

    override fun observeBalance(): Flow<Long?> = balance.asStateFlow()
    override suspend fun getBalance(): Long? = balance.value
    override val walletJustCreated: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
    override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
    override suspend fun setBalance(authoritativeBalance: Long) {
        lastSetBalance = authoritativeBalance
        balance.value = authoritativeBalance
    }

    override suspend fun deleteAll() = Unit
    override suspend fun sync(): Result<Unit> {
        syncCalls += 1
        return Result.success(Unit)
    }
}

internal class AlwaysSignedInAuth : AuthRepository {
    private val authenticated: AuthState = AuthState.Authenticated(
        userId = LOBBY_TEST_LOCAL_USER,
        isAnonymous = true,
        email = null,
    )
    private val flow = MutableStateFlow(authenticated).asStateFlow()

    override suspend fun current(): AuthState = authenticated
    override fun observe(): Flow<AuthState> = flow
    override suspend fun retry(): AuthState = authenticated
    override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
        SignInOutcome.Success

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
        SignUpOutcome.VerificationRequired(email)

    override suspend fun refreshSession(): RefreshOutcome = RefreshOutcome.EmailConfirmed
    override suspend fun resendVerificationEmail(email: String): ResendOutcome = ResendOutcome.Sent
    override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome = SendResetOutcome.Sent
    override suspend fun signOut() = Unit
    override suspend fun deleteAccount(): DeleteAccountOutcome = DeleteAccountOutcome.Success
    override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
        LinkIdentityOutcome.Success

    override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
        LinkEmailIdentityOutcome.VerificationRequired(email)

    override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = SignInOutcome.Success
}

/** Lobby tests don't exercise the avatar; never emits a profile. */
internal object NoProfileRepository : ProfileRepository {
    override suspend fun current() = error("not used")
    override fun observe(): Flow<Profile> = emptyFlow()
    override suspend fun update(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ) = error("not used")

    override suspend fun fetchAvatarPack() = error("not used")
}

internal const val LOBBY_TEST_LOCAL_USER = "11111111-1111-1111-1111-111111111111"
