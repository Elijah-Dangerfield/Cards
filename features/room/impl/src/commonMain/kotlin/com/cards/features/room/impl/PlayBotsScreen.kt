package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Slider
import com.dangerfield.cards.libraries.ui.components.text.BasicTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

private val ChipGold = Color(0xFFE0B863)
private val CardWhite = Color(0xFFF4F1E8)
private val CardRed = Color(0xFFC42E2E)
private val CardBlack = Color(0xFF1A1A1A)
private val CardBackBlue = Color(0xFF2E4A9E)
private val SeatActive = Color(0xFFFFD66E)

@Composable
fun PlayBotsScreen(
    state: PlayBotsState,
    onAction: (PlayBotsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var raiseSheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect((state.table as? TableUiState.Active)?.isHumanTurn) {
        if ((state.table as? TableUiState.Active)?.isHumanTurn != true) {
            raiseSheetOpen = false
        }
    }

    Screen(modifier = modifier) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
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
                        onExpandRaise = { raiseSheetOpen = true },
                    )
                }
            }

            val active = state.table as? TableUiState.Active
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
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = AppTheme.colors.text.color,
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
                tint = AppTheme.colors.text.color,
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
    onExpandRaise: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(8.dp))
        OpponentsRow(table = table)

        Spacer(modifier = Modifier.height(28.dp))
        BoardArea(table = table)

        Spacer(modifier = Modifier.weight(1f))
        PlayerArea(table = table)

        Spacer(modifier = Modifier.height(20.dp))
        QuickActionBar(table = table, onIntent = onIntent, onExpandRaise = onExpandRaise)
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
        opponents.forEach { seat ->
            OpponentSeat(seat = seat, bigBlind = table.bigBlind, smallBlind = table.smallBlind)
        }
    }
}

@Composable
private fun OpponentSeat(seat: SeatView, bigBlind: Long, smallBlind: Long) {
    val folded = seat.participation == HandParticipation.Folded
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .alpha(if (folded) 0.4f else 1f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (seat.isActing) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(SeatActive.copy(alpha = 0.28f)),
                )
            }
            AvatarCircle(name = seat.displayName, size = 44.dp)
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
            ChipPill(
                amount = seat.contributedThisStreet,
                isSmallBlind = seat.contributedThisStreet == smallBlind,
                isBigBlind = seat.contributedThisStreet == bigBlind,
            )
        }
    }
}

@Composable
private fun BoardArea(table: TableUiState.Active) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
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
        if (table.pot > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text = table.pot.toString(),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }
        if (table.handResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            HandResultBanner(result = table.handResult, seats = table.seats)
        }
    }
}

@Composable
private fun PlayerArea(table: TableUiState.Active) {
    val human = table.seats.firstOrNull { it.isHuman } ?: return
    val folded = human.participation == HandParticipation.Folded
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.alpha(if (folded) 0.35f else 1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (human.holeCards.isNotEmpty()) {
                human.holeCards.forEach { CardView(card = it, size = CardSize.Hole) }
            } else {
                repeat(2) { CardSlot(size = CardSize.Hole) }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        PlayerInfoTile(
            stack = human.stack,
            handLabel = table.humanHandLabel,
            currentBet = human.contributedThisStreet,
            isActing = human.isActing,
        )
    }
}

@Composable
private fun PlayerInfoTile(stack: Long, handLabel: String?, currentBet: Long, isActing: Boolean) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(
                width = if (isActing) 2.dp else 1.dp,
                color = if (isActing) SeatActive.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = handLabel ?: "",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(if (handLabel != null) 6.dp else 0.dp))
        AvatarCircle(name = "You", size = 44.dp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stack.toString(),
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
        if (currentBet > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            ChipPill(amount = currentBet, isSmallBlind = false, isBigBlind = false)
        }
    }
}

@Composable
private fun QuickActionBar(
    table: TableUiState.Active,
    onIntent: (PlayerIntent) -> Unit,
    onExpandRaise: () -> Unit,
) {
    val legal = table.humanLegalActions
    if (!table.isHumanTurn || legal == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    table.handResult != null -> "Hand complete · next dealing"
                    else -> "Waiting for opponents…"
                },
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
        return
    }

    val humanSeat = table.seats.first { it.isHuman }
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
                if (legal.canCheck) PlayerIntent.Check(humanSeat.index)
                else PlayerIntent.Call(humanSeat.index),
            )
        }
        if (legal.canRaise) {
            PrimaryPill(
                label = "Raise ${legal.minRaiseTotal}",
                modifier = Modifier.weight(1f),
            ) {
                onIntent(PlayerIntent.Raise(humanSeat.index, legal.minRaiseTotal))
            }
        }
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
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

