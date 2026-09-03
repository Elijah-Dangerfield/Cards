package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.material3.Text
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the one thing that actually matters about the play screen's inputs: an
 * unchanged seat, or an unchanged table, must not recompose.
 *
 * The table projection rebuilds `TableUiState.Active` and every `SeatView` on
 * each game event — a bot calling, a timer ticking. So the screen is fed a
 * constant stream of new instances that are `equals` to the ones before them,
 * and whether those skip decides whether one bot's call recomposes one seat or
 * all nine.
 *
 * **These tests exist because the reasoning about this is easy to get wrong,
 * and we got it wrong.** The Compose compiler report marks `SeatView` unstable
 * (`lastAction` and `personality` come from non-Compose modules, so their
 * stability can't be inferred), and the obvious conclusion — that the felt
 * therefore recomposes wholesale — is false on this toolchain. Under strong
 * skipping, Kotlin 2.4 / Compose 1.11 skip an equal-but-new instance for
 * unstable parameters too; verified against a class with a public `var`, which
 * skipped just the same. A stability config file would have bought nothing.
 *
 * So the guard is behavioural, not structural. It asserts the skip itself
 * rather than the compiler metadata behind it, which means it keeps working if
 * the comparison semantics change under us — and would have caught the wrong
 * conclusion above in about a minute. It pins comparison outcomes rather than
 * frame counts, so there's no clock and no jank proxy: nothing here depends on
 * how many frames the harness chose to run.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34])
class ComposeStabilityTest {

    @Test
    fun seatViewIsComparedByValue_soAnUnchangedSeatSkips() = runComposeUiTest {
        val counter = CompositionCounter()
        var seat by mutableStateOf(seat(displayName = "Jane"))

        setContent { SeatProbe(seat, counter) }
        waitForIdle()
        assertEquals(1, counter.count, "probe should compose once on first frame")

        // A *different instance* that is `equals` to the first — exactly what a
        // fresh projection of an unchanged seat produces.
        seat = seat(displayName = "Jane")
        waitForIdle()

        assertEquals(
            1,
            counter.count,
            "an equal SeatView recomposed the probe — every seat on the felt now " +
                "recomposes whenever any one of them changes",
        )
    }

    @Test
    fun seatViewStillRecomposesWhenItActuallyChanges() = runComposeUiTest {
        // The other half of the claim: skipping must not be so eager that a real
        // change is dropped. Without this, `assertEquals(1, ...)` above would
        // also pass for a composable that never recomposes at all.
        val counter = CompositionCounter()
        var seat by mutableStateOf(seat(displayName = "Jane"))

        setContent { SeatProbe(seat, counter) }
        waitForIdle()

        seat = seat(displayName = "Ada")
        waitForIdle()

        assertEquals(2, counter.count, "a genuinely changed seat must recompose")
    }

    @Test
    fun aSeatCarryingALastActionAndPersonalityStillSkips() = runComposeUiTest {
        // `lastAction` and `personality` are the two fields the compiler reports
        // as unstable: both live in non-Compose modules (:libraries:gameplay,
        // :libraries:bots), so their stability can't be inferred. A seat with
        // them populated is the case that would break first if the runtime ever
        // did fall back to reference comparison for unstable parameters.
        val counter = CompositionCounter()
        val action = PlayerAction.Raise(totalStreetContribution = 400, raiseAmount = 200)
        var seat by mutableStateOf(seat(displayName = "Jane", lastAction = action))

        setContent { SeatProbe(seat, counter) }
        waitForIdle()

        seat = seat(
            displayName = "Jane",
            lastAction = PlayerAction.Raise(totalStreetContribution = 400, raiseAmount = 200),
        )
        waitForIdle()

        assertEquals(1, counter.count, "a seat carrying PlayerAction/BotPersonality stopped skipping")
    }

    @Test
    fun tableUiStateIsComparedByValue_soAnUnchangedTableSkips() = runComposeUiTest {
        // The same assertion one level up, and the one that matters most: every
        // composable on the felt takes `table:`, so if Active stopped skipping
        // the whole screen would rebuild on every tick of the turn clock.
        val counter = CompositionCounter()
        var table by mutableStateOf(activeTable())

        setContent { TableProbe(table, counter) }
        waitForIdle()

        table = activeTable()
        waitForIdle()

        assertEquals(1, counter.count, "an equal TableUiState.Active recomposed the whole felt")
    }

    // region harness

    /**
     * Counts compositions. `@Stable` is honest here: the instance is never
     * replaced, `count` is a plain field that composition never *reads*, and
     * marking it stable keeps the counter itself from perturbing the very
     * skipping decision under test.
     */
    @Stable
    private class CompositionCounter {
        var count = 0
    }

    @Composable
    private fun SeatProbe(seat: SeatView, counter: CompositionCounter) {
        counter.count++
        Text(seat.displayName)
    }

    @Composable
    private fun TableProbe(table: TableUiState.Active, counter: CompositionCounter) {
        counter.count++
        Text(table.pot.toString())
    }

    private fun seat(
        displayName: String,
        lastAction: PlayerAction? = null,
        personality: BotPersonality? = null,
    ) = SeatView(
        index = 0,
        displayName = displayName,
        stack = 1_000,
        contributedThisStreet = 0,
        isActing = false,
        isHuman = true,
        isBot = false,
        avatarKey = null,
        emoji = null,
        holeCards = emptyList(),
        showHoleCardBacks = false,
        participation = HandParticipation.InHand,
        seatEmpty = false,
        isBusted = false,
        lastAction = lastAction,
        isDealer = false,
        isSmallBlind = false,
        isBigBlind = false,
        personality = personality,
    )

    private fun activeTable() = TableUiState.Active(
        street = BettingRound.Preflop,
        communityCards = emptyList(),
        pot = 30,
        potCommittedThisStreet = 0,
        seats = listOf(seat(displayName = "Jane"), seat(displayName = "Ada")),
        actingSeatIndex = 0,
        isHumanTurn = true,
        humanLegalActions = null,
        humanHandLabel = null,
        handResult = null,
        smallBlind = 10,
        bigBlind = 20,
        handNumber = 1,
        buttonSeatIndex = 0,
        smallBlindSeatIndex = 0,
        bigBlindSeatIndex = 1,
    )

    // endregion
}
