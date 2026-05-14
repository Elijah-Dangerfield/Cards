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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt
import kotlin.random.Random


/**
 * Public dialog entry point that mirrors Compose's windowed dialog API but renders
 * entirely inside our Compose hierarchy. Supply a [DialogState] if you need to trigger
 * animated dismissals from inside the dialog; otherwise a default state is provided.
 */
@Composable
fun Dialog(
    state: DialogState = rememberDialogState(),
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
    /**
     * Cap the dialog's height to a fraction of available screen height. Pass
     * a value in (0f, 1f] to enable scrolling for tall content — the caller's
     * content must use `verticalScroll` to actually scroll. Null (default)
     * keeps the historical wrap-content behavior for short dialogs.
     */
    maxHeightFraction: Float? = null,
    content: @Composable () -> Unit = {},
) {
    HostedDialog(
        state = state,
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        properties = properties,
        animationSpec = animationSpec,
        scrimColor = scrimColor,
        contentAlignment = contentAlignment
    ) {
        val sizeModifier = if (maxHeightFraction != null) {
            Modifier
                .fillMaxWidth(0.8f)
                .fillMaxHeight(maxHeightFraction.coerceIn(0.1f, 1f))
        } else {
            Modifier
                .fillMaxWidth(0.8f)
                .animateContentSize()
        }
        Box(
            modifier = sizeModifier
                .clipToBounds()
                .background(AppTheme.colors.surfacePrimary.color, shape = Radii.Card.shape),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

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

/**
 * Window-free dialog host that handles scrim + content animations and dismissal behaviour entirely
 * in Compose Multiplatform.
 */
/**
 * Lower-level alternative used when callers want to provide their own dialog surface.
 * Registers the provided [content] with [DialogHostState] so it renders on top of the app.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HostedDialog(
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