@Composable
private fun PrimaryPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun RaiseSheet(
    legal: LegalActions,
    humanSeatIndex: Int,
    onDismiss: () -> Unit,
    onIntent: (PlayerIntent) -> Unit,
) {
    var raiseTotal by remember(legal.minRaiseTotal, legal.maxRaiseTotal) {
        mutableStateOf(legal.minRaiseTotal.coerceAtMost(legal.maxRaiseTotal))
    }
    var raiseText by remember(legal.minRaiseTotal, legal.maxRaiseTotal) {
        mutableStateOf(raiseTotal.toString())
    }
    fun setRaiseTotal(v: Long) {
        val clamped = v.coerceIn(legal.minRaiseTotal, legal.maxRaiseTotal)
        raiseTotal = clamped
        raiseText = clamped.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "More options",
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = AppTheme.colors.text.color,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryPill(label = "Fold", modifier = Modifier.weight(1f)) {
                onIntent(PlayerIntent.Fold(humanSeatIndex))
            }
            SecondaryPill(label = "All In ${legal.allInAmount}", modifier = Modifier.weight(1f)) {
                onIntent(PlayerIntent.AllIn(humanSeatIndex))
            }
        }

        if (legal.canRaise) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Raise to",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
                RaiseField(
                    text = raiseText,
                    onTextChange = { typed ->
                        val digitsOnly = typed.filter { it.isDigit() }.take(9)
                        raiseText = digitsOnly
                        digitsOnly.toLongOrNull()?.let { setRaiseTotal(it) }
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "max ${legal.maxRaiseTotal}",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
            Slider(
                value = raiseTotal.toFloat(),
                onValueChange = { setRaiseTotal(it.toLong()) },
                valueRange = legal.minRaiseTotal.toFloat()..legal.maxRaiseTotal.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val pot = legal.potIfYouCall
                QuickRaise(label = "½ pot", modifier = Modifier.weight(1f)) {
                    setRaiseTotal(pot / 2)
                }
                QuickRaise(label = "¾ pot", modifier = Modifier.weight(1f)) {
                    setRaiseTotal((pot * 3) / 4)
                }
                QuickRaise(label = "Pot", modifier = Modifier.weight(1f)) {
                    setRaiseTotal(pot)
                }
            }
            PrimaryConfirm(label = "Raise to $raiseTotal") {
                onIntent(PlayerIntent.Raise(humanSeatIndex, raiseTotal))
            }
        }
    }
}

@Composable
private fun SecondaryPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun PrimaryConfirm(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppTheme.colors.accentPrimary.color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun QuickRaise(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
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
private fun RaiseField(text: String, onTextChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            typographyToken = AppTheme.typography.Body.B600,
        )
    }
}

@Composable
private fun AvatarCircle(name: String, size: Dp) {
    val hues = listOf(
        Color(0xFFE07AB1), Color(0xFFF6B26B), Color(0xFFFFD966),
        Color(0xFF93C47D), Color(0xFF76A5AF), Color(0xFF8E7CC3),
    )
    val seed = name.hashCode()
    val bg = hues[((seed % hues.size) + hues.size) % hues.size]
    val initial = name.firstOrNull()?.uppercase() ?: "?"
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
private fun ChipPill(amount: Long, isSmallBlind: Boolean, isBigBlind: Boolean) {
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

private enum class CardSize(val width: Dp, val height: Dp) {
    Small(28.dp, 40.dp),
    Hole(72.dp, 100.dp),
    Board(56.dp, 80.dp),
}

@Composable
private fun CardView(card: Card, size: CardSize) {
    val isRed = card.suit == Suit.Hearts || card.suit == Suit.Diamonds
    Box(
        modifier = Modifier
            .size(width = size.width, height = size.height)
            .shadow(4.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(CardWhite)
            .padding(6.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = card.rank.short,
                typography = if (size == CardSize.Hole) AppTheme.typography.Heading.H600
                else AppTheme.typography.Body.B600,
                color = if (isRed) AppTheme.colors.danger else AppTheme.colors.background,
                modifier = Modifier,
                textAlign = TextAlign.Start,
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomStart) {
                Text(
                    text = card.suit.symbol,
                    typography = if (size == CardSize.Hole) AppTheme.typography.Heading.H600
                    else AppTheme.typography.Body.B600,
                    color = if (isRed) AppTheme.colors.danger else AppTheme.colors.background,
                )
            }
        }
    }
}

@Composable
private fun CardBackView(size: CardSize) {
    Box(
        modifier = Modifier
            .size(width = size.width, height = size.height)
            .shadow(3.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(CardBackBlue, CardBackBlue.copy(alpha = 0.7f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun CardSlot(size: CardSize) {
    Box(
        modifier = Modifier
            .size(width = size.width, height = size.height)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun HandResultBanner(result: HandResultView, seats: List<SeatView>) {
    val lines = result.winners.map { w ->
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
        lines.forEach {
            Text(
                text = it,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        }
    }
}
