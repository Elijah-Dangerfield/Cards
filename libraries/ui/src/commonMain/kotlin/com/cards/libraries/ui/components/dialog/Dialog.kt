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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.LowLevelDSComponent
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
 * When [topAccessory] is non-null, the dialog gains the same notched-top
 * + bubble treatment used by [com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet]'s
 * `BottomSheetDragHandle.Accessory`. Use it for hero / unlock / commerce
 * dialogs where a glanceable cue should land before the user reads the
 * title. Rendering goes through the same [TopAccessoryBubble] primitive
 * so sheets and dialogs stay in lockstep.
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
    topAccessory: TopAccessory? = null,
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
        val capModifier = Modifier.layout { measurable, constraints ->
            val cap = (constraints.maxHeight * 0.92f).toInt()
            val capped = constraints.copy(maxHeight = cap)
            val placeable = measurable.measure(capped)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val surfaceShape: Shape = if (topAccessory != null) {
            NotchedSheetShape(
                cornerRadius = DialogCardCornerRadius,
                notchRadius = TopAccessoryNotchRadius,
                notchCornerRadius = TopAccessoryDefaults.notchCornerRadiusFor(topAccessory.style),
                bottomCornerRadius = DialogCardCornerRadius,
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
            if (topAccessory != null) {
                Column {
                    TopAccessoryBubble(
                        accessory = topAccessory,
                        fallbackSurface = BubbleSurface.Solid(AppTheme.colors.surfacePrimary),
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

/**
 * Title + freeform body preset. For content-only dialogs (explainers,
 * read-only callouts) that share a title-then-rows shape but each
 * stack different content rows underneath. Bakes the standard heading
 * typography, centered alignment, outer padding, and inter-row spacing
 * so callsites don't each re-pick them.
 *
 * [itemSpacing] controls the vertical gap between the title and each
 * body row (default [Dimension.D500] = 12.dp). Override for explainers
 * whose rows need more breathing room (16 / 20.dp).
 *
 * Use [Dialog]'s 3-arg `(title, description, primaryButtonText, ...)`
 * overload when the dialog has buttons. Use this overload when it
 * doesn't (and the body needs more than a single description string).
 */
@Composable
fun Dialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    state: DialogState = rememberDialogState(),
    topAccessory: TopAccessory? = null,
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
    itemSpacing: Dp = Dimension.D500,
    body: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        state = state,
        topAccessory = topAccessory,
        properties = properties,
        animationSpec = animationSpec,
        scrimColor = scrimColor,
        contentAlignment = contentAlignment,
        itemSpacing = itemSpacing,
        title = {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
        },
        body = body,
    )
}

/**
 * Composite-title variant of the title preset. Same outer padding +
 * inter-row spacing as the string-title overload, but takes a
 * `title: @Composable` slot for cases where the title isn't a single
 * line of text (e.g. headline + sub-badge stack, icon + title row).
 * Callers using this slot own the title's typography + alignment —
 * the preset only owns layout. Prefer the string overload when the
 * title is plain text so we keep typography consistent by default.
 *
 * [itemSpacing] controls the vertical gap between every direct child
 * of the inner column (title row + each body row). Default is
 * [Dimension.D500] (12.dp); override when an explainer's rows need
 * more breathing room.
 */
@Composable
fun Dialog(
    title: @Composable ColumnScope.() -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    state: DialogState = rememberDialogState(),
    topAccessory: TopAccessory? = null,
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
    itemSpacing: Dp = Dimension.D500,
    body: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        state = state,
        topAccessory = topAccessory,
        properties = properties,
        animationSpec = animationSpec,
        scrimColor = scrimColor,
        contentAlignment = contentAlignment,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimension.D900, vertical = Dimension.D1000),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            title()
            body()
        }
    }
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
            topAccessory = topAccessoryChipBubble(),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = Dimension.D800),
                text = "Chip-themed dialog (rebuy / bust / chip rewards)",
            )
        }
    }
}

@Preview
@Composable
private fun PreviewDialog_PrimaryButtonOnly() {
    PreviewContent {
        Dialog(
            title = "You're all set",
            description = "Your changes have been saved. You can keep playing whenever you're ready.",
            primaryButtonText = "Got it",
            onDismissRequest = {},
            onPrimaryButtonClicked = {},
        )
    }
}

