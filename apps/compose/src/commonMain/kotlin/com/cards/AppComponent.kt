package com.dangerfield.cards

import com.dangerfield.cards.libraries.cards.InAppMessageManager
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.cards.impl.AppEventDispatcher
import com.dangerfield.cards.libraries.config.AppConfigFlow
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.config.EnsureAppConfigLoaded
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.navigation.DeepLinkBridge
import com.dangerfield.cards.libraries.navigation.impl.DelegatingRouter
import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

@ContributesTo(AppScope::class)
@SingleIn(AppScope::class)
interface AppComponent {
    val featureEntryPoints: Set<FeatureEntryPoint>
    val appViewModel: AppViewModel  // Singleton, shared between MainActivity and App
    val delegatingRouter: DelegatingRouter
    val telemetry: Telemetry
    val shakeHandler: ShakeHandler
    val deepLinkBridge: DeepLinkBridge
    val appConfigFlow: AppConfigFlow
    val ensureAppConfigLoaded: EnsureAppConfigLoaded
    val configOverrideRepository: ConfigOverrideRepository
    val userMessageRepository: UserMessageRepository
    val profileRepository: ProfileRepository
    val inAppMessageManager: InAppMessageManager
    val appState: AppState

    /**
     * Eagerly initialized to start observing app lifecycle events.
     * This ensures sessions are created on foreground entry.
     */
    val appEventDispatcher: AppEventDispatcher

    @Provides
    fun provideClock(): Clock = Clock.System

}
