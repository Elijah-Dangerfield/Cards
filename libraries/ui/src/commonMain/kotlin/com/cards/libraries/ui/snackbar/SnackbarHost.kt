package com.dangerfield.cards.libraries.ui.snackbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.ui.components.LocalAppBottomBarHeight
import com.dangerfield.cards.system.Dimension
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Renders the head entry of [hostState] with anchor-aware positioning,
 * entrance/exit animation, swipe-to-dismiss, and a11y-aware auto-timeout.
 *
 * Queue policy: one visible at a time. The host walks `entries` in
 * arrival order and shows the first one. When it dismisses, the next
 * one takes its place.
 *
 * Mount once near the top of the app (next to `DialogHost`) so every
 * screen shares the same queue and positioning rules.
 */
@Composable
fun SnackbarHost(
    modifier: Modifier = Modifier,
    hostState: SnackbarHostState? = LocalSnackbarHostState.current,
) {
    if (hostState == null) return

    LaunchedEffect(hostState) {
        SnackBarPresenter.requests.collect { request ->
            Catching {
                if (request.delayBy.inWholeMilliseconds > 0) {
                    launch {
                        delay(request.delayBy)
                        hostState.presentRequest(request)
                    }
                } else {
                    hostState.presentRequest(request)
                }
            }.logOnFailure("Could not present snackbar request $request")
        }
    }

    val entries by hostState.entries.collectAsState()
    val current = entries.firstOrNull() ?: return

    Box(modifier = modifier.fillMaxSize()) {
        key(current.id) {
            CurrentSnackbar(
                entry = current,
                onDismissComplete = { hostState.remove(current.id) },
            )
        }
    }
}

@Composable
private fun CurrentSnackbar(
    entry: SnackbarHostEntry,
    onDismissComplete: () -> Unit,
) {
    val accessibilityManager = LocalAccessibilityManager.current
    // In a static @Preview LaunchedEffects don't fire, so the
    // entrance transition would never trigger and the snackbar would
    // render as nothing. Seeding the transition state with `true` in
    // inspection mode skips the animation and shows the snackbar
    // immediately — same trick `DialogOverlay` uses.
    val isInPreview = LocalInspectionMode.current
    val transitionState = remember { MutableTransitionState(isInPreview) }
    transitionState.targetState = entry.visible

    // Lift into view on first compose. We don't fight the upstream
    // `visible` flag — if the entry was registered already dismissing
    // we won't try to animate it back in.
    LaunchedEffect(entry.id) {
        if (entry.visible) transitionState.targetState = true
    }

    // Auto-dismiss after the duration (a11y-multiplied).
    LaunchedEffect(entry.id, entry.visible) {
        if (!entry.visible) return@LaunchedEffect
        val base = when (entry.duration) {
            SnackbarDuration.Indefinite -> return@LaunchedEffect
            else -> entry.duration.toDuration().inWholeMilliseconds
        }
        val adjusted = accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = base,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: base
        delay(adjusted)
        entry.requestDismiss()
    }

    // After exit anim completes: remove the entry from the queue first
    // (so the next entry can take its place) then notify the upstream
    // source. Order matters — `onDismissed` may navigate or fire more
    // snackbars; we want the queue free before that happens.
    val onUpstreamDismissed by rememberUpdatedState(entry.onDismissed)
    val removeFromQueue by rememberUpdatedState(onDismissComplete)
    LaunchedEffect(transitionState.currentState, transitionState.targetState) {
        val finishedExit = !transitionState.currentState && !transitionState.targetState
        if (finishedExit) {
            removeFromQueue()
            onUpstreamDismissed()
        }
    }

    val anchorAlignment = when (entry.anchor) {
        SnackbarAnchor.Top -> Alignment.TopCenter
        SnackbarAnchor.Bottom -> Alignment.BottomCenter
    }
    val enter = when (entry.anchor) {
        SnackbarAnchor.Top -> SnackbarAnims.enterFromTop
        SnackbarAnchor.Bottom -> SnackbarAnims.enterFromBottom
    }
    val exit = when (entry.anchor) {
        SnackbarAnchor.Top -> SnackbarAnims.exitToTop
        SnackbarAnchor.Bottom -> SnackbarAnims.exitToBottom
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(anchorInsets(entry.anchor)),
        contentAlignment = anchorAlignment,
    ) {
        AnimatedVisibility(
            visibleState = transitionState,
            enter = enter,
            exit = exit,
        ) {
            SwipeAndDragDismiss(entry = entry) {
                entry.content()
            }
        }
    }
}

