package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.LocalBotsSession
import com.dangerfield.cards.features.room.impl.session.PokerSession
import com.dangerfield.cards.features.room.impl.session.PokerSessionFactory

import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.BotSpeed
import com.dangerfield.cards.libraries.cards.Criterion
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.TurnFeedback
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.review.ReviewTrigger
import com.dangerfield.cards.libraries.ui.components.PlayerBadge
import com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle
import com.dangerfield.cards.libraries.ui.components.poker.EquippedFelt
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the new [PlayPokerViewModel] — Phase 0.2.f of the MP-readiness refactor.
 *
 * These pin the bot/human-agnostic VM API in isolation, using fakes for the
 * session and repository collaborators. The companion integration suite drives
 * the same VM against a real [LocalBotsSession]; together they cover both the
 * MVI contract and the wiring.
 */
class PlayPokerViewModelTest : CoroutineTest() {

    // ---------- Initial state ----------

    @Test
    fun initialState_isDefault() = runUnitTest {
        val vm = buildVm()
        // Init flows drain on UnconfinedTestDispatcher without explicit advance.
        val state = vm.state
        assertEquals(2, state.occupants.size, "occupants seeded from stub game state")
        assertEquals(false, state.cheatSheetOpen)
        assertEquals(0L, state.xp)
        assertEquals(null, state.lastHandXpAwarded)
        assertTrue(state.recentlyEarned.isEmpty())
        assertEquals(TurnFeedback.Vibrate, state.turnFeedback)
        assertEquals(XpMode.BOTS, state.xpMode, "default factory advertises bot mode")
    }

    @Test
    fun initialState_xpMode_mirrorsFactory_forMultiplayerSessions() = runUnitTest {
        val factory = FakePokerSessionFactory(xpMode = XpMode.MULTIPLAYER)
        val vm = buildVm(factory = factory)
        assertEquals(
            XpMode.MULTIPLAYER,
            vm.state.xpMode,
            "MP factory must propagate xpMode so the screen can pick the right unlock-celebration shape",
        )
    }

    // ---------- Settings mirror (AppCache → state) ----------

