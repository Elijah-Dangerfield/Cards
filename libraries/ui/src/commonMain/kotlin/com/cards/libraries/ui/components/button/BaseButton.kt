package com.dangerfield.cards.libraries.ui.components.button

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.ui.tooling.preview.Preview
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.thenIf
import com.dangerfield.cards.libraries.ui.Border
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.StandardBorderWidth
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.icon.SmallIcon
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.text.ProvideTextConfig
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.components.text.TextConfig
import com.dangerfield.cards.libraries.ui.system.LowLevelDSComponent

@OptIn(ExperimentalMaterial3Api::class)
@LowLevelDSComponent
@Composable
internal fun BaseButton(
    backgroundColor: ColorResource?,
    borderColor: ColorResource?,
    contentColor: ColorResource,
    size: ButtonSize,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    contentPadding: PaddingValues = size.padding(hasIcon = icon != null),
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {

    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {

        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.thenIf(enabled) {
                bounceClick(
                    mutableInteractionSource = interactionSource,
                    onClick = onClick
                )
            }
        ) {
            Surface(
                modifier = modifier
                    .semantics { role = Role.Button },
                radius = Radii.Button,
                elevation = if (backgroundColor != null) Elevation.Button else Elevation.None,
                color = backgroundColor,
                contentColor = contentColor,
                border = borderColor?.let {
                    Border(
                        it,
                        OutlinedButtonBorderWidth
                    )
                },
                contentPadding = contentPadding
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(
                        ButtonIconSpacing,
                        Alignment.CenterHorizontally
                    )
                ) {
                    if (icon != null) {
                        SmallIcon(
                            icon = icon
                        )
                    }

                    ProvideTextConfig(size.textConfig(), content = content)
                }
            }

        }
    }
}


@Composable
private fun ButtonSize.textConfig(): TextConfig = when (this) {
    ButtonSize.ExtraSmall -> ExtraSmallButtonTextConfig

    ButtonSize.Small -> SmallButtonTextConfig

    ButtonSize.Medium -> MediumButtonTextConfig

    ButtonSize.Large -> LargeButtonTextConfig
}

internal fun ButtonSize.padding(hasIcon: Boolean): PaddingValues =
    when (this) {
        ButtonSize.ExtraSmall -> if (hasIcon) ExtraSmallButtonWithIconPadding else ExtraSmallButtonPadding
        ButtonSize.Small -> if (hasIcon) SmallButtonWithIconPadding else SmallButtonPadding
        ButtonSize.Medium -> if (hasIcon) MediumButtonWithIconPadding else MediumButtonPadding
        ButtonSize.Large -> if (hasIcon) LargeButtonWithIconPadding else LargeButtonPadding
    }

// Typography scale for buttons - uses Label typography (1.2x line height)
// designed specifically for UI elements. Body typography (1.5x) is for reading,
// not buttons, so we avoid it even for text-only buttons.
//
// Size progression:
// - ExtraSmall: L400 (10sp) - Minimal UI like chips, badges
// - Small: L500 (12sp) - Compact buttons in toolbars
// - Medium: L600 (14sp) - Most common button size
// - Large: L600.SemiBold (14sp, heavier weight) - Primary CTAs

private val ExtraSmallButtonTextConfig: TextConfig
    @Composable get() = TextConfig(
        typography = AppTheme.typography.Label.L400,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        allCaps = true
    )

private val SmallButtonTextConfig: TextConfig
    @Composable get() = TextConfig(
        typography = AppTheme.typography.Label.L500,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        allCaps = true
    )

private val MediumButtonTextConfig: TextConfig
    @Composable get() = TextConfig(
        typography = AppTheme.typography.Label.L600,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        allCaps = true
    )

private val LargeButtonTextConfig: TextConfig
    @Composable get() = TextConfig(
        typography = AppTheme.typography.Label.L600.SemiBold,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        allCaps = true
    )


// Button padding follows a systematic approach:
// - Vertical padding stays consistent per size for predictable touch targets
// - Horizontal padding provides breathing room for text
// - Icon variants reduce start padding slightly (icon provides visual weight)
//   and increase end padding slightly (icon takes horizontal space)
// This creates balanced, symmetric button layouts

private val ExtraSmallButtonPadding = PaddingValues(
    horizontal = Dimension.D600,  // 14dp horizontal
    vertical = Dimension.D400      // 10dp vertical
)

private val ExtraSmallButtonWithIconPadding = PaddingValues(
    horizontal = Dimension.D500,  // 12dp both sides (icon adds visual weight)
    vertical = Dimension.D400      // 10dp vertical (same as text-only)
)

private val SmallButtonPadding = PaddingValues(
    horizontal = Dimension.D800,  // 20dp horizontal
    vertical = Dimension.D500      // 12dp vertical
)

private val SmallButtonWithIconPadding = PaddingValues(
    start = Dimension.D600,       // 14dp start (reduced, icon adds weight)
    end = Dimension.D800,          // 20dp end (extra space for icon)
    top = Dimension.D500,          // 12dp vertical (same as text-only)
    bottom = Dimension.D500
)

private val MediumButtonPadding = PaddingValues(
    horizontal = Dimension.D900,  // 24dp horizontal
    vertical = Dimension.D600      // 14dp vertical
)

private val MediumButtonWithIconPadding = PaddingValues(
    start = Dimension.D700,       // 16dp start (reduced, icon adds weight)
    end = Dimension.D900,          // 24dp end (extra space for icon)
    top = Dimension.D600,          // 14dp vertical (same as text-only)
    bottom = Dimension.D600
)

private val LargeButtonPadding = PaddingValues(
    horizontal = Dimension.D800,
    vertical = Dimension.D900
)

private val LargeButtonWithIconPadding = PaddingValues(
    start = Dimension.D800,       // 20dp start (reduced, icon adds weight)
    end = Dimension.D900,          // 24dp end (extra space for icon)
    top = Dimension.D900,          // 16dp vertical (same as text-only)
    bottom = Dimension.D900
)

private val ButtonIconSpacing = Dimension.D200
private val OutlinedButtonBorderWidth = StandardBorderWidth

@OptIn(LowLevelDSComponent::class)
@Preview
@Composable
private fun LargeButton() {
    PreviewContent {
        BaseButton(
            backgroundColor = AppTheme.colors.accentPrimary,
            borderColor = null,
            contentColor = AppTheme.colors.onAccentPrimary,
            size = ButtonSize.Large,
            onClick = {},
            content = { Text(text = "Filled Button") }
        )
    }
}

@OptIn(LowLevelDSComponent::class)
@Preview
@Composable
private fun MediumButton() {
    PreviewContent {
        BaseButton(
            backgroundColor = null,
            borderColor = AppTheme.colors.border,
            contentColor = AppTheme.colors.text,
            size = ButtonSize.Medium,
            onClick = {},
            content = { Text(text = "Outlined Button") }
        )
    }
}

@OptIn(LowLevelDSComponent::class)
@Preview
@Composable
private fun SmallButton() {
    PreviewContent {
        BaseButton(
            backgroundColor = null,
            borderColor = null,
            contentColor = AppTheme.colors.accentPrimary,
            size = ButtonSize.Small,
            onClick = {},
            content = { Text(text = "Text Button") }
        )
    }
}