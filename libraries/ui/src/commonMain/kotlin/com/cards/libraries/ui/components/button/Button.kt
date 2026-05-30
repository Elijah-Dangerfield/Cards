/**
 * # Button Component System
 *
 * A button exposes ONE emphasis semantic — [ButtonType] — plus a [ButtonStyle] (Filled / Outlined
 * / Text) and a [ButtonSize]. Filled, enabled buttons render a hard 3D "lip" (the `deep` token)
 * that the face drops onto when pressed; everything else stays flat.
 *
 * ## Emphasis hierarchy (most → least prominent)
 *
 * | Type | Use case | Example |
 * |------|----------|---------|
 * | **Primary**   | The main CTA — gold by default | "Continue", "Save", "Sign In" |
 * | **Secondary** | Important but not the CTA — neutral fill / strong border | "Cancel", "Skip" |
 * | **Ghost**     | Minimal weight, inline links | "Forgot Password?", "Terms" |
 * | **Danger**    | Destructive action | "Delete", "Leave game" |
 *
 * Limit Primary to 1–2 per screen. Use Filled > Outlined > Text for decreasing emphasis.
 *
 * ## Accent (the rare two-CTA case)
 *
 * A *filled Primary* can be recolored with [ButtonAccent] (Primary = gold, Secondary = teal,
 * Tertiary = coral). Accent is **role-named, never a literal color** — repointing or dropping an
 * accent token never touches a button. Reach for it only when a screen genuinely needs two
 * primary-level actions in different brand colors.
 *
 * ```kotlin
 * ButtonPrimary(onClick = { }) { Text("Continue") }
 * ButtonSecondary(onClick = { }) { Text("Cancel") }
 * ButtonGhost(onClick = { }) { Text("Forgot Password?") }
 * ButtonDanger(onClick = { }) { Text("Delete") }
 *
 * // two distinct primary-level CTAs
 * ButtonPrimary(onClick = { }, accent = ButtonAccent.Secondary) { Text("Upgrade") }
 *
 * // full control
 * Button(type = ButtonType.Primary, style = ButtonStyle.Outlined, size = ButtonSize.Small, onClick = { }) {
 *     Text("Continue")
 * }
 * ```
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
    accent: ButtonAccent = ButtonAccent.Primary,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = LocalButtonStyle.current,
    enabled: Boolean = true,
    flat: Boolean = false,
    onDisabledTap: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    val backgroundColor = backgroundColor(type, accent, style, enabled)
        ?.let { targetColor ->
            key(type, accent, style) {
                animateColorResourceAsState(
                    targetValue = targetColor,
                    label = "Background_Color_Anim"
                )
            }.value
        }

    val contentColor by key(type, accent, style) {
        animateColorResourceAsState(
            targetValue = contentColor(type, accent, style, enabled),
            label = "Content_Color_Anim"
        )
    }

    val borderColor = borderColor(type, accent, style, enabled)
    val deepColor = deepColor(type, accent, style, enabled, flat)

    BaseButton(
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        contentColor = contentColor,
        deepColor = deepColor,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        size = size,
        enabled = enabled,
        flat = flat,
        onDisabledTap = onDisabledTap,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Emphasis hierarchy — the only semantic a button exposes (most → least prominent):
 * Primary > Secondary > Ghost. Danger is destructive emphasis, orthogonal to the ladder.
 *
 * - **Primary** — the main CTA. Gold filled by default; recolor with [ButtonAccent]. 1–2 per screen.
 * - **Secondary** — important but not the CTA. Neutral fill (filled) or strong border (outlined).
 * - **Ghost** — minimal weight, text-only. Inline links, supplementary actions.
 * - **Danger** — destructive action (red).
 */
enum class ButtonType {
    /** The main CTA — gold filled by default (accentPrimary). */
    Primary,

    /** Important but not the CTA — neutral fill / strong border. */
    Secondary,

    /** Text-only button — minimal visual weight. */
    Ghost,

