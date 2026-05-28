package com.dangerfield.cards.libraries.ui.snackbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.ProvideTextConfig
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD500
import com.dangerfield.cards.system.HorizontalSpacerD600
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.color.ProvideContentColor
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_close_a11y
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The opinionated, DS-styled snackbar.
 *
 * Only callable inside [SnackbarHostState.present], which provides the
 * [SnackbarPresentationScope] receiver — the type system makes it
 * impossible to drop a `Snackbar(...)` into composition outside a
 * `present { … }` block, which is intentional: snackbars are
 * event-fired and time-bounded, not state-driven, so binding their
 * presence to the host's lifecycle (rather than to composition) keeps
 * the model honest.
 *
 * Inside the lambda, [SnackbarPresentationScope.dismiss] is the
 * canonical "fold this snackbar away" callback, and is what the
 * dismiss button and post-action behavior route through.
 *
 * Two overloads, increasing flexibility:
 * - String slots: `Snackbar(message = ..., title = ..., icon = ..., ...)` — the everyday toast.
 * - Composable slots: `Snackbar(title = { ... }, leading = { ... }) { /* body */ }` — same layout, custom rendering per slot. The trailing lambda is the body.
 *
 * For the lowest level (no DS chrome at all), don't use [Snackbar] —
 * just compose your raw content directly inside the `present { … }`
 * lambda. There's no separate `BaseSnackbar` primitive because there
 * doesn't need to be one.
 */
@Composable
fun SnackbarPresentationScope.Snackbar(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: IconResource? = null,
    emoji: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissible: Boolean = true,
) {
    SnackbarSurface(modifier = modifier) {
        SnackbarBody(
            title = title?.let { { Text(text = it, typography = AppTheme.typography.Heading.H700) } },
            body = { Text(text = message) },
            leading = snackbarLeading(icon = icon, emoji = emoji),
            action = actionLabelComposable(
                actionLabel = actionLabel,
                onAction = onAction,
                onAfterAction = dismiss,
            ),
            dismissible = dismissible,
            onDismiss = dismiss,
        )
    }
}

/**
 * Composable-slot overload — same layout as the string version, but
 * each slot is a composable so you can render rich content (icons,
 * formatted strings, badges) without losing the DS chrome.
 *
 * Typography defaults are still provided by the surface, so a plain
 * `Text("…")` inside any slot picks up the right look — only override
 * when you actually want to.
 */
@Composable
fun SnackbarPresentationScope.Snackbar(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    dismissible: Boolean = true,
    body: @Composable () -> Unit,
) {
    SnackbarSurface(modifier = modifier) {
        SnackbarBody(
            title = title,
            body = body,
            leading = leading,
            action = action,
            dismissible = dismissible,
            onDismiss = dismiss,
        )
    }
}

/**
 * The DS surface treatment shared by every opinionated snackbar:
 * `surfaceSecondary` fill, soft shadow, bubble radius, generous
 * padding, and `ProvideContentColor` + `ProvideTextConfig` so
 * unstyled `Text(...)` inside the surface picks up the right look
 * without any per-callsite config. This is what makes custom-content
 * snackbars feel native.
 */
@Composable
private fun SnackbarSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = SnackbarDefaults.shape
    ProvideContentColor(SnackbarDefaults.contentColor) {
        ProvideTextConfig(
            typography = AppTheme.typography.Body.B600,
            color = SnackbarDefaults.contentColor,
        ) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimension.D500, vertical = Dimension.D500)
                    .shadow(elevation = Dimension.D200, shape = shape)
                    .background(SnackbarDefaults.backgroundColor.color, shape)
                    .padding(Dimension.D500),
                content = { content() }
            )
        }
    }
}

@Composable
private fun SnackbarBody(
    title: (@Composable () -> Unit)?,
    body: @Composable () -> Unit,
    leading: (@Composable () -> Unit)?,
    action: (@Composable () -> Unit)?,
    dismissible: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = CenterVertically,
    ) {
        if (leading != null) {
            leading()
            HorizontalSpacerD500()
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            if (title != null) {
                title()
                VerticalSpacerD300()
            }
            body()
            if (action != null) {
                VerticalSpacerD500()
                action()
            }
        }

        if (dismissible) {
            HorizontalSpacerD500()
            IconButton(
                size = IconButton.Size.Small,
                icon = Icons.X(stringResource(Res.string.ui_close_a11y)),
                onClick = onDismiss,
            )
        }
    }
}

