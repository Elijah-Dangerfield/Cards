package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Slider
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

private val FeltDark = Color(0xFF0E2D1F)
private val FeltMid = Color(0xFF124A33)
private val FeltLight = Color(0xFF1E6A4A)
private val ChipGold = Color(0xFFE0B863)
private val CardWhite = Color(0xFFF4F1E8)
private val CardRed = Color(0xFFC42E2E)
private val CardBlack = Color(0xFF1A1A1A)
private val CardBackBlue = Color(0xFF2E4A9E)
private val ActiveGlow = Color(0xFFFFD66E)

@Composable
fun PlayBotsScreen(
    state: PlayBotsState,
    onAction: (PlayBotsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(modifier = modifier) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(FeltLight, FeltMid, FeltDark),
                        radius = 1400f,
                    ),
                ),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                TopBar(
                    handNumber = (state.table as? TableUiState.Active)?.handNumber,
                    onBack = onBack,
                    onCheatSheet = { onAction(PlayBotsAction.ToggleCheatSheet) },
                )

                when (val table = state.table) {
                    TableUiState.Loading -> LoadingTable()
                    is TableUiState.Active -> ActiveTable(
                        table = table,
                        onIntent = { onAction(PlayBotsAction.SubmitIntent(it)) },
                    )
                }
            }
        }

        if (state.cheatSheetOpen) {
            HandRankingsCheatSheet(onDismiss = { onAction(PlayBotsAction.ToggleCheatSheet) })
        }
    }
}

@Composable
private fun TopBar(
    handNumber: Int?,
    onBack: () -> Unit,
    onCheatSheet: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
        Text(
            text = if (handNumber != null) "Hand #$handNumber" else "Dealing…",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onCheatSheet) {
            Icon(
                imageVector = Icons.Default.HelpOutline,
                contentDescription = "Hand rankings",
                tint = Color.White,
            )
        }
    }
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(8.dp))

        OpponentsRow(table = table)

        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            BoardAndPot(table = table)
        }

        HumanArea(table = table)

        Spacer(modifier = Modifier.height(8.dp))

        ActionBar(table = table, onIntent = onIntent)

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun OpponentsRow(table: TableUiState.Active) {
    val opponents = table.seats.filter { !it.isHuman }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        opponents.forEach { seat -> OpponentSeat(seat = seat) }
    }
}

@Composable
private fun OpponentSeat(seat: SeatView) {
    val folded = seat.participation == HandParticipation.Folded
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp).alpha(if (folded) 0.45f else 1f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (seat.isActing) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(ActiveGlow.copy(alpha = 0.35f)),
                )
            }
            AvatarCircle(name = seat.displayName, size = 48.dp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = seat.displayName,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
        ChipBadge(amount = seat.stack)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (seat.holeCards.isNotEmpty()) {
                seat.holeCards.forEach { CardView(card = it, size = CardSize.Small) }
            } else if (seat.showHoleCardBacks) {
                repeat(2) { CardBackView(size = CardSize.Small) }
            }
        }
        if (seat.contributedThisStreet > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            ChipPill(amount = seat.contributedThisStreet)
        }
        if (folded) {
            Text(
                text = "FOLD",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun BoardAndPot(table: TableUiState.Active) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (table.pot > 0) {
            PotBadge(amount = table.pot)
            Spacer(modifier = Modifier.height(12.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until 5) {
                val c = table.communityCards.getOrNull(i)
                if (c != null) CardView(card = c, size = CardSize.Board)
                else CardSlot(size = CardSize.Board)
            }
        }
        if (table.handResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            HandResultBanner(result = table.handResult, seats = table.seats)
        }
    }
}

@Composable
private fun HumanArea(table: TableUiState.Active) {
    val human = table.seats.firstOrNull { it.isHuman } ?: return
    val folded = human.participation == HandParticipation.Folded
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (human.isActing) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(ActiveGlow.copy(alpha = 0.35f)),
                )
            }
            AvatarCircle(name = "You", size = 48.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "You",
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
            )
            ChipBadge(amount = human.stack)
            if (human.contributedThisStreet > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                ChipPill(amount = human.contributedThisStreet)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.alpha(if (folded) 0.4f else 1f)) {
            if (human.holeCards.isNotEmpty()) {
                human.holeCards.forEach { CardView(card = it, size = CardSize.Hole) }
            } else {
                repeat(2) { CardSlot(size = CardSize.Hole) }
            }
        }
    }
}

