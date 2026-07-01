package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.NextHandRefusal
import com.dangerfield.cards.features.room.impl.session.PokerSession
import com.dangerfield.cards.features.room.impl.session.PokerSessionFactory
import com.dangerfield.cards.features.room.impl.session.RemoteEmote
import com.dangerfield.cards.features.room.impl.session.seatToOccupant
import com.dangerfield.cards.features.room.impl.ui.label

import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.GameSpeed
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.PlayStyleHandSummary
import com.dangerfield.cards.libraries.cards.PlayStyleRepository
import com.dangerfield.cards.libraries.cards.PlayerStatHandSummary
import com.dangerfield.cards.libraries.cards.PlayerStats
import com.dangerfield.cards.libraries.cards.PlayerStatsRepository
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.RedeemResult
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
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.rooms.ClosedReason
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

    private val _roomClosed = MutableSharedFlow<ClosedReason>(extraBufferCapacity = 1)
    override val roomClosed: SharedFlow<ClosedReason> = _roomClosed.asSharedFlow()

    private val _opponentsLeft = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    override val opponentsLeft: SharedFlow<Unit> = _opponentsLeft.asSharedFlow()

    fun emitOpponentsLeft() {
        _opponentsLeft.tryEmit(Unit)
    }

    private val _emoteBlasts = MutableSharedFlow<RemoteEmote>(extraBufferCapacity = 32)
    override val emoteBlasts: SharedFlow<RemoteEmote> = _emoteBlasts.asSharedFlow()

    private val _nextHandRefused = MutableSharedFlow<NextHandRefusal>(extraBufferCapacity = 4)
    override val nextHandRefused: SharedFlow<NextHandRefusal> = _nextHandRefused.asSharedFlow()

    private val _tableCosmetics =
        MutableStateFlow<com.dangerfield.cards.features.room.impl.session.TableCosmetics?>(null)
    override val tableCosmetics: StateFlow<com.dangerfield.cards.features.room.impl.session.TableCosmetics?> =
        _tableCosmetics

    /** Drive the host's table-wide felt + card back the way the real session would (SHOP-3). */
    fun emitTableCosmetics(felt: String?, cardBack: String?) {
        _tableCosmetics.value =
            com.dangerfield.cards.features.room.impl.session.TableCosmetics(
                feltProductId = felt,
                cardBackProductId = cardBack,
            )
    }

    /** Drive a next-hand refusal the way the real session would (MP-22). */
    fun emitNextHandRefused(refusal: NextHandRefusal) {
        _nextHandRefused.tryEmit(refusal)
    }

    val submittedIntents = mutableListOf<PlayerIntent>()
    val sentEmotes = mutableListOf<String>()
    var requestNextHandCount: Int = 0
    var leaveCount: Int = 0
    // The authoritative post-cash-out balance leave() returns (MP-29). Null models
    // a leave that settled nothing (deferral / failure / free table).
    var leaveSettledBalance: Long? = null
    var rebuyCount: Int = 0

    // When set, [submit] records the intent then throws it — modelling a
    // server rejection (the real session surfaces a rejected ack as a throw).
    var submitError: Throwable? = null

    // When set, [rebuy] records the attempt then throws it — modelling a server
    // rejection (e.g. IntentRejectedException("insufficient chips")).
    var rebuyError: Throwable? = null

    /** Drive an inbound opponent emote the way the real session would. */
    fun emitEmote(seatIndex: Int, emoji: String) {
        _emoteBlasts.tryEmit(RemoteEmote(seatIndex = seatIndex, emoji = emoji))
    }

    fun emitRoomClosed(reason: ClosedReason) {
        _roomClosed.tryEmit(reason)
    }

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
        submitError?.let { throw it }
    }

    override fun requestNextHand() {
        requestNextHandCount += 1
    }

    override suspend fun rebuy() {
        rebuyCount += 1
        rebuyError?.let { throw it }
    }

    override suspend fun leave(): Long? {
        leaveCount += 1
        return leaveSettledBalance
    }

    override suspend fun sendEmote(emoji: String) {
        sentEmotes += emoji
    }
}

// ---------- ProgressionConfig ----------

/**
 * Drives the server-tunable level curve under test. Defaults to the bundled
 * [DefaultLevelCurve]; pass a steeper [curve] to prove the table derives
 * opponent/human levels through the configured curve rather than the bundled
 * default.
 */
