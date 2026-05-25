/**
 * # Button Component System
 * 
 * A comprehensive button system following industry-standard Material Design principles
 * with custom brand colors.
 * 
 * ## Quick Reference Guide
 * 
 * ### When to Use Each Type:
 * 
 * | Type | Visual | Use Case | Example |
 * |------|--------|----------|---------|
 * | **Primary** | Green filled | Most important action, main CTA | "Continue", "Save", "Sign In" |
 * | **PrimaryAlt** | Cyan filled | Alternative important action | "Try Premium", "Upgrade" |
 * | **Secondary** | White outlined | Important but not primary | "Cancel", "Skip", "Learn More" |
 * | **Tertiary** | Dark filled | Subtle action, less emphasis | "Advanced Settings", tertiary nav |
 * | **Ghost** | Text only | Minimal weight, inline links | "Forgot Password?", "Terms" |
 * 
 * ### Button Styles:
 * 
 * - **Filled**: Solid background, highest prominence
 * - **Outlined**: Border only, medium prominence  
 * - **Text**: No background/border, lowest prominence
 * 
 * ### Common Patterns:
 * 
 * ```kotlin
 * // ✨ RECOMMENDED: Use convenience functions for better readability
 * ButtonPrimary(onClick = { }) { Text("Continue") }
 * ButtonSecondary(onClick = { }) { Text("Cancel") }
 * ButtonGhost(onClick = { }) { Text("Forgot Password?") }
 * 
 * // Dialog with two actions
 * Row {
 *     ButtonGhost(onClick = { }) { Text("Cancel") }
 *     ButtonPrimary(onClick = { }) { Text("Confirm") }
 * }
 * 
 * // Alternative brand action
 * ButtonPrimaryAlt(onClick = { }) { Text("Upgrade to Premium") }
 * 
 * // Subtle tertiary action
 * ButtonTertiary(onClick = { }) { Text("Advanced Settings") }
 * 
 * // Or use the full Button() with explicit type if you need more control:
 * Button(
 *     type = ButtonType.Primary,
 *     style = ButtonStyle.Outlined,  // Override default style
 *     size = ButtonSize.Small,       // Override default size
 *     onClick = { }
 * ) { Text("Continue") }
 * ```
 * 
 * ### Visual Hierarchy Rules:
 * 
 * 1. **One primary per screen**: Limit Primary/PrimaryAlt to 1-2 buttons max
 * 2. **Clear hierarchy**: Use different types to show importance
 * 3. **Consistent positioning**: Primary on right/bottom in button groups
 * 4. **Appropriate style**: Filled > Outlined > Text for decreasing emphasis
 * 
 * ### Color Mappings:
 * 
 * - **Primary**: accentPrimary (Aurora500 #00907C)
 * - **PrimaryAlt**: accentSecondary (Pulse500 #7555FF)
 * - **Secondary**: surfacePrimary (Midnight800 #0A101E) with border
 * - **Tertiary**: surfaceSecondary (Midnight700 #101A2C)
 * - **Ghost**: Text color only, no surface
 * - **Disabled**: surfaceDisabled (Graphite600 #3A3F50)
 */
