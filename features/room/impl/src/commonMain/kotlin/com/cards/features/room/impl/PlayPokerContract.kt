package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.MatchOverCountdown
import com.dangerfield.cards.features.room.impl.session.NextHandCountdown
import com.dangerfield.cards.features.room.impl.session.PokerSessionFactory
import com.dangerfield.cards.features.room.impl.usecase.EmoteGate
import com.dangerfield.cards.features.room.impl.usecase.WinOddsEngine

import com.dangerfield.cards.libraries.bots.EquityBreakdown
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.EmojiBlast
import com.dangerfield.cards.libraries.cards.GameSpeed
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.TurnFeedback
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.ui.components.PlayerBadge
import com.dangerfield.cards.libraries.ui.components.poker.EquippedFelt

// MVI contract for the play-poker screen: the [PlayPokerState] the VM exposes,
// the [PlayPokerAction]s it reduces, and the one-off [PlayPokerEvent]s it emits.
// Extracted from PlayPokerViewModel so the VM file is just behaviour.

data class PlayPokerState(
    /** UI-projected table the screen renders, produced by [PokerSessionFactory.tableFor]. */
    val table: TableUiState = TableUiState.Loading,
    val occupants: List<SeatOccupant> = emptyList(),
    val cheatSheetOpen: Boolean = false,
    val xp: Long = 0,
    /** Active XP-boost expiry (epoch-ms), or null — drives the level-pill countdown. */
    val xpBoostExpiresAtEpochMs: Long? = null,
    /** Human's derived level from [xp], shown as the seat pill; null pre-load. */
    val humanLevel: Int? = null,
    val lastHandXpAwarded: Int? = null,
    val recentlyEarned: List<EarnedAchievement> = emptyList(),
    /**
     * True from hand-end until achievement recording resolves (async). The
     * bot-mode dismiss path waits on this so a fast "next hand" can't skip a reveal.
     */
    val awaitingHandEndAchievements: Boolean = false,
    val turnFeedback: TurnFeedback = TurnFeedback.Vibrate,
    /** AppData-derived play-screen pacing; scales the deal/reveal animations. */
    val gameSpeed: GameSpeed = GameSpeed.Normal,
    /**
     * AppData-derived: show the "you can turn these off in Settings" footer on
     * the celebration sheet. True only for the first few celebrations (capped
     * in [PlayPokerViewModel]); the toggle that silences reveals entirely lives
     * in Settings and is enforced at hand-end, not via this flag.
     */
    val showAchievementSettingsHint: Boolean = false,
    val connection: ConnectionState = ConnectionState.Connected,
    /** Equipped felt; drives the background paint via [feltSurfaceColor]. */
    val equippedFelt: EquippedFelt = EquippedFelt.Default,
    /** Equipped card-back; pushed into the composition via `LocalCardBackStyle`. */
    val equippedCardBack: com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle =
        com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle.Default,
    /** Whether the Win-Odds tool is equipped — gates [humanWinOdds] so non-owners pay nothing. */
    val winOddsToolEquipped: Boolean = false,
    /**
     * Live win/tie/lose breakdown for the human, or null when not computed
     * (tool unequipped / no hand / pre-first-run). Recomputed only on input
     * changes — see [WinOddsEngine].
     */
    val humanWinOdds: EquityBreakdown? = null,
    /** Equipped permanent seat-badge emoji, or null for an empty slot. */
    val equippedBadgeEmoji: String? = null,
    /** Equipped badges + titles (catalog-resolved) for the profile-sheet chips. */
    val equippedBadges: List<PlayerBadge> = emptyList(),
    /** Product catalog — resolves an opponent's equipped badge ids for their sheet. */
    val catalog: com.dangerfield.cards.libraries.products.ProductCatalog =
        com.dangerfield.cards.libraries.products.ProductCatalog.Empty,
    /** AppData mirror: true = swipe-to-fold skips the confirmation dialog. */
    val swipeFoldGestureAck: Boolean = false,
    /** AppData mirror: true once the user has flipped the win-odds tile (suppresses the wiggle hint). */
    val winOddsFlipHintSeen: Boolean = false,
    /** Blast-tray emojis from owned `emotes_*` packs; empty hides the tray. */
    val availableEmojis: List<String> = emptyList(),
    /**
     * Per-seat mute set (keys from [seatMuteKey]), mirrored from AppData. Drives
     * the avatar-tap toggle state and filters inbound MP emotes via [EmoteGate].
     */
    val mutedEmojiPlayerKeys: Set<String> = emptySet(),
    /** Active full-screen blast; cleared on [PlayPokerAction.EmojiBlastConsumed]. */
    val emojiBlast: EmojiBlast? = null,
    /** Seat the [emojiBlast] came from; null = the local human's own blast. */
    val emojiBlastEmitterSeatIndex: Int? = null,
    /** Epoch-ms the user can blast again (0 = none); gates [PlayPokerAction.BlastEmoji]. */
    val emojiCooldownEndsAtMs: Long = 0L,
    /**
     * Bot vs multiplayer play; constant for the screen's life. Drives the
     * achievement-celebration split (bots get the full-bleed sheet; MP keeps the
     * inline row).
     */
    val xpMode: XpMode = XpMode.BOTS,
    /**
     * Wallet chip balance, mirrored from `ChipsRepository`. `null` until the
     * first sync hydrates it. Drives the bust dialog's rebuy gate (afford the
     * buy-in?) and the quick-buy sheet's balance line.
     */
    val chipBalance: Long? = null,
    /**
     * True when the player has armed a pre-fold from the waiting bar (GAME-30) —
     * a fold that fires automatically the moment their turn arrives, no matter
     * what the action did while they waited. Set while it isn't their turn; the
     * VM submits the fold on turn arrival and clears the flag, and it's retired on
     * a new deal so a stale arm can't fold a fresh hand. Cancelable until it fires.
     */
    val preFoldArmed: Boolean = false,
    /** True while the in-game quick-buy chip-pack sheet is shown (MP bust upsell). */
    val quickBuyOpen: Boolean = false,
    /** True while a quick-buy IAP round-trip is in flight; the sheet shows a spinner. */
    val purchaseInFlight: Boolean = false,
    /** The human's own derived play-style for their self-card; null pre-sync. */
    val ownPlayStyle: PlayStyleAxes? = null,
    /**
     * True when the user owns the `tool_opponent_style` shop utility, which
     * unlocks the play-style readout for human opponents on the seat-tap card.
     */
    val ownsOpponentStyleReader: Boolean = false,
    /**
     * Fetched play-style per opponent userId, populated on demand when their
     * card opens (and the reader is owned). A present key with a null value
     * means "fetched, none yet"; an absent key means "not fetched".
     */
    val opponentStyles: Map<String, PlayStyleAxes?> = emptyMap(),
    /**
     * Opponent userIds whose friend request flipped to "Sent" (optimistically on
     * tap, kept on a successful / auto-accepted request, un-flipped only when the
     * server rejects). Gates the player-card "Add friend" button to its sent
     * state. Mirrors Home's recently-played add-friend model.
     */
    val friendRequestSentIds: Set<String> = emptySet(),
    /**
     * Master gate for the at-table "Add friend" affordance (SOC-2). Mirrors the
     * `social.enabled` app-config flag, default off — when false the player-card
     * hides the add-friend section entirely rather than showing it disabled.
     */
    val socialEnabled: Boolean = false,
    /**
     * Room code for shareable MP sessions, surfaced in the cheat sheet so a
     * host can still invite a friend mid-game (the lobby's code surface is
     * gone by then). Null in solo / bot-only sessions where there's nothing
     * to share.
     */
    val roomCode: String? = null,
    /** Hands the local player has won this session — shown in the "…" overflow sheet. */
    val sessionHandsWon: Int = 0,
    /** Hands the local player was dealt into but didn't win this session. */
    val sessionHandsLost: Int = 0,
    /**
     * Live heads-up rebuy-grace countdown (MP-14), or null when no match-over is
     * pending. Drives the on-table countdown banner: the busted player sees
     * "rebuy in Ns or lose your seat" + a rebuy CTA, the winner sees
     * "auto-continues in Ns". Cleared on a rebuy; a terminal expiry routes the
     * screen off instead. Always null for solo / bot sessions.
     */
    val matchOverCountdown: MatchOverCountdown? = null,
    /**
     * Live between-hands auto-advance countdown (or null between/within hands).
     * On a real-chip table the screen renders "Next hand in 0:0X" with a draining
     * fill in the action area — the window to leave with your winnings before the
     * next hand auto-deals. Null and unused on practice tables (they wait on a tap)
     * and solo. See [realChipsAtStake].
     */
    val nextHandCountdown: NextHandCountdown? = null,
    /**
     * Set when the heads-up match resolved (the rebuy grace expired) — drives the
     * match-over result overlay (MP-14). The screen shows a win/loss result, then
     * routes off when the player dismisses it. Null until the terminal resolve;
     * always null for solo / bot sessions.
     */
    val matchOverResult: MatchOverResult? = null,
) {
    /**
     * Real-chips multiplayer (MP xpMode, not bots-only practice). Gates the bust
     * dialog: real MP shows the terminal Leave/Buy-chips dialog; solo and
     * practice keep the "deal me in" rebuy. False until the table projects.
     */
    val isRealMultiplayer: Boolean
        get() = xpMode == XpMode.MULTIPLAYER &&
            (table as? TableUiState.Active)?.practiceTierBotsOnly == false

    /**
     * True when the hand pays out real chips — a human MP game or the public
     * disclosed-bot subsidy table (bots-only but real chips at stake). False
     * for solo and private practice-bot games.
     */
    val realChipsAtStake: Boolean
        get() = isRealMultiplayer ||
            (table as? TableUiState.Active)?.subsidizedBotTable == true

    /**
     * Whether backing out to Home must prompt a leave/forfeit confirmation
     * rather than leaving silently. True when a hand is live (there's progress
     * to lose) OR this is a real-money multiplayer seat — including when the
     * table is stuck or degraded and never projected to [TableUiState.Active],
     * because a silent back-out there can strand real chips at the seat
     * (MP-31). A free practice-bot table with no live hand still leaves without
     * a prompt. [isRealMultiplayer] can't be used here: it requires an Active
     * projection and so reads false in exactly the degraded case we must catch.
     */
    val requiresLeaveConfirmation: Boolean
        get() {
            val active = table as? TableUiState.Active
            val handInProgress = active != null && active.handResult == null
            val realMoneySeat = xpMode == XpMode.MULTIPLAYER &&
                active?.practiceTierBotsOnly != true
            return handInProgress || realMoneySeat
        }
}

