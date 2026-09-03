package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.dangerfield.cards.features.room.impl.FakeAchievementRepository
import com.dangerfield.cards.features.room.impl.FakeAppCache
import com.dangerfield.cards.features.room.impl.FakeChipsRepository
import com.dangerfield.cards.features.room.impl.FakeEquipmentRepository
import com.dangerfield.cards.features.room.impl.FakeFriendRepository
import com.dangerfield.cards.features.room.impl.FakeInventoryRepository
import com.dangerfield.cards.features.room.impl.FakeLeaveCashOutNotifier
import com.dangerfield.cards.features.room.impl.FakePlayStyleRepository
import com.dangerfield.cards.features.room.impl.FakePlayerStatsRepository
import com.dangerfield.cards.features.room.impl.FakeProductsRepository
import com.dangerfield.cards.features.room.impl.FakeProfileRepository
import com.dangerfield.cards.features.room.impl.FakeProgressionConfig
import com.dangerfield.cards.features.room.impl.FakeProgressionRepository
import com.dangerfield.cards.features.room.impl.FakePurchaseChipPackUseCase
import com.dangerfield.cards.features.room.impl.FakeReportRepository
import com.dangerfield.cards.features.room.impl.FakeReviewPromptCoordinator
import com.dangerfield.cards.features.room.impl.HarnessSoloFactory
import com.dangerfield.cards.features.room.impl.PlayPokerAction
import com.dangerfield.cards.features.room.impl.PlayPokerViewModel
import com.dangerfield.cards.features.room.impl.ScriptedBotDecider
import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.features.room.impl.cards
import com.dangerfield.cards.features.room.impl.stackedDeck
import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Deck
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.social.SocialEnabled
import com.dangerfield.cards.libraries.ui.PreviewContent
import android.os.Looper
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock
import org.robolectric.Shadows.shadowOf

/**
 * Renders [PlayPokerScreen] on top of a **real** [PlayPokerViewModel] driving a
 * **real** bots session.
 *
 * Every other Compose test on this screen hands it a `TableUiState` built by
 * hand, which tests the screen given a state. The ViewModel suites test that the
 * ViewModel produces the right state. Neither covers the seam: a projection that
 * builds the wrong `TableUiState` passes both, because the hand-built state in
 * the UI test is what the author *believed* the projection emits.
 *
 * These tests close that. Nothing here constructs a `TableUiState` — the only
 * inputs are a stacked deck and a bot script, and every assertion is against
 * what ends up on screen after the real projection runs.
 *
 * ## The clock
 *
 * [PokerScenarioTest][com.dangerfield.cards.features.room.impl.PokerScenarioTest]
 * drives the same harness on `TestScope` virtual time via `advanceUntilIdle`.
 * That does not compose with `runComposeUiTest`, which owns the Robolectric main
 * looper and pumps it itself — two schedulers each waiting for the other is a
 * deadlock, and it is why the original plan called for taking the wall-clock
 * path first.
 *
 * So this harness puts *every* dispatcher on `Dispatchers.Main`, which under
 * Robolectric is the same looper the Compose harness drives. One scheduler, and
 * `waitForIdle()` drains the ViewModel, the session and the bot loop along with
 * composition. `HarnessSoloFactory` already runs bots with zero think delay, so
 * there is no wall-clock waiting to do.
 */
@OptIn(ExperimentalTestApi::class)
internal object OnLooper : DispatcherProvider {
    // Everything on the Robolectric main looper, deliberately. The bot loop
    // suspends on `delay()` between decisions, and the first action of a hand is
    // floored at `BotTiming.HAND_START_GRACE_MS` so a snap fold can't beat the
    // deal animation. A looper-scheduled delay can be *advanced*; a delay on
    // Unconfined or a background dispatcher can only be waited out in real time.
    // Keeping it all here lets [RenderedScenario.settle] fast-forward instead.
    override val io: CoroutineDispatcher get() = Dispatchers.Main
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Main.immediate
    override val default: CoroutineDispatcher get() = Dispatchers.Main
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}

/** Builder for a rendered solo scenario. Mirrors `SoloScenarioBuilder`'s vocabulary. */
@OptIn(ExperimentalTestApi::class)
internal class RenderedScenarioBuilder(private val test: ComposeUiTest) {
    private var seatCount: Int = 2
    private var holeBySeat: List<List<Card>>? = null
    private var board: List<Card> = emptyList()
    private val decider = ScriptedBotDecider()

    fun seats(count: Int) = apply { seatCount = count }

    /** Stack the deck: one hole-card spec per seat, in seat order. */
    fun deal(vararg holePerSeat: String) = apply {
        holeBySeat = holePerSeat.map { cards(it) }
    }

    fun board(spec: String) = apply { board = cards(spec) }

