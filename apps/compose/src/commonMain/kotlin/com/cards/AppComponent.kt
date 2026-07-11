package com.dangerfield.cards

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.InAppMessageManager
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.cards.XpBoostRepository
import com.dangerfield.cards.libraries.config.AppConfigFlow
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.config.EnsureAppConfigLoaded
import com.dangerfield.cards.libraries.config.QaConfigValue
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.navigation.DeepLinkBridge
import com.dangerfield.cards.libraries.products.ShopBadgeStateRepository
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
    val chipsRepository: ChipsRepository
    val shopBadgeStateRepository: ShopBadgeStateRepository
    val xpBoostRepository: XpBoostRepository
    val inAppMessageManager: InAppMessageManager
    val appState: AppState
    val authRepository: AuthRepository
    val guestAccountCreator: GuestAccountCreator
    val progressionConfig: ProgressionConfig
    val appCache: AppCache
    val dispatcherProvider: DispatcherProvider

    /**
     * Singletons that need to construct at app boot rather than lazily
     * on first injection. Anvil populates this set via the
     * `@ContributesBinding(... AutoInit::class, multibinding = true)`
     * annotation on each implementer — see [AutoInit] for the
     * contract and when to opt in.
     *
     * The set is resolved once on first composition in `App.kt`; the
     * act of resolving forces every contributor to construct, which
     * runs each implementer's `init {}` block. That's how
     * [com.dangerfield.cards.libraries.cards.impl.AppEventDispatcher]
     * registers its lifecycle listener at boot, how the products
     * catalog hydrates from disk before the Shop tab is touched,
     * etc.
     */
    val autoInits: Set<AutoInit>

    /**
     * Every configured value in the app, populated by anvil via the
     * `@ContributesBinding(AppScope::class, QaConfigValue::class, multibinding = true)`
     * annotation on each value. The QA menu iterates this set to list and
     * override config without any per-value wiring.
     */
    val configuredValues: Set<QaConfigValue>

    @Provides
    fun provideClock(): Clock = Clock.System

}