    @Test
    fun appCacheEmission_mirrorsTurnFeedback() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)

        cache.emit(AppData(turnFeedback = TurnFeedback.Vibrate))
        assertEquals(TurnFeedback.Vibrate, vm.state.turnFeedback)
    }

    @Test
    fun appCacheBotSpeed_isExposedToSession() = runUnitTest {
        val cache = FakeAppCache()
        val factory = FakePokerSessionFactory()
        val vm = buildVm(appCache = cache, factory = factory)
        vm  // suppress unused — the VM is the system under test, constructed for side effects

        cache.emit(AppData(botSpeed = BotSpeed.Fast))

        assertEquals(BotSpeed.Fast, factory.capturedBotSpeedProvider?.invoke())
    }

    @Test
    fun appCacheEmission_mirrorsSwipeFoldGestureAck() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)

        assertEquals(false, vm.state.swipeFoldGestureAck)

        cache.emit(AppData(swipeFoldGestureAck = true))
        assertEquals(true, vm.state.swipeFoldGestureAck)
    }

    @Test
    fun acknowledgeSwipeFoldGesture_writesThroughToAppCache() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)

        assertEquals(false, cache.get().swipeFoldGestureAck)

        vm.takeAction(PlayPokerAction.AcknowledgeSwipeFoldGesture)

        assertEquals(true, cache.get().swipeFoldGestureAck)
        assertEquals(true, vm.state.swipeFoldGestureAck)
    }

    // ---------- Emoji tray + cooldown + mute ----------

    @Test
    fun availableEmojis_defaultsToEmpty_whenNoPacksOwned() = runUnitTest {
        // Emoji blasts are a paid surface — a fresh user with no pack
        // should see no emojis. Screen relies on this to hide the tray.
        val vm = buildVm()
        assertEquals(emptyList(), vm.state.availableEmojis)
    }

    @Test
    fun inventoryEmission_populatesAvailableEmojisFromOwnedPacks() = runUnitTest {
        val inventory = FakeInventoryRepository()
        val vm = buildVm(inventoryRepository = inventory)
        inventory.emit(
            listOf(
                com.dangerfield.cards.libraries.cards.InventoryItem(
                    productId = "emotes_cute",
                    state = com.dangerfield.cards.libraries.cards.PurchaseState.Confirmed,
                    purchasedAtEpochMs = 0L,
                    costChipsAtPurchase = 0L,
                ),
            ),
        )

        assertTrue("🥺" in vm.state.availableEmojis)
        assertTrue("🥰" in vm.state.availableEmojis)
    }

    @Test
    fun blastEmoji_setsBlastAndCooldownDeadline() = runUnitTest {
        val fixedNow = 1_000_000L
        val vm = buildVm(clock = FixedClock(fixedNow))

        vm.takeAction(PlayPokerAction.BlastEmoji("🔥"))

        assertEquals("🔥", vm.state.emojiBlast?.emoji)
        assertEquals(fixedNow, vm.state.emojiBlast?.emittedAtEpochMs)
        assertEquals(fixedNow + 8_000L, vm.state.emojiCooldownEndsAtMs)
    }

    @Test
    fun blastEmoji_duringCooldown_isIgnored() = runUnitTest {
        val clock = MutableFixedClock(1_000_000L)
        val vm = buildVm(clock = clock)

        vm.takeAction(PlayPokerAction.BlastEmoji("🔥"))
        val firstBlastTs = vm.state.emojiBlast?.emittedAtEpochMs

        // Still inside the 8s window — second tap is dropped.
        clock.advanceTo(1_005_000L)
        vm.takeAction(PlayPokerAction.BlastEmoji("🎉"))

        assertEquals("🔥", vm.state.emojiBlast?.emoji)
        assertEquals(firstBlastTs, vm.state.emojiBlast?.emittedAtEpochMs)
    }

    @Test
    fun blastEmoji_afterCooldown_succeeds() = runUnitTest {
        val clock = MutableFixedClock(1_000_000L)
        val vm = buildVm(clock = clock)

        vm.takeAction(PlayPokerAction.BlastEmoji("🔥"))
        vm.takeAction(PlayPokerAction.EmojiBlastConsumed(1_000_000L))
        assertEquals(null, vm.state.emojiBlast)

        // Past the 8s cooldown boundary.
        clock.advanceTo(1_009_000L)
        vm.takeAction(PlayPokerAction.BlastEmoji("🎉"))

        assertEquals("🎉", vm.state.emojiBlast?.emoji)
        assertEquals(1_009_000L, vm.state.emojiBlast?.emittedAtEpochMs)
    }

    @Test
    fun emojiBlastConsumed_clearsOnlyMatchingBlast() = runUnitTest {
        val clock = MutableFixedClock(1_000_000L)
        val vm = buildVm(clock = clock)

        vm.takeAction(PlayPokerAction.BlastEmoji("🔥"))
        val firstTs = vm.state.emojiBlast!!.emittedAtEpochMs

        // A stale "consumed" from a different blast id is a no-op.
        vm.takeAction(PlayPokerAction.EmojiBlastConsumed(emittedAtEpochMs = 999L))
        assertEquals("🔥", vm.state.emojiBlast?.emoji)

        vm.takeAction(PlayPokerAction.EmojiBlastConsumed(emittedAtEpochMs = firstTs))
        assertEquals(null, vm.state.emojiBlast)
    }

    // ---------- Inbound MP emotes (PokerSession.emoteBlasts → state) ----------

    @Test
    fun blastEmoji_sendsEmoteOverTheWire() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory, clock = FixedClock(1_000_000L))

        vm.takeAction(PlayPokerAction.BlastEmoji("🔥"))

        assertEquals(listOf("🔥"), session.sentEmotes, "the local blast must also ride the socket to opponents")
    }

    @Test
    fun remoteEmote_fromOpponentSeat_rendersBlastAttributedToThatSeat() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory, clock = FixedClock(2_000_000L))

        // The stub table seats "You" (0, human) + "Steve" (1, opponent).
        vm.takeAction(PlayPokerAction.RemoteEmoteReceived(seatIndex = 1, emoji = "🎉"))

        assertEquals("🎉", vm.state.emojiBlast?.emoji)
        assertEquals(2_000_000L, vm.state.emojiBlast?.emittedAtEpochMs)
        assertEquals(1, vm.state.emojiBlastEmitterSeatIndex, "an opponent's blast is attributed to their seat")
    }

    @Test
    fun remoteEmote_fromOwnSeat_isIgnored() = runUnitTest {
        val vm = buildVm()

        // Seat 0 is the local human — an echo of our own blast must not re-render.
        vm.takeAction(PlayPokerAction.RemoteEmoteReceived(seatIndex = 0, emoji = "🎉"))

        assertEquals(null, vm.state.emojiBlast)
    }

    @Test
    fun remoteEmote_fromMutedSeat_isIgnored() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)

        cache.emit(AppData(mutedEmojiPlayerKeys = setOf("Steve")))
        vm.takeAction(PlayPokerAction.RemoteEmoteReceived(seatIndex = 1, emoji = "🔥"))

        assertEquals(null, vm.state.emojiBlast, "a muted opponent's emote is dropped")
    }

    @Test
    fun toggleMutePlayer_addsThenRemovesFromAppCache() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)

        vm.takeAction(PlayPokerAction.ToggleMutePlayer("Jane"))
        assertEquals(setOf("Jane"), cache.get().mutedEmojiPlayerKeys)
        assertEquals(setOf("Jane"), vm.state.mutedEmojiPlayerKeys)

        vm.takeAction(PlayPokerAction.ToggleMutePlayer("Jane"))
        assertEquals(emptySet<String>(), cache.get().mutedEmojiPlayerKeys)
        assertEquals(emptySet<String>(), vm.state.mutedEmojiPlayerKeys)
    }

    @Test
    fun mutedSet_mirroredFromAppCacheOnEmission() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)

        cache.emit(AppData(mutedEmojiPlayerKeys = setOf("David", "Steve")))
        assertEquals(setOf("David", "Steve"), vm.state.mutedEmojiPlayerKeys)
    }

    // ---------- XP mirror (ProgressionRepository → state) ----------

    // ---------- Connection state mirror (PokerSession → state) ----------

    @Test
    fun sessionConnectionState_mirrorsIntoVmState() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        // Default — local sessions stay Connected.
        assertEquals(ConnectionState.Connected, vm.state.connection)

        session.emitConnectionState(ConnectionState.Reconnecting)
        assertEquals(ConnectionState.Reconnecting, vm.state.connection)

        session.emitConnectionState(ConnectionState.Disconnected)
        assertEquals(ConnectionState.Disconnected, vm.state.connection)

        session.emitConnectionState(ConnectionState.Connected)
        assertEquals(ConnectionState.Connected, vm.state.connection)
    }

    @Test
    fun progressionEmission_mirrorsTotalXp() = runUnitTest {
        val progression = FakeProgressionRepository()
        val vm = buildVm(progressionRepository = progression)

        progression.emit(Progression.Empty.copy(totalXp = 4_200))
        assertEquals(4_200L, vm.state.xp)
    }

    // ---------- Engine state subscription ----------

    @Test
    fun gameStateEmission_populatesTable() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        // The stub state has 2 seats and is on the Preflop street — the
        // derived TableUiState should be Active (not Loading) and reflect that.
        val table = vm.state.table
        assertTrue(table is TableUiState.Active, "table is Active once gameState emits")
        assertEquals(2, (table as TableUiState.Active).seats.size)
    }

    @Test
    fun gameStateEmission_populatesOccupants() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        val initial = vm.state.occupants
        assertEquals(2, initial.size)
        assertTrue(initial[0] is SeatOccupant.Human)
        assertTrue(initial[1] is SeatOccupant.Bot)

        session.emitGameState(
            stubGameState(
                seats = listOf(
                    testSeat(0, "You", isBot = false, playerId = "human"),
                    testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                    testSeat(2, "Jane", isBot = true, playerId = "bot-2"),
                ),
            ),
        )
        assertEquals(3, vm.state.occupants.size)
        assertTrue(vm.state.occupants[2] is SeatOccupant.Bot)
    }

    // ---------- Submit / RequestNextHand → session ----------

    @Test
    fun submit_forwardsIntentToSession() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Fold(seatIndex = 0)))

        assertEquals(1, session.submittedIntents.size)
        assertEquals(PlayerIntent.Fold(seatIndex = 0), session.submittedIntents.first())
    }

    @Test
    fun submit_doubleTapOnSameDecision_forwardsOnce() = runUnitTest {
        val session = FakePokerSession()
        val vm = buildVm(factory = FakePokerSessionFactory(session = session))
        session.emitGameState(stubGameState(lastSequence = 5))

        // A slow ack: the user taps the same action twice before the resulting
        // snapshot lands. Only the first reaches the session.
        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Call(seatIndex = 0)))
        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Call(seatIndex = 0)))

        assertEquals(1, session.submittedIntents.size)
    }

    @Test
    fun submit_nextDecisionAfterSnapshot_forwardsAgain() = runUnitTest {
        val session = FakePokerSession()
        val vm = buildVm(factory = FakePokerSessionFactory(session = session))

        session.emitGameState(stubGameState(lastSequence = 5))
        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Call(seatIndex = 0)))

        // The action resolved and the next decision arrived (sequence advanced) —
        // a fresh token, so the next submit is not mistaken for a duplicate.
        session.emitGameState(stubGameState(lastSequence = 7))
        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Check(seatIndex = 0)))

        assertEquals(2, session.submittedIntents.size)
    }

    @Test
    fun submit_rejectedThenRetriedOnSameDecision_forwardsAgain() = runUnitTest {
        val session = FakePokerSession().apply { submitError = IllegalArgumentException("illegal raise") }
        val vm = buildVm(factory = FakePokerSessionFactory(session = session))
        session.emitGameState(stubGameState(lastSequence = 5))

        // A rejected submit must not latch the guard — a corrected resubmit on the
        // same decision still goes through.
        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Raise(seatIndex = 0, totalAmountThisStreet = 10)))
        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Raise(seatIndex = 0, totalAmountThisStreet = 50)))

        assertEquals(2, session.submittedIntents.size)
    }

    @Test
    fun requestNextHand_signalsSession_andClearsTransients() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        // Pre-seed transient fields by simulating a hand-end on the prior hand.
        vm.takeAction(PlayPokerAction.HandXpAwarded(amount = 73))
        vm.takeAction(
            PlayPokerAction.AchievementsEarned(
                earned = listOf(testEarnedAchievement()),
            ),
        )
        assertEquals(73, vm.state.lastHandXpAwarded)
        assertEquals(1, vm.state.recentlyEarned.size)

        vm.takeAction(PlayPokerAction.RequestNextHand)

        assertEquals(1, session.requestNextHandCount)
        assertEquals(null, vm.state.lastHandXpAwarded)
        assertTrue(vm.state.recentlyEarned.isEmpty())
    }

    // ---------- Local UI actions ----------

    @Test
    fun toggleCheatSheet_flipsState() = runUnitTest {
        val vm = buildVm()
        assertEquals(false, vm.state.cheatSheetOpen)

        vm.takeAction(PlayPokerAction.ToggleCheatSheet)
        assertEquals(true, vm.state.cheatSheetOpen)

        vm.takeAction(PlayPokerAction.ToggleCheatSheet)
        assertEquals(false, vm.state.cheatSheetOpen)
    }

    @Test
    fun handEndAchievements_pendingThenEarned_togglesAwaitingFlag() = runUnitTest {
        // The dialog dismiss path waits on `awaitingHandEndAchievements` so a
        // fast tap can't skip a reveal that's still being computed. Pin the
        // flag: pending sets it, the resolution (earned, even empty) clears it.
        val vm = buildVm()
        assertEquals(false, vm.state.awaitingHandEndAchievements)

        vm.takeAction(PlayPokerAction.HandEndAchievementsPending)
        assertTrue(vm.state.awaitingHandEndAchievements, "hand-end marks achievements in flight")
        assertTrue(vm.state.recentlyEarned.isEmpty(), "pending clears any stale earned list")

        vm.takeAction(PlayPokerAction.AchievementsEarned(earned = listOf(testEarnedAchievement())))
        assertEquals(false, vm.state.awaitingHandEndAchievements, "resolution clears the in-flight flag")
        assertEquals(1, vm.state.recentlyEarned.size)
    }

    @Test
    fun handEndAchievements_resolvedEmpty_stillClearsAwaiting() = runUnitTest {
        // No unlocks this hand still has to clear the flag, or the dismiss path
        // would hold forever waiting on a reveal that never comes.
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.HandEndAchievementsPending)
        assertTrue(vm.state.awaitingHandEndAchievements)

        vm.takeAction(PlayPokerAction.AchievementsEarned(earned = emptyList()))
        assertEquals(false, vm.state.awaitingHandEndAchievements)
        assertTrue(vm.state.recentlyEarned.isEmpty())
    }

    @Test
    fun dismissEarnedToast_clearsList() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(
            PlayPokerAction.AchievementsEarned(
                earned = listOf(testEarnedAchievement()),
            ),
        )
        assertEquals(1, vm.state.recentlyEarned.size)

        vm.takeAction(PlayPokerAction.DismissEarnedToast)
        assertTrue(vm.state.recentlyEarned.isEmpty())
    }

    // ---------- Profile → human-seat projection ----------

    @Test
    fun authenticatedProfile_drivesHumanSeatNameEmojiAndBackgroundColor() = runUnitTest {
        val profile = Profile.Authenticated(
            id = "u-1",
            displayName = "QuietAce72",
            avatarEmoji = "🦊",
            avatarBackgroundColor = "#58E47C",
            email = null,
            isAnonymous = true,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        )
        val profileRepo = FakeProfileRepository().apply { emit(profile) }
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory, profileRepository = profileRepo)

        // Re-emit so the projection runs against the profile captured at init.
        session.emitGameState(
            stubGameState(
                seats = listOf(
                    testSeat(0, "You", isBot = false, playerId = "human"),
                    testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                ),
            ),
        )

        val table = vm.state.table as TableUiState.Active
        val humanSeat = table.seats.first { it.isHuman }
        assertEquals("QuietAce72", humanSeat.displayName)
        assertEquals("🦊", humanSeat.emoji)
        assertEquals("#58E47C", humanSeat.avatarBackgroundColorHex)
        // Bot seat unaffected — engine name, no profile-driven background.
        val botSeat = table.seats.first { it.isBot }
        assertEquals("Steve", botSeat.displayName)
        assertEquals(null, botSeat.avatarBackgroundColorHex)
    }

    @Test
    fun unresolvedProfile_keepsEngineSeatName() = runUnitTest {
        // Default FakeProfileRepository emits nothing — projection should
        // fall back to the engine seat's own displayName.
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        session.emitGameState(
            stubGameState(
                seats = listOf(
                    testSeat(0, "You", isBot = false, playerId = "human"),
                    testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                ),
            ),
        )

        val table = vm.state.table as TableUiState.Active
        assertEquals("You", table.seats.first { it.isHuman }.displayName)
    }

    // ---------- Hand-end callback flow ----------

    @Test
    fun handEnded_awardsXp_andSurfacesAmount() = runUnitTest {
        val progression = FakeProgressionRepository().apply {
            nextAwardedEvents = listOf(
                XpEvent(
                    id = 1,
                    deltaXp = 24,
                    source = XpSource.BASE,
                    mode = XpMode.BOTS,
                    handId = "h-1",
                    createdAtEpochMs = 0,
                ),
                XpEvent(
                    id = 2,
                    deltaXp = 6,
                    source = XpSource.INVESTMENT,
                    mode = XpMode.BOTS,
                    handId = "h-1",
                    createdAtEpochMs = 0,
                ),
            )
        }
        val factory = FakePokerSessionFactory()
        val vm = buildVm(factory = factory, progressionRepository = progression)

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(
                sequence = 0,
                winners = emptyList(),
                board = emptyList(),
                revealedHoleCards = emptyMap(),
            ),
            stubGameState(),
            /* humanStartingStack = */ 1_000L,
        )

        assertEquals(1, progression.awardedSummaries.size)
        assertEquals(30, vm.state.lastHandXpAwarded, "24 + 6 from the two XP events")
    }

    // ---------- Winner rendering vs event/snapshot ordering (regression) ----------
    // The server emits the Complete snapshot and the HandEnded event on two
    // independent flows with no ordering guarantee. The table is projected on
    // GameState updates; HandEnded only sets a transient. If the projection
    // doesn't also run on the event, a snapshot-before-event ordering drops the
    // winner ("nothing happened on the last call"). Both orderings must render.

    @Test
    fun handEndedEvent_afterCompleteSnapshot_rendersWinnerOnTable() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        // Snapshot FIRST (the ordering that used to drop the winner).
        session.emitGameState(stubGameState(street = BettingRound.Complete, actingSeatIndex = null))
        session.emitEvent(
            GameEvent.HandEnded(
                sequence = 1,
                winners = listOf(HandWinner(seatIndex = 0, amount = 200, handRank = null, byFold = true)),
                board = emptyList(),
                revealedHoleCards = emptyMap(),
            ),
        )

        val table = vm.state.table as TableUiState.Active
        assertEquals(
            listOf(0),
            table.handResult?.winners?.map { it.seatIndex },
            "winner must render even when the Complete snapshot arrives before HandEnded",
        )
    }

    @Test
    fun handEndedEvent_beforeCompleteSnapshot_rendersWinnerOnTable() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        // Event FIRST (the ordering bot sessions happen to use).
        session.emitEvent(
            GameEvent.HandEnded(
                sequence = 1,
                winners = listOf(HandWinner(seatIndex = 1, amount = 200, handRank = null, byFold = true)),
                board = emptyList(),
                revealedHoleCards = emptyMap(),
            ),
        )
        session.emitGameState(stubGameState(street = BettingRound.Complete, actingSeatIndex = null))

        val table = vm.state.table as TableUiState.Active
        assertEquals(listOf(1), table.handResult?.winners?.map { it.seatIndex })
    }

    // ---------- Action pills vs event/snapshot ordering (regression) ----------
    // The "Called 50" / "Folded" pill below a seat is a per-hand transient the VM
    // tracks from ActionTaken events — GameState alone can't carry it. The table
    // is otherwise only re-projected on a GameState snapshot. The server emits
    // events and snapshots on two independent flows with no ordering guarantee,
    // so an ActionTaken that lands without a following snapshot must still paint
    // the pill (the same class of ordering bug the winner-rendering tests pin).

    @Test
    fun actionTakenEvent_withoutFollowingSnapshot_rendersActionPill() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        session.emitGameState(
            stubGameState(
                seats = listOf(
                    testSeat(0, "You", isBot = false, playerId = "human"),
                    testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                ),
                actingSeatIndex = 0,
            ),
        )
        // Only the event arrives — no fresh snapshot behind it.
        session.emitEvent(
            GameEvent.ActionTaken(
                sequence = 1,
                seatIndex = 1,
                action = PlayerAction.Call(50),
                resultingStreetContribution = 50,
            ),
        )

        val table = vm.state.table as TableUiState.Active
        assertEquals(
            PlayerAction.Call(50),
            table.seats.first { it.index == 1 }.lastAction,
            "an ActionTaken must re-project the seat's pill even with no following snapshot",
        )
    }

    @Test
    fun streetAdvancedEvent_clearsStaleActionPills() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(session = session)
        val vm = buildVm(factory = factory)

        session.emitGameState(
            stubGameState(
                seats = listOf(
                    testSeat(0, "You", isBot = false, playerId = "human"),
                    testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                ),
                actingSeatIndex = 0,
            ),
        )
        session.emitEvent(
            GameEvent.ActionTaken(
                sequence = 1,
                seatIndex = 1,
                action = PlayerAction.Call(50),
                resultingStreetContribution = 50,
            ),
        )
        assertEquals(
            PlayerAction.Call(50),
            (vm.state.table as TableUiState.Active).seats.first { it.index == 1 }.lastAction,
        )

        // A street change wipes last-street action pills — again with no snapshot.
        session.emitEvent(
            GameEvent.StreetAdvanced(
                sequence = 2,
                street = BettingRound.Flop,
                communityCards = emptyList(),
            ),
        )
        assertEquals(
            null,
            (vm.state.table as TableUiState.Active).seats.first { it.index == 1 }.lastAction,
            "advancing the street must clear stale per-seat action pills",
        )
    }

    @Test
    fun handEnded_recordsAchievement_andSurfacesEarned() = runUnitTest {
        val earnedSample = testEarnedAchievement()
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(earnedSample)
        }
        val factory = FakePokerSessionFactory()
        val vm = buildVm(factory = factory, achievementRepository = achievements)

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(1, achievements.recordedHands.size)
        assertEquals(listOf(earnedSample), vm.state.recentlyEarned)
    }

    @Test
    fun handEnded_recordsAchievement_butSuppressesReveal_whenPopupsOff() = runUnitTest {
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(testEarnedAchievement())
        }
        val factory = FakePokerSessionFactory()
        val vm = buildVm(
            appCache = FakeAppCache(AppData(showAchievementPopups = false)),
            factory = factory,
            achievementRepository = achievements,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        // The unlock is still banked — only the in-game reveal is silenced.
        assertEquals(1, achievements.recordedHands.size)
        assertTrue(vm.state.recentlyEarned.isEmpty())
    }

    @Test
    fun handEnded_passesOpponentBotNames_toAchievementContext() = runUnitTest {
        val achievements = FakeAchievementRepository()
        val factory = FakePokerSessionFactory(difficultyName = "Challenging")
        val vm = buildVm(factory = factory, achievementRepository = achievements)
        vm  // constructed for side effects

        val state = stubGameState(
            seats = listOf(
                testSeat(0, "You", isBot = false, playerId = "human", stack = 1_500),
                testSeat(1, "Steve", isBot = true, playerId = "bot-1"),
                testSeat(2, "Jane", isBot = true, playerId = "bot-2"),
            ),
        )
        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            state,
            /* humanStartingStack = */ 1_000L,
        )

        val context = achievements.recordedHands.single().second
        assertEquals(listOf("Steve", "Jane"), context.opponentBotNames)
        assertEquals("Challenging", context.botDifficulty)
        assertEquals(1_000L, context.humanStartingStack)
        assertEquals(1_500L, context.humanEndingStack)
        assertEquals(10L, context.bigBlind)
    }

    @Test
    fun handEnded_countsBustedOpponents_byEndOfHandStack() = runUnitTest {
        val achievements = FakeAchievementRepository()
        val factory = FakePokerSessionFactory()
        buildVm(factory = factory, achievementRepository = achievements)

        // Steve busted (stack 0), Jane survived. Human stack is irrelevant
        // to the count — bots only.
        val state = stubGameState(
            seats = listOf(
                testSeat(0, "You", isBot = false, playerId = "human", stack = 2_500),
                testSeat(1, "Steve", isBot = true, playerId = "bot-1", stack = 0),
                testSeat(2, "Jane", isBot = true, playerId = "bot-2", stack = 800),
            ),
        )
        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            state,
            /* humanStartingStack = */ 1_500L,
        )

        assertEquals(1, achievements.recordedHands.single().second.bustedOpponentCount)
    }

    @Test
    fun handEnded_zeroBustedOpponents_whenAllSurvive() = runUnitTest {
        val achievements = FakeAchievementRepository()
        val factory = FakePokerSessionFactory()
        buildVm(factory = factory, achievementRepository = achievements)

        val state = stubGameState(
            seats = listOf(
                testSeat(0, "You", isBot = false, playerId = "human", stack = 1_000),
                testSeat(1, "Steve", isBot = true, playerId = "bot-1", stack = 1_000),
                testSeat(2, "Jane", isBot = true, playerId = "bot-2", stack = 1_000),
            ),
        )
        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            state,
            /* humanStartingStack = */ 1_000L,
        )

        assertEquals(0, achievements.recordedHands.single().second.bustedOpponentCount)
    }

    // ---------- Review prompt wiring ----------

    @Test
    fun handEnded_unlocksRareAchievement_requestsReviewPrompt() = runUnitTest {
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(testEarnedAchievement(rarity = AchievementRarity.RARE))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            achievementRepository = achievements,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(listOf(ReviewTrigger.AchievementUnlocked), coordinator.requested)
    }

    @Test
    fun handEnded_unlocksLegendaryAchievement_requestsReviewPrompt() = runUnitTest {
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(testEarnedAchievement(rarity = AchievementRarity.LEGENDARY))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            achievementRepository = achievements,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(listOf(ReviewTrigger.AchievementUnlocked), coordinator.requested)
    }

    @Test
    fun handEnded_unlocksCommonOnly_doesNotRequestAchievementPrompt() = runUnitTest {
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(testEarnedAchievement(rarity = AchievementRarity.COMMON))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            achievementRepository = achievements,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertTrue(
            ReviewTrigger.AchievementUnlocked !in coordinator.requested,
            "common-only earnings must not fire AchievementUnlocked",
        )
    }

    @Test
    fun handEnded_levelChange_requestsLevelUpPrompt() = runUnitTest {
        val progression = FakeProgressionRepository(initial = Progression.Empty.copy(totalXp = 0))
        progression.onAwardForHand = {
            progression.emit(Progression.Empty.copy(totalXp = 150))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            progressionRepository = progression,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(listOf(ReviewTrigger.LevelUp), coordinator.requested)
    }

    @Test
    fun handEnded_noLevelChange_doesNotRequestLevelUpPrompt() = runUnitTest {
        val progression = FakeProgressionRepository(initial = Progression.Empty.copy(totalXp = 0))
        progression.onAwardForHand = {
            progression.emit(Progression.Empty.copy(totalXp = 30))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            progressionRepository = progression,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertTrue(coordinator.requested.isEmpty(), "no prompts when level doesn't change")
    }

    @Test
    fun handEnded_achievementUnlock_takesPriorityOverLevelUp() = runUnitTest {
        val progression = FakeProgressionRepository(initial = Progression.Empty.copy(totalXp = 0))
        progression.onAwardForHand = {
            progression.emit(Progression.Empty.copy(totalXp = 150))
        }
        val achievements = FakeAchievementRepository().apply {
            nextEarned = listOf(testEarnedAchievement(rarity = AchievementRarity.EPIC))
        }
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory()
        buildVm(
            factory = factory,
            progressionRepository = progression,
            achievementRepository = achievements,
            reviewPromptCoordinator = coordinator,
        )

        factory.capturedOnHandEnded?.invoke(
            GameEvent.HandEnded(sequence = 0, winners = emptyList(), board = emptyList(), revealedHoleCards = emptyMap()),
            stubGameState(),
            1_000L,
        )

        assertEquals(
            listOf(ReviewTrigger.AchievementUnlocked),
            coordinator.requested,
            "achievement unlock subsumes the level-up trigger",
        )
    }

    @Test
    fun leaveTable_inBotMode_requestsSessionEndPrompt() = runUnitTest {
        val coordinator = FakeReviewPromptCoordinator()
        val vm = buildVm(reviewPromptCoordinator = coordinator)

        vm.takeAction(PlayPokerAction.LeaveTable)

        assertEquals(listOf(ReviewTrigger.SessionEnd), coordinator.requested)
    }

    @Test
    fun leaveTable_outsideBotMode_doesNotRequestPrompt() = runUnitTest {
        val coordinator = FakeReviewPromptCoordinator()
        val factory = FakePokerSessionFactory(xpMode = com.dangerfield.cards.libraries.cards.XpMode.MULTIPLAYER)
        val vm = buildVm(factory = factory, reviewPromptCoordinator = coordinator)

        vm.takeAction(PlayPokerAction.LeaveTable)

        assertTrue(
            coordinator.requested.isEmpty(),
            "SessionEnd must not fire on MP exit — disconnects shouldn't masquerade as positive moments",
        )
    }

    @Test
    fun leaveTable_sendsDurableLeaveToSession() = runUnitTest {
        val session = FakePokerSession()
        val factory = FakePokerSessionFactory(
            session = session,
            xpMode = com.dangerfield.cards.libraries.cards.XpMode.MULTIPLAYER,
        )
        val vm = buildVm(factory = factory)

        vm.takeAction(PlayPokerAction.LeaveTable)

        assertEquals(
            1,
            session.leaveCount,
            "leaving the table must send the server leave so the seat is freed",
        )
    }

    // ---------- Multiplayer bust dialog + opponent-left ----------

    @Test
    fun isRealMultiplayer_trueForMultiplayerActiveTable() = runUnitTest {
        val vm = buildVm(factory = FakePokerSessionFactory(xpMode = XpMode.MULTIPLAYER))

        assertTrue(
            vm.state.isRealMultiplayer,
            "a multiplayer session with a live table is real-MP (not practice/solo)",
        )
    }

    @Test
    fun isRealMultiplayer_falseForBotsMode() = runUnitTest {
        val vm = buildVm(factory = FakePokerSessionFactory(xpMode = XpMode.BOTS))

        assertFalse(vm.state.isRealMultiplayer, "solo bots is never real multiplayer")
    }

    @Test
    fun openQuickBuy_fromBustDialog_opensInGameSheet_noNavigation() = runUnitTest {
        val vm = buildVm(factory = FakePokerSessionFactory(xpMode = XpMode.MULTIPLAYER))
        val events = mutableListOf<PlayPokerEvent>()
        val job = launch { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.OpenQuickBuy)
        advanceUntilIdle()

        assertTrue(vm.state.quickBuyOpen, "Buy chips opens the in-game quick-buy sheet")
        assertTrue(events.isEmpty(), "quick-buy stays in-game — no navigation event; got $events")
        job.cancel()
    }

    @Test
    fun confirmQuickBuy_success_emitsQuickBuyFinished_andSyncsChips() = runUnitTest {
        val chips = FakeChipsRepository(initialBalance = 0)
        val purchase = FakePurchaseChipPackUseCase(
            nextOutcome = com.dangerfield.cards.libraries.billing.IapPurchaseOutcome.Success(grantedChips = 30_000),
        )
        val vm = buildVm(
            factory = FakePokerSessionFactory(xpMode = XpMode.MULTIPLAYER),
            chipsRepository = chips,
            purchaseChipPack = purchase,
        )
        val events = mutableListOf<PlayPokerEvent>()
        val job = launch { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.OpenQuickBuy)
        vm.takeAction(PlayPokerAction.ConfirmQuickBuy(SAMPLE_CHIP_PACK))
        advanceUntilIdle()

        assertEquals(listOf(SAMPLE_CHIP_PACK), purchase.calls, "use case invoked with the pack")
        assertFalse(vm.state.quickBuyOpen, "sheet closes after a purchase settles")
        assertFalse(vm.state.purchaseInFlight, "in-flight flag cleared")
        assertTrue(
            events.any {
                it is PlayPokerEvent.QuickBuyFinished &&
                    it.outcome is com.dangerfield.cards.libraries.billing.IapPurchaseOutcome.Success
            },
            "QuickBuyFinished(Success) emitted; got $events",
        )
        assertTrue(chips.syncCount >= 1, "chips synced so the rebuy gate sees the fresh balance")
        job.cancel()
    }

    @Test
    fun confirmQuickBuy_anonymous_emitsClaimAccountRequired() = runUnitTest {
        val purchase = FakePurchaseChipPackUseCase(
            nextOutcome = com.dangerfield.cards.libraries.billing.IapPurchaseOutcome.ClaimAccountRequired,
        )
        val vm = buildVm(
            factory = FakePokerSessionFactory(xpMode = XpMode.MULTIPLAYER),
            purchaseChipPack = purchase,
        )
        val events = mutableListOf<PlayPokerEvent>()
        val job = launch { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.ConfirmQuickBuy(SAMPLE_CHIP_PACK))
        advanceUntilIdle()

        assertTrue(
            events.any { it is PlayPokerEvent.ClaimAccountRequired },
            "anonymous purchase routes to claim; got $events",
        )
        assertFalse(
            events.any { it is PlayPokerEvent.QuickBuyFinished },
            "claim signal must not also fire QuickBuyFinished",
        )
        job.cancel()
    }

    @Test
    fun rebuy_success_emitsRebuySucceeded_andCallsSession() = runUnitTest {
        val session = FakePokerSession()
        val vm = buildVm(
            factory = FakePokerSessionFactory(session = session, xpMode = XpMode.MULTIPLAYER),
        )
        val events = mutableListOf<PlayPokerEvent>()
        val job = launch { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.Rebuy)
        advanceUntilIdle()

        assertEquals(1, session.rebuyCount, "rebuy reaches the session")
        assertTrue(
            events.contains(PlayPokerEvent.RebuySucceeded),
            "successful rebuy emits RebuySucceeded; got $events",
        )
        job.cancel()
    }

    @Test
    fun rebuy_insufficientChips_emitsRebuyInsufficientChips() = runUnitTest {
        val session = FakePokerSession().apply {
            rebuyError = com.dangerfield.cards.features.room.impl.session
                .IntentRejectedException("insufficient chips")
        }
        val vm = buildVm(
            factory = FakePokerSessionFactory(session = session, xpMode = XpMode.MULTIPLAYER),
        )
        val events = mutableListOf<PlayPokerEvent>()
        val job = launch { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.Rebuy)
        advanceUntilIdle()

        assertTrue(
            events.contains(PlayPokerEvent.RebuyInsufficientChips),
            "a rejected-for-funds rebuy surfaces RebuyInsufficientChips; got $events",
        )
        job.cancel()
    }

    @Test
    fun leaveGameFromBust_sendsDurableLeave() = runUnitTest {
        val session = FakePokerSession()
        val vm = buildVm(
            factory = FakePokerSessionFactory(session = session, xpMode = XpMode.MULTIPLAYER),
        )

        vm.takeAction(PlayPokerAction.LeaveGameFromBust)

        assertEquals(1, session.leaveCount, "Leave game from the bust dialog frees the seat")
    }

    @Test
    fun opponentsLeft_fromSession_emitsOpponentsLeftEvent() = runUnitTest {
        val session = FakePokerSession()
        val vm = buildVm(
            factory = FakePokerSessionFactory(session = session, xpMode = XpMode.MULTIPLAYER),
        )
        val events = mutableListOf<PlayPokerEvent>()
        val job = launch { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()

        session.emitOpponentsLeft()
        advanceUntilIdle()

        assertTrue(
            events.contains(PlayPokerEvent.OpponentsLeft),
            "the session's opponents-left signal surfaces as a one-shot VM event; got $events",
        )
        job.cancel()
    }

    // ---------- Settings / cosmetics / win-odds mirrors ----------

    @Test
    fun xpBoostChanged_mirrorsExpiry() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.XpBoostChanged(expiresAtEpochMs = 12_345L))
        assertEquals(12_345L, vm.state.xpBoostExpiresAtEpochMs)
    }

    @Test
    fun equippedFeltChanged_repaintsTableSurface() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.EquippedFeltChanged(EquippedFelt.Neon))
        assertEquals(EquippedFelt.Neon, vm.state.equippedFelt)
    }

    @Test
    fun equippedCardBackChanged_setsStyle() = runUnitTest {
        val style = CardBackStyle.entries.first { it != CardBackStyle.Default }
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.EquippedCardBackChanged(style))
        assertEquals(style, vm.state.equippedCardBack)
    }

    @Test
    fun equippedBadgeChanged_setsEmoji() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.EquippedBadgeChanged("🔥"))
        assertEquals("🔥", vm.state.equippedBadgeEmoji)
    }

    @Test
    fun catalogChanged_mirrorsCatalog() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.CatalogChanged(ProductCatalog.Empty))
        assertEquals(ProductCatalog.Empty, vm.state.catalog)
    }

    @Test
    fun winOddsFlipHintSeenChanged_mirrorsFlag() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.WinOddsFlipHintSeenChanged(seen = true))
        assertTrue(vm.state.winOddsFlipHintSeen)
    }

    @Test
    fun winOddsToolEquipped_flippingOff_clearsBreakdown() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.WinOddsToolEquippedChanged(equipped = true))
        assertTrue(vm.state.winOddsToolEquipped)

        vm.takeAction(PlayPokerAction.WinOddsToolEquippedChanged(equipped = false))
        assertFalse(vm.state.winOddsToolEquipped)
        assertEquals(null, vm.state.humanWinOdds, "stale odds are cleared when the tool unequips")
    }

    @Test
    fun winOddsChanged_null_clearsBreakdown() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.WinOddsChanged(breakdown = null))
        assertEquals(null, vm.state.humanWinOdds)
    }

    @Test
    fun markWinOddsFlipHintSeen_writesThroughToAppCache() = runUnitTest {
        val cache = FakeAppCache()
        val vm = buildVm(appCache = cache)
        assertEquals(false, cache.get().winOddsFlipHintSeen)

        vm.takeAction(PlayPokerAction.MarkWinOddsFlipHintSeen)

        assertEquals(true, cache.get().winOddsFlipHintSeen)
    }

    @Test
    fun equippedBadgesChanged_mirrorsList() = runUnitTest {
        val badges = listOf(PlayerBadge(productId = "badge_1", emoji = "🔥", name = "Hot Streak"))
        val vm = buildVm()
        vm.takeAction(PlayPokerAction.EquippedBadgesChanged(badges))
        assertEquals(badges, vm.state.equippedBadges)
    }

    // ---------- Out-of-order / non-happy-path FSM ----------

    @Test
    fun remoteEmote_fromUnknownSeat_isIgnored() = runUnitTest {
        val vm = buildVm(factory = FakePokerSessionFactory(xpMode = XpMode.MULTIPLAYER))
        advanceUntilIdle() // let the first snapshot project an Active table

        vm.takeAction(PlayPokerAction.RemoteEmoteReceived(seatIndex = 99, emoji = "🎉"))

        assertEquals(null, vm.state.emojiBlast, "an emote for a seat not at the table is dropped")
    }

    @Test
    fun achievementsEarned_setsRecentlyEarned_andClearsAwaiting_evenWithoutPriorPending() = runUnitTest {
        // The pending→earned ordering is the happy path; this pins that a bare
        // AchievementsEarned (e.g. a late async resolve) still lands correctly.
        val earned = listOf(testEarnedAchievement())
        val vm = buildVm()

        vm.takeAction(PlayPokerAction.AchievementsEarned(earned))

        assertEquals(earned, vm.state.recentlyEarned)
        assertEquals(false, vm.state.awaitingHandEndAchievements)
    }

    @Test
    fun requestNextHand_whileAwaitingAchievements_signalsSession_andClearsHandTransients() = runUnitTest {
        val session = FakePokerSession()
        val vm = buildVm(factory = FakePokerSessionFactory(session = session))
        // Mid-hand-end: achievements still computing, an XP award already landed.
        vm.takeAction(PlayPokerAction.HandEndAchievementsPending)
        vm.takeAction(PlayPokerAction.HandXpAwarded(amount = 42))
        assertEquals(true, vm.state.awaitingHandEndAchievements)

        vm.takeAction(PlayPokerAction.RequestNextHand)

        assertEquals(1, session.requestNextHandCount, "next hand is still requested")
        assertEquals(null, vm.state.lastHandXpAwarded, "stale hand-end XP is cleared")
        assertTrue(vm.state.recentlyEarned.isEmpty(), "stale earned list is cleared")
    }

    // ---------- Helpers ----------

    private fun buildVm(
        factory: PokerSessionFactory = FakePokerSessionFactory(),
        progressionRepository: FakeProgressionRepository = FakeProgressionRepository(),
        progressionConfig: ProgressionConfig = FakeProgressionConfig(),
        achievementRepository: FakeAchievementRepository = FakeAchievementRepository(),
        appCache: FakeAppCache = FakeAppCache(),
        profileRepository: FakeProfileRepository = FakeProfileRepository(),
        reviewPromptCoordinator: FakeReviewPromptCoordinator = FakeReviewPromptCoordinator(),
        inventoryRepository: FakeInventoryRepository = FakeInventoryRepository(),
        productsRepository: FakeProductsRepository = FakeProductsRepository(),
        chipsRepository: FakeChipsRepository = FakeChipsRepository(),
        purchaseChipPack: FakePurchaseChipPackUseCase = FakePurchaseChipPackUseCase(),
        clock: kotlin.time.Clock = kotlin.time.Clock.System,
    ): PlayPokerViewModel = PlayPokerViewModel(
        sessionFactory = factory,
        progressionRepository = progressionRepository,
        progressionConfig = progressionConfig,
        achievementRepository = achievementRepository,
        appCache = appCache,
        equipmentRepository = FakeEquipmentRepository(),
        inventoryRepository = inventoryRepository,
        productsRepository = productsRepository,
        chipsRepository = chipsRepository,
        purchaseChipPack = purchaseChipPack,
        profileRepository = profileRepository,
        reviewPromptCoordinator = reviewPromptCoordinator,
        dispatcherProvider = dispatchers,
        appScope = com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope(dispatchers),
        clock = clock,
    )

    private class FixedClock(private val nowMs: Long) : kotlin.time.Clock {
        override fun now(): kotlin.time.Instant =
            kotlin.time.Instant.fromEpochMilliseconds(nowMs)
    }

    private class MutableFixedClock(initialMs: Long) : kotlin.time.Clock {
        private var nowMs: Long = initialMs
        fun advanceTo(ms: Long) { nowMs = ms }
        override fun now(): kotlin.time.Instant =
            kotlin.time.Instant.fromEpochMilliseconds(nowMs)
    }

    private fun testEarnedAchievement(
        rarity: AchievementRarity = AchievementRarity.COMMON,
    ): EarnedAchievement = EarnedAchievement(
        achievement = Achievement(
            id = AchievementId.FIRST_HAND,
            name = "First Hand",
            description = "Play your first hand.",
            icon = "icon",
            rarity = rarity,
            criterion = Criterion.HandsPlayed(target = 1),
            xpReward = 50,
        ),
        earnedAtEpochMs = 0,
    )
}

private val SAMPLE_CHIP_PACK = com.dangerfield.cards.libraries.products.Product.ChipPack(
    id = "chip_pack_medium",
    title = "Tall Stack",
    subtitle = "30,000 chips",
    iconEmoji = "💰",
    grantsChips = 30_000,
    store = com.dangerfield.cards.libraries.products.StoreSku("chips_medium", "$4.99"),
)
