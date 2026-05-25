package com.dangerfield.cards.features.room.impl.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonDanger
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD200
import com.dangerfield.cards.system.HorizontalSpacerD400
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD1100

/**
 * Self-contained scripted poker tutorial. Reuses shared UI primitives
 * (AvatarCircle, PlayingCard, Button*) but does not depend on
 * `PlayPokerScreen`'s sub-composables or `TableUiState` — the tutorial
 * has its own simpler state model ([TutorialTable]) so the script
 * doesn't have to round-trip through the live engine's data shapes.
 */
@Composable
internal fun TutorialPokerScreen(
    state: TutorialState,
    onAction: (TutorialAction) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.completed) {
        TutorialCompletedScreen(onExit = onExit, modifier = modifier)
        return
    }

    val step = state.step
    val table = step.table

    Screen(
        modifier = modifier,
        topBar = {
            TopBar(
                title = "Hand ${table.handNumber} of ${table.totalHands}",
                onNavigateBack = onExit,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            VerticalSpacerD200()
            Text(
                text = table.handTitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD600()

            OpponentsRow(
                opponents = table.opponents,
                highlighted = step.coach.anchor == TutorialAnchor.Opponents,
            )

            VerticalSpacerD1100()

            BoardArea(
                community = table.community,
                pot = table.pot,
                potHighlighted = step.coach.anchor == TutorialAnchor.Pot,
                communityHighlighted = step.coach.anchor == TutorialAnchor.Community,
            )

            // Coach-mark + hero + action bar fill the rest. Coach-mark
            // sits just above the action bar regardless of anchor — the
            // anchor controls which UI region gets the highlight ring,
            // not the card's position.
            Spacer(modifier = Modifier.weight(1f))

            CoachMarkCard(
                mark = step.coach,
                onContinue = { onAction(TutorialAction.Continue) },
            )

            VerticalSpacerD500()

            HeroArea(
                holeCards = table.heroHoleCards,
                name = table.heroName,
                emoji = table.heroEmoji,
                backgroundColorHex = table.heroBackgroundColorHex,
                stack = table.heroStack,
                role = table.heroRole,
                lastAction = table.heroLastAction,
                handLabel = table.heroHandLabel,
                holeHighlighted = step.coach.anchor == TutorialAnchor.HoleCards,
                stackHighlighted = step.coach.anchor == TutorialAnchor.Stack,
            )

            VerticalSpacerD500()

            ActionBar(
                actions = table.legalActions,
                actionBarHighlighted = step.coach.anchor == TutorialAnchor.ActionBar,
                onAction = onAction,
            )

            VerticalSpacerD500()
        }
    }
}

// ---------------------------------------------------------------------
// Opponents row
// ---------------------------------------------------------------------

@Composable
private fun OpponentsRow(
    opponents: List<TutorialOpponent>,
    highlighted: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.tutorialHighlight() else Modifier)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        opponents.forEach { opponent ->
            OpponentSeat(opponent)
        }
    }
}

