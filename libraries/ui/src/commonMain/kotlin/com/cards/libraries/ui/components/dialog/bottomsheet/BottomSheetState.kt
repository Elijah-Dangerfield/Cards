package com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
@OptIn(ExperimentalMaterial3Api::class)
class BottomSheetState(
    initialValue: BottomSheetValue,
    density: Density,
    internal var confirmValueChange: (BottomSheetValue) -> Boolean = { true },
    skipPartiallyExpanded: Boolean = true,
    skipHiddenState: Boolean = false,
) {
    internal val materialSheetStateDelegate = SheetState(
        skipPartiallyExpanded = skipPartiallyExpanded,
        initialValue = initialValue.materialValue,
        confirmValueChange = { confirmValueChange(it.toBottomSheetValue()) },
        skipHiddenState = skipHiddenState,
        positionalThreshold = { with(density) { BottomSheetDefaults.PositionalThreshold.toPx() } },
        velocityThreshold = { with(density) { BottomSheetDefaults.VelocityThreshold.toPx() } }
    )

    val currentValue: BottomSheetValue
        get() = materialSheetStateDelegate.currentValue.toBottomSheetValue()

    val targetValue: BottomSheetValue
        get() = materialSheetStateDelegate.targetValue.toBottomSheetValue()

    val isVisible: Boolean
        get() = materialSheetStateDelegate.isVisible

    val offset: Float
        get() = materialSheetStateDelegate.requireOffset()

    suspend fun show() = materialSheetStateDelegate.show()
    suspend fun hide() = materialSheetStateDelegate.hide()

    private var dismissalScope: CoroutineScope? = null
    private var onDismissComplete: (() -> Unit)? = null
    private var dismissJob: Job? = null

    internal fun attachDismissController(
        scope: CoroutineScope,
        onDismissComplete: () -> Unit
    ) {
        dismissalScope = scope
        this.onDismissComplete = onDismissComplete
    }

    internal fun detachDismissController(scope: CoroutineScope) {
        if (dismissalScope == scope) {
            dismissalScope = null
            onDismissComplete = null
            dismissJob?.cancel()
            dismissJob = null
        }
    }

    fun dismiss() {
        val completion = onDismissComplete ?: return
        val scope = dismissalScope ?: return

        if (!isVisible) {
            completion()
            return
        }

        if (dismissJob?.isActive == true) return

        dismissJob = scope.launch {
            try {
                materialSheetStateDelegate.hide()
                completion()
            } finally {
                dismissJob = null
            }
        }
    }

    companion object {
        @Suppress("FunctionNaming")
        fun Saver(confirmValueChange: (BottomSheetValue) -> Boolean, density: Density): Saver<com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetState, *> =
            Saver(
                save = { it.currentValue },
                restore = {
                    BottomSheetState(
                        it,
                        density,
                        confirmValueChange,
                    )
                }
            )
    }
}

@Composable
fun rememberDestinationBottomSheetState(
    destinationId: String,
    initialState: BottomSheetValue = if (LocalInspectionMode.current) BottomSheetValue.Expanded else BottomSheetValue.Hidden,
    confirmValueChange: (BottomSheetValue) -> Boolean = { true },
    skipPartiallyExpanded: Boolean = initialState != BottomSheetValue.PartiallyExpanded,
    skipHiddenState: Boolean = false,
    density: Density = LocalDensity.current,
) = rememberSaveable(destinationId, saver = BottomSheetState.Saver(confirmValueChange, density)) {
    BottomSheetState(
        initialState,
        density,
        confirmValueChange,
        skipPartiallyExpanded = skipPartiallyExpanded,
        skipHiddenState = skipHiddenState
    )
}


@Composable
fun rememberBottomSheetState(
    initialState: BottomSheetValue = if (LocalInspectionMode.current) BottomSheetValue.Expanded else BottomSheetValue.Hidden,
    confirmValueChange: (BottomSheetValue) -> Boolean = { true },
    skipPartiallyExpanded: Boolean = initialState != BottomSheetValue.PartiallyExpanded,
    skipHiddenState: Boolean = false,
    density: Density = LocalDensity.current,
) = rememberSaveable(saver = BottomSheetState.Saver(confirmValueChange, density)) {
    BottomSheetState(
        initialState,
        density,
        confirmValueChange,
        skipPartiallyExpanded = skipPartiallyExpanded,
        skipHiddenState = skipHiddenState
    )
}
