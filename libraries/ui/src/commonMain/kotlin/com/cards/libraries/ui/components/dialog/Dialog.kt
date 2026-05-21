package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.EmojiHandleStyle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.NotchedSheetShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.LowLevelDSComponent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt
import kotlin.random.Random


/**
 * Public dialog entry point that mirrors Compose's windowed dialog API but renders
 * entirely inside our Compose hierarchy. Supply a [DialogState] if you need to trigger
 * animated dismissals from inside the dialog; otherwise a default state is provided.
 *
 * When [emoji] is non-null, the dialog gains the same notched-top + bubble
 * treatment used by [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet]'s
 * `BottomSheetDragHandle.Emoji`. Use it for hero / unlock / commerce
 * dialogs where a glanceable cue should land before the user reads the
 * title. Rendering goes through the same [EmojiBubble] primitive so
 * sheets and dialogs stay in lockstep.
 */
@OptIn(LowLevelDSComponent::class)
@Composable
fun Dialog(
    state: DialogState = rememberDialogState(),
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
    emoji: DialogEmoji? = null,
    content: @Composable () -> Unit = {},
) {
    BaseDialog(
        state = state,
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        properties = properties,
        animationSpec = animationSpec,
        scrimColor = scrimColor,
        contentAlignment = contentAlignment
    ) {
        // Hard ceiling at 92% of screen height so tall content (long
        // achievement lists, multi-seat showdowns) is reachable via
        // `verticalScroll` instead of running off the bottom. Short content
        // ignores the cap and sits at its natural size.
        val capModifier = Modifier.layout { measurable, constraints ->
            val cap = (constraints.maxHeight * 0.92f).toInt()
            val capped = constraints.copy(maxHeight = cap)
            val placeable = measurable.measure(capped)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val surfaceShape: Shape = if (emoji != null) {
            NotchedSheetShape(
                cornerRadius = DialogCardCornerRadius,
                notchRadius = EmojiBubbleNotchRadius,
                notchCornerRadius = EmojiBubbleDefaults.notchCornerRadiusFor(emoji.style),
            )
        } else {
            Radii.Card.shape
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .then(capModifier)
                .animateContentSize()
                .clipToBounds()
                .background(AppTheme.colors.surfacePrimary.color, shape = surfaceShape),
            contentAlignment = Alignment.Center
        ) {
            if (emoji != null) {
                Column {
                    EmojiBubble(
                        emoji = emoji.emoji,
                        style = emoji.style,
                        surface = emoji.surface
                            ?: BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
                        contentColor = AppTheme.colors.onSurfacePrimary,
                    )
                    content()
                }
            } else {
                content()
            }
        }
    }
}

/**
 * Specifies the emoji bubble that overhangs the top of a [Dialog].
 *
 * Mirrors [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle.Emoji]
 * — same DS constants, same shape choices. Defaults to a circle
 * bubble; pass [EmojiHandleStyle.Squircle] for commerce / equip dialogs.
 * [surface] overrides the bubble fill (color or gradient); leave null to
 * match the dialog's surface for a seamless top edge.
 *
 * Construction is restricted to the `:libraries:ui` module so every caller
 * routes through the composable factory [dialogEmoji]. The factory owns
 * theme-aware defaults — keeping it the single chokepoint means the DS
 * can pin a default surface token in one place rather than retuning every
 * callsite.
 */
@Immutable
data class DialogEmoji internal constructor(
    val emoji: String,
    val style: EmojiHandleStyle = EmojiHandleStyle.Circle,
    val surface: BubbleSurface? = null,
)


@Composable
fun dialogEmoji(
    emoji: String,
    style: EmojiHandleStyle = EmojiHandleStyle.Circle,
    surface: BubbleSurface? = BubbleSurface.Solid(AppTheme.colors.surfaceTertiary),
) = DialogEmoji(emoji, style, surface)

/**
 * Factory for the chip-themed top bubble used by chip-related dialogs
 * (rebuy, bust, chip rewards, soft-bust grant, tip-the-dealer). Paints
 * a solid casino-gold circle with a `$` glyph in the middle.
 *
 * Routes through the same [DialogEmoji] / [EmojiBubble] chokepoint as
 * [dialogEmoji] so geometry, notch, and ring stay in lockstep. The
 * spec called this a "sibling primitive"; we ship the factory now and
 * defer a fully separate render path until there's a documented visual
 * problem with the shared one (`$` already renders cleanly at the
 * shared `H1100` typography — verify in [Dialog]'s preview pane).
 */
@Composable
fun dialogChipBubble(): DialogEmoji = DialogEmoji(
    emoji = "$",
    style = EmojiHandleStyle.Circle,
    surface = BubbleSurface.Solid(
        color = ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold"),
    ),
)

@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    topContent: @Composable () -> Unit,
    bottomContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    state: DialogState = rememberDialogState(),
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
) {
    Dialog(
        state = state,
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        properties = properties,
        animationSpec = animationSpec,
        scrimColor = scrimColor,
        contentAlignment = contentAlignment,
    ) {
        ModalContent(
            modifier = modifier.padding(
                top = Dimension.D800,
                start = Dimension.D800,
                end = Dimension.D800,
                bottom = Dimension.D800,
            ),
            topContent = topContent,
            content = content,
            bottomContent = bottomContent,
        )
    }
}