@Composable
private fun OpponentSeat(opponent: TutorialOpponent) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .alpha(if (opponent.folded) 0.4f else 1f),
    ) {
        AvatarCircle(
            name = opponent.name,
            emoji = opponent.emoji,
            backgroundColorHex = opponent.backgroundColorHex,
            size = 52.dp,
        )
        VerticalSpacerD200()
        Text(
            text = opponent.name,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
        opponent.role?.let { role ->
            VerticalSpacerD100()
            BlindBadge(role = role)
        }
        VerticalSpacerD100()
        StackPill(amount = opponent.stack)
        opponent.lastAction?.let { action ->
            VerticalSpacerD100()
            Text(
                text = action,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BlindBadge(role: BlindRole) {
    val (label, accent) = when (role) {
        BlindRole.SmallBlind -> "SB" to AppTheme.colors.accentSecondary.color
        BlindRole.BigBlind -> "BB" to AppTheme.colors.accentPrimary.color
        BlindRole.Button -> "D" to AppTheme.colors.surfaceSecondary.color
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent)
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
private fun StackPill(amount: Long) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = formatChipShort(amount),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
    }
}

// ---------------------------------------------------------------------
// Board area — community cards + pot pill
// ---------------------------------------------------------------------

@Composable
private fun BoardArea(
    community: List<Card>,
    pot: Long,
    potHighlighted: Boolean,
    communityHighlighted: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = if (communityHighlighted) Modifier.tutorialHighlight() else Modifier,
        ) {
            repeat(5) { i ->
                val card = community.getOrNull(i)
                if (card != null) {
                    PlayingCard(card = card, size = PlayingCardSize.Mini)
                } else {
                    EmptyCardSlot(width = PlayingCardSize.Mini.width.value.toInt(), height = PlayingCardSize.Mini.height.value.toInt())
                }
            }
        }
        VerticalSpacerD400()
        PotBadge(amount = pot, highlighted = potHighlighted)
    }
}

@Composable
private fun PotBadge(amount: Long, highlighted: Boolean) {
    Box(
        modifier = Modifier
            .then(if (highlighted) Modifier.tutorialHighlight() else Modifier)
            .clip(RoundedCornerShape(50))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = "POT  ${formatChipShort(amount)}",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun EmptyCardSlot(width: Int, height: Int) {
    Box(
        modifier = Modifier
            .size(width = width.dp, height = height.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(AppTheme.colors.surfacePrimary.color)
            .border(1.dp, AppTheme.colors.borderSecondary.color, RoundedCornerShape(4.dp)),
    )
}

// ---------------------------------------------------------------------
// Hero area — hole cards + avatar + stack
// ---------------------------------------------------------------------

@Composable
private fun HeroArea(
    holeCards: List<Card>,
    name: String,
    emoji: String,
    backgroundColorHex: String,
    stack: Long,
    role: BlindRole?,
    lastAction: String?,
    handLabel: String?,
    holeHighlighted: Boolean,
    stackHighlighted: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = if (holeHighlighted) Modifier.tutorialHighlight() else Modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            holeCards.forEach { card ->
                PlayingCard(card = card, size = PlayingCardSize.Board)
            }
        }
        HorizontalSpacerD400()
        Column(
            modifier = Modifier
                .then(if (stackHighlighted) Modifier.tutorialHighlight() else Modifier)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvatarCircle(
                name = name,
                emoji = emoji,
                backgroundColorHex = backgroundColorHex,
                size = 44.dp,
            )
            VerticalSpacerD100()
            Text(
                text = name,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.text,
            )
            VerticalSpacerD100()
            StackPill(amount = stack)
            role?.let {
                VerticalSpacerD100()
                BlindBadge(role = it)
            }
            lastAction?.let {
                VerticalSpacerD100()
                Text(
                    text = it,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }
    }
    handLabel?.let {
        VerticalSpacerD200()
        Text(
            text = it,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

// ---------------------------------------------------------------------
// Action bar — Fold / Call (or Check) / Raise
// ---------------------------------------------------------------------

@Composable
private fun ActionBar(
    actions: TutorialLegalActions,
    actionBarHighlighted: Boolean,
    onAction: (TutorialAction) -> Unit,
) {
    val show = actions.showCheck || actions.showCall || actions.showRaise || actions.showFold
    if (!show) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (actionBarHighlighted) Modifier.tutorialHighlight() else Modifier)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (actions.showFold) {
            Box(modifier = Modifier.weight(1f)) {
                ButtonDanger(
                    onClick = { onAction(TutorialAction.Fold) },
                    enabled = actions.enabled == TutorialAction.Fold,
                    style = ButtonStyle.Outlined,
                    size = ButtonSize.Medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Fold")
                }
            }
        }
        if (actions.showCheck) {
            Box(modifier = Modifier.weight(1f)) {
                ButtonSecondary(
                    onClick = { onAction(TutorialAction.Check) },
                    enabled = actions.enabled == TutorialAction.Check,
                    size = ButtonSize.Medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Check")
                }
            }
        }
        if (actions.showCall) {
            Box(modifier = Modifier.weight(1f)) {
                ButtonSecondary(
                    onClick = { onAction(TutorialAction.Call) },
                    enabled = actions.enabled == TutorialAction.Call,
                    size = ButtonSize.Medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Call ${actions.callAmount}")
                }
            }
        }
        if (actions.showRaise) {
            Box(modifier = Modifier.weight(1f)) {
                ButtonPrimary(
                    onClick = { onAction(TutorialAction.Raise) },
                    enabled = actions.enabled == TutorialAction.Raise,
                    size = ButtonSize.Medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Raise ${actions.raiseAmount}")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Completed state
// ---------------------------------------------------------------------

@Composable
private fun TutorialCompletedScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(
        modifier = modifier,
        topBar = {
            TopBar(title = "Tutorial complete", onNavigateBack = onExit)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "🎓",
                typography = AppTheme.typography.Display.D1400,
                color = AppTheme.colors.text,
            )
            VerticalSpacerD800()
            Text(
                text = "You're ready",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD400()
            Text(
                text = "Raise the strong hands, call when the price is right, fold the rest. The bots are waiting in Practice.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD800()
            ButtonPrimary(
                onClick = onExit,
                size = ButtonSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }
}

// ---------------------------------------------------------------------
// Highlight modifier — golden ring around the anchored region
// ---------------------------------------------------------------------

@Composable
private fun Modifier.tutorialHighlight(): Modifier =
    this
        .clip(Radii.R400.shape)
        .border(
            width = 2.dp,
            color = AppTheme.colors.accentPrimary.color,
            shape = Radii.R400.shape,
        )

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

private fun formatChipShort(amount: Long): String = when {
    amount >= 1_000_000 -> "${amount / 1_000_000}M"
    amount >= 10_000 -> "${amount / 1_000}k"
    amount >= 1_000 -> {
        val k = amount.toDouble() / 1_000.0
        if (k % 1.0 == 0.0) "${k.toInt()}k" else "${(k * 10).toInt() / 10.0}k"
    }
    else -> amount.toString()
}
