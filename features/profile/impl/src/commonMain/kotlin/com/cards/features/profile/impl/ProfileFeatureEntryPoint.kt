package com.dangerfield.cards.features.profile.impl

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.profile.ProfileRoute
import com.dangerfield.cards.features.profile.QaMenuRoute
import com.dangerfield.cards.features.progression.XpDetailSheetRoute
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.config.AppConfigRepository
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class ProfileFeatureEntryPoint(
    private val appConfigRepository: AppConfigRepository,
    private val configOverrideRepository: ConfigOverrideRepository,
    private val progressionRepository: ProgressionRepository,
) : FeatureEntryPoint {
    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<ProfileRoute> {
            val progression by progressionRepository.observeProgression()
                .collectAsStateWithLifecycle(initialValue = Progression.Empty)

            ProfileScreen(
                settings = ProfileSettings(
                    displayName = "You",
                    handle = "anon-1742",
                    rank = 1200,
                    xp = progression.totalXp,
                    handsPlayed = progression.handsPlayed,
                    isAnonymous = true,
                    gameplaySpeed = GameplaySpeed.Normal,
                    appVersion = "0.1.0",
                    showQaMenu = BuildInfo.isDebug,
                ),
                onClaimAccount = {},
                onEditProfile = {},
                onChangeGameplaySpeed = {},
                onTapXp = { router.navigate(XpDetailSheetRoute()) },
                onSendFeedback = {},
                onReportBug = {},
                onPrivacyPolicy = {},
                onTermsOfService = {},
                onDeleteAccount = {},
                onOpenQaMenu = { router.navigate(QaMenuRoute()) },
            )
        }

        screen<QaMenuRoute> {
            QaMenuScreen(
                configStream = appConfigRepository.configStream(),
                initialConfig = appConfigRepository.config(),
                overrideRepository = configOverrideRepository,
                onBack = { router.goBack() },
            )
        }
    }
}
