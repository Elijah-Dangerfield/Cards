package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Every semantic color token, grouped by the role it plays. This is the palette — if it isn't
 * here, it isn't a token. Read it top to bottom: neutral surfaces, the one content ramp, the
 * accent triads, universal status, borders, the premium gradient, categorical.
 */
@Preview(widthDp = 1100, heightDp = 1900)
@Composable
private fun ColorCatalog() {
    CatalogPage(
        title = "Color",
        subtitle = "Warm felt — dark-only. Roles, never literal colors. Status is flat; accents come in solid / on / deep / subtle.",
    ) {
        val c = AppTheme.colors

        CatalogSection("Surfaces · neutral elevation ladder") {
            SwatchFlow {
                ColorSwatch("background", c.background, c.content)
                ColorSwatch("surface", c.surface, c.content)
                ColorSwatch("surfaceRaised", c.surfaceRaised, c.content)
                ColorSwatch("surfaceHigh", c.surfaceHigh, c.content)
                ColorSwatch("surfaceDisabled", c.surfaceDisabled, c.contentDisabled)
                ColorSwatch("scrim", c.scrim, c.content)
                ColorSwatch("shadow", c.shadow, c.content)
            }
        }

        CatalogSection("Content · one foreground ramp") {
            SwatchFlow {
                ColorSwatch("content", c.content, c.background)
                ColorSwatch("contentSecondary", c.contentSecondary, c.background)
                ColorSwatch("contentTertiary", c.contentTertiary, c.background)
                ColorSwatch("contentDisabled", c.contentDisabled, c.background)
            }
        }

        CatalogSection("Accent · Primary (gold)") {
            SwatchFlow {
                ColorSwatch("accentPrimary", c.accentPrimary, c.onAccentPrimary)
                ColorSwatch("onAccentPrimary", c.onAccentPrimary, c.accentPrimary)
                ColorSwatch("accentPrimaryDeep", c.accentPrimaryDeep, c.onAccentPrimary)
                ColorSwatch("accentPrimarySubtle", c.accentPrimarySubtle, c.content)
            }
        }

        CatalogSection("Accent · Secondary (teal)") {
            SwatchFlow {
                ColorSwatch("accentSecondary", c.accentSecondary, c.onAccentSecondary)
                ColorSwatch("onAccentSecondary", c.onAccentSecondary, c.accentSecondary)
                ColorSwatch("accentSecondaryDeep", c.accentSecondaryDeep, c.onAccentSecondary)
                ColorSwatch("accentSecondarySubtle", c.accentSecondarySubtle, c.content)
            }
        }

        CatalogSection("Accent · Tertiary (coral)") {
            SwatchFlow {
                ColorSwatch("accentTertiary", c.accentTertiary, c.onAccentTertiary)
                ColorSwatch("onAccentTertiary", c.onAccentTertiary, c.accentTertiary)
                ColorSwatch("accentTertiaryDeep", c.accentTertiaryDeep, c.onAccentTertiary)
                ColorSwatch("accentTertiarySubtle", c.accentTertiarySubtle, c.content)
            }
        }

        CatalogSection("Status · universal states") {
            SwatchFlow {
                ColorSwatch("info", c.info, c.onInfo)
                ColorSwatch("infoSubtle", c.infoSubtle, c.content)
                ColorSwatch("success", c.success, c.onSuccess)
                ColorSwatch("successSubtle", c.successSubtle, c.content)
                ColorSwatch("warning", c.warning, c.onWarning)
                ColorSwatch("warningSubtle", c.warningSubtle, c.content)
                ColorSwatch("danger", c.danger, c.onDanger)
                ColorSwatch("dangerSubtle", c.dangerSubtle, c.content)
                ColorSwatch("dangerDeep", c.dangerDeep, c.onDanger)
            }
        }

        CatalogSection("Borders") {
            SwatchFlow {
                ColorSwatch("border", c.border, c.content)
                ColorSwatch("borderStrong", c.borderStrong, c.content)
                ColorSwatch("borderDisabled", c.borderDisabled, c.content)
            }
        }

        CatalogSection("Gradient · accentPrimaryGradient (premium / promo only)") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(Radii.Card.shape)
                    .background(c.accentPrimaryGradient)
                    .border(1.dp, c.border.color, Radii.Card.shape)
                    .padding(Dimension.D600),
            ) {
                Text(text = "accentPrimaryGradient", typography = AppTheme.typography.Label.L600)
            }
        }

        CatalogSection("Categorical · colors.league (NOT an accent)") {
            SwatchFlow {
                ColorSwatch("league.amethyst", c.league.amethyst, c.background)
            }
        }

        CatalogSection("Categorical · colors.poker (physical-object game colors)") {
            SwatchFlow {
                ColorSwatch("poker.chipGold", c.poker.chipGold, c.poker.coinGlyph)
                ColorSwatch("poker.cardWhite", c.poker.cardWhite, c.poker.cardBackBlue)
                ColorSwatch("poker.cardBackBlue", c.poker.cardBackBlue, c.poker.cardWhite)
                ColorSwatch("poker.seatActive", c.poker.seatActive, c.poker.coinGlyph)
                ColorSwatch("poker.blindRed", c.poker.blindRed, c.content)
                ColorSwatch("poker.progressionCyan", c.poker.progressionCyan, c.background)
                ColorSwatch("poker.progressionGreen", c.poker.progressionGreen, c.background)
                ColorSwatch("poker.sparkleGold", c.poker.sparkleGold, c.poker.coinGlyph)
                ColorSwatch("poker.rankBadgePurple", c.poker.rankBadgePurple, c.background)
                ColorSwatch("poker.rankBadgePink", c.poker.rankBadgePink, c.background)
                ColorSwatch("poker.feltGreen", c.poker.feltGreen, c.content)
            }
        }

        CatalogSection("Categorical · colors.rarity (achievement tiers)") {
            SwatchFlow {
                ColorSwatch("rarity.common", c.rarity.common, c.background)
                ColorSwatch("rarity.rare", c.rarity.rare, c.background)
                ColorSwatch("rarity.epic", c.rarity.epic, c.background)
                ColorSwatch("rarity.legendary", c.rarity.legendary, c.background)
            }
        }
    }
}
