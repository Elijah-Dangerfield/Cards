package com.dangerfield.cards

import com.dangerfield.cards.libraries.core.ShakeDetector
import com.dangerfield.cards.libraries.core.ShakeEvent
import com.dangerfield.cards.libraries.core.ShakeMessageContext
import com.dangerfield.cards.libraries.core.ShakeMessageProvider
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.ShakeDialogRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
class ShakeHandler(
    private val shakeDetector: ShakeDetector,
    private val shakeMessageProvider: ShakeMessageProvider,
    private val profileRepository: ProfileRepository,
    private val router: Router,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isShowingDialog = false

    fun start() {
        shakeDetector.start()
        scope.launch {
            shakeDetector.shakeEvents.collect { event ->
                handleShake(event)
            }
        }
    }

    fun stop() {
        shakeDetector.stop()
    }

    fun onDialogDismissed() {
        isShowingDialog = false
    }

    private suspend fun handleShake(event: ShakeEvent) {
        if (isShowingDialog) return

        // Pull the display name from the profile (server-authoritative).
        // `shakeCount` / `isFirstSession` are no longer tracked — every
        // shake uses the "first shake" pool, which has enough variety on
        // its own. See ShakeMessageProviderImpl's branch table.
        val displayName = (profileRepository.current() as? Profile.Authenticated)?.displayName

        val context = ShakeMessageContext(
            intensity = event.intensity,
            isLateNight = false,
            userName = displayName,
        )

        val message = shakeMessageProvider.getMessage(context)

        isShowingDialog = true
        router.navigate(
            ShakeDialogRoute(
                headline = message.headline,
                subtext = message.subtext,
            )
        )
    }
}