class FakeProgressionConfig(
    private val curve: LevelCurve = DefaultLevelCurve,
) : ProgressionConfig {
    override fun rewardsForLevel(level: Int): List<LevelReward> = emptyList()
    override fun levelCurve(): LevelCurve = curve
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
    var capturedGameSpeedProvider: (() -> GameSpeed)? = null

    override fun create(
        humanSeatIndex: Int,
        gameSpeedProvider: () -> GameSpeed,
        onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit,
    ): PokerSession {
        capturedOnHandEnded = onHandEnded
        capturedGameSpeedProvider = gameSpeedProvider
        return session
    }

    override suspend fun bootstrap(session: PokerSession) {
        bootstrapCalled = true
        // Test stays paused here — production loop runs the bot loop; fake just records.
    }

    override fun humanSeatIndex(state: GameState): Int =
        state.seats.firstOrNull { !it.isBot }?.index ?: 0

    override fun occupantsFor(
        state: GameState,
        curve: com.dangerfield.cards.libraries.cards.LevelCurve,
    ): List<SeatOccupant> = state.seats.map { seat ->
        seatToOccupant(seat, personalities[seat.index], curve)
    }

    override fun tableFor(
        state: GameState,
        lastWinners: GameEvent.HandEnded?,
        lastActionBySeat: Map<Int, PlayerAction>,
        humanProfile: Profile.Authenticated?,
        humanLevel: Int?,
        curve: com.dangerfield.cards.libraries.cards.LevelCurve,
    ): TableUiState = TableUiState.fromGameState(
        gameState = state,
        humanSeatIndex = state.seats.firstOrNull { !it.isBot }?.index ?: 0,
        personalitiesBySeat = emptyMap(),
        lastWinners = lastWinners,
        lastActionBySeat = lastActionBySeat,
        humanProfile = humanProfile,
        humanLevel = humanLevel,
        curve = curve,
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

    override suspend fun sync(): Result<Unit> = Result.success(Unit)

    override suspend fun deleteAll() { state.value = Progression.Empty }

    override suspend fun debugSetTotalXp(totalXp: Long) {
        state.value = state.value.copy(totalXp = totalXp)
    }

    fun emit(progression: Progression) { state.value = progression }
}

// ---------- PlayStyleRepository ----------

class FakePlayStyleRepository(initial: PlayStyleAxes? = null) : PlayStyleRepository {
    private val state = MutableStateFlow(initial)
    val recordedHands = mutableListOf<PlayStyleHandSummary>()
    var opponentStyle: PlayStyleAxes? = null

    override fun observeOwnStyle(): Flow<PlayStyleAxes?> = state
    override suspend fun getOwnStyle(): PlayStyleAxes? = state.value

    override suspend fun recordHand(summary: PlayStyleHandSummary) {
        recordedHands += summary
    }

    override suspend fun sync(): Result<Unit> = Result.success(Unit)

    override suspend fun getStyleFor(userId: String): Result<PlayStyleAxes?> =
        Result.success(opponentStyle)

    override suspend fun deleteAll() { state.value = null }

    fun emit(style: PlayStyleAxes?) { state.value = style }
}

// ---------- PlayerStatsRepository ----------

class FakePlayerStatsRepository(initial: PlayerStats? = null) : PlayerStatsRepository {
    private val state = MutableStateFlow(initial)
    val recordedHands = mutableListOf<PlayerStatHandSummary>()

    private val effective = MutableStateFlow<Map<String, Long>>(emptyMap())

    override fun observeStats(): Flow<PlayerStats?> = state
    override suspend fun getStats(): PlayerStats? = state.value

    override fun observeEffectiveCounters(): Flow<Map<String, Long>> = effective
    override suspend fun effectiveCounters(): Map<String, Long> = effective.value

    override suspend fun recordHand(summary: PlayerStatHandSummary) {
        recordedHands += summary
    }

    override suspend fun sync(): Result<Unit> = Result.success(Unit)

    override suspend fun deleteAll() { state.value = null }

    fun emit(stats: PlayerStats?) { state.value = stats }
    fun emitCounters(counters: Map<String, Long>) { effective.value = counters }
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

    override suspend fun recordTutorialComplete(): EarnedAchievement? = null

    override suspend fun sync(): Result<Unit> = Result.success(Unit)

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

// ---------- InventoryRepository ----------

class FakeInventoryRepository(
    initial: List<InventoryItem> = emptyList(),
) : InventoryRepository {
    private val state = MutableStateFlow(initial)

    fun emit(items: List<InventoryItem>) { state.value = items }

    override fun observeInventory(): Flow<List<InventoryItem>> = state
    override suspend fun getInventory(): List<InventoryItem> = state.value
    override suspend fun redeemChipOffer(productId: String, costChips: Long): RedeemResult =
        RedeemResult.Success
    override suspend fun markConfirmed(productIds: Collection<String>) { }
    override suspend fun revertPurchase(productId: String) { }
    override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) { }
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
    lastSequence: Long = 0,
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
    lastSequence = lastSequence,
)

fun testSeat(
    index: Int,
    displayName: String = "Player$index",
    isBot: Boolean = false,
    playerId: String? = "p-$index",
    stack: Long = 1_000,
    xp: Long? = null,
    holeCards: List<Card> = emptyList(),
): Seat = Seat(
    index = index,
    playerId = playerId,
    displayName = displayName,
    stack = stack,
    seatStatus = SeatStatus.Active,
    handParticipation = HandParticipation.InHand,
    isBot = isBot,
    xp = xp,
    holeCards = holeCards,
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

/**
 * Catalog source for the room VM. Defaults to empty (badge-resolution only);
 * pass a non-empty [catalog] to exercise the MP quick-buy chip-pack sheet.
 */
class FakeProductsRepository(
    private val catalog: com.dangerfield.cards.libraries.products.ProductCatalog =
        com.dangerfield.cards.libraries.products.ProductCatalog.Empty,
) : com.dangerfield.cards.libraries.products.ProductsRepository {
    override fun observeCatalog(): Flow<com.dangerfield.cards.libraries.products.ProductCatalog> =
        MutableStateFlow(catalog)
    override suspend fun refresh(force: Boolean): Result<com.dangerfield.cards.libraries.products.ProductCatalog> =
        Result.success(catalog)
    override fun observeTimeAnchor(): Flow<com.dangerfield.cards.libraries.products.CatalogTimeAnchor?> =
        MutableStateFlow(null)
    override fun observeIsRefreshing(): Flow<Boolean> = MutableStateFlow(false)
}

/** In-memory wallet balance for the rebuy-gate + quick-buy tests. */
class FakeChipsRepository(
    initialBalance: Long? = 0L,
) : com.dangerfield.cards.libraries.cards.ChipsRepository {
    private val balance = MutableStateFlow(initialBalance)
    var syncCount: Int = 0
        private set

    /** How many times [setBalance] was called + the last value — MP-29 applies the
     *  server's post-cash-out balance directly on a settled leave, instead of a sync. */
    var setBalanceCount: Int = 0
        private set
    var lastSetBalance: Long? = null
        private set

    /**
     * Credit applied to the balance on the next [sync], simulating the
     * server cashing a leaver's seat stack back into the wallet. Consumed
     * once, then reset to zero.
     */
    var creditOnNextSync: Long = 0L

    override val walletJustCreated: StateFlow<Boolean> = MutableStateFlow(false)
    override fun observeBalance(): Flow<Long?> = balance
    override suspend fun getBalance(): Long? = balance.value
    override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
        balance.value = (balance.value ?: 0L) + amount
    }
    override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) {
        balance.value = (balance.value ?: 0L) - amount
    }
    override suspend fun setBalance(authoritativeBalance: Long) {
        setBalanceCount += 1
        lastSetBalance = authoritativeBalance
        balance.value = authoritativeBalance
    }
    override suspend fun deleteAll() { balance.value = null }
    override suspend fun sync(): Result<Unit> {
        syncCount += 1
        if (creditOnNextSync != 0L) {
            balance.value = (balance.value ?: 0L) + creditOnNextSync
            creditOnNextSync = 0L
        }
        return Result.success(Unit)
    }
    fun emit(value: Long?) { balance.value = value }
}

