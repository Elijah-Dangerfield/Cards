package com.dangerfield.cards.libraries.flowroutines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.ConcurrentHashMap
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.throwIfDebug
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class IllegalViewModelStateException(
    override val message: String?,
    override val cause: Throwable? = null
): Exception()


/**
 * A view model that revolves around State (S), Events (E) and Actions (A)
 * Encourages a unidirectional flow of data from actions to state.
 *
 * S - State - The state of the view. Should be an immutable class that represents the
 * current state of the view. Should represent view state, NOT view element state
 * See https://developer.android.com/topic/architecture/ui-layer/stateholders#elements-ui
 *
 * E - Event - Events are used to trigger one time events that should not be stored in the state.
 * Examples: Navigation, Showing a toast, etc...
 * Storing one time events in the state requires acknowledgment of the state from the view and can
 * lead to complications and bugs if not careful. Turns out to be easier to just roll with events in
 * a channel.
 *

 * A - Action - Actions are the only way to update state. They represent work to be done either user
 * triggered or from the view model itself.
 * Tips:
 * - If you need data to load on init, have the view model take an action in the init block.
 */
abstract class SEAViewModel<S : Any, E : Any, A : Any>(
    private val initialStateArg: S? = null,
) : ViewModel() {

    private val actions = Channel<A>(Channel.UNLIMITED)
    private val events = Channel<E>(Channel.UNLIMITED)
    private val actionDebouncer = ConcurrentHashMap<String, Channel<suspend (S) -> S>>()
    private val _initialState: S by lazy { initialState() }

    // Lazy so that we do not let initialState() from the child get called before the child is initialized
    private val mutableStateFlow: MutableStateFlow<S> by lazy {
        MutableStateFlow(_initialState)
    }

    /**
     * The flow exposing the state of the view model
     * Lazy so that Mutable State flow doesnt get created before it needs to be
     */
    val stateFlow: StateFlow<S> by lazy {
        mutableStateFlow.mapNotNull {
            Catching { mapEachState(it) }
                .logOnFailure()
                .throwIfDebug()
                .getOrNull()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = _initialState,
        )
    }

    /**
     * The flow exposing events from the view mode
     */
    val eventFlow = events.receiveAsFlow()

    /**
     * The current state value
     */
    val state: S get() = stateFlow.value

    init {
        viewModelScope.launch {
            for (action in actions) {
                handleAction(action = action)
            }
        }
    }

    /**
     * Submits an action to be handled by the view model.
     */
    fun takeAction(action: A) {
        actions.trySend(action)
    }

    /**
     * Updates the state of the view model.
     * This is the only way to update the state.
     *
     * Callers must have an action to update the state, this helps maintain UDF.
     */
    suspend fun A.updateState(f: suspend (S) -> S) {
        Catching {
            mutableStateFlow.update {
                val mappedValue = Catching { mapEachState(it) }.logOnFailure().throwIfDebug().getOrNull()
                f(mappedValue ?: it)
            }
        }
            .logOnFailure("Could not up state for: ${state::class.simpleName ?: "missing name"}")
            .throwIfDebug()
    }

    /**
     * Updates state in a debounced manner.
     *
     * The update will not happen until the debounce time has passed without another call to
     * `debounceUpdateState` from the same action type. Every update from the same action
     * type will reset the debounce timer.
     *
     * This can be useful if you have a bit of state that is updated frequently and there is
     * work to be done on each update. This can help prevent unnecessary work from being done.
     *
     * Examples:
     * - Search field state updates that trigger a network call.
     * - Typing in a form field that triggers validation. (prevent error spam if the user is still typing)
     */
    @OptIn(FlowPreview::class)
    suspend fun A.updateStateDebounced(duration: Duration = 1.seconds, f: suspend (S) -> S) {
        val actionIdentifier = this::class.simpleName ?: "Action missing"
        val debouncedChannel = actionDebouncer[actionIdentifier]

        if (debouncedChannel == null) {
            val channel = Channel<suspend (S) -> S>(Channel.UNLIMITED)

            actionDebouncer[actionIdentifier] = channel

            channel.receiveAsFlow()
                .debounce(duration.inWholeMilliseconds)
                .collectIn(viewModelScope) {
                    updateState(it)
                }

            channel.trySend(f)
        } else {
            debouncedChannel.trySend(f)
        }
    }

    /**
     * Sets the initial state to be emitted by the `states` flow.
     *
     * We use a function to force for lazy initialization of the state, allowing the view model to
     * define the initial state in a more flexible way.
     */
    protected open fun initialState(): S {
        return initialStateArg
            ?: throw IllegalStateException("Initial state must be passed in or overridden in the initialState function.")
    }

    /**
     * Events in this code base are synonymous with side effects. They are used to trigger
     * one time events that should not be stored in the state.
     *
     * Examples:
     * - Navigation
     * - Showing a toast
     * - etc...
     */
    fun sendEvent(event: E) {
        KLog.i("Sending event ${event::class.simpleName}")
        events.trySend(event)
    }

    /**
     * Adds a mapper to the state flow. This is useful for mapping the entire state to a different
     * state on each update.
     *
     * The mapping is called safely, if an error is thrown it will be silently caught, logged and
     * will not update the state.
     *
     * Child view models can also always expose their own state stream that maps over the parents
     * if that's preferred.
     *
     * ex:
     * ```
     * val stateStream: StateFlow<State> = this.states.map {
     *    it.copy(something = somethingElse)
     *    }.stateIn(viewModelScope, ...)
     * ```
     */
    protected open suspend fun mapEachState(
        state: S
    ): S {
        return state
    }

    /**
     * Function that ensures all children handle their actions
     *
     * This helps to ensure that the flow is unidirectional.
     *
     * View -> Action -> State -> View
     *
     * @param action the action to be handled
     */
    protected abstract suspend fun handleAction(action: A)
}