sealed interface PlayPokerAction {
    // Engine subscriptions (internal — fired by VM's own session observers)
    data class GameStateUpdated(val state: GameState) : PlayPokerAction
    data class GameEventReceived(val event: GameEvent) : PlayPokerAction
    data class OccupantsUpdated(val occupants: List<SeatOccupant>) : PlayPokerAction

    // Player intents (from UI taps)
    data class Submit(val intent: PlayerIntent) : PlayPokerAction
    data object RequestNextHand : PlayPokerAction

    /**
     * Arm (true) or cancel (false) a pre-fold from the waiting bar (GAME-30). An
     * armed pre-fold folds automatically the moment the human's turn arrives.
     */
    data class SetPreFold(val armed: Boolean) : PlayPokerAction

    // Local UI
    data object ToggleCheatSheet : PlayPokerAction
    data object DismissEarnedToast : PlayPokerAction

    // Settings mirrors (cache flow → state)
    data class XpChanged(val totalXp: Long) : PlayPokerAction
    data class TurnFeedbackChanged(val value: TurnFeedback) : PlayPokerAction
    data class GameSpeedChanged(val value: GameSpeed) : PlayPokerAction
    data class XpBoostChanged(val expiresAtEpochMs: Long?) : PlayPokerAction

    // Hand-end transients (internal — fired by hand-end callback)
    data class HandXpAwarded(val amount: Int) : PlayPokerAction
    data object HandEndAchievementsPending : PlayPokerAction
    data class AchievementsEarned(val earned: List<EarnedAchievement>) : PlayPokerAction

