package com.dangerfield.cards.libraries.flowroutines

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Observe a flow with the lifecycle of the current composable
 * @param flow the flow to observe
 * @param onItem the action to take when an item is emitted from the flow
 *
 * Observes on main immediate which ensures no emissions are missed
 */
@Composable
fun <T> ObserveWithLifecycle(flow: Flow<T>,  onItem: suspend (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner.lifecycle, flow) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                flow.collect(onItem)
            }
        }
    }
}

@Composable
fun <T : Any> SEAViewModel<*, T, *>.ObserveEvents(onItem: suspend (T) -> Unit) {
    ObserveWithLifecycle(eventFlow, onItem = onItem)
}

/**
 * Observe a flow with the provided lifecycle and scope
 * @param onItem the action to take when an item is emitted from the flow
 *
 * starts collection when the lifecycle reaches the started state,
 * stops collection when the lifecycle falls below the started state,
 *
 * Observes on main immediate which ensures no emissions are missed
 */
 fun <T> Flow<T>.observeWithLifecycleIn(
    lifecycleOwner: Lifecycle,
    scope: CoroutineScope,
    onItem: suspend (T) -> Unit
) {
    scope.launch {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                collect(onItem)
            }
        }
    }
}

/**
 * Observe a flow with the provided lifecycle
 * @param onItem the action to take when an item is emitted from the flow
 *
 * starts collection when the lifecycle reaches the started state,
 * stops collection when the lifecycle falls below the started state,
 *
 * Observes on main immediate which ensures no emissions are missed
 */
suspend fun <T> Flow<T>.observeWithLifecycle(
    state: Lifecycle.State = Lifecycle.State.STARTED,
    lifecycle: Lifecycle,
    onItem: suspend (T) -> Unit
) {
    lifecycle.repeatOnLifecycle(state) {
        withContext(Dispatchers.Main.immediate) {
            collect(onItem)
        }
    }
}