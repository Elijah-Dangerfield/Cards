package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetState
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetValue
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.rememberBottomSheetState
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun StatsExplainersSheet(
    state: BottomSheetState,
    onDismissRequest: () -> Unit,
) {
    BottomSheet(
        state = state,
        onDismissRequest = onDismissRequest,
        dragHandle = BottomSheetDragHandle.Basic,
        stickyTopContent = {
            Text(
                text = "How XP works",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
            )
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SheetSectionTitle("How you earn XP")
                Spacer(modifier = Modifier.height(8.dp))
                HowToEarnCard()
                Spacer(modifier = Modifier.height(20.dp))

                SheetSectionTitle("What XP does for you")
                Spacer(modifier = Modifier.height(8.dp))
                WhatXpDoesCard()
            }
        },
    )
}

@Composable
private fun HowToEarnCard() {
    SheetInfoCard {
        SheetBullet("Finishing a hand — every hand counts, even quick folds")
        SheetBullet("Chips you put in the pot — playing more invested hands earns more")
        SheetBullet("Reaching showdown — bonus for sticking around to the end")
        SheetBullet("Stronger hands at showdown — bigger reveals, bigger reward")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Bots count at half the rate of multiplayer. XP never depends on whether you win or lose the hand — just on how engaged you were.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun WhatXpDoesCard() {
    SheetInfoCard {
        Text(
            text = "XP is your lifetime engagement score. It never goes down. Every session adds to it whether you stack chips or bust out.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Future updates will unlock cosmetics, table titles, and achievement badges as your XP climbs. Multiplayer earns 2× when it ships.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun SheetInfoCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun SheetBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "·",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun SheetSectionTitle(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.text,
    )
}

@Preview
@Composable
private fun StatsExplainersSheetPreview() {
    PreviewContent {
        StatsExplainersSheet(
            state = rememberBottomSheetState(BottomSheetValue.Expanded),
            onDismissRequest = {},
        )
    }
}
