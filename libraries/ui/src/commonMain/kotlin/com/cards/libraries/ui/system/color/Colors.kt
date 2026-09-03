package com.dangerfield.cards.system.color

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import androidx.compose.ui.tooling.preview.Preview

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

    /* ── Inverse surface · a light fill flipped onto the dark theme ─ */
    val surfaceInverse: ColorResource      // near-white fill (white button/chip)
    val onSurfaceInverse: ColorResource    // dark ink on the inverse surface
    val surfaceInverseDeep: ColorResource  // 3D lip under a filled inverse button

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
    val poker: PokerColors    // physical-object game colors (chip gold, card back, …)
    val rarity: RarityColors  // achievement rarity tiers
}

interface LeagueColors {
    val amethyst: ColorResource   // current purple (was RankBadgePurple / old accentEarned)
    // Bronze … Diamond tiers fill in here from the leagues feature.
}

/**
 * Physical-object poker colors — a chip is gold, a card back is blue, regardless of theme. These
 * are categorical, NOT semantic; reach for [Colors] accents/surfaces for anything theme-driven.
 */
interface PokerColors {
    val chipGold: ColorResource
    val chipGoldOutline: ColorResource
    val cardWhite: ColorResource
    val dealerWhite: ColorResource   // alias of cardWhite, kept distinct so the role reads at the call site
    val cardBackBlue: ColorResource
    val seatActive: ColorResource
    val blindRed: ColorResource
    val cardSlot: ColorResource
    val cardSlotOutline: ColorResource
    val progressionCyan: ColorResource
    val progressionGreen: ColorResource
    val sparkleGold: ColorResource
    val coinGradientStart: ColorResource
    val coinGradientEnd: ColorResource
    val coinOutline: ColorResource
    val coinGlyph: ColorResource
    val rankBadgePurple: ColorResource
    val rankBadgePink: ColorResource
    val feltGreen: ColorResource
}

/** Achievement rarity tier identity colors. Epic reuses [PokerColors.chipGold]. */
interface RarityColors {
    val common: ColorResource
    val rare: ColorResource
    val epic: ColorResource
    val legendary: ColorResource
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

    // Inverse surface
    override val surfaceInverse = ColorResource.SurfaceInverse
    override val onSurfaceInverse = ColorResource.Espresso950
    override val surfaceInverseDeep = ColorResource.SurfaceInverseDeep

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
    override val poker: PokerColors = object : PokerColors {
        override val chipGold = ColorResource.PokerChipGold
        override val chipGoldOutline = ColorResource.PokerChipGoldOutline
        override val cardWhite = ColorResource.PokerCardWhite
        override val dealerWhite = ColorResource.PokerCardWhite
        override val cardBackBlue = ColorResource.PokerCardBackBlue
        override val seatActive = ColorResource.PokerSeatActive
        override val blindRed = ColorResource.PokerBlindRed
        override val cardSlot = ColorResource.PokerCardSlot
        override val cardSlotOutline = ColorResource.PokerCardSlotOutline
        override val progressionCyan = ColorResource.PokerProgressionCyan
        override val progressionGreen = ColorResource.PokerProgressionGreen
        override val sparkleGold = ColorResource.PokerSparkleGold
        override val coinGradientStart = ColorResource.PokerCoinGradientStart
        override val coinGradientEnd = ColorResource.PokerCoinGradientEnd
        override val coinOutline = ColorResource.PokerCoinOutline
        override val coinGlyph = ColorResource.PokerCoinGlyph
        override val rankBadgePurple = ColorResource.PokerRankBadgePurple
        override val rankBadgePink = ColorResource.PokerRankBadgePink
        override val feltGreen = ColorResource.PokerFeltGreen
    }
    override val rarity: RarityColors = object : RarityColors {
        override val common = ColorResource.RarityCommon
        override val rare = ColorResource.RarityRare
        override val epic = ColorResource.PokerChipGold
        override val legendary = ColorResource.RarityLegendary
    }
}

@Composable
fun ProvideContentColor(color: ColorResource, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides color,
        androidx.compose.material3.LocalContentColor provides color.color,
        content = content
    )
}