@Composable
fun Dialog(
    title: String,
    description: String,
    primaryButtonText: String,
    onDismissRequest: () -> Unit,
    onPrimaryButtonClicked: () -> Unit,
    modifier: Modifier = Modifier,
    state: DialogState = rememberDialogState(),
    secondaryButtonText: String? = null,
    onSecondaryButtonClicked: (() -> Unit)? = null,
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        state = state,
        properties = properties,
        animationSpec = animationSpec,
        scrimColor = scrimColor,
        contentAlignment = contentAlignment,
        topContent = { Text(text = title) },
        content = { Text(text = description) },
        bottomContent = {
            Column(
                modifier = Modifier.padding(horizontal = Dimension.D1000),
            ) {
                Button(
                    size = ButtonSize.Medium,
                    onClick = onPrimaryButtonClicked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = primaryButtonText)
                }
                if (secondaryButtonText != null && onSecondaryButtonClicked != null) {
                    Spacer(modifier = Modifier.height(Dimension.D600))
                    Button(
                        size = ButtonSize.Medium,
                        type = ButtonType.Tertiary,
                        onClick = onSecondaryButtonClicked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = secondaryButtonText)
                    }
                }
            }
        },
    )
}

/** Dialog top-corner radius. Matches [Radii.Card] visually but expressed
 *  as a Dp because [NotchedSheetShape] takes Dp directly. */
private val DialogCardCornerRadius = 20.dp

@Preview
@Composable
private fun PreviewDialog() {
    PreviewContent {
        Dialog(
            onDismissRequest = { -> },
        ) {
            Text("This is all a dialog is")
        }
    }
}

@Preview
@Composable
private fun PreviewDialog_ChipBubble() {
    PreviewContent {
        Dialog(
            onDismissRequest = { -> },
            emoji = dialogChipBubble(),
        ) {
            Text(
                modifier = Modifier.padding(Dimension.D800),
                text = "Chip-themed dialog (rebuy / bust / chip rewards)",
            )
        }
    }
}

/**
 * Low-level dialog primitive — registers the provided [content] with
 * [DialogHostState] so it renders on top of the app, handles scrim +
 * content animations + dismissal, but **does not** apply any DS surface,
 * shape, padding, or emoji-bubble treatment.
 *
 * For 99% of dialogs use [Dialog] — it wraps [BaseDialog] with the DS
 * surface, max-height cap, and emoji affordance. Reach for [BaseDialog]
 * only when the caller is deliberately escaping the defaults (custom
 * animation, non-DS marketing surface, one-off shape). The
 * [LowLevelDSComponent] opt-in is the discoverable signal that this is the
 * escape hatch, not the standard path.
 */