    /** Fired by the AppCache mirror; gates the celebration sheet's Settings-hint footer. */
    data class AchievementSettingsHintVisibilityChanged(val show: Boolean) : PlayPokerAction

    /** Celebration sheet showed the hint footer — writes through so it fades after a few shows. */
    data object MarkAchievementSettingsHintShown : PlayPokerAction

    /** Fired by the equipment subscription; repaints the table surface. */
    data class EquippedFeltChanged(val felt: EquippedFelt) : PlayPokerAction

    /** Fired by the equipment subscription; flips the ambient card back style. */
    data class EquippedCardBackChanged(
        val style: com.dangerfield.cards.libraries.ui.components.poker.CardBackStyle,
    ) : PlayPokerAction

    /** Fired by the equipment subscription; gates win-odds computation. */
    data class WinOddsToolEquippedChanged(val equipped: Boolean) : PlayPokerAction

    /** Fired by the equity flow after a fresh Monte Carlo run resolves. */
    data class WinOddsChanged(val breakdown: EquityBreakdown?) : PlayPokerAction

    /** Fired by the equipment subscription; flips the equipped permanent seat badge. */
    data class EquippedBadgeChanged(val emoji: String?) : PlayPokerAction

    /** The human's equipped badges + titles, resolved from the catalog, for the
     *  tappable chips on the player-profile sheet. */
    data class EquippedBadgesChanged(val badges: List<PlayerBadge>) : PlayPokerAction

