package com.dangerfield.cards.libraries.ui.snackbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration as MaterialSnackbarDuration
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.PodawanSnackbarVisuals
import com.dangerfield.cards.libraries.ui.components.Snackbar
import com.dangerfield.cards.libraries.ui.components.snackBarData
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

@Composable
fun PresenterSnackbarHost(
    modifier: Modifier = Modifier,
    hostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val accessibilityManager = LocalAccessibilityManager.current

    LaunchedEffect(hostState) {
        SnackBarPresenter.requests.collect { message ->
            Catching {
                if (message.delayBy != Duration.ZERO) {
                    launch {
                        delay(message.delayBy)
                        if (isActive) {
                            val result = hostState.showSnackbar(message.toVisuals())
                            SnackBarPresenter.onResult(message.id, result)
                        }
                    }
                } else {
                    val result = hostState.showSnackbar(message.toVisuals())
                    SnackBarPresenter.onResult(message.id, result)
                }
            }.logOnFailure("Could not display snackbar $message")
        }
    }

    val currentSnackbarData = hostState.currentSnackbarData

    // Mirror default host auto-dismiss behavior so the state queue continues to flow.
    LaunchedEffect(currentSnackbarData, accessibilityManager) {
        if (currentSnackbarData != null) {
            val durationMillis = currentSnackbarData.visuals.duration.toTimeoutMillis(
                hasAction = currentSnackbarData.visuals.actionLabel != null,
                accessibilityManager = accessibilityManager
            )
            if (durationMillis != Long.MAX_VALUE) {
                delay(durationMillis)
                currentSnackbarData.dismiss()
            }
        }
    }

    Box(modifier = modifier) {
        currentSnackbarData?.let { data ->
            SwipeDismissSnackbar(data)
        }
    }
}

@Composable
private fun SwipeDismissSnackbar(data: SnackbarData) {
    val visuals = data.visuals as? PodawanSnackbarVisuals ?: return
    key(visuals.id) {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                val dismissed = value == SwipeToDismissBoxValue.EndToStart ||
                        value == SwipeToDismissBoxValue.StartToEnd
                if (dismissed) {
                    data.dismiss()
                }
                dismissed
            }
        )
        val density = LocalDensity.current
        val dragDismissThreshold = with(density) { 56.dp.toPx() }
        val verticalOffset = remember { Animatable(0f) }
        val entranceOffset = remember { Animatable(64f) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(visuals.id) {
            verticalOffset.snapTo(0f)
            entranceOffset.snapTo(64f)
            entranceOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }

        SwipeToDismissBox(
            modifier = Modifier.fillMaxWidth(),
            state = dismissState,
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
            backgroundContent = {}
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset {
                        IntOffset(
                            0,
                            (verticalOffset.value + entranceOffset.value)
                                .roundToInt()
                        )
                    }
                    .pointerInput(visuals.id) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                val newOffset = (verticalOffset.value + dragAmount)
                                    .coerceAtLeast(0f)
                                scope.launch {
                                    verticalOffset.snapTo(newOffset)
                                }
                            },
                            onDragEnd = {
                                if (verticalOffset.value >= dragDismissThreshold) {
                                    scope.launch {
                                        verticalOffset.animateTo(
                                            targetValue = dragDismissThreshold * 1.5f,
                                            animationSpec = tween(durationMillis = 150)
                                        )
                                        data.dismiss()
                                    }
                                } else {
                                    scope.launch {
                                        verticalOffset.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy
                                            )
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    verticalOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy
                                        )
                                    )
                                }
                            }
                        )
                    }
            ) {
                Snackbar(
                    podawanSnackbarData = snackBarData(
                        visuals = visuals,
                        onAction = data::performAction,
                        onDismiss = data::dismiss
                    )
                )
            }
        }
    }
}


private fun MaterialSnackbarDuration.toTimeoutMillis(
    hasAction: Boolean,
    accessibilityManager: AccessibilityManager?,
): Long {
    val base = when (this) {
        MaterialSnackbarDuration.Indefinite -> Long.MAX_VALUE
        MaterialSnackbarDuration.Long -> 10_000L
        MaterialSnackbarDuration.Short -> 4_000L
    }
    if (base == Long.MAX_VALUE) return base
    return accessibilityManager?.calculateRecommendedTimeoutMillis(
        base,
        containsIcons = true,
        containsText = true,
        containsControls = hasAction
    ) ?: base
}