@LowLevelDSComponent
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BaseDialog(
    state: DialogState = rememberDialogState(),
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
    hostState: DialogHostState? = LocalDialogHostState.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val resolvedHostState = hostState ?: return
    val entryId = remember { Random.nextLong() }

    val currentModifier by rememberUpdatedState(modifier)
    val currentProperties by rememberUpdatedState(properties)
    val currentAnimation by rememberUpdatedState(animationSpec)
    val currentScrim by rememberUpdatedState(scrimColor)
    val currentAlignment by rememberUpdatedState(contentAlignment)
    val currentOnDismissComplete by rememberUpdatedState(onDismissRequest)
    val currentContent by rememberUpdatedState(content)
    val visible = state.isVisible

    val requestDismiss = remember(state) {
        {
            state.dismiss()
        }
    }

    SideEffect {
        resolvedHostState.upsert(
            DialogHostEntry(
                id = entryId,
                visible = visible,
                modifier = currentModifier,
                properties = currentProperties,
                animationSpec = currentAnimation,
                scrimColor = currentScrim,
                contentAlignment = currentAlignment,
                requestDismiss = requestDismiss,
                onDismissed = currentOnDismissComplete,
                content = currentContent
            )
        )
    }

    DisposableEffect(resolvedHostState, entryId) {
        onDispose { resolvedHostState.remove(entryId) }
    }
}

/**
 * Renders the actual scrim + animated surface for a hosted dialog.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DialogOverlay(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onDismissComplete: () -> Unit,
    modifier: Modifier = Modifier,
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val isInPreview = LocalInspectionMode.current
    val transitionState = remember { MutableTransitionState(isInPreview) }
    val dismissComplete by rememberUpdatedState(onDismissComplete)
    var pendingDismiss by remember { mutableStateOf(false) }
    transitionState.targetState = visible

    LaunchedEffect(visible) {
        if (!visible) {
            pendingDismiss = true
        } else {
            pendingDismiss = false
        }
    }

    val shouldRender =
        transitionState.currentState || transitionState.targetState || pendingDismiss

    if (!shouldRender) {
        return
    }

    LaunchedEffect(
        pendingDismiss,
        transitionState.currentState,
        transitionState.targetState
    ) {
        val shouldFinishDismiss =
            pendingDismiss && !transitionState.currentState && !transitionState.targetState
        if (shouldFinishDismiss) {
            pendingDismiss = false
            dismissComplete()
        }
    }

    BackHandler(
        enabled = shouldRender && transitionState.currentState,
        onBack = {
            if (properties.dismissOnBackPress) {
                onDismissRequest()
            }
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                dialog()
                stateDescription = "Dialog"
            },
        contentAlignment = contentAlignment
    ) {
        AnimatedVisibility(
            visibleState = transitionState,
            enter = animationSpec.scrimEnter,
            exit = animationSpec.scrimExit
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor)
                    .pointerInput(properties.dismissOnClickOutside) {
                        detectTapGestures {
                            if (properties.dismissOnClickOutside) {
                                onDismissRequest()
                            }
                        }
                    }
            )
        }

        AnimatedVisibility(
            visibleState = transitionState,
            enter = animationSpec.contentEnter,
            exit = animationSpec.contentExit
        ) {
            Box(modifier = modifier, content = content)
        }
    }
}

@Stable
data class ModalDialogProperties(
    val dismissOnBackPress: Boolean = true,
    val dismissOnClickOutside: Boolean = true,
)

@Stable
data class ModalDialogAnimationSpec(
    val scrimEnter: EnterTransition = ModalDialogDefaults.scrimEnter(),
    val scrimExit: ExitTransition = ModalDialogDefaults.scrimExit(),
    val contentEnter: EnterTransition = ModalDialogDefaults.contentEnter(),
    val contentExit: ExitTransition = ModalDialogDefaults.contentExit(),
)

object ModalDialogDefaults {
    private val enterMillis = 260
    private val exitMillis = 180

    @Composable
    fun scrimColor(): Color = AppTheme.colors.backgroundOverlay.color

    fun scrimEnter(): EnterTransition = fadeIn(
        animationSpec = tween(enterMillis)
    )

    fun scrimExit(): ExitTransition = fadeOut(
        animationSpec = tween(exitMillis)
    )

    fun contentEnter(): EnterTransition =
        slideInVertically(
            animationSpec = tween(enterMillis),
            initialOffsetY = { (it * 0.12f).roundToInt() }
        ) +
            fadeIn(animationSpec = tween(enterMillis)) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.65f,
                    stiffness = 350f
                )
            )

    fun contentExit(): ExitTransition =
        fadeOut(animationSpec = tween(exitMillis)) +
            slideOutVertically(
                animationSpec = tween(exitMillis),
                targetOffsetY = { (it * 0.08f).roundToInt() }
            ) +
            scaleOut(
                targetScale = 0.9f,
                animationSpec = tween(exitMillis)
            )

}