    /** Catalog snapshot — lets the screen resolve an opponent's badge ids. */
    data class CatalogChanged(
        val catalog: com.dangerfield.cards.libraries.products.ProductCatalog,
    ) : PlayPokerAction

    /** Fired by the session's connection-state subscription. */
    data class ConnectionChanged(val connection: ConnectionState) : PlayPokerAction

    /**
     * Fired by the session's match-over countdown subscription (MP-14). Null
     * clears the on-table countdown banner (rebuy landed); non-null opens it.
     */
    data class MatchOverCountdownChanged(
        val countdown: com.dangerfield.cards.features.room.impl.session.MatchOverCountdown?,
    ) : PlayPokerAction

    /**
     * Fired by the session's next-hand countdown subscription. Non-null opens the
     * between-hands "Next hand in 0:0X" countdown; null clears it (the next hand
     * dealt, or the advance was cancelled).
     */
    data class NextHandCountdownChanged(
        val countdown: NextHandCountdown?,
    ) : PlayPokerAction

    /**
     * User-initiated clean exit (back / confirmed leave). Fires
     * [ReviewTrigger.SessionEnd] in bot mode; navigation is the screen's job.
     */
    data object LeaveTable : PlayPokerAction

    /** "Leave game" on the MP bust dialog — same teardown as [LeaveTable]. */
    data object LeaveGameFromBust : PlayPokerAction

    /**
     * Internal — the heads-up match resolved (rebuy grace expired). Surfaces the
     * match-over result overlay (MP-14); the screen routes off when the player
     * dismisses it.
     */
    data class MatchOverResolved(val localPlayerWon: Boolean) : PlayPokerAction

    /** "Buy chips" on the MP bust dialog — opens the in-game quick-buy sheet. */
    data object OpenQuickBuy : PlayPokerAction

    /** Dismiss the quick-buy sheet (scrim tap / cancel / after a purchase). */
    data object DismissQuickBuy : PlayPokerAction

    /** Confirm a chip-pack purchase from the quick-buy sheet — drives the IAP use case. */
    data class ConfirmQuickBuy(
        val pack: com.dangerfield.cards.libraries.products.Product.ChipPack,
    ) : PlayPokerAction

    /** Fired by the chip-balance subscription. */
    data class ChipsChanged(val balance: Long?) : PlayPokerAction

    /**
     * "Rebuy" on the MP bust dialog — buys back into the table. Server debits
     * the buy-in and refills the seat; emits [PlayPokerEvent.RebuySucceeded] or
     * [PlayPokerEvent.RebuyInsufficientChips].
     */
    data object Rebuy : PlayPokerAction

    /** Fired by the AppCache mirror; flips the swipe-fold confirmation gate. */
    data class SwipeFoldAckChanged(val acknowledged: Boolean) : PlayPokerAction

    /** "Don't show again" on the swipe-fold dialog — writes through to AppCache. */
    data object AcknowledgeSwipeFoldGesture : PlayPokerAction

    /** Fired by the AppCache mirror; gates the win-odds flip-tile wiggle hint. */
    data class WinOddsFlipHintSeenChanged(val seen: Boolean) : PlayPokerAction

    /** First flip of the win-odds tile — writes through so the wiggle never replays. */
    data object MarkWinOddsFlipHintSeen : PlayPokerAction

    /** Fired by the inventory subscription. */
    data class AvailableEmojisChanged(val emojis: List<String>) : PlayPokerAction

    /** Fired by the AppCache mirror. */
    data class MutedEmojiPlayersChanged(val keys: Set<String>) : PlayPokerAction

    /** Tray emoji tap; gated on the cooldown, then sets [PlayPokerState.emojiBlast]. */
    data class BlastEmoji(val emoji: String) : PlayPokerAction

    /** Inbound opponent emote (MP); dropped for own-echo/muted/unknown seats. */
    data class RemoteEmoteReceived(val seatIndex: Int, val emoji: String) : PlayPokerAction

    /** Blast animation finished; the timestamp guards against clearing a newer blast. */
    data class EmojiBlastConsumed(val emittedAtEpochMs: Long) : PlayPokerAction

