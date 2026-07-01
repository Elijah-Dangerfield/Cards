package com.dangerfield.cards.system.typography

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import cards.libraries.ui.generated.resources.DMSerifText_Italic
import cards.libraries.ui.generated.resources.DMSerifText_Regular
import cards.libraries.ui.generated.resources.Res
import cards.libraries.ui.generated.resources.poppins_bold
import cards.libraries.ui.generated.resources.poppins_light
import cards.libraries.ui.generated.resources.poppins_medium
import cards.libraries.ui.generated.resources.poppins_regular
import cards.libraries.ui.generated.resources.poppins_semibold

val SansSerifFontFamily: FontFamily
    @Composable get() = FontFamily(
        Font(
            resource = Res.font.poppins_light, weight = FontWeight.Light
        ), Font(
            resource = Res.font.poppins_regular, weight = FontWeight.Normal
        ), Font(
            resource = Res.font.poppins_medium, weight = FontWeight.Medium
        ), Font(
            resource = Res.font.poppins_bold, weight = FontWeight.Bold
        ), Font(
            resource = Res.font.poppins_semibold, weight = FontWeight.SemiBold
        )
    )

val SerifFontFamily: FontFamily
    @Composable get() = FontFamily(
        Font(
            resource = Res.font.DMSerifText_Regular, weight = FontWeight.Normal
        ),

        Font(
            resource = Res.font.DMSerifText_Italic, style = FontStyle.Italic
        ),
    )