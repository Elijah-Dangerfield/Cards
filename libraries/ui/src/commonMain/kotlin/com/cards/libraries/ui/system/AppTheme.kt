package com.dangerfield.cards.system

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.dangerfield.cards.libraries.ui.system.LocalColors
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.libraries.ui.system.LocalTypography
import com.dangerfield.cards.system.color.Colors
import com.dangerfield.cards.system.color.defaultColors
import com.dangerfield.cards.system.typography.rememberTypography

object AppTheme {
    val colors: Colors
        @ReadOnlyComposable
        @Composable
        get() = LocalColors.current

    val typography
        @ReadOnlyComposable
        @Composable
        get() = LocalTypography.current
}

@Composable
fun AppThemeProvider(
    content: @Composable () -> Unit
) {

    val colors = defaultColors

    val textSelectionColors = TextSelectionColors(
        handleColor = colors.accentPrimary.color,
        backgroundColor = colors.accentPrimary.color.copy(alpha = 0.4F)
    )

    val typography = rememberTypography()

    MaterialWrapper {
        CompositionLocalProvider(
            LocalContentColor provides colors.text,
            LocalTextSelectionColors provides textSelectionColors,
            LocalTypography provides typography,
            androidx.compose.material3.LocalContentColor provides colors.text.color,
            LocalColors provides colors,
            content = content
        )
    }
}

@Composable
private fun MaterialWrapper(content: @Composable () -> Unit) {
    val invalidTextStyle = TextStyle(color = Color.Red)
    MaterialTheme(
        typography = Typography(
            displayLarge = invalidTextStyle,
            displayMedium = invalidTextStyle,
            displaySmall = invalidTextStyle,
            headlineLarge = invalidTextStyle,
            headlineMedium = invalidTextStyle,
            headlineSmall = invalidTextStyle,
            titleLarge = invalidTextStyle,
            titleMedium = invalidTextStyle,
            titleSmall = invalidTextStyle,
            bodyLarge = invalidTextStyle,
            bodyMedium = invalidTextStyle,
            bodySmall = invalidTextStyle,
            labelLarge = invalidTextStyle,
            labelMedium = invalidTextStyle,
            labelSmall = invalidTextStyle
        ),
        colorScheme = ColorScheme(
            primary = Color.Red,
            onPrimary = Color.Red,
            primaryContainer = Color.Red,
            onPrimaryContainer = Color.Red,
            inversePrimary = Color.Red,
            secondary = Color.Red,
            onSecondary = Color.Red,
            secondaryContainer = Color.Red,
            onSecondaryContainer = Color.Red,
            tertiary = Color.Red,
            onTertiary = Color.Red,
            tertiaryContainer = Color.Red,
            onTertiaryContainer = Color.Red,
            background = Color.Red,
            onBackground = Color.Red,
            surface = Color.Red,
            onSurface = Color.Red,
            surfaceVariant = Color.Red,
            onSurfaceVariant = Color.Red,
            surfaceTint = Color.Red,
            inverseSurface = Color.Red,
            inverseOnSurface = Color.Red,
            error = Color.Red,
            onError = Color.Red,
            errorContainer = Color.Red,
            onErrorContainer = Color.Red,
            outline = Color.Red,
            outlineVariant = Color.Red,
            scrim = Color.Red
        ),
        content = content
    )
}