    fun scriptOpponent(seat: Int, block: ScriptedBotDecider.SeatScript.() -> Unit) =
        apply { decider.seat(seat).block() }

    fun start(): RenderedScenario {
        holeBySeat?.let {
            require(it.size == seatCount) {
                "deal() must cover all $seatCount seats in index order; got ${it.size}"
            }
        }
        val deckFactory: ((Int) -> Deck)? =
            holeBySeat?.let { hbs -> { _ -> stackedDeck(hbs, board) } }

        val vm = PlayPokerViewModel(
            sessionFactory = HarnessSoloFactory(
                seatsCount = seatCount,
                difficulty = BotDifficulty.Casual,
                decider = decider,
                deckFactory = deckFactory,
                dispatchers = OnLooper,
            ),
            progressionRepository = FakeProgressionRepository(),
            playStyleRepository = FakePlayStyleRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            progressionConfig = FakeProgressionConfig(),
            achievementRepository = FakeAchievementRepository(),
            appCache = FakeAppCache(),
            equipmentRepository = FakeEquipmentRepository(),
            inventoryRepository = FakeInventoryRepository(),
            productsRepository = FakeProductsRepository(),
            chipsRepository = FakeChipsRepository(),
            purchaseChipPack = FakePurchaseChipPackUseCase(),
            profileRepository = FakeProfileRepository(),
            friendRepository = FakeFriendRepository(),
            reportRepository = FakeReportRepository(),
            reviewPromptCoordinator = FakeReviewPromptCoordinator(),
            leaveCashOutNotifier = FakeLeaveCashOutNotifier(),
            dispatcherProvider = OnLooper,
            appScope = AppCoroutineScope(OnLooper),
            clock = Clock.System,
            socialEnabledConfig = SocialEnabled.forTest(enabled = false),
        )

        test.setContent {
            val state by vm.stateFlow.collectAsState()
            PreviewContent {
                PlayPokerScreen(state = state, onAction = vm::takeAction, onBack = {})
            }
        }
        test.waitForIdle()
        return RenderedScenario(vm, test)
    }
}

/**
 * A started, rendered scenario. The `iXxx()` methods submit the human's action
 * and let the bot loop and the projection settle before returning, so an
 * assertion straight afterwards reads a stable frame.
 */
@OptIn(ExperimentalTestApi::class)
internal class RenderedScenario(
    val vm: PlayPokerViewModel,
    private val test: ComposeUiTest,
) {
    private val humanSeat = 0

    /**
     * The projection the screen is currently rendering. Read it to *describe* a
     * test's setup, never to assert — the point of this harness is that
     * assertions go through the semantics tree, not through the state object.
     */
    val table: TableUiState.Active
        get() = vm.state.table as? TableUiState.Active
            ?: error("table is ${vm.state.table::class.simpleName}, not Active")

    fun iCall() = act(PlayerIntent.Call(humanSeat))
    fun iCheck() = act(PlayerIntent.Check(humanSeat))
    fun iFold() = act(PlayerIntent.Fold(humanSeat))
    fun iBet(amount: Long) = act(PlayerIntent.Bet(humanSeat, amount))
    fun iRaiseTo(totalThisStreet: Long) = act(PlayerIntent.Raise(humanSeat, totalThisStreet))
    fun iAllIn() = act(PlayerIntent.AllIn(humanSeat))

    /** The human's seat as the real projection currently describes it. */
    fun humanSeat() = table.seats.first { it.isHuman }

    fun iRequestNextHand() {
        vm.takeAction(PlayPokerAction.RequestNextHand)
        settle()
    }

    private fun act(intent: PlayerIntent) {
        vm.takeAction(PlayPokerAction.Submit(intent))
        settle()
    }

    /**
     * Pumps composition, then advances past the felt's deal-in and reveal
     * animations so cards are mounted and tappable rather than mid-flight.
     */
    fun settle() {
        // Two clocks have to be walked forward, and they are not the same one.
        //
        // `mainClock` is Compose's; it drives animations. The ViewModel, the
        // session and the bot loop run on `Dispatchers.Main`, which under
        // Robolectric is the main *looper*, scheduled against Robolectric's own
        // SystemClock. Advancing Compose's clock does not run a `delay()` posted
        // to the looper, so without `idle()` the bots simply never act and every
        // hand sits at preflop forever.
        repeat(3) {
            // `idleFor` moves Robolectric's clock forward and runs everything
            // that comes due, which is what releases the bot's think delay.
            // Plain `idle()` only drains what is already due, so the bots never
            // act and the hand sits at preflop with the bot on the clock.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MS))
            test.waitForIdle()
            test.mainClock.advanceTimeBy(SETTLE_MS)
        }
        test.waitForIdle()
    }

    private companion object {
        /** Covers the deal-in animations *and* `BotTiming.HAND_START_GRACE_MS`. */
        const val SETTLE_MS = 4_000L
    }
}
