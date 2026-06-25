package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.RoomVisibility
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Survivor-routing scenarios for the multiplayer play screen.**
 *
 * Pins the chain
 * `Room snapshot member-diff` → `RemotePokerSession.opponentsLeft / opponentLeft` →
 * `PlayPokerViewModel.eventFlow` — i.e. when humans drop at the table, the VM
 * fires the right one-shot event for the entry point's navigation/snackbar
 * handlers to react to. Regression coverage for two bugs that landed on the
 * develop branch on 2026-06-24:
 *
 *  - **Lone-survivor routing.** When two humans become one, [PlayPokerEvent.
 *    OpponentsLeft] (plural) must fire so the entry point pops the survivor
 *    off the dead table. Was silently broken once by a duplicate-lobby push
 *    that re-mounted the play screen instead of popping.
 *  - **Three-plus-humans diff.** When three humans become two, [PlayPokerEvent.
 *    OpponentLeft] (singular, with display name) fires for the toast, and
 *    the plural variant does NOT — the game continues.
 *
 * What this suite does NOT test:
 *  - The navigation pop itself (entry-point composable; see PlayMultiplayer
 *    FeatureEntryPoint and its `popBackTo(LobbyRoute::class, ...)` call site).
 *  - The server-side hand cleanup on a leaver (forfeit / removePlayer / cashOut)
 *    — pinned in InMemoryRoomServiceTest and RoomSocketRoutesTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayPokerViewModelMpLeaveTest : CoroutineTest() {

    @Test
    fun twoHumansMidHand_opponentLeaves_firesOpponentsLeftOnce() = runUnitTest {
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()

        // Baseline: both humans seated, hand in flight.
        scenario.serverConnection(connected(humans = listOf(LOCAL, PEER)))
        scenario.serverSnapshot(twoHumanTable(actingSeatIndex = 0))

        // Opponent leaves the room mid-hand: the server broadcasts a fresh
        // room snapshot with only the survivor in members. That diff drives
        // the survivor-routing signal.
        scenario.serverConnection(connected(humans = listOf(LOCAL)))
        advanceUntilIdle()

        assertEquals(
            1,
            scenario.events.events.count { it is PlayPokerEvent.OpponentsLeft },
            "lone-survivor signal must fire once when humans drop from 2 to 1",
        )
        assertFalse(
            scenario.events.events.any { it is PlayPokerEvent.OpponentLeft },
            "single-departure toast must NOT fire when this is also the last-opponent event",
        )
    }

    @Test
    fun twoHumansBetweenHands_opponentLeaves_firesOpponentsLeft() = runUnitTest {
        // Mirror of the mid-hand case: no in-flight snapshot between the
        // baseline connection and the departure. Survivor routing is a
        // function of the member diff, NOT the hand state, so the event
        // fires either way.
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()
        scenario.serverConnection(connected(humans = listOf(LOCAL, PEER)))

        scenario.serverConnection(connected(humans = listOf(LOCAL)))
        advanceUntilIdle()

        assertEquals(
            1,
            scenario.events.events.count { it is PlayPokerEvent.OpponentsLeft },
        )
    }

    @Test
    fun opponentsLeft_replaysOnceForLateCollector() = runUnitTest {
        // The signal is a one-shot — collectors mounting after it fires still
        // see it exactly once (replay=1 on the underlying SharedFlow). Tests
        // the recorder's late-attach via the harness's own attach order;
        // doubles as a guard against accidental replay=0 regressions.
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()
        scenario.serverConnection(connected(humans = listOf(LOCAL, PEER)))
        scenario.serverConnection(connected(humans = listOf(LOCAL)))
        advanceUntilIdle()

        // Pushing the same drop a second time must not re-fire — the session
        // latches `opponentsAlreadyLeft` after the first emission.
        scenario.serverConnection(connected(humans = listOf(LOCAL)))
        advanceUntilIdle()

        assertEquals(
            1,
            scenario.events.events.count { it is PlayPokerEvent.OpponentsLeft },
            "opponents-left is terminal; a redundant snapshot must not re-fire it",
        )
    }

    @Test
    fun threeHumans_oneLeaves_firesOpponentLeftWithName_notPlural() = runUnitTest {
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()
        scenario.serverConnection(
            connectedNamed(humans = listOf(LOCAL to "Me", PEER to "Bob", "third" to "Cara")),
        )

        // Cara leaves; Bob and the local player still seated → game continues.
        scenario.serverConnection(connectedNamed(humans = listOf(LOCAL to "Me", PEER to "Bob")))
        advanceUntilIdle()

        val left = scenario.events.events.filterIsInstance<PlayPokerEvent.OpponentLeft>()
        assertEquals(1, left.size, "exactly one departure toast")
        assertEquals("Cara", left.single().displayName)
        assertFalse(
            scenario.events.events.any { it is PlayPokerEvent.OpponentsLeft },
            "lone-survivor signal must NOT fire while 2+ humans remain",
        )
    }

    @Test
    fun botPresent_humanOpponentLeaves_stillFiresOpponentsLeft() = runUnitTest {
        // Bots don't count as humans for the survivor signal (the player is
        // alone among humans even if the table is full of bots), so a 2-human
        // table dropping its single non-local human still routes the survivor.
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()
        scenario.serverConnection(
            connectedNamed(
                humans = listOf(LOCAL to "Me", PEER to "Bob"),
                bots = listOf("bot-1" to "Bot Alice"),
            ),
        )

        scenario.serverConnection(
            connectedNamed(humans = listOf(LOCAL to "Me"), bots = listOf("bot-1" to "Bot Alice")),
        )
        advanceUntilIdle()

        assertEquals(
            1,
            scenario.events.events.count { it is PlayPokerEvent.OpponentsLeft },
        )
    }

    @Test
    fun leaveTable_sendsLeaveOverSession_notViaWireFrame() = runUnitTest {
        // The session's leave path goes through HTTP DELETE (the onLeave lambda
        // wired in RemotePokerSessionFactory.create), NOT a ClientFrame on the
        // socket — pin that no leave frame is ever sent to avoid the screen
        // appearing to leave-via-socket if someone later wires it that way.
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()
        scenario.serverConnection(connected(humans = listOf(LOCAL, PEER)))
        scenario.serverSnapshot(twoHumanTable(actingSeatIndex = 0))

        scenario.vm.takeAction(PlayPokerAction.LeaveTable)
        advanceUntilIdle()

        assertTrue(
            scenario.sentFrames().none { it is ClientFrame.SubmitIntent },
            "Leave must not piggyback an intent onto the socket",
        )
    }

    @Test
    fun leaveTable_onRealChipTable_syncsWalletSoCreditedStackLands() = runUnitTest {
        // MP-7 / CARDS-3C: the server cashes the leaver's final stack back to the
        // wallet on leave, but the client only sees the new balance after a sync.
        // Leaving a real-chip MP table must trigger that sync so a won pot is not
        // invisible until the next foreground.
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()
        scenario.serverConnection(connected(humans = listOf(LOCAL, PEER)))
        scenario.serverSnapshot(twoHumanTable(actingSeatIndex = 0))

        scenario.vm.takeAction(PlayPokerAction.LeaveTable)
        advanceUntilIdle()

        assertEquals(
            1,
            scenario.chipsRepository.syncCount,
            "leaving a real-chip MP table must reconcile the wallet exactly once",
        )
    }

    @Test
    fun leaveGameFromBust_onRealChipTable_syncsWallet() = runUnitTest {
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()
        scenario.serverConnection(connected(humans = listOf(LOCAL, PEER)))
        scenario.serverSnapshot(twoHumanTable(actingSeatIndex = 0))

        scenario.vm.takeAction(PlayPokerAction.LeaveGameFromBust)
        advanceUntilIdle()

        assertEquals(1, scenario.chipsRepository.syncCount)
    }

    @Test
    fun leaveTable_creditedStack_confirmsCreditWithAmountAndNewBalance() = runUnitTest {
        // MP-6: leaving a real-chip table where the server cashed the seat
        // stack back surfaces the credited amount + new balance, so the wallet
        // bump on Home never reads as an unexplained glitch (CARDS-2N / 2Y).
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()
        scenario.serverConnection(connected(humans = listOf(LOCAL, PEER)))
        scenario.serverSnapshot(twoHumanTable(actingSeatIndex = 0))
        scenario.chipsRepository.emit(900L)
        scenario.chipsRepository.creditOnNextSync = 600L

        scenario.vm.takeAction(PlayPokerAction.LeaveTable)
        advanceUntilIdle()

        assertEquals(
            listOf(FakeLeaveCashOutNotifier.Credit(credited = 600L, balanceAfter = 1_500L)),
            scenario.leaveCashOutNotifier.credits,
            "the credited delta and post-sync balance must reach the confirmation surface",
        )
    }

    @Test
    fun leaveTable_noCredit_doesNotConfirm() = runUnitTest {
        // A leave that nets no credit (lost the stack / left empty) stays
        // silent — no "+0 chips" noise.
        val scenario = MpScenarioBuilder(this, dispatchers, LOCAL).start()
        scenario.serverConnection(connected(humans = listOf(LOCAL, PEER)))
        scenario.serverSnapshot(twoHumanTable(actingSeatIndex = 0))
        scenario.chipsRepository.emit(900L)

        scenario.vm.takeAction(PlayPokerAction.LeaveTable)
        advanceUntilIdle()

        assertTrue(
            scenario.leaveCashOutNotifier.credits.isEmpty(),
            "no confirmation when nothing was credited",
        )
    }

    // ---------- helpers ----------

    private fun twoHumanTable(actingSeatIndex: Int) = mpTable(
        seats = listOf(
            mpSeat(index = 0, playerId = LOCAL, displayName = "Me"),
            mpSeat(index = 1, playerId = PEER, displayName = "Bob"),
        ),
        actingSeatIndex = actingSeatIndex,
    )

    private fun connected(
        humans: List<String>,
        bots: List<Pair<String, String>> = emptyList(),
    ): RoomConnection.Connected = connectedNamed(
        humans = humans.map { it to "User-$it" },
        bots = bots,
    )

    private fun connectedNamed(
        humans: List<Pair<String, String>>,
        bots: List<Pair<String, String>> = emptyList(),
    ): RoomConnection.Connected {
        val members = buildList {
            humans.forEachIndexed { i, (id, name) ->
                add(memberOf(userId = id, displayName = name, seatIndex = i, isBot = false))
            }
            bots.forEachIndexed { i, (id, name) ->
                add(memberOf(userId = id, displayName = name, seatIndex = humans.size + i, isBot = true))
            }
        }
        return RoomConnection.Connected(
            Room(
                code = ROOM_CODE,
                hostUserId = humans.firstOrNull()?.first ?: "missing-host",
                createdAtEpochMs = 0L,
                maxSeats = 6,
                status = RoomStatus.Playing,
                members = members,
                visibility = RoomVisibility.Private,
            ),
        )
    }

    private fun memberOf(
        userId: String,
        displayName: String,
        seatIndex: Int,
        isBot: Boolean,
    ): RoomMember = RoomMember(
        userId = userId,
        displayName = displayName,
        seatIndex = seatIndex,
        joinedAtEpochMs = 0L,
        isConnected = true,
        isBot = isBot,
    )

    private companion object {
        const val LOCAL = "local-user"
        const val PEER = "peer-user"
        const val ROOM_CODE = "ABCDEF"
    }
}