@Preview
@Composable
private fun PreviewDialog_PrimaryAndSecondary() {
    PreviewContent {
        Dialog(
            title = "Leave table?",
            description = "Your seat will be released and your stack returned to your bankroll.",
            primaryButtonText = "Leave",
            secondaryButtonText = "Stay",
            onDismissRequest = {},
            onPrimaryButtonClicked = {},
            onSecondaryButtonClicked = {},
        )
    }
}

@Preview
@Composable
private fun PreviewDialog_LongDescription() {
    PreviewContent {
        Dialog(
            title = "How blinds work",
            description = "Blinds are forced bets that rotate around the table each hand. " +
                "They guarantee there's always something in the pot to fight over, " +
                "and they increase over time so stacks don't sit untouched forever.",
            primaryButtonText = "Continue",
            secondaryButtonText = "Tell me more",
            onDismissRequest = {},
            onPrimaryButtonClicked = {},
            onSecondaryButtonClicked = {},
        )
    }
}

@Preview
@Composable
private fun PreviewDialog_TitlePresetWithCustomButtons() {
    PreviewContent {
        Dialog(
            title = "Cash out?",
            onDismissRequest = {},
        ) {
            Text(
                text = "You'll be cashed out at your current stack size.",
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Dimension.D400))
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.Danger,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Cash out")
            }
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.Ghost,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Never mind")
            }
        }
    }
}

@Preview
@Composable
private fun PreviewDialog_PrimaryAltAndSecondary() {
    PreviewContent {
        Dialog(
            title = "Daily reward",
            onDismissRequest = {},
            topAccessory = topAccessoryChipBubble(),
        ) {
            Text(
                text = "Claim your 5,000 chip bonus.",
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Dimension.D400))
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.PrimaryAlt,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Claim")
            }
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.Secondary,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Later")
            }
        }
    }
}

@Preview
@Composable
private fun PreviewDialog_DangerWithGhost() {
    PreviewContent {
        Dialog(
            title = "Delete account?",
            onDismissRequest = {},
            topAccessory = topAccessoryEmoji(emoji = "⚠️"),
        ) {
            Text(
                text = "This cannot be undone. Your stats, achievements, and chips will be lost.",
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Dimension.D400))
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.Danger,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Delete forever")
            }
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.Ghost,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Cancel")
            }
        }
    }
}

@Preview
@Composable
private fun PreviewDialog_GhostOnly() {
    PreviewContent {
        Dialog(
            title = "Heads up",
            onDismissRequest = {},
        ) {
            Text(
                text = "Tournaments pause automatically if the host disconnects.",
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Dimension.D400))
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.Ghost,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Got it")
            }
        }
    }
}

@Preview
@Composable
private fun PreviewDialog_TitlePresetBodyRows() {
    PreviewContent {
        Dialog(
            title = "What's new",
            onDismissRequest = {},
            topAccessory = topAccessoryEmoji(emoji = "✨"),
        ) {
            Text(
                text = "Faster table loads, smoother animations, and a redesigned action bar.",
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Tap any chip to see the new bet pill explainer.",
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview
@Composable
private fun PreviewDialog_IconAccessoryPrimary() {
    PreviewContent {
        Dialog(
            title = "Verified",
            onDismissRequest = {},
            topAccessory = topAccessoryIcon(
                icon = Icons.Check("check"),
            ),
        ) {
            Text(
                text = "Your email has been confirmed.",
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Dimension.D400))
            Button(
                size = ButtonSize.Medium,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Continue")
            }
        }
    }
}

@Preview
@Composable
private fun PreviewDialog_ThreeStackedButtons() {
    PreviewContent {
        Dialog(
            title = "Choose a buy-in",
            onDismissRequest = {},
        ) {
            Text(
                text = "Pick the stack you want to bring to the table.",
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Dimension.D400))
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.Primary,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "10,000 chips")
            }
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.Secondary,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "25,000 chips")
            }
            Button(
                size = ButtonSize.Medium,
                type = ButtonType.Tertiary,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "50,000 chips")
            }
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
