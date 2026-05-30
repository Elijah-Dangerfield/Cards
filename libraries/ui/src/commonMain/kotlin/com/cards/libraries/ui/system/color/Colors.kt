package com.dangerfield.cards.system.color

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.libraries.ui.system.color.ColorCard
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.toHexString
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/*
 * The warm "felt" semantic schema. Naming is role / universal-meaning only.
 * Status tokens are FLAT (info/onInfo/infoSubtle, …) — same shape as Material's
 * error/onError/errorContainer. Categorical colors are namespaced (colors.league.*).
 * Old names remain as @Deprecated default getters so call sites compile during migration.
 */
@Immutable
@Suppress("LongParameterList")
interface Colors {

    /* ── Surfaces · neutral elevation ladder ───────────────────── */
    val background: ColorResource        // app canvas, scaffolds, base of full-screen sheets
    val surface: ColorResource           // default container: cards, sheets, menus, banners, rows
    val surfaceRaised: ColorResource     // a thing ON a surface: inputs, nested cards, selected rows
    val surfaceHigh: ColorResource       // highest: pressed states, floating menus/tooltips/popovers
    val surfaceDisabled: ColorResource   // disabled control fills
    val scrim: ColorResource             // dim behind modals/sheets
    val shadow: ColorResource

    /* ── Content · one foreground ramp for background + all surfaces ─ */
    val content: ColorResource           // primary text & active icons
    val contentSecondary: ColorResource  // supporting text, captions, inactive icons
    val contentTertiary: ColorResource   // metadata, placeholders, timestamps
    val contentDisabled: ColorResource   // disabled text/icons

    /* ── Accents · solid / on / deep (3D lip) / subtle (tint) ──── */
    val accentPrimary: ColorResource;        val onAccentPrimary: ColorResource
    val accentPrimaryDeep: ColorResource;    val accentPrimarySubtle: ColorResource
    val accentSecondary: ColorResource;      val onAccentSecondary: ColorResource
    val accentSecondaryDeep: ColorResource;  val accentSecondarySubtle: ColorResource
    val accentTertiary: ColorResource;       val onAccentTertiary: ColorResource
    val accentTertiaryDeep: ColorResource;   val accentTertiarySubtle: ColorResource

    /* ── Status · universal states · solid / on / subtle ───────── */
    val info: ColorResource;     val onInfo: ColorResource;     val infoSubtle: ColorResource
    val success: ColorResource;  val onSuccess: ColorResource;  val successSubtle: ColorResource
    val warning: ColorResource;  val onWarning: ColorResource;  val warningSubtle: ColorResource
    val danger: ColorResource;   val onDanger: ColorResource;   val dangerSubtle: ColorResource
    val dangerDeep: ColorResource // 3D lip for the Danger button

    /* ── Borders ──────────────────────────────────────────────── */
    val border: ColorResource         // default edges, dividers, input rest
    val borderStrong: ColorResource   // focused / selected edges
    val borderDisabled: ColorResource

    /* ── Gradient · the one, derived from the gold accent ──────── */
    val accentPrimaryGradient: Brush   // premium / promo surfaces only

    /* ── Categorical · namespaced, NOT accents ─────────────────── */
    val league: LeagueColors
}

interface LeagueColors {
    val amethyst: ColorResource   // current purple (was RankBadgePurple / old accentEarned)
    // Bronze … Diamond tiers fill in here from the leagues feature.
}

private val WarmPremiumGradient = Brush.linearGradient(
    listOf(Color(0xFF3F320F), Color(0xFF6E5A1F)) // gradient FORM of the gold accent (dark gold)
)

val defaultColors: Colors = object : Colors {
    // Surfaces
    override val background = ColorResource.Espresso950
    override val surface = ColorResource.Espresso900
    override val surfaceRaised = ColorResource.Espresso800
    override val surfaceHigh = ColorResource.Espresso700
    override val surfaceDisabled = ColorResource.Espresso800
    override val scrim = ColorResource.Black_A70
    override val shadow = ColorResource.Black_A30

    // Content
    override val content = ColorResource.WarmWhite
    override val contentSecondary = ColorResource.WarmWhite_A64
    override val contentTertiary = ColorResource.WarmWhite_A44
    override val contentDisabled = ColorResource.WarmWhite_A30

    // Accents
    override val accentPrimary = ColorResource.Gold500
    override val onAccentPrimary = ColorResource.GoldInk
    override val accentPrimaryDeep = ColorResource.Gold800
    override val accentPrimarySubtle = ColorResource.Gold500.withAlpha(0.13f)
    override val accentSecondary = ColorResource.Teal500
    override val onAccentSecondary = ColorResource.TealInk
    override val accentSecondaryDeep = ColorResource.Teal800
    override val accentSecondarySubtle = ColorResource.Teal500.withAlpha(0.13f)
    override val accentTertiary = ColorResource.Coral500
    override val onAccentTertiary = ColorResource.CoralInk
    override val accentTertiaryDeep = ColorResource.Coral800
    override val accentTertiarySubtle = ColorResource.Coral500.withAlpha(0.13f)

    // Status
    override val info = ColorResource.Blue500W
    override val onInfo = ColorResource.BlueInk
    override val infoSubtle = ColorResource.Blue500W.withAlpha(0.13f)
    override val success = ColorResource.Green500W
    override val onSuccess = ColorResource.GreenInk
    override val successSubtle = ColorResource.Green500W.withAlpha(0.13f)
    override val warning = ColorResource.Amber500W
    override val onWarning = ColorResource.AmberInk
    override val warningSubtle = ColorResource.Amber500W.withAlpha(0.13f)
    override val danger = ColorResource.Red500W
    override val onDanger = ColorResource.RedInk
    override val dangerSubtle = ColorResource.Red500W.withAlpha(0.13f)
    override val dangerDeep = ColorResource.Red800W

    // Borders
    override val border = ColorResource.Hairline_09
    override val borderStrong = ColorResource.Hairline_22
    override val borderDisabled = ColorResource.Hairline_06

    // Gradient
    override val accentPrimaryGradient: Brush = WarmPremiumGradient

    // Categorical
    override val league: LeagueColors = object : LeagueColors {
        override val amethyst = ColorResource.LeagueAmethyst
    }
}