package com.dangerfield.cards.libraries.ui.components.button

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.animateColorResourceAsState
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.LowLevelDSComponent
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@OptIn(LowLevelDSComponent::class)
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    type: ButtonType = LocalButtonType.current,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = LocalButtonStyle.current,
    enabled: Boolean = true,
    onDisabledTap: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    val backgroundColor = backgroundColor(type, style, enabled)
        ?.let { targetColor ->
            key(type, style) {
                animateColorResourceAsState(
                    targetValue = targetColor,
                    label = "Background_Color_Anim"
                )
            }.value
        }

    val contentColor by key(type, style) {
        animateColorResourceAsState(
            targetValue = type.contentColor(style, enabled),
            label = "Content_Color_Anim"
        )
    }

    val borderColor = borderColor(type, style, enabled)

    BaseButton(
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        contentColor = contentColor,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        size = size,
        enabled = enabled,
        onDisabledTap = onDisabledTap,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Button type determines the visual hierarchy and semantic meaning of the button.
 * 
 * Visual hierarchy (most to least prominent):
 * Primary > Secondary > Tertiary > Ghost
 * 
 * ## Usage Guidelines:
 * 
 * **Primary** - Most important action on the screen
 * - Brand green color (accentPrimary)
 * - High visual prominence
 * - Examples: "Continue", "Save", "Submit", "Sign In"
 * - Limit to 1-2 per screen for maximum impact
 * 
 * **PrimaryAlt** - Alternative primary action with different brand color
 * - Secondary brand color (accentSecondary - cyan)
 * - Same prominence as Primary but different semantic meaning
 * - Examples: "Try Premium", "Upgrade", secondary CTAs
 * - Use when you need two distinct primary-level actions
 * 
 * **Secondary** - Important but not primary action
 * - White/light background with border
 * - Clear but less prominent than primary
 * - Examples: "Cancel", "Back", "Skip", "Learn More"
 * - Can have multiple per screen
 * 
 * **Tertiary** - Subtle actions, less important
 * - Dark background (surfaceSecondary)
 * - Blends with dark theme but still distinct
 * - Examples: "Advanced Options", "Settings", tertiary navigation
 * - Use for actions that shouldn't dominate the UI
 * 
 * **Ghost** - Least prominent, text-only appearance
 * - No background, colored text only
 * - Minimal visual weight
 * - Examples: "Forgot Password?", "Terms", inline links
 * - Use for supplementary actions
 */
enum class ButtonType {
    /** Brand green CTA - most important action (accentPrimary) */
    Primary,
    
    /** Alternative brand cyan CTA - secondary important action (accentSecondary) */
    PrimaryAlt,
    
    /** White outlined button - important but not primary */
    Secondary,
    
    /** Dark subtle button - less prominent action */
    Tertiary,
    
    /** Text-only button - minimal visual weight */
    Ghost,

    /** Danger - red button */
    Danger
}

enum class ButtonSize {
    Large,
    Medium,
    Small,
    ExtraSmall
}

/**
 * Button style determines the visual treatment.
 * 
 * **Filled** - Solid background color
 * - Used for Primary, PrimaryAlt, and Tertiary by default
 * - High visual prominence
 * - Clear clickable affordance
 * 
 * **Outlined** - Border only, transparent background
 * - Used for Secondary by default
 * - Less prominent than filled
 * - Works well on any background
 * 
 * **Text** - No background or border, text only
 * - Used for Ghost by default
 * - Minimal visual weight
 * - Looks like a clickable text link
 */
enum class ButtonStyle {
    Filled,
    Outlined,
    Text,
}

@Composable
fun ProvideButtonConfig(
    type: ButtonType = LocalButtonType.current,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = LocalButtonStyle.current,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalButtonType provides type,
        LocalButtonSize provides size,
        LocalButtonStyle provides style,
        content = content
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Convenience Functions - For Better Code Readability
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Primary button - Main call-to-action with brand green color.
 * 
 * Use for the most important action on a screen (e.g., "Continue", "Save", "Submit").
 * Limit to 1-2 per screen for maximum impact.
 * 
 * @see Button for full documentation
 */
@Composable
fun ButtonPrimary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = ButtonStyle.Filled,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.Primary,
        size = size,
        style = style,
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Alternative primary button with cyan brand color.
 * 
 * Use for secondary CTAs that need high prominence (e.g., "Upgrade to Premium", "Try Now").
 * 
 * @see Button for full documentation
 */
@Composable
fun ButtonPrimaryAlt(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = ButtonStyle.Filled,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.PrimaryAlt,
        size = size,
        style = style,
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Secondary button - White outlined, important but not primary.
 * 
 * Use for important actions that aren't the main CTA (e.g., "Cancel", "Skip", "Back").
 * Multiple allowed per screen.
 * 
 * @see Button for full documentation
 */
@Composable
fun ButtonSecondary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = ButtonStyle.Outlined,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.Secondary,
        size = size,
        style = style,
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Tertiary button - Dark subtle button for less prominent actions.
 * 
 * Use for actions that shouldn't dominate the UI (e.g., "Advanced Options", "Settings").
 * 
 * @see Button for full documentation
 */
@Composable
fun ButtonTertiary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = ButtonStyle.Filled,
    enabled: Boolean = true,
    onDisabledTap: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.Tertiary,
        size = size,
        style = style,
        enabled = enabled,
        onDisabledTap = onDisabledTap,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Ghost button - Text-only with minimal visual weight.
 * 
 * Use for supplementary actions and inline links (e.g., "Forgot Password?", "Terms").
 * 
 * @see Button for full documentation
 */
@Composable
fun ButtonGhost(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = ButtonStyle.Text,
    enabled: Boolean = true,
    onDisabledTap: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.Ghost,
        size = size,
        onDisabledTap = onDisabledTap,
        style = style,
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Danger button
 * @see Button for full documentation
 */
@Composable
fun ButtonDanger(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = ButtonStyle.Filled,
    onDisabledTap: (() -> Unit)? = null,

    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.Danger,
        size = size,
        style = style,
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
}

private val LocalButtonType =
    compositionLocalOf { ButtonType.Primary }
internal val LocalButtonSize =
    compositionLocalOf { ButtonSize.Large }
private val LocalButtonStyle =
    compositionLocalOf { ButtonStyle.Filled }

@Composable
@ReadOnlyComposable
private fun backgroundColor(
    type: ButtonType,
    style: ButtonStyle,
    enabled: Boolean,
): ColorResource? = when {
    !enabled && style == ButtonStyle.Filled -> AppTheme.colors.surfaceDisabled
    !enabled && style == ButtonStyle.Outlined -> null
    !enabled && style == ButtonStyle.Text -> null
    style == ButtonStyle.Text -> null
    style == ButtonStyle.Outlined -> null
    else -> when (type) {
        ButtonType.Primary -> AppTheme.colors.accentPrimary
        ButtonType.PrimaryAlt -> AppTheme.colors.accentSecondary
        ButtonType.Secondary -> AppTheme.colors.surfacePrimary
        ButtonType.Tertiary -> AppTheme.colors.onSurfaceTertiary
        ButtonType.Ghost -> null
        ButtonType.Danger -> AppTheme.colors.danger
    }
}

@Composable
@ReadOnlyComposable
private fun borderColor(
    type: ButtonType,
    style: ButtonStyle,
    enabled: Boolean
): ColorResource? = when {
    style != ButtonStyle.Outlined -> null
    !enabled -> AppTheme.colors.borderDisabled
    else -> when (type) {
        ButtonType.Primary -> AppTheme.colors.accentPrimary
        ButtonType.PrimaryAlt -> AppTheme.colors.accentSecondary
        ButtonType.Secondary -> AppTheme.colors.border
        ButtonType.Tertiary -> AppTheme.colors.border
        ButtonType.Ghost -> null
        ButtonType.Danger -> AppTheme.colors.danger
    }
}

@Composable
@ReadOnlyComposable
private fun ButtonType.contentColor(
    style: ButtonStyle,
    enabled: Boolean
): ColorResource = when {
    !enabled -> AppTheme.colors.textDisabled
    style == ButtonStyle.Filled -> when (this) {
        ButtonType.Primary -> AppTheme.colors.onAccentPrimary
        ButtonType.PrimaryAlt -> AppTheme.colors.onAccentSecondary
        ButtonType.Secondary -> AppTheme.colors.onSurfacePrimary
        ButtonType.Tertiary -> AppTheme.colors.background
        ButtonType.Ghost -> AppTheme.colors.text
        ButtonType.Danger -> AppTheme.colors.danger.onColor
    }
    style == ButtonStyle.Outlined -> when (this) {
        ButtonType.Primary -> AppTheme.colors.accentPrimary
        ButtonType.PrimaryAlt -> AppTheme.colors.accentSecondary
        ButtonType.Secondary -> AppTheme.colors.text
        ButtonType.Tertiary -> AppTheme.colors.textSecondary
        ButtonType.Ghost -> AppTheme.colors.text
        ButtonType.Danger -> AppTheme.colors.danger
    }
    style == ButtonStyle.Text -> when (this) {
        ButtonType.Primary -> AppTheme.colors.text
        ButtonType.PrimaryAlt -> AppTheme.colors.textSecondary
        ButtonType.Secondary -> AppTheme.colors.textSecondary
        ButtonType.Tertiary -> AppTheme.colors.text
        ButtonType.Ghost -> AppTheme.colors.text
        ButtonType.Danger -> AppTheme.colors.danger
    }
    else -> AppTheme.colors.text
}


@Preview
@Composable
private fun PreviewButtonSizes() {
    PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D500),
            modifier = Modifier.padding(Dimension.D800)
        ) {
            Text("Button Size Progression", typography = AppTheme.typography.Heading.H600)

            Button(
                onClick = {},
                size = ButtonSize.Large,
                content = { Text("Large Button") }
            )

            Button(
                onClick = {},
                size = ButtonSize.Medium,
                content = { Text("Medium Button") }
            )

            Button(
                onClick = {},
                size = ButtonSize.Small,
                content = { Text("Small Button") }
            )

            Button(
                onClick = {},
                size = ButtonSize.ExtraSmall,
                content = { Text("Extra Small") }
            )
        }
    }
}

@Preview
@Composable
private fun PreviewButtonHierarchy() {
    PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D500),
            modifier = Modifier.padding(Dimension.D800)
        ) {
            Text("Button Type Hierarchy", typography = AppTheme.typography.Heading.H600)
            
            Text("Filled Style (Default)", typography = AppTheme.typography.Label.L500)
            
            Button(
                onClick = {},
                type = ButtonType.Primary,
                content = { Text("Primary - Main CTA") }
            )

            Button(
                onClick = {},
                type = ButtonType.PrimaryAlt,
                content = { Text("Primary Alt - Alt CTA") }
            )

            Button(
                onClick = {},
                type = ButtonType.Secondary,
                content = { Text("Secondary - Important") }
            )

            Button(
                onClick = {},
                type = ButtonType.Tertiary,
                content = { Text("Tertiary - Subtle") }
            )

            Button(
                onClick = {},
                type = ButtonType.Ghost,
                content = { Text("Ghost - Minimal") }
            )


            Button(
                onClick = {},
                type = ButtonType.Danger,
                content = { Text("Danger - Errors") }
            )
        }
    }
}

