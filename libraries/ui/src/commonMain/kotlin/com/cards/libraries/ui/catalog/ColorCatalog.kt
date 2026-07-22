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

/*
 * Color catalog content. Broken into four granular section-group composables so the (large) color
 * area can be spread across columns and split across multiple previews — a single combined preview
 * of all of color is past the IDE's max render size. The previews themselves live in
 * DesignSystemPreview.kt (ColorPrimaryPreview / ColorSupportPreview).
 */

/** Surfaces + the content ramp. */
@Composable
internal fun ColorSurfacesContent() {
    val c = AppTheme.colors
    CatalogSection(
        "Surfaces",
        "The neutral elevation ladder. Each step sits visually 'on top of' the one before it.",
    ) {
        SwatchFlow {
            ColorRow("background", c.background, c.content, "App canvas, scaffolds, and the base of full-screen sheets.")
            ColorRow("surface", c.surface, c.content, "The default container — cards, sheets, menus, banners, list rows.")
            ColorRow("surfaceRaised", c.surfaceRaised, c.content, "A thing ON a surface — text inputs, nested cards, selected rows.")
            ColorRow("surfaceHigh", c.surfaceHigh, c.content, "The highest layer — pressed states, floating menus, tooltips, popovers.")
            ColorRow("surfaceDisabled", c.surfaceDisabled, c.contentDisabled, "Fill for a disabled control (e.g. a disabled filled button).")
            ColorRow("scrim", c.scrim, c.content, "Dims the screen behind a modal or bottom sheet.")
            ColorRow("shadow", c.shadow, c.content, "Drop-shadow color cast by elevated surfaces.")
        }
    }

    CatalogSection(
        "Content",
        "One foreground ramp that works on the background and every surface. Step down for less emphasis.",
    ) {
        SwatchFlow {
            ColorRow("content", c.content, c.background, "Primary text and active icons.")
            ColorRow("contentSecondary", c.contentSecondary, c.background, "Supporting text, captions, inactive icons.")
            ColorRow("contentTertiary", c.contentTertiary, c.background, "Metadata, placeholders, timestamps.")
            ColorRow("contentDisabled", c.contentDisabled, c.background, "Disabled text and icons.")
        }
    }
}

/** The three accent triads. */
@Composable
internal fun ColorAccents() {
    val c = AppTheme.colors
    CatalogSection(
        "Accent · Primary (gold)",
        "The brand. The main CTA, focus rings, selected states. solid → fill, on → text on it, deep → 3D lip, subtle → tint.",
    ) {
        SwatchFlow {
            ColorRow("accentPrimary", c.accentPrimary, c.onAccentPrimary, "Primary buttons, focus, selection — the one thing you want tapped.")
            ColorRow("onAccentPrimary", c.onAccentPrimary, c.accentPrimary, "Text and icons rendered on an accentPrimary fill.")
            ColorRow("accentPrimaryDeep", c.accentPrimaryDeep, c.onAccentPrimary, "The hard band under a filled primary button — the 3D 'lip'.")
            ColorRow("accentPrimarySubtle", c.accentPrimarySubtle, c.content, "Low-alpha gold wash — promo wells, highlighted rows.")
        }
    }

    CatalogSection(
        "Accent · Secondary (teal)",
        "The second brand accent — only for the rare screen with two primary-level CTAs in different colors.",
    ) {
        SwatchFlow {
            ColorRow("accentSecondary", c.accentSecondary, c.onAccentSecondary, "A second, distinct primary-level action (e.g. 'Upgrade' beside 'Continue').")
            ColorRow("onAccentSecondary", c.onAccentSecondary, c.accentSecondary, "Text and icons on an accentSecondary fill.")
            ColorRow("accentSecondaryDeep", c.accentSecondaryDeep, c.onAccentSecondary, "3D lip under a Secondary-accented filled button.")
            ColorRow("accentSecondarySubtle", c.accentSecondarySubtle, c.content, "Low-alpha teal tint.")
        }
    }

    CatalogSection(
        "Accent · Tertiary (coral)",
        "The third brand accent — sparingly, when a third categorical pop is genuinely needed.",
    ) {
        SwatchFlow {
            ColorRow("accentTertiary", c.accentTertiary, c.onAccentTertiary, "A third categorical accent — rare.")
            ColorRow("onAccentTertiary", c.onAccentTertiary, c.accentTertiary, "Text and icons on an accentTertiary fill.")
            ColorRow("accentTertiaryDeep", c.accentTertiaryDeep, c.onAccentTertiary, "3D lip under a Tertiary-accented filled button.")
            ColorRow("accentTertiarySubtle", c.accentTertiarySubtle, c.content, "Low-alpha coral tint.")
        }
    }
}