@Composable
private fun ActionBar(table: TableUiState.Active, onIntent: (PlayerIntent) -> Unit) {
    val legal = table.humanLegalActions
    if (!table.isHumanTurn || legal == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    table.handResult != null -> "Hand complete — dealing next…"
                    else -> "Waiting for opponents…"
                },
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
            )
        }
        return
    }

    val humanSeat = table.seats.first { it.isHuman }
    var raiseTotal by remember(legal.minRaiseTotal, legal.maxRaiseTotal) {
        mutableStateOf(legal.minRaiseTotal.coerceAtMost(legal.maxRaiseTotal))
    }
    LaunchedEffect(legal.minRaiseTotal, legal.maxRaiseTotal) {
        raiseTotal = raiseTotal.coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton("Fold", modifier = Modifier.weight(1f)) {
                onIntent(PlayerIntent.Fold(humanSeat.index))
            }
            if (legal.canCheck) {
                ActionButton("Check", modifier = Modifier.weight(1f)) {
                    onIntent(PlayerIntent.Check(humanSeat.index))
                }
            } else if (legal.canCall) {
                ActionButton("Call ${legal.callAmount}", modifier = Modifier.weight(1f)) {
                    onIntent(PlayerIntent.Call(humanSeat.index))
                }
            }
            if (legal.canAllIn) {
                ActionButton("All In", modifier = Modifier.weight(1f)) {
                    onIntent(PlayerIntent.AllIn(humanSeat.index))
                }
            }
        }

        if (legal.canRaise) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Raise to",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "$raiseTotal",
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.text,
                )
            }
            Slider(
                value = raiseTotal.toFloat(),
                onValueChange = { raiseTotal = it.toLong() },
                valueRange = legal.minRaiseTotal.toFloat()..legal.maxRaiseTotal.toFloat(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val pot = legal.potIfYouCall
                QuickRaise("½ pot", modifier = Modifier.weight(1f)) {
                    raiseTotal = (pot / 2).coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
                }
                QuickRaise("¾ pot", modifier = Modifier.weight(1f)) {
                    raiseTotal = ((pot * 3) / 4).coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
                }
                QuickRaise("Pot", modifier = Modifier.weight(1f)) {
                    raiseTotal = pot.coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
                }
            }
            ActionButton(
                "Raise to $raiseTotal",
                modifier = Modifier.fillMaxWidth(),
            ) {
                onIntent(PlayerIntent.Raise(humanSeat.index, raiseTotal))
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier) {
        Text(label, typography = AppTheme.typography.Body.B500)
    }
}

@Composable
private fun QuickRaise(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun AvatarCircle(name: String, size: androidx.compose.ui.unit.Dp) {
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    val seed = name.hashCode()
    val hues = listOf(
        Color(0xFFE07AB1), Color(0xFFF6B26B), Color(0xFFFFD966),
        Color(0xFF93C47D), Color(0xFF76A5AF), Color(0xFF8E7CC3),
    )
    val bg = hues[((seed % hues.size) + hues.size) % hues.size]
    Box(
        modifier = Modifier
            .size(size)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun ChipBadge(amount: Long) {
    Text(
        text = amount.toString(),
        typography = AppTheme.typography.Body.B400,
        color = AppTheme.colors.textSecondary,
    )
}

@Composable
private fun ChipPill(amount: Long) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ChipGold)
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
private fun PotBadge(amount: Long) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, ChipGold.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = "Pot $amount",
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
    }
}

private enum class CardSize(val width: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp) {
    Small(28.dp, 40.dp),
    Hole(60.dp, 84.dp),
    Board(48.dp, 68.dp),
}

@Composable
private fun CardView(card: Card, size: CardSize) {
    val isRed = card.suit == Suit.Hearts || card.suit == Suit.Diamonds
    val color = if (isRed) CardRed else CardBlack
    Box(
        modifier = Modifier
            .size(width = size.width, height = size.height)
            .shadow(3.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(CardWhite)
            .padding(4.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = card.rank.short,
                typography = if (size == CardSize.Small) AppTheme.typography.Body.B400 else AppTheme.typography.Body.B600,
                color = AppTheme.colors.background,
                modifier = Modifier,
                textAlign = TextAlign.Start,
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomEnd) {
                Text(
                    text = card.suit.symbol,
                    typography = if (size == CardSize.Small) AppTheme.typography.Body.B500 else AppTheme.typography.Body.B700,
                    color = AppTheme.colors.background,
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().alpha(if (isRed) 1f else 0f).background(color.copy(alpha = 0f)))
    }
}

@Composable
private fun CardBackView(size: CardSize) {
    Box(
        modifier = Modifier
            .size(width = size.width, height = size.height)
            .shadow(3.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(CardBackBlue, CardBackBlue.copy(alpha = 0.7f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun CardSlot(size: CardSize) {
    Box(
        modifier = Modifier
            .size(width = size.width, height = size.height)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun HandResultBanner(result: HandResultView, seats: List<SeatView>) {
    val winnerLines = result.winners.map { w ->
        val seat = seats.firstOrNull { it.index == w.seatIndex }
        val name = seat?.displayName ?: "Seat ${w.seatIndex}"
        if (w.byFold) "$name wins ${w.amount} by fold"
        else "$name wins ${w.amount}${w.handRank?.category?.let { " · ${it.displayName}" } ?: ""}"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        winnerLines.forEach {
            Text(
                text = it,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        }
    }
}

