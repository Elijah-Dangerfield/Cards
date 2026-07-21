package com.dangerfield.cards

import com.dangerfield.cards.features.profile.QaMenuRoute
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.ShakeDetector
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.navigation.NavigationOptions
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
                if (BuildInfo.isDebug || BuildInfo.isTestFlight) {
                    // Non-production (debug + TestFlight): shake jumps straight
                    // to the QA menu. launchSingleTop so a second shake while
                    // it's open doesn't stack another copy — no isShowingDialog
                    // gate needed here (that gate is reset by the shake dialog's
                    // onDispose, which the QA screen never triggers).
                    router.navigate(QaMenuRoute(), NavigationOptions(launchSingleTop = true))
                } else {
                    // Production (App Store): the classic feedback dialog it has
                    // always shown. Gated so a re-shake can't stack a second one.
                    if (isShowingDialog) return@collect
                    isShowingDialog = true
                    router.navigate(ShakeDialogRoute())
                }
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