/** Status states, borders, and the one gradient. */
@Composable
internal fun ColorStatusBordersGradient() {
    val c = AppTheme.colors
    CatalogSection(
        "Status",
        "Universal state meaning, same shape as Material's error roles: solid fill + on-color + subtle tint.",
    ) {
        SwatchFlow {
            ColorRow("info", c.info, c.onInfo, "Neutral, informational callouts and banners.")
            ColorRow("infoSubtle", c.infoSubtle, c.content, "Tinted info background — info banner fill, trust card.")
            ColorRow("success", c.success, c.onSuccess, "Positive confirmation — saved, completed, hand won.")
            ColorRow("successSubtle", c.successSubtle, c.content, "Tinted success background.")
            ColorRow("warning", c.warning, c.onWarning, "Caution — low balance, expiring soon, risky action.")
            ColorRow("warningSubtle", c.warningSubtle, c.content, "Tinted warning background.")
            ColorRow("danger", c.danger, c.onDanger, "Errors and destructive actions (delete, leave).")
            ColorRow("dangerSubtle", c.dangerSubtle, c.content, "Tinted danger background.")
            ColorRow("dangerDeep", c.dangerDeep, c.onDanger, "3D lip under a filled Danger button.")
        }
    }

    CatalogSection(
        "Borders",
        "Edges and dividers. Strength signals interaction state.",
    ) {
        SwatchFlow {
            ColorRow("border", c.border, c.content, "Default edges, dividers, input rest state.")
            ColorRow("borderStrong", c.borderStrong, c.content, "Focused / selected edges, and quiet rest rings (radio, switch).")
            ColorRow("borderDisabled", c.borderDisabled, c.content, "Edges of disabled controls.")
        }
    }

    CatalogSection(
        "Gradient",
        "The single gradient in the system, derived from gold. Premium / promo surfaces only — not decoration.",
    ) {
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
}

/** The categorical (non-semantic) groups: poker, rarity, league. */
@Composable
internal fun ColorCategorical() {
    val c = AppTheme.colors
    CatalogSection(
        "Categorical · colors.poker",
        "Physical-object game colors — a chip is gold, a card back is blue, regardless of theme. NOT semantic; use only on a literal game element.",
    ) {
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

    CatalogSection(
        "Categorical · colors.rarity",
        "Achievement tier identity — a Legendary is the same hue everywhere. Epic intentionally reuses the chip gold.",
    ) {
        SwatchFlow {
            ColorSwatch("rarity.common", c.rarity.common, c.background)
            ColorSwatch("rarity.rare", c.rarity.rare, c.background)
            ColorSwatch("rarity.epic", c.rarity.epic, c.background)
            ColorSwatch("rarity.legendary", c.rarity.legendary, c.background)
        }
    }

    CatalogSection(
        "Categorical · colors.league",
        "League tier color (currently the amethyst from the old rank badge). NOT an accent.",
    ) {
        SwatchFlow {
            ColorSwatch("league.amethyst", c.league.amethyst, c.background)
        }
    }
}
