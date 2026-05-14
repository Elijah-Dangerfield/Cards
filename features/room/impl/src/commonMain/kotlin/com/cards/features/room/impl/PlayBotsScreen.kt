package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Slider
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSlot
import com.dangerfield.cards.libraries.ui.components.text.BasicTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay


@Composable
fun PlayBotsScreen(
    state: PlayBotsState,
    onAction: (PlayBotsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var raiseSheetOpen by remember { mutableStateOf(false) }
    val active = state.table as? TableUiState.Active
    LaunchedEffect(active?.isHumanTurn) {
        if (active?.isHumanTurn != true) raiseSheetOpen = false
    }

    Screen(modifier = modifier) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                TopBar(
                    handNumber = active?.handNumber,
                    street = active?.street,
                    onBack = onBack,
                    onCheatSheet = { onAction(PlayBotsAction.ToggleCheatSheet) },
                )

                if (active == null) {
                    LoadingTable()
                } else {
                    ActiveTable(
                        table = active,
                        onIntent = { onAction(PlayBotsAction.SubmitIntent(it)) },
                        onExpandRaise = { raiseSheetOpen = true },
                    )
                }
            }

            AnimatedVisibility(
                visible = raiseSheetOpen && active?.isHumanTurn == true && active.humanLegalActions != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                active?.humanLegalActions?.let { legal ->
                    RaiseSheet(
                        legal = legal,
                        humanSeatIndex = active.seats.first { it.isHuman }.index,
                        onDismiss = { raiseSheetOpen = false },
                        onIntent = { intent ->
                            raiseSheetOpen = false
                            onAction(PlayBotsAction.SubmitIntent(intent))
                        },
                    )
                }
            }
        }

        if (state.cheatSheetOpen) {
            HandRankingsCheatSheet(
                onDismiss = { onAction(PlayBotsAction.ToggleCheatSheet) },
                handNumber = active?.handNumber,
                street = active?.street,
            )
        }

        val handResult = active?.handResult
        if (handResult != null && active.seats.isNotEmpty()) {
            ShowdownDialog(
                result = handResult,
                seats = active.seats,
                onNextHand = { onAction(PlayBotsAction.AdvanceNextHand) },
            )
        }
    }
}

@Composable
private fun TopBar(
    handNumber: Int?,
    street: BettingRound?,
    onBack: () -> Unit,
    onCheatSheet: () -> Unit,
) {
    // Minimal top row — just navigation + the info affordance. Hand number
    // and street live in the cheat-sheet/info dialog now (tap "?").
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = AppTheme.colors.text.color,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onCheatSheet) {
            Icon(
                imageVector = Icons.Default.HelpOutline,
                contentDescription = "Hand info and rankings",
                tint = AppTheme.colors.text.color,
            )
        }
    }
}

private fun streetLabel(street: BettingRound): String = when (street) {
    BettingRound.Preflop -> "Preflop"
    BettingRound.Flop -> "Flop"
    BettingRound.Turn -> "Turn"
    BettingRound.River -> "River"
    BettingRound.Showdown -> "Showdown"
    BettingRound.Complete -> "Hand complete"
}

