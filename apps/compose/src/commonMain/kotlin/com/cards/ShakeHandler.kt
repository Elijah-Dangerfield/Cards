package com.dangerfield.cards

import com.dangerfield.cards.libraries.core.ShakeDetector
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.ShakeDialogRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
class ShakeHandler(
    private val shakeDetector: ShakeDetector,
    private val router: Router,
    dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private var isShowingDialog = false

    fun start() {
        shakeDetector.start()
        scope.launch {
            shakeDetector.shakeEvents.collect {
                if (isShowingDialog) return@collect
                isShowingDialog = true
                router.navigate(ShakeDialogRoute())
            }
        }
    }

    fun stop() {
        shakeDetector.stop()
    }

    // Called by `ShakeDialogEntryPoint` when the dialog leaves composition,
    // so the next shake can open it again. Without this hook the gate stays
    // closed for the rest of the process lifetime.
    fun onDialogDismissed() {
        isShowingDialog = false
    }
}
