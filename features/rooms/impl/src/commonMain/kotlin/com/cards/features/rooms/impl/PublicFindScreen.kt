package com.dangerfield.cards.features.rooms.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.public_find_blinds_label
import cards.libraries.resources.generated.resources.public_find_blinds_value
import cards.libraries.resources.generated.resources.public_find_cta
import cards.libraries.resources.generated.resources.public_find_cta_hint
import cards.libraries.resources.generated.resources.public_find_explainer_body
import cards.libraries.resources.generated.resources.public_find_explainer_title
import cards.libraries.resources.generated.resources.public_find_range_label
import cards.libraries.resources.generated.resources.public_find_range_per_table
import cards.libraries.resources.generated.resources.public_find_subtitle
import cards.libraries.resources.generated.resources.public_find_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.room.RangeSlider
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.room.RoomVisibility
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.components.room.VisTag
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.color.ProvideContentColor
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Public "Find a table" screen (SPEC §4) — set a buy-in RANGE you're
 * comfortable with, then ask to be seated. No browse list. Visual shell:
 * "Find a table" routes into the (mock) matchmaking Searching screen.
 */
@Composable
fun PublicFindScreen(
    onBack: () -> Unit,
    onFind: (minBuyIn: Long, maxBuyIn: Long) -> Unit,
) {
    var range by remember { mutableStateOf(0.18f..0.6f) }
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.public_find_title),
                sub = stringResource(Res.string.public_find_subtitle),
                onNavigateBack = onBack,
                right = { VisTag(kind = RoomVisibility.Public) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
        ) {
            Spacer(Modifier.height(Dimension.D500))

            PublicHeroCard(
                title = stringResource(Res.string.public_find_explainer_title),
                body = stringResource(Res.string.public_find_explainer_body),
                leading = { HeroBadge { Text("✦", typography = AppTheme.typography.Heading.H700, color = AppTheme.colors.content) } },
            )

            Spacer(Modifier.height(Dimension.D900))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Eyebrow(stringResource(Res.string.public_find_range_label))
                Text(
                    text = stringResource(Res.string.public_find_range_per_table),
                    typography = AppTheme.typography.Label.L400,
                    color = AppTheme.colors.contentTertiary,
                )
            }
            Spacer(Modifier.height(Dimension.D500))

            // Value chips reflecting the current range endpoints.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BuyInChip(buyInFor(range.start))
                BuyInChip(buyInFor(range.endInclusive))
            }
            Spacer(Modifier.height(Dimension.D300))
            ProvideContentColor(AppTheme.colors.accentPrimary) {
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("100", "5k", "25k", "100k").forEach {
                    Text(it, typography = AppTheme.typography.Label.L300, color = AppTheme.colors.contentDisabled)
                }
            }

            Spacer(Modifier.height(Dimension.D900))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R700.shape)
                    .background(AppTheme.colors.surface.color)
                    .border(1.dp, AppTheme.colors.border.color, Radii.R700.shape)
                    .padding(horizontal = Dimension.D600, vertical = Dimension.D500),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Eyebrow(stringResource(Res.string.public_find_blinds_label))
                    Text(
                        text = stringResource(Res.string.public_find_blinds_value),
                        typography = AppTheme.typography.Label.L500,
                        color = AppTheme.colors.content,
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            ButtonPrimary(
                onClick = {
                    onFind(
                        buyInFor(range.start).toLong(),
                        buyInFor(range.endInclusive).toLong(),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.public_find_cta))
            }
            Spacer(Modifier.height(Dimension.D300))
            Text(
                text = stringResource(Res.string.public_find_cta_hint),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Dimension.D800))
        }
    }
}

@Composable
private fun BuyInChip(amount: Int) {
    Row(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.surfaceRaised.color)
            .border(1.dp, AppTheme.colors.border.color, Radii.Round.shape)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D200),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipCoin(size = 13.dp)
        Spacer(Modifier.size(Dimension.D200))
        Text(
            text = formatThousands(amount),
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.content,
        )
    }
}

/** Map a 0..1 slider position to a buy-in figure on a 100..100k scale, rounded.
 *  Starts low so small-stakes tables are reachable (matches the private-create
 *  minimum buy-in). */
private fun buyInFor(fraction: Float): Int {
    val min = 100
    val max = 100_000
    val raw = min + fraction * (max - min)
    val step = 100
    return ((raw / step).roundToInt() * step)
}

private fun formatThousands(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PublicFindScreenPreview() {
    PreviewContent {
        PublicFindScreen(onBack = {}, onFind = { _, _ -> })
    }
}
