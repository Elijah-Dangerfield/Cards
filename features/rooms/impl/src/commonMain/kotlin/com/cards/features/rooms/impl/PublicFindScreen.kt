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
import cards.libraries.resources.generated.resources.public_find_balance_cap
import cards.libraries.resources.generated.resources.public_find_blinds_label
import cards.libraries.resources.generated.resources.public_find_blinds_value
import cards.libraries.resources.generated.resources.public_find_cta
import cards.libraries.resources.generated.resources.public_find_cta_hint
import cards.libraries.resources.generated.resources.public_find_insufficient_hint
import cards.libraries.resources.generated.resources.public_find_explainer_body
import cards.libraries.resources.generated.resources.public_find_explainer_title
import cards.libraries.resources.generated.resources.public_find_range_label
import cards.libraries.resources.generated.resources.public_find_range_per_table
import cards.libraries.resources.generated.resources.public_find_title
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.room.RangeSlider
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.color.ProvideContentColor
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Public "Find a table" screen (SPEC §4) — set a buy-in RANGE you're
 * comfortable with, then ask to be seated. No browse list.
 * "Find a table" routes into the matchmaking Searching screen.
 */
@Composable
fun PublicFindScreen(
    onBack: () -> Unit,
    onFind: (minBuyIn: Long, maxBuyIn: Long) -> Unit,
    chipBalance: Long?,
) {
    var range by remember { mutableStateOf(0.18f..0.6f) }

    // You can't search for a table you couldn't sit at: cap the top of the range
    // at your wallet (or the table ceiling, whichever's lower). Floored to a clean
    // step so the highest selectable buy-in is always ≤ your balance. The server
    // re-checks this too — the slider is the friendly fence, not the only one.
    val affordable = chipBalance == null || chipBalance >= MIN_BUY_IN
    val effectiveMax = ((chipBalance ?: TABLE_MAX.toLong())
        .coerceAtMost(TABLE_MAX.toLong()) / BUY_IN_STEP * BUY_IN_STEP)
        .coerceAtLeast(MIN_BUY_IN.toLong())
        .toInt()
    // The wallet — not the table ceiling — is what's binding the top of the range.
    val cappedByBalance = chipBalance != null && effectiveMax < TABLE_MAX
    Screen(
        topBar = {
            RoomHeader(
                title = stringResource(Res.string.public_find_title),
                onNavigateBack = onBack,
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
                BuyInChip(buyInFor(range.start, effectiveMax))
                BuyInChip(buyInFor(range.endInclusive, effectiveMax))
            }
            Spacer(Modifier.height(Dimension.D300))
            ProvideContentColor(AppTheme.colors.accentPrimary) {
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    enabled = affordable,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Endpoints reflect the affordable band so the scale stays honest when
            // the wallet caps the top below the table ceiling.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf(0f, 1f / 3f, 2f / 3f, 1f).forEach { fraction ->
                    Text(
                        compactChips(buyInFor(fraction, effectiveMax)),
                        typography = AppTheme.typography.Label.L300,
                        color = AppTheme.colors.contentDisabled,
                    )
                }
            }
            if (cappedByBalance) {
                Spacer(Modifier.height(Dimension.D300))
                Text(
                    text = stringResource(Res.string.public_find_balance_cap),
                    typography = AppTheme.typography.Label.L300,
                    color = AppTheme.colors.contentTertiary,
                )
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
                        buyInFor(range.start, effectiveMax).toLong(),
                        buyInFor(range.endInclusive, effectiveMax).toLong(),
                    )
                },
                enabled = affordable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.public_find_cta))
            }
            Spacer(Modifier.height(Dimension.D300))
            Text(
                text = if (affordable) stringResource(Res.string.public_find_cta_hint)
                else stringResource(Res.string.public_find_insufficient_hint),
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
            text = formatThousands(amount.toLong()),
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.content,
        )
    }
}

/** Smallest buy-in a table allows — mirrors the server's `RoomSettings.MIN_BUY_IN`. */
private const val MIN_BUY_IN = 100

/** Top of the public buy-in scale — the highest table the radar will seat you at. */
private const val TABLE_MAX = 100_000

/** Buy-ins snap to this so blinds stay clean and the cap lands on a round figure. */
private const val BUY_IN_STEP = 100

/** Map a 0..1 slider position to a buy-in figure on a [MIN_BUY_IN]..[maxBuyIn] scale,
 *  snapped to [BUY_IN_STEP] and never above [maxBuyIn] (so a wallet-capped top can't
 *  round past your balance). Starts low so small-stakes tables are reachable. */
private fun buyInFor(fraction: Float, maxBuyIn: Int): Int {
    val raw = MIN_BUY_IN + fraction * (maxBuyIn - MIN_BUY_IN)
    return ((raw / BUY_IN_STEP).roundToInt() * BUY_IN_STEP).coerceIn(MIN_BUY_IN, maxBuyIn)
}

/** Compact label for the scale ticks under the slider (e.g. 33_300 -> "33k"). */
private fun compactChips(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}m"
    value >= 1_000 -> "${value / 1_000}k"
    else -> value.toString()
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PublicFindScreenPreview() {
    PreviewContent {
        PublicFindScreen(onBack = {}, onFind = { _, _ -> }, chipBalance = 50_000)
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PublicFindScreenInsufficientPreview() {
    PreviewContent {
        PublicFindScreen(onBack = {}, onFind = { _, _ -> }, chipBalance = 0)
    }
}
