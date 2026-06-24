package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.LocalBotsSession
import com.dangerfield.cards.features.room.impl.session.PokerSession
import com.dangerfield.cards.features.room.impl.session.PokerSessionFactory
import com.dangerfield.cards.features.room.impl.session.RemotePokerSession
import com.dangerfield.cards.features.room.impl.session.SoloBotsPokerSessionFactory
import com.dangerfield.cards.features.room.impl.session.seatToOccupant

import com.dangerfield.cards.libraries.bots.BotDecider
import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Deck
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.identity.profile.Profile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Base class for behaviour-driven poker scenario tests.
 *
 * Extend this (not [CoroutineTest] directly) so `soloScenario()` can reach the
 * test [dispatchers]. A scenario drives a **real** [PlayPokerViewModel] over a
 * **real** [LocalBotsSession] with scripted bot actions and (optionally) a
 * stacked deck, then asserts on [TableUiState] — what the user sees.
 *
 * ```
 * class MyScenarios : PokerScenarioTest() {
 *     @Test fun opponentRaises() = runUnitTest {
 *         val s = soloScenario()
 *             .deal("Ah Kh", "Qs Qd")
 *             .scriptOpponent(1) { raisesTo(200) }
 *             .start()
 *         assertTable(s.table) { humanCanCall(190); seatPill(1, "Raised to 200") }
 *     }
 * }
 * ```
 *
 * The human always sits at seat 0 (the solo VM seats them there); seat indices
 * 1..n are bots, matching [deal] order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class PokerScenarioTest : CoroutineTest() {
    protected fun TestScope.soloScenario(): SoloScenarioBuilder =
        SoloScenarioBuilder(scope = this, dispatchers = dispatchers)

    /**
     * Start a multiplayer scenario: a real [PlayPokerViewModel] over a real
     * [RemotePokerSession] fed by a [FakeRoomConnectionHandle]. Opponent actions
     * are injected as server frames (snapshots + events); assertions use the
     * same [assertTable] vocabulary as solo. [localUserId] decides which seat is
     * "you" (matched by `Seat.playerId`).
     */
    protected fun TestScope.mpScenario(
        localUserId: String = MP_LOCAL_USER,
    ): MpScenarioBuilder =
        MpScenarioBuilder(scope = this, dispatchers = dispatchers, localUserId = localUserId)
}

const val MP_LOCAL_USER: String = "local-user"

@OptIn(ExperimentalCoroutinesApi::class)
class SoloScenarioBuilder(
    private val scope: TestScope,
    private val dispatchers: DispatcherProvider,
) {
    private var seatCount: Int = 2
    private var difficulty: BotDifficulty = BotDifficulty.Casual
    private var holeBySeat: List<List<Card>>? = null
    private var board: List<Card> = emptyList()
    private val decider = ScriptedBotDecider()

    /** Number of seats at the table (1 human at seat 0 + bots). Default 2. */
    fun seats(count: Int): SoloScenarioBuilder = apply { seatCount = count }

    fun difficulty(value: BotDifficulty): SoloScenarioBuilder = apply { difficulty = value }

    /**
     * Stack the deck so each seat is dealt the given hole cards, in seat-index
     * order (seat 0 = human). Must cover every seat. Implies a stacked deck.
     */
    fun deal(vararg holePerSeat: String): SoloScenarioBuilder = apply {
        holeBySeat = holePerSeat.map { cards(it) }
    }

    /** Stack the community cards: flop (3), turn (1), river (1), in order. */
    fun board(spec: String): SoloScenarioBuilder = apply { board = cards(spec) }

    /** Script a bot seat's actions, in the order the hand will reach it. */
    fun scriptOpponent(
        seat: Int,
        block: ScriptedBotDecider.SeatScript.() -> Unit,
    ): SoloScenarioBuilder = apply { decider.seat(seat).block() }

    suspend fun start(): RunningScenario {
        holeBySeat?.let {
            require(it.size == seatCount) {
                "deal() must cover all $seatCount seats in index order; got ${it.size}"
            }
        }
        val deckFactory: ((Int) -> Deck)? = holeBySeat?.let { hbs -> { _ -> stackedDeck(hbs, board) } }

        val factory = HarnessSoloFactory(
            seatsCount = seatCount,
            difficulty = difficulty,
            decider = decider,
            deckFactory = deckFactory,
            dispatchers = dispatchers,
        )
        val progression = FakeProgressionRepository()
        val achievements = FakeAchievementRepository()
        val appCache = FakeAppCache()
        val vm = PlayPokerViewModel(
            sessionFactory = factory,
            progressionRepository = progression,
            playStyleRepository = FakePlayStyleRepository(),
            progressionConfig = FakeProgressionConfig(),
            achievementRepository = achievements,
            appCache = appCache,
            equipmentRepository = FakeEquipmentRepository(),
            inventoryRepository = FakeInventoryRepository(),
            productsRepository = FakeProductsRepository(),
            chipsRepository = FakeChipsRepository(),
            purchaseChipPack = FakePurchaseChipPackUseCase(),
            profileRepository = FakeProfileRepository(),
            friendRepository = FakeFriendRepository(),
            reviewPromptCoordinator = FakeReviewPromptCoordinator(),
            dispatcherProvider = dispatchers,
            appScope = AppCoroutineScope(dispatchers),
            clock = Clock.System,
            socialEnabledConfig = com.dangerfield.cards.libraries.social.SocialEnabled.forTest(enabled = false),
        )
        val recorder = EventRecorder().also { it.attach(scope.backgroundScope, vm) }
        scope.advanceUntilIdle()

        return RunningScenario(
            vm = vm,
            session = factory.builtSession ?: error("session was not built"),
            events = recorder,
            scope = scope,
            humanSeatIndex = 0,
            progression = progression,
            achievements = achievements,
            appCache = appCache,
        )
    }
}