@Preview
@Composable
private fun PreviewOutlinedButtons() {
    PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D500),
            modifier = Modifier.padding(Dimension.D800)
        ) {
            Text("Outlined Button Style", typography = AppTheme.typography.Heading.H600)

            Button(
                onClick = {},
                type = ButtonType.Primary,
                style = ButtonStyle.Outlined,
                content = { Text("Primary Outlined") }
            )

            Button(
                onClick = {},
                type = ButtonType.PrimaryAlt,
                style = ButtonStyle.Outlined,
                content = { Text("Primary Alt Outlined") }
            )

            Button(
                onClick = {},
                type = ButtonType.Secondary,
                style = ButtonStyle.Outlined,
                content = { Text("Secondary Outlined") }
            )

            Button(
                onClick = {},
                type = ButtonType.Danger,
                style = ButtonStyle.Outlined,
                content = { Text("Danger Outlined") }
            )
        }
    }
}

@Preview
@Composable
private fun PreviewTextButtons() {
    PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D500),
            modifier = Modifier.padding(Dimension.D800)
        ) {
            Text("Text Button Style", typography = AppTheme.typography.Heading.H600)

            Button(
                onClick = {},
                type = ButtonType.Primary,
                style = ButtonStyle.Text,
                content = { Text("Primary Text") }
            )

            Button(
                onClick = {},
                type = ButtonType.PrimaryAlt,
                style = ButtonStyle.Text,
                content = { Text("Primary Alt Text") }
            )

            Button(
                onClick = {},
                type = ButtonType.Ghost,
                style = ButtonStyle.Text,
                content = { Text("Ghost Text") }
            )

            Button(
                onClick = {},
                type = ButtonType.Danger,
                style = ButtonStyle.Text,
                content = { Text("Danger Text") }
            )
        }
    }
}