@Composable
private fun LoadingTable() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Dealing in…",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun ActiveTable(
    table: TableUiState.Active,
    onIntent: (PlayerIntent) -> Unit,
    onExpandRaise: () -> Unit,
) {
    // Pinned-bottom layout: opponents + board scroll if needed, but the
    // player's hand and the action bar always sit at the bottom in reach.
    // No "Your turn" banner — the pulsing gold band on the active player
    // (human or bot) carries that signal visually.
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            OpponentsRow(table = table)

            Spacer(modifier = Modifier.height(20.dp))
            BoardArea(table = table)
        }

        PlayerArea(table = table)
        Spacer(modifier = Modifier.height(12.dp))
        QuickActionBar(table = table, onIntent = onIntent, onExpandRaise = onExpandRaise)
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun OpponentsRow(table: TableUiState.Active) {
    val opponents = table.seats.filter { !it.isHuman }
    val winners = table.handResult?.winners?.map { it.seatIndex }?.toSet().orEmpty()
    // Each opponent gets equal share of the row via weight, and the avatar
    // size scales with that share: at 2 opponents the avatars are big and
    // beefy, at 6 they're small and compact, but it never wraps or clips.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rowWidth = maxWidth
        val count = opponents.size.coerceAtLeast(1)
        val perOpponent = (rowWidth / count).coerceAtLeast(56.dp)
        // Leave ~28% margin so name/stack/chip pill have breathing room.
        val avatarSize = (perOpponent * 0.62f).coerceIn(40.dp, 76.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            opponents.forEach { seat ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    OpponentSeat(
                        seat = seat,
                        isWinner = seat.index in winners,
                        avatarSize = avatarSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpponentSeat(seat: SeatView, isWinner: Boolean, avatarSize: Dp) {
    val folded = seat.participation == HandParticipation.Folded
    val ringSize = avatarSize + 12.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .alpha(if (folded) 0.4f else 1f),
    ) {
        // Last action label — crossfades when it changes so it doesn't jump.
        Box(
            modifier = Modifier.height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState = seat.lastAction,
                animationSpec = tween(220),
                label = "last-action",
            ) { action ->
                if (action != null) LastActionPill(label = action.shortLabel())
            }
        }
        Spacer(modifier = Modifier.height(2.dp))

        // Chevron pointing down at the active seat.
        Box(modifier = Modifier.height(14.dp), contentAlignment = Alignment.Center) {
            if (seat.isActing) {
                Text(
                    text = "▼",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.text,
                )
            }
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ringSize)) {
            if (seat.isActing) PulsingActiveRing(modifier = Modifier.size(ringSize))
            if (isWinner) WinnerGlow(modifier = Modifier.size(ringSize))
            AvatarCircle(name = seat.displayName, size = avatarSize, emoji = seat.emoji)
            // Dealer / SB / BB chip in the corner.
            BlindMarker(
                seat = seat,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = seat.displayName,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
        Text(
            text = seat.stack.toString(),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
        if (seat.contributedThisStreet > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            ChipPill(amount = seat.contributedThisStreet)
        }
    }
}

/**
 * Drives the active-turn pulse. At any given moment only one of these is
 * on-screen (the acting seat), so the per-frame work is bounded.
 *
 * Reused for both the opponent ring and the human's player-tile border so the
 * two surfaces pulse in unison.
 */
@Composable
private fun pulseAlpha(low: Float = 0.32f, high: Float = 0.78f): Float {
    val transition = rememberInfiniteTransition(label = "active-pulse")
    val alpha by transition.animateFloat(
        initialValue = low,
        targetValue = high,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    return alpha
}

@Composable
private fun PulsingActiveRing(modifier: Modifier = Modifier) {
    val alpha = pulseAlpha()
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(PokerPalette.SeatActive.copy(alpha = alpha)),
    )
}

@Composable
private fun WinnerGlow(modifier: Modifier = Modifier) {
    // Static gold halo (see PulsingActiveRing for the rationale on dropping the
    // infinite transition).
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(PokerPalette.ChipGold.copy(alpha = 0.7f)),
    )
}

@Composable
private fun LastActionPill(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun BlindMarker(seat: SeatView, modifier: Modifier = Modifier) {
    val (label, bg) = when {
        seat.isDealer -> "D" to PokerPalette.DealerWhite
        seat.isSmallBlind -> "SB" to PokerPalette.ChipGold
        seat.isBigBlind -> "BB" to PokerPalette.BlindRed
        else -> null to null
    }
    if (label == null || bg == null) return
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.background,
        )
    }
}

@Composable
private fun BoardArea(table: TableUiState.Active) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // Five community cards, overlapping. Later cards render on top of earlier
        // ones — since rank+suit are on the left edge of each card, the right
        // edge being clipped by the next card is fine.
        Row(
            horizontalArrangement = Arrangement.spacedBy((-28).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until 5) {
                val c = table.communityCards.getOrNull(i)
                val streetIndexInStreet = if (i < 3) i else 0
                BoardCard(
                    card = c,
                    slotIndex = i,
                    revealDelayMs = streetIndexInStreet * 240,
                    size = PlayingCardSize.Board,
                )
            }
        }
        if (table.pot > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Pot ${table.pot}",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
            )
        }
        // Showdown is rendered as a modal sheet so it doesn't fight the table
        // layout for vertical space — see ShowdownDialog at the screen root.
    }
}

