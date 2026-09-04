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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.animateColorResourceAsState
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.system.LowLevelDSComponent
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import com.dangerfield.cards.libraries.ui.catalog.BUTTON_SUBTITLE
import com.dangerfield.cards.libraries.ui.catalog.ButtonCatalogBody
import com.dangerfield.cards.libraries.ui.catalog.CatalogPage

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
}

/**
 * Which accent a *filled Primary* renders. Role-named, never a literal color, so dropping or
 * repointing an accent token never touches a button. [Inverse] is the odd one out — not a brand
 * hue but the inverse surface (a near-white fill with dark ink), for the occasional "white button".
 */
enum class ButtonAccent { Primary, Secondary, Tertiary, Inverse }

enum class ButtonSize {
    Large,
    Medium,
    Small,
    ExtraSmall
}

/**
 * The visual treatment, independent of [ButtonType] emphasis.
 *
 * - **Filled** — solid background; highest prominence. Filled + enabled gets the 3D lip.
 * - **Outlined** — border only, transparent fill; medium prominence, works on any surface.
 * - **Text** — no background or border; minimal weight, reads as a link.
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
    ButtonAccent.Inverse -> AppTheme.colors.surfaceInverse
}

@Composable
@ReadOnlyComposable
private fun accentDeep(a: ButtonAccent) = when (a) {
    ButtonAccent.Primary -> AppTheme.colors.accentPrimaryDeep
    ButtonAccent.Secondary -> AppTheme.colors.accentSecondaryDeep
    ButtonAccent.Tertiary -> AppTheme.colors.accentTertiaryDeep
    ButtonAccent.Inverse -> AppTheme.colors.surfaceInverseDeep
}

@Composable
@ReadOnlyComposable
private fun onAccent(a: ButtonAccent) = when (a) {
    ButtonAccent.Primary -> AppTheme.colors.onAccentPrimary
    ButtonAccent.Secondary -> AppTheme.colors.onAccentSecondary
    ButtonAccent.Tertiary -> AppTheme.colors.onAccentTertiary
    ButtonAccent.Inverse -> AppTheme.colors.onSurfaceInverse
}

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
    }
}

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
    }
}

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
    }
}

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
    }
    style == ButtonStyle.Outlined -> when (type) {
        ButtonType.Primary -> accentSolid(accent)
        ButtonType.Secondary -> AppTheme.colors.content
        ButtonType.Ghost -> AppTheme.colors.content
        ButtonType.Danger -> AppTheme.colors.danger
    }
    else -> when (type) { // Text
        ButtonType.Danger -> AppTheme.colors.danger
        ButtonType.Secondary -> AppTheme.colors.contentSecondary
        else -> AppTheme.colors.content
    }
}

@Preview(widthDp = 1100, heightDp = 1500)
@Composable
private fun ButtonsPreview() {
    CatalogPage(title = "Buttons", subtitle = BUTTON_SUBTITLE) { ButtonCatalogBody() }
}