@Preview
@Composable
private fun PreviewDisabledStates() {
    PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D500),
            modifier = Modifier.padding(Dimension.D800)
        ) {
            Text("Disabled States", typography = AppTheme.typography.Heading.H600)

            Button(
                onClick = {},
                type = ButtonType.Primary,
                enabled = false,
                content = { Text("Primary Disabled") }
            )

            Button(
                onClick = {},
                type = ButtonType.Secondary,
                style = ButtonStyle.Outlined,
                enabled = false,
                content = { Text("Outlined Disabled") }
            )

            Button(
                onClick = {},
                type = ButtonType.Ghost,
                style = ButtonStyle.Text,
                enabled = false,
                content = { Text("Text Disabled") }
            )

            Button(
                onClick = {},
                type = ButtonType.Danger,
                style = ButtonStyle.Text,
                enabled = false,
                content = { Text("Danger Disabled") }
            )


        }
    }
}

@Preview
@Composable
private fun PreviewConvenienceFunctions() {
    PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D500),
            modifier = Modifier.padding(Dimension.D800)
        ) {
            Text("Convenience Functions - Better Readability", typography = AppTheme.typography.Heading.H600)
            
            Text("Much easier to scan and understand intent:", typography = AppTheme.typography.Body.B400)

            ButtonPrimary(
                onClick = {}
            ) { Text("Submit Application") }

            ButtonPrimaryAlt(
                onClick = {}
            ) { Text("Upgrade to Premium") }

            ButtonSecondary(
                onClick = {}
            ) { Text("Cancel") }

            ButtonTertiary(
                onClick = {}
            ) { Text("Advanced Settings") }

            ButtonGhost(
                onClick = {}
            ) { Text("Forgot Password?") }
        }
    }
}

@Preview
@Composable
private fun PreviewRealWorldDialog() {
    PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D800),
            modifier = Modifier.padding(Dimension.D800)
        ) {
            Text("Real-World Example: Confirmation Dialog", typography = AppTheme.typography.Heading.H600)
            
            Text("Are you sure you want to delete this item?", typography = AppTheme.typography.Body.B500)
            
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D500)
            ) {
                ButtonGhost(
                    onClick = {},
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                
                ButtonDanger(
                    onClick = {},
                    modifier = Modifier.weight(1f)
                ) { Text("Delete") }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewRealWorldForm() {
    PreviewContent {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimension.D500),
            modifier = Modifier.padding(Dimension.D800)
        ) {
            Text("Real-World Example: Form", typography = AppTheme.typography.Heading.H600)
            
            // Form fields would go here
            Text("Name: _____________", typography = AppTheme.typography.Body.B500)
            Text("Email: _____________", typography = AppTheme.typography.Body.B500)
            
            ButtonPrimary(
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create Account") }
            
            ButtonGhost(
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            ) { Text("Already have an account? Sign in") }
        }
    }
}

