package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.stats_explainer_does_bullet_level
import cards.libraries.resources.generated.resources.stats_explainer_does_bullet_medals
import cards.libraries.resources.generated.resources.stats_explainer_does_bullet_shop
import cards.libraries.resources.generated.resources.stats_explainer_does_intro
import cards.libraries.resources.generated.resources.stats_explainer_does_section
import cards.libraries.resources.generated.resources.stats_explainer_earn_bullet_hand
import cards.libraries.resources.generated.resources.stats_explainer_earn_bullet_invest
import cards.libraries.resources.generated.resources.stats_explainer_earn_bullet_showdown
import cards.libraries.resources.generated.resources.stats_explainer_earn_bullet_strength
import cards.libraries.resources.generated.resources.stats_explainer_earn_note
import cards.libraries.resources.generated.resources.stats_explainer_earn_section
import cards.libraries.resources.generated.resources.stats_explainer_mp_xp
import cards.libraries.resources.generated.resources.stats_explainer_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetState
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetValue
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.rememberBottomSheetState
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
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
                text = stringResource(Res.string.stats_explainer_title),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SheetSectionTitle(stringResource(Res.string.stats_explainer_earn_section))
                Spacer(modifier = Modifier.height(8.dp))
                HowToEarnCard()
                Spacer(modifier = Modifier.height(20.dp))

                SheetSectionTitle(stringResource(Res.string.stats_explainer_does_section))
                Spacer(modifier = Modifier.height(8.dp))
                WhatXpDoesCard()
            }
        },
    )
}

@Composable
private fun HowToEarnCard() {
    InfoCard {
        Bullet(stringResource(Res.string.stats_explainer_earn_bullet_hand))
        Bullet(stringResource(Res.string.stats_explainer_earn_bullet_invest))
        Bullet(stringResource(Res.string.stats_explainer_earn_bullet_showdown))
        Bullet(stringResource(Res.string.stats_explainer_earn_bullet_strength))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.stats_explainer_earn_note),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun WhatXpDoesCard() {
    InfoCard {
        Text(
            text = stringResource(Res.string.stats_explainer_does_intro),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Bullet(stringResource(Res.string.stats_explainer_does_bullet_shop))
        Bullet(stringResource(Res.string.stats_explainer_does_bullet_medals))
        Bullet(stringResource(Res.string.stats_explainer_does_bullet_level))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.stats_explainer_mp_xp),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

@Composable
private fun SheetSectionTitle(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Body.B600.SemiBold,
        color = AppTheme.colors.content,
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