@Composable
private fun SectionTitle(text: String, colors: Colors) {
    Text(
        text = text,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.contentSecondary.color,
        modifier = Modifier.padding(bottom = Dimension.D400)
    )
}

@Composable
private fun HeroPanel(colors: Colors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(colors.surface.color)
            .border(1.dp, colors.border.color, Radii.Card.shape)
            .padding(Dimension.D700)
    ) {
        Text(
            text = "Color palette",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colors.content.color
        )
        Text(
            text = "Modern light theme",
            fontSize = 14.sp,
            color = colors.contentSecondary.color,
            modifier = Modifier.padding(top = Dimension.D200)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimension.D600),
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(Radii.Card.shape)
                    .background(colors.surfaceRaised.color)
                    .padding(Dimension.D500)
            ) {
                Text(
                    text = "Active session",
                    fontSize = 14.sp,
                    color = colors.contentSecondary.color
                )
                Text(
                    text = "42m remaining",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.contentSecondary.color,
                    modifier = Modifier.padding(top = Dimension.D200)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(Radii.Card.shape)
                    .background(colors.scrim.color)
                    .padding(Dimension.D500)
            ) {
                Text(
                    text = "Status",
                    fontSize = 14.sp,
                    color = colors.content.color
                )
                Text(
                    text = "All good",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentSecondary.color,
                    modifier = Modifier.padding(top = Dimension.D200)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimension.D600),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Recent activity",
                fontSize = 14.sp,
                color = colors.contentSecondary.color
            )
            Box(
                modifier = Modifier
                    .clip(Radii.Button.shape)
                    .background(colors.accentPrimary.color)
                    .padding(horizontal = Dimension.D800, vertical = Dimension.D400)
            ) {
                Text(
                    text = "View all",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onAccentPrimary.color
                )
            }
        }
    }
}

@Composable
private fun AccentPalette(colors: Colors) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        SectionTitle("Accent stack", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500)
        ) {
            AccentChip(
                label = "Primary",
                background = colors.accentPrimary,
                foreground = colors.onAccentPrimary,
                supporting = colors.accentPrimary.toHexString()
            )
            AccentChip(
                label = "Secondary",
                background = colors.accentSecondary,
                foreground = colors.onAccentSecondary,
                supporting = colors.accentSecondary.toHexString()
            )
        }
    }
}

@Composable
private fun RowScope.AccentChip(
    label: String,
    background: ColorResource,
    foreground: ColorResource,
    supporting: String
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(Radii.Card.shape)
            .background(background.color.copy(alpha = 0.15f))
            .border(1.dp, background.color, Radii.Card.shape)
            .padding(Dimension.D500)
    ) {
        Box(
            modifier = Modifier
                .clip(Radii.Button.shape)
                .background(background.color)
                .padding(horizontal = Dimension.D800, vertical = Dimension.D400)
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = foreground.color
            )
        }
        Text(
            text = background.designSystemName,
            fontSize = 12.sp,
            color = background.color,
            modifier = Modifier.padding(top = Dimension.D300)
        )
        Text(
            text = supporting,
            fontSize = 10.sp,
            color = foreground.color.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun SurfaceStack(colors: Colors) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        SectionTitle("Surface ladder", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500)
        ) {
            SurfaceCard(
                title = "Primary",
                background = colors.surface,
                foreground = colors.content,
                border = colors.border,
                supporting = colors.surface.toHexString()
            )
            SurfaceCard(
                title = "Secondary",
                background = colors.surfaceRaised,
                foreground = colors.contentSecondary,
                border = colors.border,
                supporting = colors.surfaceRaised.toHexString()
            )
            SurfaceCard(
                title = "Tertiary",
                background = colors.surfaceHigh,
                foreground = colors.contentSecondary,
                border = colors.border,
                supporting = colors.surfaceHigh.toHexString()
            )

            SurfaceCard(
                title = "Disabled",
                background = colors.surfaceDisabled,
                foreground = colors.contentDisabled,
                border = colors.border,
                supporting = colors.surfaceHigh.toHexString()
            )
        }
    }
}

