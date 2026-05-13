package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun PlayBotsScreen(
    state: PlayBotsState,
    onAction: (PlayBotsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            TopBar(
                handNumber = (state.table as? TableUiState.Active)?.handNumber,
                onBack = onBack,
                onCheatSheet = { onAction(PlayBotsAction.ToggleCheatSheet) },
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (val table = state.table) {
                TableUiState.Loading -> LoadingTable()
                is TableUiState.Active -> ActiveTable(
                    table = table,
                    onIntent = { onAction(PlayBotsAction.SubmitIntent(it)) },
                )
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = AppTheme.colors.text.color,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (handNumber != null) "Hand #$handNumber" else "Loading…",
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.fillMaxWidth(0f).weight(1f))
        IconButton(onClick = onCheatSheet) {
            Icon(
                imageVector = Icons.Default.HelpOutline,
                contentDescription = "Hand rankings",
                tint = AppTheme.colors.text.color,
            )
        }
    }
}

@Composable
private fun LoadingTable() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Dealing in…",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ActiveTable(
    table: TableUiState.Active,
    onIntent: (PlayerIntent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        OpponentsRow(table = table)

        Spacer(modifier = Modifier.height(20.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CommunityCards(table.communityCards)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Pot ${table.pot}",
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.text,
                )
                if (table.handResult != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HandResultBanner(result = table.handResult, seats = table.seats)
                }
            }
        }

        HumanRow(table = table)

        Spacer(modifier = Modifier.height(12.dp))

        ActionBar(table = table, onIntent = onIntent)
    }
}

@Composable
private fun OpponentsRow(table: TableUiState.Active) {
    val opponents = table.seats.filter { !it.isHuman }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        opponents.forEach { seat ->
            OpponentSeat(seat = seat)
        }
    }
}

@Composable
private fun OpponentSeat(seat: SeatView) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(4.dp)
            .then(if (seat.isActing) Modifier.border(2.dp, AppTheme.colors.accentPrimary.color, RoundedCornerShape(8.dp)).padding(4.dp) else Modifier),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AppTheme.colors.surfaceSecondary.color),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = seat.displayName,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
        Text(
            text = if (seat.participation == HandParticipation.Folded) "FOLD" else seat.stack.toString(),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row {
            if (seat.holeCards.isNotEmpty()) {
                seat.holeCards.forEach { PlayingCard(card = it, small = true) }
            } else if (seat.showHoleCardBacks) {
                repeat(2) { PlayingCardBack(small = true) }
            }
        }
        if (seat.contributedThisStreet > 0) {
            Text(
                text = seat.contributedThisStreet.toString(),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.accentPrimary,
            )
        }
    }
}

@Composable
private fun CommunityCards(cards: List<Card>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 5) {
            val c = cards.getOrNull(i)
            if (c != null) PlayingCard(card = c) else PlayingCardSlot()
        }
    }
}

@Composable
private fun HumanRow(table: TableUiState.Active) {
    val human = table.seats.firstOrNull { it.isHuman } ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            human.holeCards.forEach { PlayingCard(card = it) }
            if (human.holeCards.isEmpty()) {
                repeat(2) { PlayingCardSlot() }
            }
        }
        Column {
            Text(
                text = human.displayName,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
            )
            Text(
                text = "${human.stack} chips",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
            if (human.contributedThisStreet > 0) {
                Text(
                    text = "Bet ${human.contributedThisStreet}",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.accentPrimary,
                )
            }
        }
    }
}

@Composable
private fun PlayingCard(card: Card, small: Boolean = false) {
    val w = if (small) 24.dp else 48.dp
    val h = if (small) 34.dp else 68.dp
    val isRed = card.suit == Suit.Hearts || card.suit == Suit.Diamonds
    Box(
        modifier = Modifier
            .size(width = w, height = h)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${card.rank.short}${card.suit.symbol}",
            typography = if (small) AppTheme.typography.Body.B400 else AppTheme.typography.Body.B500,
            color = if (isRed) AppTheme.colors.accentPrimary else AppTheme.colors.background,
        )
    }
}

@Composable
private fun PlayingCardBack(small: Boolean = false) {
    val w = if (small) 24.dp else 48.dp
    val h = if (small) 34.dp else 68.dp
    Box(
        modifier = Modifier
            .size(width = w, height = h)
            .clip(RoundedCornerShape(6.dp))
            .background(AppTheme.colors.accentPrimary.color),
    )
}

@Composable
private fun PlayingCardSlot() {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 68.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AppTheme.colors.surfaceSecondary.color),
    )
}

@Composable
private fun HandResultBanner(result: HandResultView, seats: List<SeatView>) {
    val winnerNames = result.winners.map { w ->
        val seat = seats.firstOrNull { it.index == w.seatIndex }
        val name = seat?.displayName ?: "Seat ${w.seatIndex}"
        if (w.byFold) "$name wins ${w.amount} by fold"
        else "$name wins ${w.amount}${w.handRank?.category?.let { " with ${it.displayName}" } ?: ""}"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        winnerNames.forEach {
            Text(
                text = it,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        }
    }
}

@Composable
private fun ActionBar(
    table: TableUiState.Active,
    onIntent: (PlayerIntent) -> Unit,
) {
    val legal = table.humanLegalActions
    if (!table.isHumanTurn || legal == null) {
        Box(modifier = Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
            Text(
                text = if (table.handResult != null) "Hand complete" else "Waiting for opponents…",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
        return
    }

    val humanSeat = table.seats.first { it.isHuman }
    var raiseTotal by remember(legal.minRaiseTotal, legal.maxRaiseTotal) {
        mutableStateOf(legal.minRaiseTotal.coerceAtMost(legal.maxRaiseTotal))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onIntent(PlayerIntent.Fold(humanSeat.index)) },
                modifier = Modifier.weight(1f),
            ) { Text("Fold") }

            if (legal.canCheck) {
                Button(
                    onClick = { onIntent(PlayerIntent.Check(humanSeat.index)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Check") }
            } else if (legal.canCall) {
                Button(
                    onClick = { onIntent(PlayerIntent.Call(humanSeat.index)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Call ${legal.callAmount}") }
            }

            if (legal.canAllIn) {
                Button(
                    onClick = { onIntent(PlayerIntent.AllIn(humanSeat.index)) },
                    modifier = Modifier.weight(1f),
                ) { Text("All In") }
            }
        }

        if (legal.canRaise) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Raise to: $raiseTotal",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.text,
                )
            }
            Slider(
                value = raiseTotal.toFloat(),
                onValueChange = { raiseTotal = it.toLong() },
                valueRange = legal.minRaiseTotal.toFloat()..legal.maxRaiseTotal.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pot = legal.potIfYouCall
                QuickRaiseChip("½ pot") {
                    raiseTotal = (pot / 2).coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
                }
                QuickRaiseChip("¾ pot") {
                    raiseTotal = ((pot * 3) / 4).coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
                }
                QuickRaiseChip("Pot") {
                    raiseTotal = pot.coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
                }
                Button(
                    onClick = {
                        onIntent(PlayerIntent.Raise(humanSeat.index, raiseTotal))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Raise to $raiseTotal") }
            }
        }
    }
}

@Composable
private fun QuickRaiseChip(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(label, typography = AppTheme.typography.Body.B400)
    }
}
