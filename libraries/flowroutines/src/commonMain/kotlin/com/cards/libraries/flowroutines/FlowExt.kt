package com.dangerfield.cards.libraries.flowroutines

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Collects the flow and calls the collector with the previous and current value.
 * Useful when you want to compare the previous and current value.
 */
suspend fun <T : Any> Flow<T>.collect(collector: suspend (previous: T?, current: T) -> Unit) {
    var previous: T? = null
    collect {
        collector(previous, it)
        previous = it
    }
}

suspend fun <T> Flow<T>.collectWithPrevious(action: suspend (previous: T?, current: T) -> Unit) {
    var previousValue: T? = null
    return this.onEach { currentValue ->
        action(previousValue, currentValue)
        previousValue = currentValue
    }.collect()
}

fun <T> Flow<T>.collectWithPreviousIn(scope: CoroutineScope, action: suspend (previous: T?, current: T) -> Unit): Job {
    var previousValue: T? = null
    return collectIn(scope) { currentValue ->
        action(previousValue, currentValue)
        previousValue = currentValue
    }
}

//@Composable
//fun <T> Flow<T>.collectWithPreviousAsStateWithLifecycle(
//    initialValue: T,
//    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
//    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
//    context: CoroutineContext = EmptyCoroutineContext
//): State<Pair<T?, T>> {
//    val initial: Pair<T?, T> = (null to initialValue)
//    return produceState(initial, this, lifecycleOwner.lifecycle, minActiveState, context) {
//
//        lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
//            if (context == EmptyCoroutineContext) {
//
//                this@collectWithPreviousAsStateWithLifecycle.collectWithPrevious { previous, current ->
//                    this@produceState.value = previous to current
//                }
//            } else withContext(context) {
//                this@collectWithPreviousAsStateWithLifecycle.collectWithPrevious { previous, current ->
//                    this@produceState.value = previous to current
//                }
//            }
//        }
//    }
//}


@Composable
fun <T> Flow<T>.collectWithPreviousAsStateWithLifecycle(
    initialValue: T,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext
): State<Pair<T?, T>> {
    val initial: Pair<T?, T> = (null to initialValue)
    return produceState(initial, this, lifecycleOwner.lifecycle, minActiveState, context) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(minActiveState) {
            withContext(context) {
                this@collectWithPreviousAsStateWithLifecycle.collectWithPrevious { previous, current ->
                    this@produceState.value = previous to current
                }
            }
        }
    }
}

fun <T> Flow<T>.collectIn(scope: CoroutineScope, collector: FlowCollector<T>): Job =
    scope.launch { collect(collector) }

suspend fun <T> Flow<T>.waitFor(preditcate: (T) -> Boolean): T {
    return this.first { preditcate(it) }
}

fun <T> Flow<T>.onCollection(job: suspend kotlinx.coroutines.channels.ProducerScope<T>.() -> Unit) =
    channelFlow {
        launch {
            ProducerScope<T>(this, this@channelFlow.channel).job()
        }
        collect(::send)
    }
        .buffer(Channel.RENDEZVOUS) // suspends each send until the previous one is received

private class ProducerScope<E>(
    scope: CoroutineScope,
    override val channel: SendChannel<E>,
) : kotlinx.coroutines.channels.ProducerScope<E>, CoroutineScope by scope, SendChannel<E> by channel