/**
 * Anchor-aware insets so snackbars never overlap the bottom tab bar
 * or system gesture areas.
 *
 * Bottom anchor:
 *   `LocalAppBottomBarHeight` (already 0 when the bar is hidden) +
 *   system safe-drawing inset + a small breathing gap. The bar slot
 *   itself adds its own safe-area padding underneath its content, so
 *   adding both here lets the snackbar sit exactly above the bar's
 *   top edge.
 *
 * Top anchor:
 *   system safe-drawing top inset + breathing gap. No app-bar
 *   equivalent today; if a top bar starts living above content it
 *   should publish its own height the same way `BottomBarSizes` does.
 */
@Composable
private fun anchorInsets(anchor: SnackbarAnchor): PaddingValues {
    val safe = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Vertical)
        .asPaddingValues()
    val breathingGap = Dimension.D300
    return when (anchor) {
        SnackbarAnchor.Bottom -> PaddingValues(
            bottom = LocalAppBottomBarHeight.current +
                safe.calculateBottomPadding() +
                breathingGap,
        )
        SnackbarAnchor.Top -> PaddingValues(
            top = safe.calculateTopPadding() + breathingGap,
        )
    }
}

@Composable
private fun SwipeAndDragDismiss(
    entry: SnackbarHostEntry,
    content: @Composable () -> Unit,
) {
    if (!entry.dismissible) {
        Box(modifier = Modifier.fillMaxWidth()) { content() }
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val dismissed = value == SwipeToDismissBoxValue.EndToStart ||
                value == SwipeToDismissBoxValue.StartToEnd
            if (dismissed) entry.requestDismiss()
            dismissed
        }
    )

    val verticalOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dragDismissThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        56.dp.toPx()
    }
    // Pull-toward-edge direction depends on anchor:
    // - Bottom anchor: dragging *down* (positive) dismisses
    // - Top anchor: dragging *up* (negative) dismisses
    val signTowardEdge = when (entry.anchor) {
        SnackbarAnchor.Bottom -> 1f
        SnackbarAnchor.Top -> -1f
    }

    SwipeToDismissBox(
        modifier = Modifier.fillMaxWidth(),
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, verticalOffset.value.roundToInt()) }
                .pointerInput(entry.id) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            // Only allow drag toward the dismiss edge — bias
                            // the value so the resting state is 0.
                            val raw = verticalOffset.value + dragAmount
                            val clamped = if (signTowardEdge > 0) {
                                raw.coerceAtLeast(0f)
                            } else {
                                raw.coerceAtMost(0f)
                            }
                            scope.launch { verticalOffset.snapTo(clamped) }
                        },
                        onDragEnd = {
                            val past = verticalOffset.value * signTowardEdge >= dragDismissThresholdPx
                            if (past) {
                                scope.launch {
                                    verticalOffset.animateTo(
                                        targetValue = signTowardEdge * dragDismissThresholdPx * 1.5f,
                                        animationSpec = tween(durationMillis = 150)
                                    )
                                    entry.requestDismiss()
                                }
                            } else {
                                scope.launch {
                                    verticalOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                verticalOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)
                                )
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}

private object SnackbarAnims {
    private const val EnterMillis = 240
    private const val ExitMillis = 180

    val enterFromBottom: EnterTransition =
        slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            initialOffsetY = { it }
        ) + fadeIn(animationSpec = tween(EnterMillis))

    val exitToBottom: ExitTransition =
        slideOutVertically(
            animationSpec = tween(ExitMillis),
            targetOffsetY = { it }
        ) + fadeOut(animationSpec = tween(ExitMillis))

    val enterFromTop: EnterTransition =
        slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            initialOffsetY = { -it }
        ) + fadeIn(animationSpec = tween(EnterMillis))

    val exitToTop: ExitTransition =
        slideOutVertically(
            animationSpec = tween(ExitMillis),
            targetOffsetY = { -it }
        ) + fadeOut(animationSpec = tween(ExitMillis))
}

/**
 * Bridges a [SnackbarRequest] (fired from a ViewModel via
 * [showSnackBar]) into a standard `present { Snackbar(...) }` call.
 * Every path — compose-side `present` and global `showSnackBar` —
 * ultimately ends up in [SnackbarHostState.present], so the queue and
 * lifecycle behavior are identical regardless of where the snackbar
 * came from.
 */
private fun SnackbarHostState.presentRequest(request: SnackbarRequest) {
    present(
        anchor = request.anchor,
        duration = request.duration,
        dismissible = request.withDismissAction,
        onDismiss = request.onDismiss,
    ) {
        PresenterSnackbar(request)
    }
}
