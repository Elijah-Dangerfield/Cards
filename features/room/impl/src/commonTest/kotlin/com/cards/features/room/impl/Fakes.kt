package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.game.Personality
import com.dangerfield.cards.libraries.game.PlayStyle
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import com.dangerfield.cards.libraries.review.ReviewPromptCoordinator
import com.dangerfield.cards.libraries.review.ReviewTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Test fakes for [PlayPokerViewModel]. Hand-rolled rather than mock-library because
 * commonMain code can't rely on JVM-only mock libraries, and the interfaces are small
 * enough that hand-rolled fakes are clearer than mock setup anyway.
 *
 * Each fake exposes controllable flow handles + spy fields so tests can drive engine
 * state, simulate cache updates, and assert calls made by the VM.
 */

// ---------- PokerSession ----------

class FakePokerSession(
    initialGameState: GameState = stubGameState(),
) : PokerSession {

    private val _gameStateFlow = MutableStateFlow(initialGameState)
    override val gameStateFlow: StateFlow<GameState> = _gameStateFlow

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.Connected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    val submittedIntents = mutableListOf<PlayerIntent>()
    var requestNextHandCount: Int = 0

    fun emitGameState(state: GameState) {
        _gameStateFlow.value = state
    }

    fun emitEvent(event: GameEvent) {
        _events.tryEmit(event)
    }

    fun emitConnectionState(connection: ConnectionState) {
        _connectionState.value = connection
    }

    override suspend fun submit(intent: PlayerIntent) {
        submittedIntents += intent
    }

    override fun requestNextHand() {
        requestNextHandCount += 1
    }
}

// ---------- PokerSessionFactory ----------

class FakePokerSessionFactory(
    val session: FakePokerSession = FakePokerSession(),
    override val difficultyName: String = "Standard",
    override val xpMode: com.dangerfield.cards.libraries.cards.XpMode = com.dangerfield.cards.libraries.cards.XpMode.BOTS,
    val personalities: Map<Int, Personality> = emptyMap(),
) : PokerSessionFactory {

    var bootstrapCalled: Boolean = false
    var capturedOnHandEnded: ((GameEvent.HandEnded, GameState, Long) -> Unit)? = null
    var capturedBotSpeedProvider: (() -> BotSpeed)? = null

    override fun create(
        humanSeatIndex: Int,
        botSpeedProvider: () -> BotSpeed,
        onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit,
    ): PokerSession {
        capturedOnHandEnded = onHandEnded
        capturedBotSpeedProvider = botSpeedProvider
        return session
    }

    override suspend fun bootstrap(session: PokerSession) {
        bootstrapCalled = true
        // Test stays paused here — production loop runs the bot loop; fake just records.
    }

    override fun occupantsFor(state: GameState): List<SeatOccupant> = state.seats.map { seat ->
        seatToOccupant(seat, personalities[seat.index])
    }

    override fun tableFor(
        state: GameState,
        lastWinners: GameEvent.HandEnded?,
        lastActionBySeat: Map<Int, PlayerAction>,
        humanProfile: Profile.Authenticated?,
    ): TableUiState = TableUiState.fromGameState(
        gameState = state,
        humanSeatIndex = state.seats.firstOrNull { !it.isBot }?.index ?: 0,
        personalitiesBySeat = emptyMap(),
        lastWinners = lastWinners,
        lastActionBySeat = lastActionBySeat,
        humanProfile = humanProfile,
    )
}

// ---------- AppCache ----------

class FakeAppCache(initial: AppData = AppData()) : AppCache {
    private val state = MutableStateFlow(initial)
    override val updates: Flow<AppData> = state
    override suspend fun get(): AppData = state.value
    override suspend fun set(value: AppData) { state.value = value }
    override suspend fun clear() { state.value = AppData() }

    fun emit(newValue: AppData) { state.value = newValue }
}

// ---------- ProgressionRepository ----------

class FakeProgressionRepository(initial: Progression = Progression.Empty) : ProgressionRepository {
    private val state = MutableStateFlow(initial)
    val awardedSummaries = mutableListOf<HandResultSummary>()
    var nextAwardedEvents: List<XpEvent> = emptyList()
    var onAwardForHand: (() -> Unit)? = null

    override fun observeProgression(): Flow<Progression> = state
    override suspend fun getProgression(): Progression = state.value

    override suspend fun awardForHand(summary: HandResultSummary): List<XpEvent> {
        awardedSummaries += summary
        onAwardForHand?.invoke()
        return nextAwardedEvents
    }

    override suspend fun applyAchievementXp(delta: Int, description: String?): XpEvent =
        XpEvent(
            id = 0,
            deltaXp = delta,
            source = XpSource.ACHIEVEMENT,
            mode = XpMode.BOTS,
            handId = null,
            description = description,
            createdAtEpochMs = 0,
        )

    override suspend fun deleteAll() { state.value = Progression.Empty }

    fun emit(progression: Progression) { state.value = progression }
}

// ---------- AchievementRepository ----------