/**
 * A started solo scenario. `table` is the current [TableUiState.Active]
 * projection; the `iXxx()` methods submit the human's action and drain the
 * scheduler (running the scripted bot loop) before returning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunningScenario(
    val vm: PlayPokerViewModel,
    val session: LocalBotsSession,
    val events: EventRecorder,
    private val scope: TestScope,
    private val humanSeatIndex: Int,
    val progression: FakeProgressionRepository,
    val achievements: FakeAchievementRepository,
    val appCache: FakeAppCache,
) {
    val table: TableUiState.Active
        get() = vm.state.table as? TableUiState.Active
            ?: error("table is ${vm.state.table::class.simpleName}, not Active yet")

    val gameState: GameState get() = session.gameStateFlow.value

    suspend fun iCall() = act(PlayerIntent.Call(humanSeatIndex))
    suspend fun iCheck() = act(PlayerIntent.Check(humanSeatIndex))
    suspend fun iFold() = act(PlayerIntent.Fold(humanSeatIndex))
    suspend fun iBet(amount: Long) = act(PlayerIntent.Bet(humanSeatIndex, amount))
    suspend fun iRaiseTo(totalThisStreet: Long) = act(PlayerIntent.Raise(humanSeatIndex, totalThisStreet))
    suspend fun iAllIn() = act(PlayerIntent.AllIn(humanSeatIndex))

    suspend fun iRequestNextHand() {
        vm.takeAction(PlayPokerAction.RequestNextHand)
        scope.advanceUntilIdle()
    }

    private suspend fun act(intent: PlayerIntent) {
        vm.takeAction(PlayPokerAction.Submit(intent))
        scope.advanceUntilIdle()
    }
}

/**
 * Test [PokerSessionFactory] that builds a real [LocalBotsSession] wired with a
 * scripted [BotDecider] and an optional stacked deck. Mirrors the production
 * `SoloBotsPokerSessionFactory` projection shape but lives in the test source
 * set. Always uses zero bot delay and the test dispatchers so the bot loop
 * advances on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HarnessSoloFactory(
    private val seatsCount: Int,
    private val difficulty: BotDifficulty,
    private val decider: BotDecider,
    private val deckFactory: ((Int) -> Deck)?,
    private val dispatchers: DispatcherProvider,
    override val difficultyName: String = "Casual",
    override val xpMode: XpMode = XpMode.BOTS,
) : PokerSessionFactory {

    private val personalities: List<BotPersonality> = listOf(
        BotPersonality.Steve,
        BotPersonality.Jane,
        BotPersonality.David,
    ).take(seatsCount - 1)

    var builtSession: LocalBotsSession? = null
        private set

    override fun create(
        humanSeatIndex: Int,
        botSpeedProvider: () -> BotSpeed,
        onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit,
    ): PokerSession {
        val session = if (deckFactory != null) {
            LocalBotsSession(
                difficulty = difficulty,
                humanSeatIndex = humanSeatIndex,
                botPersonalities = personalities,
                random = Random(42L),
                botActionDelayMs = 0L,
                botSpeedProvider = botSpeedProvider,
                onHandEnded = onHandEnded,
                dispatchers = dispatchers,
                botDecider = decider,
                deckFactory = deckFactory,
            )
        } else {
            LocalBotsSession(
                difficulty = difficulty,
                humanSeatIndex = humanSeatIndex,
                botPersonalities = personalities,
                random = Random(42L),
                botActionDelayMs = 0L,
                botSpeedProvider = botSpeedProvider,
                onHandEnded = onHandEnded,
                dispatchers = dispatchers,
                botDecider = decider,
            )
        }
        builtSession = session
        return session
    }

    override suspend fun bootstrap(session: PokerSession) {
        (session as LocalBotsSession).runUntilHumansTurnOrComplete()
    }

    override fun humanSeatIndex(state: GameState): Int =
        state.seats.firstOrNull { !it.isBot }?.index ?: 0

    override fun occupantsFor(state: GameState, curve: LevelCurve): List<SeatOccupant> =
        state.seats.map { seat -> seatToOccupant(seat, personality = null, curve) }

    override fun tableFor(
        state: GameState,
        lastWinners: GameEvent.HandEnded?,
        lastActionBySeat: Map<Int, PlayerAction>,
        humanProfile: Profile.Authenticated?,
        humanLevel: Int?,
        curve: LevelCurve,
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