@Composable
private fun PotDisplay(amount: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Tiny stacked chips icon.
        Box(modifier = Modifier.size(width = 18.dp, height = 14.dp)) {
            Box(
                modifier = Modifier
                    .offset(y = (-4).dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(PokerPalette.ChipGold)
                    .border(1.dp, AppTheme.colors.background.color, CircleShape),
            )
            Box(
                modifier = Modifier
                    .offset(x = 4.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(PokerPalette.BlindRed)
                    .border(1.dp, AppTheme.colors.background.color, CircleShape),
            )
        }
        Text(
            text = if (amount > 0) "Pot $amount" else "Pot —",
            typography = AppTheme.typography.Body.B500,
            color = if (amount > 0) AppTheme.colors.text else AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun DeckStack(size: PlayingCardSize) {
    Box(modifier = Modifier.size(width = size.width + 6.dp, height = size.height + 6.dp)) {
        // Two faint shadow-cards offset behind the top to give a "stack" feel.
        Box(
            modifier = Modifier
                .offset(x = 6.dp, y = 6.dp)
                .size(width = size.width, height = size.height)
                .clip(RoundedCornerShape(8.dp))
                .background(PokerPalette.CardBackBlue.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
        )
        Box(
            modifier = Modifier
                .offset(x = 3.dp, y = 3.dp)
                .size(width = size.width, height = size.height)
                .clip(RoundedCornerShape(8.dp))
                .background(PokerPalette.CardBackBlue.copy(alpha = 0.8f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp)),
        )
        PlayingCardBack(size = size)
    }
}

@Composable
private fun BoardCard(card: Card?, slotIndex: Int, revealDelayMs: Int, size: PlayingCardSize) {
    if (card == null) {
        PlayingCardSlot(size = size)
        return
    }
    // Compose previews don't drive animations to completion — without this the
    // card would render mid-flight (translated up, half-flipped). In preview,
    // jump straight to the settled face-up state.
    val inPreview = LocalInspectionMode.current
    key(card) {
        var arrived by remember { mutableStateOf(inPreview) }
        var revealed by remember { mutableStateOf(inPreview) }
        var settled by remember { mutableStateOf(inPreview) }
        if (!inPreview) {
            LaunchedEffect(Unit) {
                delay(revealDelayMs.toLong())
                arrived = true
                delay(340)
                revealed = true
                delay(420)
                settled = true
            }
        }
        if (settled) {
            // Animation finished — render plain, no graphicsLayer.
            PlayingCard(card = card, size = size)
        } else {
            val flightDp = -120f
            val flightPx = with(LocalDensity.current) { flightDp.dp.toPx() }
            val translationY by animateFloatAsState(
                targetValue = if (arrived) 0f else flightPx,
                animationSpec = tween(340, easing = FastOutSlowInEasing),
                label = "board-fly",
            )
            val rotation by animateFloatAsState(
                targetValue = if (revealed) 180f else 0f,
                animationSpec = tween(380),
                label = "board-flip",
            )
            Box(
                modifier = Modifier
                    .size(width = size.width, height = size.height)
                    .graphicsLayer {
                        this.translationY = translationY
                        rotationY = rotation
                        cameraDistance = 12f * density
                    },
            ) {
                if (rotation <= 90f) {
                    PlayingCardBack(size = size)
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f },
                    ) {
                        PlayingCard(card = card, size = size)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerArea(table: TableUiState.Active) {
    val human = table.seats.firstOrNull { it.isHuman } ?: return
    val folded = human.participation == HandParticipation.Folded
    val isWinner = table.handResult?.winners?.any { it.seatIndex == human.index } == true
    // Pulse the band around the whole player area when it's the human's turn.
    // This replaces the dropped "Your turn" text banner.
    val pulseAlpha = if (human.isActing) pulseAlpha(low = 0.30f, high = 0.85f) else 0f
    val borderColor = when {
        isWinner -> PokerPalette.ChipGold
        human.isActing -> PokerPalette.SeatActive.copy(alpha = pulseAlpha)
        else -> Color.Transparent
    }
    val borderWidth = if (isWinner || human.isActing) 2.dp else 0.dp
    // Lock the row's content height to the hole-card height. If we derived it
    // from intrinsic sizing the info tile's content would change with `isActing`
    // (the last-action text shows/hides) and the row would shift shape every
    // time the turn flips. Locking matches the hole cards exactly and keeps the
    // info tile a consistent rectangle.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .height(PlayingCardSize.Hole.height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .alpha(if (folded) 0.35f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-28).dp)) {
                HoleCardSlot(card = human.holeCards.getOrNull(0), dealDelayMs = 0, size = PlayingCardSize.Hole)
                HoleCardSlot(card = human.holeCards.getOrNull(1), dealDelayMs = 150, size = PlayingCardSize.Hole)
            }
        }
        PlayerInfoTile(
            seat = human,
            handLabel = table.humanHandLabel,
            isWinner = isWinner,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun HoleCardSlot(card: Card?, dealDelayMs: Int, size: PlayingCardSize) {
    if (card == null) {
        PlayingCardSlot(size = size)
        return
    }
    val inPreview = LocalInspectionMode.current
    key(card) {
        var arrived by remember { mutableStateOf(inPreview) }
        var revealed by remember { mutableStateOf(inPreview) }
        var settled by remember { mutableStateOf(inPreview) }
        if (!inPreview) {
            LaunchedEffect(Unit) {
                delay(dealDelayMs.toLong())
                arrived = true
                delay(320)
                revealed = true
                delay(420)
                settled = true
            }
        }
        if (settled) {
            PlayingCard(card = card, size = size)
        } else {
            val flightDp = -260f
            val flightPx = with(LocalDensity.current) { flightDp.dp.toPx() }
            val translationY by animateFloatAsState(
                targetValue = if (arrived) 0f else flightPx,
                animationSpec = tween(360, easing = FastOutSlowInEasing),
                label = "hole-fly",
            )
            val rotation by animateFloatAsState(
                targetValue = if (revealed) 180f else 0f,
                animationSpec = tween(380),
                label = "hole-flip",
            )
            Box(
                modifier = Modifier
                    .size(width = size.width, height = size.height)
                    .graphicsLayer {
                        this.translationY = translationY
                        rotationY = rotation
                        cameraDistance = 12f * density
                    },
            ) {
                if (rotation <= 90f) {
                    PlayingCardBack(size = size)
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f },
                    ) {
                        PlayingCard(card = card, size = size)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerInfoTile(
    seat: SeatView,
    handLabel: String?,
    isWinner: Boolean,
    modifier: Modifier = Modifier,
) {
    // The outer PlayerArea now carries the active-turn band and the winner
    // highlight, so this tile stays neutral — just the player's avatar and chip
    // info. Keeps the visual hierarchy clean (one gold accent, not two nested ones).
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = handLabel ?: "",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(if (handLabel != null) 6.dp else 0.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
            if (isWinner) WinnerGlow(modifier = Modifier.size(56.dp))
            AvatarCircle(name = "You", size = 44.dp)
            BlindMarker(
                seat = seat,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = seat.stack.toString(),
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
        if (seat.contributedThisStreet > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            ChipPill(amount = seat.contributedThisStreet)
        }
        if (seat.lastAction != null && !seat.isActing) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = seat.lastAction.shortLabel(),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun QuickActionBar(
    table: TableUiState.Active,
    onIntent: (PlayerIntent) -> Unit,
    onExpandRaise: () -> Unit,
) {
    val currentLegal = table.humanLegalActions
    val humanSeat = table.seats.firstOrNull { it.isHuman }
    val canShow = table.isHumanTurn && currentLegal != null && humanSeat != null

    // Cache the last live LegalActions so the row keeps rendering its previous
    // labels through the exit animation. Without this it'd flash blank as the
    // turn moves to a bot and `currentLegal` becomes null.
    var lastLegal by remember { mutableStateOf<LegalActions?>(null) }
    var lastSeatIndex by remember { mutableStateOf<Int?>(null) }
    if (currentLegal != null && humanSeat != null) {
        lastLegal = currentLegal
        lastSeatIndex = humanSeat.index
    }

    var raiseHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(raiseHintVisible) {
        if (raiseHintVisible) {
            delay(2400)
            raiseHintVisible = false
        }
    }

    // Reserve the bar's height always so the layout above (player area, board)
    // doesn't shift when actions slide in/out. 20dp hint row + 60dp button row.
    Box(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = canShow,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(260)) +
                fadeIn(animationSpec = tween(180)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) +
                fadeOut(animationSpec = tween(140)),
        ) {
            val legal = lastLegal
            val seatIndex = lastSeatIndex
            if (legal != null && seatIndex != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                        if (raiseHintVisible) {
                            Text(
                                text = "Not enough chips to raise — try All In via ↑",
                                typography = AppTheme.typography.Body.B400,
                                color = AppTheme.colors.textSecondary,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryPill(
                            label = if (legal.canCheck) "Check" else "Call ${legal.callAmount}",
                            modifier = Modifier.weight(1f),
                        ) {
                            onIntent(
                                if (legal.canCheck) PlayerIntent.Check(seatIndex)
                                else PlayerIntent.Call(seatIndex),
                            )
                        }
                        PrimaryPill(
                            label = when {
                                !legal.canRaise -> if (legal.isOpenBet) "Bet" else "Raise"
                                legal.isOpenBet -> "Bet ${legal.minRaiseTotal}"
                                else -> "Raise ${legal.minRaiseTotal}"
                            },
                            enabled = legal.canRaise,
                            onDisabledTap = { raiseHintVisible = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            onIntent(
                                if (legal.isOpenBet) {
                                    PlayerIntent.Bet(seatIndex, legal.minRaiseTotal)
                                } else {
                                    PlayerIntent.Raise(seatIndex, legal.minRaiseTotal)
                                },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color.White.copy(alpha = 0.14f))
                                .clickable(onClick = onExpandRaise),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "More options",
                                tint = AppTheme.colors.text.color,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryPill(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDisabledTap: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val faded = remember { ColorResource.FromColor(Color.White.copy(alpha = 0.4f), "disabled-text") }
    Box(
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.06f))
            .clickable(
                onClick = if (enabled) onClick else (onDisabledTap ?: {}),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B600,
            color = if (enabled) AppTheme.colors.text else faded,
        )
    }
}

@Composable
private fun ChipPill(amount: Long) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(PokerPalette.ChipGold)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = amount.toString(),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.background,
        )
    }
}

@Composable
private fun ShowdownDialog(
    result: HandResultView,
    seats: List<SeatView>,
    onNextHand: () -> Unit,
) {
    val winnerIndices = result.winners.map { it.seatIndex }.toSet()
    val humanSeat = seats.firstOrNull { it.isHuman }
    val humanWon = humanSeat != null && humanSeat.index in winnerIndices
    val humanWinAmount = humanSeat
        ?.let { hs -> result.winners.filter { it.seatIndex == hs.index }.sumOf { it.amount } }
        ?: 0L
    val byFold = result.winners.all { it.byFold }
    val totalPot = result.winners.sumOf { it.amount }
    val goldText = remember { ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold") }

    Dialog(onDismissRequest = onNextHand) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when {
                    humanWon -> "You win +$humanWinAmount"
                    byFold -> {
                        val winnerName = seats.firstOrNull { it.index in winnerIndices }?.displayName ?: "Player"
                        "$winnerName wins by fold"
                    }
                    else -> "Showdown"
                },
                typography = AppTheme.typography.Heading.H600,
                color = if (humanWon) goldText else AppTheme.colors.onSurfacePrimary,
            )

            if (!humanWon) {
                Text(
                    text = "Pot $totalPot",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
            }

            // Show the community cards so the player can read each hand against
            // the full board.
            if (result.board.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Board",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    result.board.forEach { card ->
                        PlayingCard(card = card, size = PlayingCardSize.Deck)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Real-poker behavior: mucked (folded) hands stay hidden. Only
            // players who reached showdown reveal their cards.
            if (!byFold) {
                val rows = seats.filter {
                    it.participation != HandParticipation.NotDealt &&
                        it.participation != HandParticipation.Folded &&
                        it.holeCards.size == 2
                }
                rows.forEach { seat ->
                    ShowdownRow(
                        seat = seat,
                        board = result.board,
                        isWinner = seat.index in winnerIndices,
                        winAmount = result.winners.filter { it.seatIndex == seat.index }.sumOf { it.amount },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppTheme.colors.accentPrimary.color)
                    .clickable(onClick = onNextHand)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Next hand",
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.onAccentPrimary,
                )
            }
        }
    }
}

@Composable
private fun ShowdownPanel(result: HandResultView, seats: List<SeatView>) {
    val winnerIndices = result.winners.map { it.seatIndex }.toSet()
    val humanSeat = seats.firstOrNull { it.isHuman }
    val humanWon = humanSeat != null && humanSeat.index in winnerIndices
    val humanLost = humanSeat != null && humanSeat.index !in winnerIndices &&
        humanSeat.participation != HandParticipation.NotDealt
    val humanWinAmount = humanSeat
        ?.let { hs -> result.winners.filter { it.seatIndex == hs.index }.sumOf { it.amount } }
        ?: 0L
    val byFold = result.winners.all { it.byFold }
    val totalPot = result.winners.sumOf { it.amount }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (humanWon) PokerPalette.ChipGold.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.70f))
            .border(
                width = if (humanWon) 2.dp else 1.dp,
                color = if (humanWon) PokerPalette.ChipGold else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Headline
        val headline = when {
            humanWon -> "You win +$humanWinAmount"
            byFold -> {
                val winnerName = seats.firstOrNull { it.index in winnerIndices }?.displayName ?: "Player"
                "$winnerName wins by fold"
            }
            else -> "Showdown"
        }
        Text(
            text = headline,
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.text,
        )
        if (!humanWon) {
            Text(
                text = if (humanLost) "Pot $totalPot" else "",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }

        // Per-player breakdown for showdowns (not fold-around wins).
        if (!byFold) {
            val rows = seats.filter {
                it.participation != HandParticipation.NotDealt &&
                    it.participation != HandParticipation.Folded &&
                    it.holeCards.size == 2
            }
            rows.forEach { seat ->
                ShowdownRow(
                    seat = seat,
                    board = result.board,
                    isWinner = seat.index in winnerIndices,
                    winAmount = result.winners.filter { it.seatIndex == seat.index }.sumOf { it.amount },
                )
            }
        }
    }
}

@Composable
private fun ShowdownRow(
    seat: SeatView,
    board: List<Card>,
    isWinner: Boolean,
    winAmount: Long,
) {
    val category = remember(seat.holeCards, board) {
        if (seat.holeCards.size == 2 && board.size in 3..5) {
            HandEvaluator.evaluate(seat.holeCards + board).category.displayName
        } else null
    }
    val goldText = remember { ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (seat.isHuman) "You" else seat.displayName,
            typography = AppTheme.typography.Body.B500,
            color = if (isWinner) goldText else AppTheme.colors.text,
            modifier = Modifier.width(64.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            seat.holeCards.forEach { PlayingCard(card = it, size = PlayingCardSize.Deck) }
        }
        Spacer(modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = category ?: "—",
                typography = AppTheme.typography.Body.B400,
                color = if (isWinner) goldText else AppTheme.colors.onSurfaceSecondary,
            )
            if (isWinner && winAmount > 0) {
                Text(
                    text = "+$winAmount",
                    typography = AppTheme.typography.Body.B500,
                    color = goldText,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------
// Preview fixtures
//
// Sample states for the bot table. Keep these private so they don't escape
// into production code paths — they exist solely for @Preview rendering and
// are too synthetic to use as test data.
// --------------------------------------------------------------------------

private fun card(rank: Rank, suit: Suit): Card = Card(rank, suit)

private fun previewBotEmoji(name: String): String = when (name) {
    "Jane" -> "🧐"
    "David" -> "😎"
    "Gina" -> "🦊"
    "Steve" -> "🐢"
    "Mike" -> "🤡"
    else -> "🤖"
}

private fun previewHumanSeat(
    stack: Long = 980,
    contributed: Long = 0,
    isActing: Boolean = true,
    holeCards: List<Card> = listOf(card(Rank.Ace, Suit.Spades), card(Rank.King, Suit.Spades)),
    isDealer: Boolean = true,
    isSmallBlind: Boolean = false,
    isBigBlind: Boolean = false,
    lastAction: PlayerAction? = null,
): SeatView = SeatView(
    index = 0,
    displayName = "You",
    stack = stack,
    contributedThisStreet = contributed,
    isActing = isActing,
    isHuman = true,
    isBot = false,
    avatarKey = null,
    emoji = null,
    holeCards = holeCards,
    showHoleCardBacks = false,
    participation = HandParticipation.InHand,
    seatEmpty = false,
    lastAction = lastAction,
    isDealer = isDealer,
    isSmallBlind = isSmallBlind,
    isBigBlind = isBigBlind,
)

private fun previewBotSeat(
    index: Int,
    name: String,
    stack: Long = 1000,
    contributed: Long = 0,
    isActing: Boolean = false,
    participation: HandParticipation = HandParticipation.InHand,
    holeCards: List<Card> = emptyList(),
    lastAction: PlayerAction? = null,
    isDealer: Boolean = false,
    isSmallBlind: Boolean = false,
    isBigBlind: Boolean = false,
): SeatView = SeatView(
    index = index,
    displayName = name,
    stack = stack,
    contributedThisStreet = contributed,
    isActing = isActing,
    isHuman = false,
    isBot = true,
    avatarKey = "avatar_$name",
    emoji = previewBotEmoji(name),
    holeCards = holeCards,
    showHoleCardBacks = participation == HandParticipation.InHand && holeCards.isEmpty(),
    participation = participation,
    seatEmpty = false,
    lastAction = lastAction,
    isDealer = isDealer,
    isSmallBlind = isSmallBlind,
    isBigBlind = isBigBlind,
)

private fun previewActive(
    street: BettingRound = BettingRound.Preflop,
    communityCards: List<Card> = emptyList(),
    pot: Long = 30,
    seats: List<SeatView> = previewDefaultSeats(),
    actingSeatIndex: Int? = 0,
    isHumanTurn: Boolean = actingSeatIndex == 0,
    humanLegalActions: LegalActions? = previewLegalActions(canRaise = true, isOpenBet = false, callAmount = 20),
    humanHandLabel: String? = null,
    handResult: HandResultView? = null,
    handNumber: Int = 1,
    buttonSeatIndex: Int = 0,
    smallBlindSeatIndex: Int? = 1,
    bigBlindSeatIndex: Int? = 2,
): TableUiState.Active = TableUiState.Active(
    street = street,
    communityCards = communityCards,
    pot = pot,
    potCommittedThisStreet = pot,
    seats = seats,
    actingSeatIndex = actingSeatIndex,
    isHumanTurn = isHumanTurn,
    humanLegalActions = humanLegalActions,
    humanHandLabel = humanHandLabel,
    lastBotThoughts = emptyMap(),
    handResult = handResult,
    smallBlind = 10,
    bigBlind = 20,
    handNumber = handNumber,
    buttonSeatIndex = buttonSeatIndex,
    smallBlindSeatIndex = smallBlindSeatIndex,
    bigBlindSeatIndex = bigBlindSeatIndex,
)

private fun previewDefaultSeats(): List<SeatView> = listOf(
    previewHumanSeat(isActing = true, isDealer = true),
    previewBotSeat(index = 1, name = "David", isSmallBlind = true, contributed = 10),
    previewBotSeat(index = 2, name = "Jane", isBigBlind = true, contributed = 20),
    previewBotSeat(index = 3, name = "Mike"),
)

private fun previewLegalActions(
    canCheck: Boolean = false,
    canCall: Boolean = true,
    callAmount: Long = 20,
    canRaise: Boolean = true,
    isOpenBet: Boolean = false,
    minRaiseTotal: Long = 40,
    maxRaiseTotal: Long = 980,
    potIfYouCall: Long = 50,
): LegalActions = LegalActions(
    canCheck = canCheck,
    canCall = canCall,
    callAmount = callAmount,
    canRaise = canRaise,
    isOpenBet = isOpenBet,
    minRaiseTotal = minRaiseTotal,
    maxRaiseTotal = maxRaiseTotal,
    canAllIn = true,
    allInAmount = 980,
    potIfYouCall = potIfYouCall,
)

@Preview
@Composable
private fun PlayBotsScreenPreview_YourTurnPreflop() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(table = previewActive()),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayBotsScreenPreview_YourTurnPostflopOpenBet() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(
                table = previewActive(
                    street = BettingRound.Flop,
                    communityCards = listOf(
                        card(Rank.Ten, Suit.Hearts),
                        card(Rank.Seven, Suit.Clubs),
                        card(Rank.Two, Suit.Diamonds),
                    ),
                    pot = 60,
                    seats = previewDefaultSeats().map {
                        if (it.index == 1) it.copy(lastAction = PlayerAction.Check)
                        else if (it.index == 2) it.copy(lastAction = PlayerAction.Check)
                        else it
                    },
                    humanLegalActions = previewLegalActions(
                        canCheck = true,
                        canCall = false,
                        callAmount = 0,
                        isOpenBet = true,
                        minRaiseTotal = 20,
                    ),
                    humanHandLabel = "High card · Ace",
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayBotsScreenPreview_RaiseUnavailable() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(
                table = previewActive(
                    seats = previewDefaultSeats().map {
                        if (it.isHuman) it.copy(stack = 15) else it
                    },
                    humanLegalActions = previewLegalActions(
                        canRaise = false,
                        callAmount = 20,
                        minRaiseTotal = 40,
                        maxRaiseTotal = 15,
                    ),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayBotsScreenPreview_BotThinking() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(
                table = previewActive(
                    actingSeatIndex = 3,
                    isHumanTurn = false,
                    humanLegalActions = null,
                    seats = previewDefaultSeats().map {
                        when (it.index) {
                            0 -> it.copy(isActing = false)
                            3 -> it.copy(isActing = true)
                            else -> it
                        }
                    },
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayBotsScreenPreview_Showdown() {
    val board = listOf(
        card(Rank.Ten, Suit.Hearts),
        card(Rank.Jack, Suit.Hearts),
        card(Rank.Queen, Suit.Hearts),
        card(Rank.Three, Suit.Clubs),
        card(Rank.Seven, Suit.Spades),
    )
    val seats = listOf(
        previewHumanSeat(
            stack = 1080,
            contributed = 0,
            isActing = false,
            holeCards = listOf(card(Rank.Ace, Suit.Hearts), card(Rank.Ace, Suit.Spades)),
        ),
        previewBotSeat(
            index = 1,
            name = "David",
            stack = 920,
            holeCards = listOf(card(Rank.King, Suit.Spades), card(Rank.King, Suit.Diamonds)),
        ),
        previewBotSeat(
            index = 2,
            name = "Jane",
            stack = 880,
            participation = HandParticipation.Folded,
        ),
        previewBotSeat(
            index = 3,
            name = "Mike",
            stack = 920,
            holeCards = listOf(card(Rank.Nine, Suit.Hearts), card(Rank.Eight, Suit.Hearts)),
        ),
    )
    val result = HandResultView(
        winners = listOf(
            HandWinner(
                seatIndex = 0,
                amount = 180,
                handRank = HandEvaluator.evaluate(seats.first().holeCards + board),
                byFold = false,
            ),
        ),
        board = board,
    )
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(
                table = previewActive(
                    street = BettingRound.Showdown,
                    communityCards = board,
                    pot = 180,
                    seats = seats,
                    actingSeatIndex = null,
                    isHumanTurn = false,
                    humanLegalActions = null,
                    handResult = result,
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayBotsScreenPreview_CheatSheet() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(table = previewActive(), cheatSheetOpen = true),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlayBotsScreenPreview_Loading() {
    PreviewContent {
        PlayBotsScreen(
            state = PlayBotsState(table = TableUiState.Loading),
            onAction = {},
            onBack = {},
        )
    }
}
