package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.PokerSessionFactory
import com.dangerfield.cards.features.room.impl.usecase.EmoteGate
import com.dangerfield.cards.features.room.impl.usecase.WinOddsEngine

import com.dangerfield.cards.libraries.bots.EquityBreakdown
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.EmojiBlast
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
    /** True while the in-game quick-buy chip-pack sheet is shown (MP bust upsell). */
    val quickBuyOpen: Boolean = false,
    /** True while a quick-buy IAP round-trip is in flight; the sheet shows a spinner. */
    val purchaseInFlight: Boolean = false,
) {
    /**
     * Real-chips multiplayer (MP xpMode, not bots-only practice). Gates the bust
     * dialog: real MP shows the terminal Leave/Buy-chips dialog; solo and
     * practice keep the "deal me in" rebuy. False until the table projects.
     */
    val isRealMultiplayer: Boolean
        get() = xpMode == XpMode.MULTIPLAYER &&
            (table as? TableUiState.Active)?.practiceTierBotsOnly == false
}

sealed interface PlayPokerAction {
    // Engine subscriptions (internal — fired by VM's own session observers)
    data class GameStateUpdated(val state: GameState) : PlayPokerAction
    data class GameEventReceived(val event: GameEvent) : PlayPokerAction
    data class OccupantsUpdated(val occupants: List<SeatOccupant>) : PlayPokerAction

    // Player intents (from UI taps)
    data class Submit(val intent: PlayerIntent) : PlayPokerAction
    data object RequestNextHand : PlayPokerAction

    // Local UI
    data object ToggleCheatSheet : PlayPokerAction
    data object DismissEarnedToast : PlayPokerAction

    // Settings mirrors (cache flow → state)
    data class XpChanged(val totalXp: Long) : PlayPokerAction
    data class TurnFeedbackChanged(val value: TurnFeedback) : PlayPokerAction
    data class XpBoostChanged(val expiresAtEpochMs: Long?) : PlayPokerAction

    // Hand-end transients (internal — fired by hand-end callback)
    data class HandXpAwarded(val amount: Int) : PlayPokerAction
    data object HandEndAchievementsPending : PlayPokerAction
    data class AchievementsEarned(val earned: List<EarnedAchievement>) : PlayPokerAction

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
     * User-initiated clean exit (back / confirmed leave). Fires
     * [ReviewTrigger.SessionEnd] in bot mode; navigation is the screen's job.
     */
    data object LeaveTable : PlayPokerAction

    /** "Leave game" on the MP bust dialog — same teardown as [LeaveTable]. */
    data object LeaveGameFromBust : PlayPokerAction

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
}

sealed interface PlayPokerEvent {
    data class PlayHaptic(val kind: HapticKind) : PlayPokerEvent
    data class PlaySound(val kind: SoundKind) : PlayPokerEvent

    /** Room closed by the server (GC'd / rejected) — terminal; the entry point pops. MP only. */
    data class RoomClosed(val reason: ClosedReason) : PlayPokerEvent

    /** Last human standing (room still exists); the entry point routes by room kind. MP only. */
    data object OpponentsLeft : PlayPokerEvent

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
}

enum class HapticKind { ActionTaken, HandWon, HandLost, Bust, LevelUp }
enum class SoundKind { CardFlick, ChipClick, Showdown }