class FakeAchievementRepository(
    initial: AchievementProgress = AchievementProgress.Empty,
) : AchievementRepository {
    private val state = MutableStateFlow(initial)
    val recordedHands = mutableListOf<Pair<HandResultSummary, AchievementHandContext>>()
    var nextEarned: List<EarnedAchievement> = emptyList()

    override fun observeProgress(): Flow<AchievementProgress> = state
    override suspend fun getProgress(): AchievementProgress = state.value

    override suspend fun recordHand(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ): List<EarnedAchievement> {
        recordedHands += (summary to context)
        return nextEarned
    }

    override suspend fun deleteAll() { state.value = AchievementProgress.Empty }
}

// ---------- EquipmentRepository ----------

class FakeEquipmentRepository(
    initial: List<com.dangerfield.cards.libraries.cards.EquipmentEntry> = emptyList(),
) : com.dangerfield.cards.libraries.cards.EquipmentRepository {
    private val state = MutableStateFlow(initial)

    fun emit(entries: List<com.dangerfield.cards.libraries.cards.EquipmentEntry>) {
        state.value = entries
    }

    override fun observeEquipped(): Flow<List<com.dangerfield.cards.libraries.cards.EquipmentEntry>> =
        state.asStateFlow().map { list -> list.filter { it.isEquipped } }

    override suspend fun getAll(): List<com.dangerfield.cards.libraries.cards.EquipmentEntry> = state.value

    override suspend fun equip(productId: String): com.dangerfield.cards.libraries.cards.EquipmentToggleResult {
        return com.dangerfield.cards.libraries.cards.EquipmentToggleResult.Success
    }

    override suspend fun unequip(productId: String): com.dangerfield.cards.libraries.cards.EquipmentToggleResult {
        return com.dangerfield.cards.libraries.cards.EquipmentToggleResult.Success
    }

    override suspend fun applyServerSnapshot(authoritative: List<com.dangerfield.cards.libraries.cards.EquipmentEntry>) {
        state.value = authoritative
    }

    override suspend fun dropOrphanEquipment(ownedProductIds: Set<String>): List<String> {
        val orphans = state.value.map { it.productId }.filter { it !in ownedProductIds }
        state.value = state.value.filter { it.productId in ownedProductIds }
        return orphans
    }

    override suspend fun deleteAll() { state.value = emptyList() }
    override suspend fun sync(): Result<Unit> = Result.success(Unit)
}

// ---------- ReviewPromptCoordinator ----------

class FakeReviewPromptCoordinator(
    var nextResult: Boolean = true,
) : ReviewPromptCoordinator {
    val requested = mutableListOf<ReviewTrigger>()
    override suspend fun requestPrompt(trigger: ReviewTrigger): Boolean {
        requested += trigger
        return nextResult
    }
}

// ---------- Test data builders ----------

private val testSettings = RoomSettings(
    smallBlind = 5,
    bigBlind = 10,
    startingStack = 1_000,
    maxSeats = 6,
    turnTimerSeconds = 30,
)

fun stubGameState(
    seats: List<Seat> = listOf(
        testSeat(index = 0, displayName = "You", isBot = false, playerId = "human"),
        testSeat(index = 1, displayName = "Steve", isBot = true, playerId = "bot-1"),
    ),
    handNumber: Int = 1,
    actingSeatIndex: Int? = 0,
    street: BettingRound = BettingRound.Preflop,
): GameState = GameState(
    settings = testSettings,
    handNumber = handNumber,
    buttonSeatIndex = 0,
    seats = seats,
    community = emptyList(),
    street = street,
    currentBetThisStreet = 0L,
    lastFullRaiseSize = 0L,
    actingSeatIndex = actingSeatIndex,
    deckRemaining = emptyList(),
)

fun testSeat(
    index: Int,
    displayName: String = "Player$index",
    isBot: Boolean = false,
    playerId: String? = "p-$index",
    stack: Long = 1_000,
): Seat = Seat(
    index = index,
    playerId = playerId,
    displayName = displayName,
    stack = stack,
    seatStatus = SeatStatus.Active,
    handParticipation = HandParticipation.InHand,
    isBot = isBot,
)

fun bizzaroPersonality(label: String = "Tight Aggressive"): Personality = Personality(
    label = label,
    style = PlayStyle.TightAggressive,
    vpip = 0.22,
    pfr = 0.18,
)

// ---------- ProfileRepository ----------

/**
 * Minimal [ProfileRepository] fake for VM tests. Only [observe] is
 * meaningfully implemented — every other method throws because the
 * play-poker VM only reads the flow.
 *
 * Defaults to "nothing emitted" so existing tests (which don't care about
 * profile-driven projection) pin the engine-side "You" displayName.
 * Tests that need to assert on the profile-driven seat shape can flip
 * the value via [emit].
 */
class FakeProfileRepository : ProfileRepository {
    private val flow = MutableSharedFlow<Profile>(replay = 1, extraBufferCapacity = 8)

    fun emit(profile: Profile) {
        flow.tryEmit(profile)
    }

    override suspend fun current(): Profile = error("not used by PlayPokerViewModel")
    override fun observe(): Flow<Profile> = flow

    override suspend fun update(
        displayName: String?,
        avatarEmoji: String?,
        avatarBackgroundColor: String?,
        clearAvatarBackgroundColor: Boolean,
    ): UpdateProfileOutcome = error("update not used")

    override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("fetchAvatarPack not used")
}