private fun snackbarLeading(
    icon: IconResource?,
    emoji: String?,
): (@Composable () -> Unit)? = when {
    !emoji.isNullOrBlank() -> { { SnackbarEmojiBubble(emoji = emoji) } }
    icon != null -> { { Icon(icon = icon) } }
    else -> null
}

private fun actionLabelComposable(
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onAfterAction: () -> Unit,
): (@Composable () -> Unit)? {
    if (actionLabel == null || onAction == null) return null
    return {
        Button(
            onClick = {
                onAction()
                // Material spec: tapping the action dismisses the
                // snackbar. Routing through the scope's dismiss keeps
                // the host's exit animation in play.
                onAfterAction()
            },
            size = ButtonSize.ExtraSmall,
        ) {
            Text(text = actionLabel)
        }
    }
}

@Composable
private fun SnackbarEmojiBubble(
    emoji: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(SnackbarEmojiBubbleSize)
            .clip(CircleShape)
            .background(AppTheme.colors.surfaceTertiary.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            typography = AppTheme.typography.Heading.H600,
        )
    }
}

private val SnackbarEmojiBubbleSize = 36.dp

object SnackbarDefaults {
    val shape: Shape @Composable get() = Radii.R600.shape
    val backgroundColor: ColorResource @Composable get() = AppTheme.colors.surfaceSecondary
    val contentColor: ColorResource @Composable get() = AppTheme.colors.onSurfaceSecondary
}

// ---------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------

@Preview
@Composable
private fun PreviewSnackbar_MessageOnly() {
    PreviewContent {
        val host = LocalSnackbarHostState.current ?: return@PreviewContent
        // The host renders the snackbar; we just trigger one on first compose.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            host.present(duration = SnackbarDuration.Indefinite) {
                Snackbar(message = "Got it. Thank you!")
            }
        }
    }
}

@Preview
@Composable
private fun PreviewSnackbar_TitleMessageEmoji() {
    PreviewContent {
        val host = LocalSnackbarHostState.current ?: return@PreviewContent
        androidx.compose.runtime.LaunchedEffect(Unit) {
            host.present(duration = SnackbarDuration.Indefinite) {
                Snackbar(
                    title = "Unlocked!",
                    message = "Royal Felt is yours.",
                    emoji = "🎰",
                    actionLabel = "Equip",
                    onAction = {},
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewSnackbar_LongCopy() {
    PreviewContent {
        val host = LocalSnackbarHostState.current ?: return@PreviewContent
        androidx.compose.runtime.LaunchedEffect(Unit) {
            host.present(duration = SnackbarDuration.Indefinite) {
                Snackbar(
                    title = "Heads up",
                    message = "This is some text that describes something that takes up a lot of space.".repeat(2),
                    icon = Icons.Bug(null),
                    actionLabel = "Do something",
                    onAction = {},
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewSnackbar_Top() {
    PreviewContent {
        val host = LocalSnackbarHostState.current ?: return@PreviewContent
        androidx.compose.runtime.LaunchedEffect(Unit) {
            host.present(
                anchor = SnackbarAnchor.Top,
                duration = SnackbarDuration.Indefinite,
            ) {
                Snackbar(
                    message = "Connection lost. Retrying…",
                    icon = Icons.Bug(null),
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewSnackbar_ComposableSlots() {
    PreviewContent {
        val host = LocalSnackbarHostState.current ?: return@PreviewContent
        androidx.compose.runtime.LaunchedEffect(Unit) {
            host.present(duration = SnackbarDuration.Indefinite) {
                Snackbar(
                    title = { Text("Custom", typography = AppTheme.typography.Heading.H800) },
                    leading = { Icon(icon = Icons.Bug(null)) },
                ) {
                    Text("Slot version — full control per slot.")
                }
            }
        }
    }
}