    /** Destructive action — red. */
    Danger,

    @Deprecated("Use ButtonType.Primary with accent = ButtonAccent.Secondary")
    PrimaryAlt,

    @Deprecated("Old filled 'Tertiary' is now Secondary")
    Tertiary,
}

/**
 * Which brand accent a *filled Primary* renders. Role-named, never a literal color, so dropping or
 * repointing an accent token never touches a button.
 */
enum class ButtonAccent { Primary, Secondary, Tertiary }

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
    accent: ButtonAccent = ButtonAccent.Primary,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = ButtonStyle.Filled,
    onDisabledTap: (() -> Unit)? = null,
    enabled: Boolean = true,
    flat: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.Primary,
        accent = accent,
        size = size,
        onDisabledTap = onDisabledTap,
        style = style,
        enabled = enabled,
        flat = flat,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Alternative primary button — a Primary recolored with the Secondary brand accent (teal).
 *
 * Use for the rare two-CTA screen that needs a distinct second primary-level action.
 *
 * @see Button for full documentation
 */
@Deprecated(
    "Use ButtonPrimary(accent = ButtonAccent.Secondary)",
    ReplaceWith("ButtonPrimary(onClick, modifier, accent = ButtonAccent.Secondary, content = content)")
)
@Composable
fun ButtonPrimaryAlt(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = ButtonStyle.Filled,
    onDisabledTap: (() -> Unit)? = null,
    enabled: Boolean = true,
    flat: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.Primary,
        accent = ButtonAccent.Secondary,
        size = size,
        onDisabledTap = onDisabledTap,
        style = style,
        enabled = enabled,
        flat = flat,
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
    style: ButtonStyle = ButtonStyle.Filled,
    onDisabledTap: (() -> Unit)? = null,
    enabled: Boolean = true,
    flat: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.Secondary,
        size = size,
        onDisabledTap = onDisabledTap,
        style = style,
        enabled = enabled,
        flat = flat,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Tertiary button — the old dark filled tertiary is now just [ButtonSecondary].
 *
 * @see Button for full documentation
 */
@Deprecated(
    "Old filled 'Tertiary' is now Secondary",
    ReplaceWith("ButtonSecondary(onClick, modifier, content = content)")
)
@Composable
fun ButtonTertiary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: IconResource? = null,
    size: ButtonSize = LocalButtonSize.current,
    style: ButtonStyle = ButtonStyle.Filled,
    enabled: Boolean = true,
    flat: Boolean = false,
    onDisabledTap: (() -> Unit)? = null,
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
        flat = flat,
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
    flat: Boolean = false,
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
        flat = flat,
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
    flat: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        type = ButtonType.Danger,
        onDisabledTap = onDisabledTap,
        size = size,
        style = style,
        enabled = enabled,
        flat = flat,
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

// ── accent token resolvers ───────────────────────────────────
@Composable
@ReadOnlyComposable
private fun accentSolid(a: ButtonAccent) = when (a) {
    ButtonAccent.Primary -> AppTheme.colors.accentPrimary
    ButtonAccent.Secondary -> AppTheme.colors.accentSecondary
    ButtonAccent.Tertiary -> AppTheme.colors.accentTertiary
}

@Composable
@ReadOnlyComposable
private fun accentDeep(a: ButtonAccent) = when (a) {
    ButtonAccent.Primary -> AppTheme.colors.accentPrimaryDeep
    ButtonAccent.Secondary -> AppTheme.colors.accentSecondaryDeep
    ButtonAccent.Tertiary -> AppTheme.colors.accentTertiaryDeep
}

@Composable
@ReadOnlyComposable
private fun onAccent(a: ButtonAccent) = when (a) {
    ButtonAccent.Primary -> AppTheme.colors.onAccentPrimary
    ButtonAccent.Secondary -> AppTheme.colors.onAccentSecondary
    ButtonAccent.Tertiary -> AppTheme.colors.onAccentTertiary
}

@Suppress("DEPRECATION")
@Composable
@ReadOnlyComposable
private fun backgroundColor(
    type: ButtonType,
    accent: ButtonAccent,
    style: ButtonStyle,
    enabled: Boolean,
): ColorResource? = when {
    !enabled && style == ButtonStyle.Filled -> AppTheme.colors.surfaceDisabled
    style != ButtonStyle.Filled -> null
    else -> when (type) {
        ButtonType.Primary -> accentSolid(accent)
        ButtonType.Secondary -> AppTheme.colors.surfaceHigh
        ButtonType.Ghost -> null
        ButtonType.Danger -> AppTheme.colors.danger
        ButtonType.PrimaryAlt -> AppTheme.colors.accentSecondary
        ButtonType.Tertiary -> AppTheme.colors.surfaceHigh
    }
}

@Suppress("DEPRECATION")
@Composable
@ReadOnlyComposable
private fun deepColor(
    type: ButtonType,
    accent: ButtonAccent,
    style: ButtonStyle,
    enabled: Boolean,
    flat: Boolean,
): ColorResource? = when {
    flat || !enabled || style != ButtonStyle.Filled -> null // only filled, enabled buttons get the lip
    else -> when (type) {
        ButtonType.Primary -> accentDeep(accent)
        ButtonType.Secondary -> AppTheme.colors.background // espresso lip under the neutral fill
        ButtonType.Ghost -> null
        ButtonType.Danger -> AppTheme.colors.dangerDeep
        ButtonType.PrimaryAlt -> AppTheme.colors.accentSecondaryDeep
        ButtonType.Tertiary -> AppTheme.colors.background
    }
}

@Suppress("DEPRECATION")
@Composable
@ReadOnlyComposable
private fun borderColor(
    type: ButtonType,
    accent: ButtonAccent,
    style: ButtonStyle,
    enabled: Boolean
): ColorResource? = when {
    style != ButtonStyle.Outlined -> null
    !enabled -> AppTheme.colors.borderDisabled
    else -> when (type) {
        ButtonType.Primary -> accentSolid(accent)
        ButtonType.Secondary -> AppTheme.colors.borderStrong
        ButtonType.Ghost -> null
        ButtonType.Danger -> AppTheme.colors.danger
        ButtonType.PrimaryAlt -> AppTheme.colors.accentSecondary
        ButtonType.Tertiary -> AppTheme.colors.borderStrong
    }
}

@Suppress("DEPRECATION")
@Composable
@ReadOnlyComposable
private fun contentColor(
    type: ButtonType,
    accent: ButtonAccent,
    style: ButtonStyle,
    enabled: Boolean
): ColorResource = when {
    !enabled -> AppTheme.colors.contentDisabled
    style == ButtonStyle.Filled -> when (type) {
        ButtonType.Primary -> onAccent(accent)
        ButtonType.Secondary -> AppTheme.colors.content
        ButtonType.Ghost -> AppTheme.colors.content
        ButtonType.Danger -> AppTheme.colors.onDanger
        ButtonType.PrimaryAlt -> AppTheme.colors.onAccentSecondary
        ButtonType.Tertiary -> AppTheme.colors.content
    }
    style == ButtonStyle.Outlined -> when (type) {
        ButtonType.Primary -> accentSolid(accent)
        ButtonType.Secondary -> AppTheme.colors.content
        ButtonType.Ghost -> AppTheme.colors.content
        ButtonType.Danger -> AppTheme.colors.danger
        ButtonType.PrimaryAlt -> AppTheme.colors.accentSecondary
        ButtonType.Tertiary -> AppTheme.colors.content
    }
    else -> when (type) { // Text
        ButtonType.Danger -> AppTheme.colors.danger
        ButtonType.Secondary -> AppTheme.colors.contentSecondary
        ButtonType.PrimaryAlt -> AppTheme.colors.contentSecondary
        else -> AppTheme.colors.content
    }
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