@Composable
private fun RowScope.SurfaceCard(
    title: String,
    background: ColorResource,
    foreground: ColorResource,
    border: ColorResource,
    supporting: String
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(Radii.Card.shape)
            .background(background.color)
            .border(1.dp, border.color, Radii.Card.shape)
            .padding(Dimension.D500)
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = foreground.color.copy(alpha = 0.9f)
        )
        Text(
            text = "Card content",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = foreground.color,
            modifier = Modifier.padding(top = Dimension.D200)
        )
        Text(
            text = supporting,
            fontSize = 10.sp,
            color = foreground.color.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = Dimension.D300)
        )
    }
}

@Composable
private fun TextHierarchy(colors: Colors) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        SectionTitle("Typography contrast", colors)
        Column(
            modifier = Modifier
                .clip(Radii.Card.shape)
                .background(colors.background.color)
                .border(1.dp, colors.border.color, Radii.Card.shape)
                .padding(Dimension.D600),
            verticalArrangement = Arrangement.spacedBy(Dimension.D500)
        ) {
            TextSample("Primary", colors.content, colors.content)
            TextSample("Secondary", colors.contentSecondary, colors.contentSecondary)
            TextSample("Disabled", colors.contentDisabled, colors.contentDisabled)
            TextSample("Danger", colors.danger, colors.danger)
        }
    }
}

@Composable
private fun TextSample(label: String, swatch: ColorResource, hexColor: ColorResource) {
    Column {
        Text(
            text = label,
            fontSize = 14.sp,
            color = swatch.color
        )
        Text(
            text = hexColor.toHexString(),
            fontSize = 11.sp,
            color = swatch.color.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun SemanticStrip(colors: Colors) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        SectionTitle("System states", colors)
        Row(
            modifier = Modifier
                .clip(Radii.Card.shape)
                .border(1.dp, colors.border.color, Radii.Card.shape)
                .background(colors.surfaceRaised.color)
                .padding(Dimension.D400),
            horizontalArrangement = Arrangement.spacedBy(Dimension.D400)
        ) {
            SemanticBadge("Background", colors.background, colors.content)
            SemanticBadge("Overlay", colors.scrim, colors.content)
            SemanticBadge("Shadow", colors.shadow, colors.content)
            SemanticBadge("Danger", colors.danger, colors.onAccentSecondary)
        }
    }
}

@Composable
private fun RowScope.SemanticBadge(
    label: String,
    background: ColorResource,
    content: ColorResource
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(Radii.Card.shape)
            .background(background.color)
            .padding(Dimension.D400)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = content.color.copy(alpha = 0.8f)
        )
        Text(
            text = background.designSystemName,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = content.color,
            modifier = Modifier.padding(top = Dimension.D200)
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PaletteGridSection(colors: Colors) {
    val palette = listOf(
        colors.background,
        colors.scrim,
        colors.content,
        colors.surface,
        colors.content,
        colors.surfaceRaised,
        colors.contentSecondary,
        colors.surfaceHigh,
        colors.contentSecondary,
        colors.surfaceDisabled,
        colors.contentDisabled,
        colors.accentPrimary,
        colors.onAccentPrimary,
        colors.accentSecondary,
        colors.onAccentSecondary,
        colors.content,
        colors.contentSecondary,
        colors.contentDisabled,
        colors.danger,
        colors.border,
        colors.borderDisabled,
        colors.shadow
    ).distinctBy { it.designSystemName }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        SectionTitle("Palette grid", colors)
        Box(
            modifier = Modifier
                .clip(Radii.Card.shape)
                .background(colors.surfaceRaised.color)
                .border(1.dp, colors.border.color, Radii.Card.shape)
                .padding(Dimension.D300)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
                verticalArrangement = Arrangement.spacedBy(Dimension.D300)
            ) {
                palette.forEach { swatch ->
                    ColorCard(
                        colorResource = swatch,
                        title = swatch.designSystemName,
                        description = swatch.toHexString()
                    )
                }
            }
        }
    }
}

@Composable
fun PreviewColorSwatch(colors: Colors) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.color)
            .padding(horizontal = Dimension.D800, vertical = Dimension.D600),
        verticalArrangement = Arrangement.spacedBy(Dimension.D700)
    ) {
        item { HeroPanel(colors) }
        item { AccentPalette(colors) }
        item { SurfaceStack(colors) }
        item { TextHierarchy(colors) }
        item { SemanticStrip(colors) }
        item { PaletteGridSection(colors) }
    }
}

@Preview(widthDp = 600, heightDp = 2000)
@Composable
private fun PreviewDefaultColors() {
    PreviewColorSwatch(defaultColors)
}

@Composable
fun ProvideContentColor(color: ColorResource, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides color,
        androidx.compose.material3.LocalContentColor provides color.color,
        content = content
    )
}