    /** Avatar-tap mute toggle — idempotent on the persisted AppCache set. */
    data class ToggleMutePlayer(val key: String) : PlayPokerAction

    // Play-style (own self-card + gated opponent readout)
    data class OwnPlayStyleChanged(val playStyle: PlayStyleAxes?) : PlayPokerAction
    data class OwnsOpponentStyleReaderChanged(val owned: Boolean) : PlayPokerAction
    /** Opening a human opponent's card; fetches their public style if the reader is owned. */
    data class RequestOpponentStyle(val userId: String) : PlayPokerAction
    data class OpponentStyleLoaded(val userId: String, val playStyle: PlayStyleAxes?) : PlayPokerAction

    /** Fired once per finished hand the human played; updates the session win-loss tally. */
    data class SessionRecordChanged(val won: Int, val lost: Int) : PlayPokerAction

    /** "Add friend" on a human opponent's player card — sends a friend request. */
    data class AddFriend(val userId: String) : PlayPokerAction
    /** Internal — the request was rejected by the server; un-flips the Sent state. */
    data class FriendRequestFailed(val userId: String) : PlayPokerAction
}

sealed interface PlayPokerEvent {
    data class PlayHaptic(val kind: HapticKind) : PlayPokerEvent
    data class PlaySound(val kind: SoundKind) : PlayPokerEvent

    /** Room closed by the server (GC'd / rejected) — terminal; the entry point pops. MP only. */
    data class RoomClosed(val reason: ClosedReason) : PlayPokerEvent

    /** Last human standing (room still exists); the entry point routes by room kind. MP only. */
    data object OpponentsLeft : PlayPokerEvent

    /** A non-last opponent left a live table; the screen toasts "X left the table". MP only. */
    data class OpponentLeft(val displayName: String) : PlayPokerEvent

    /**
     * A quick-buy IAP round-trip finished; the screen toasts the result.
     * Mirrors the shop's `PurchaseFinished` feedback, in-game.
     */
    data class QuickBuyFinished(
        val outcome: com.dangerfield.cards.libraries.billing.IapPurchaseOutcome,
    ) : PlayPokerEvent

    /** Anonymous user tapped buy; the entry point routes to the account-claim flow. */
    data object ClaimAccountRequired : PlayPokerEvent

    /** Rebuy committed — the server refilled the seat; the next snapshot restores the stack. */
    data object RebuySucceeded : PlayPokerEvent

    /** Rebuy rejected for insufficient wallet chips — the screen opens the quick-buy sheet. */
    data object RebuyInsufficientChips : PlayPokerEvent

    /**
     * The server rejected "next hand" because the table genuinely can't deal
     * another one (heads-up, the opponent busted to 0 with no rebuy yet). The
     * screen toasts a notice so the winner's tap isn't a silent no-op (MP-14).
     */
    data object NextHandUnavailable : PlayPokerEvent

    /**
     * "Next hand" was refused by a transient race — the hand is still resolving
     * or the tap rode a stale snapshot after a socket flap (MP-22). NOT the
     * opponent-rebuy case: the live snapshot stream is the resync, so the screen
     * shows a quiet "catching up, try again" hint instead of the terminal rebuy
     * copy the bug surfaced. MP only.
     */
    data object NextHandResyncing : PlayPokerEvent

    /**
     * A submitted action didn't go through — the server either never acked it in
     * time ([IntentFeedbackKind.TimedOut]) or refused it
     * ([IntentFeedbackKind.Rejected]). Without this the player taps and gets a
     * dead pause then silence (MP-20). The entry point toasts a kind-specific
     * hint. MP only — solo submits never throw.
     */
    data class IntentFeedback(val kind: IntentFeedbackKind) : PlayPokerEvent
}

/**
 * Why a submitted intent didn't land, splitting the transient hint copy:
 * [TimedOut] = no server ack ("didn't send"), [Rejected] = server refused it
 * ("not allowed").
 */
enum class IntentFeedbackKind { TimedOut, Rejected }

enum class HapticKind { ActionTaken, HandWon, HandLost, Bust, LevelUp }
enum class SoundKind { CardFlick, ChipClick, Showdown }

/**
 * The outcome of a heads-up match-over (MP-14): the rebuy grace expired and the
 * match ended. [localPlayerWon] picks the result copy — the winner kept the
 * table, the busted player is out.
 */
data class MatchOverResult(val localPlayerWon: Boolean)
