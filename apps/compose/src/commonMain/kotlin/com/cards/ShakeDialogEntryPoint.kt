package com.dangerfield.cards

import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.profile.FeedbackRoute
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.ShakeDialogRoute
import com.dangerfield.cards.libraries.navigation.dialog
import com.dangerfield.cards.libraries.networking.NetworkInspector
import com.dangerfield.cards.libraries.ui.components.dialog.ShakeDialog
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class ShakeDialogEntryPoint(
    private val shakeHandler: ShakeHandler,
    private val networkInspector: NetworkInspector,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        dialog<ShakeDialogRoute> { _, dialogState ->
            // Reset the shake gate when the dialog leaves composition.
            // Without this, ShakeHandler keeps `isShowingDialog = true`
            // forever and every subsequent shake is a no-op.
            DisposableEffect(Unit) {
                onDispose { shakeHandler.onDialogDismissed() }
            }

            ShakeDialog(
                state = dialogState,
                onDismiss = { router.goBack() },
                onSendFeedback = {
                    router.goBack()
                    router.navigate(FeedbackRoute())
                },
                // Debug-only: reuse the shake gesture to also open the
                // WiretapKMP network inspector. Hidden in release (and the
                // inspector itself is the noop there).
                onOpenNetworkInspector = if (BuildInfo.isDebug) {
                    {
                        router.goBack()
                        networkInspector.open()
                    }
                } else {
                    null
                },
            )
        }
    }
}