/** Records leave-time cash-out confirmations so a test can assert the credited amount. */
class FakeLeaveCashOutNotifier : LeaveCashOutNotifier {
    data class Credit(val credited: Long, val balanceAfter: Long)
    val credits = mutableListOf<Credit>()
    override suspend fun confirmCredit(credited: Long, balanceAfter: Long) {
        credits += Credit(credited, balanceAfter)
    }
}

/**
 * Records friend-request sends and returns a configurable [nextResult]. The
 * inbox half is unused by the play-poker VM so it stays an empty stub.
 */
class FakeFriendRepository(
    var nextResult: com.dangerfield.cards.libraries.social.SendFriendRequestResult =
        com.dangerfield.cards.libraries.social.SendFriendRequestResult.Requested,
) : com.dangerfield.cards.libraries.social.FriendRepository {
    val sentTo = mutableListOf<String>()

    override suspend fun sendRequest(
        userId: String,
    ): com.dangerfield.cards.libraries.social.SendFriendRequestResult {
        sentTo += userId
        return nextResult
    }

    override fun observeIncomingRequests():
        Flow<List<com.dangerfield.cards.libraries.social.FriendProfile>> =
        MutableStateFlow(emptyList())

    override suspend fun refreshIncomingRequests() = Unit

    override suspend fun accept(
        userId: String,
    ): com.dangerfield.cards.libraries.social.RespondToRequestResult =
        com.dangerfield.cards.libraries.social.RespondToRequestResult.Ok

    override suspend fun decline(
        userId: String,
    ): com.dangerfield.cards.libraries.social.RespondToRequestResult =
        com.dangerfield.cards.libraries.social.RespondToRequestResult.Ok
}

/** Records quick-buy calls and returns a configurable [nextOutcome]. */
class FakePurchaseChipPackUseCase(
    var nextOutcome: com.dangerfield.cards.libraries.billing.IapPurchaseOutcome =
        com.dangerfield.cards.libraries.billing.IapPurchaseOutcome.Success(grantedChips = 0),
) : com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase {
    val calls = mutableListOf<com.dangerfield.cards.libraries.products.Product.ChipPack>()
    override suspend fun invoke(
        pack: com.dangerfield.cards.libraries.products.Product.ChipPack,
    ): com.dangerfield.cards.libraries.billing.IapPurchaseOutcome {
        calls += pack
        return nextOutcome
    }
}
