package com.dangerfield.cards.features.profile.impl

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.profile.ProfileRoute
import com.dangerfield.cards.features.profile.QaMenuRoute
import com.dangerfield.cards.features.progression.RankDetailSheetRoute
import com.dangerfield.cards.features.progression.XpDetailSheetRoute
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.UserRepository
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
    private val userRepository: UserRepository,
) : FeatureEntryPoint {
    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<ProfileRoute> {
            val progression by progressionRepository.observeProgression()
                .collectAsStateWithLifecycle(initialValue = Progression.Empty)
            val user by userRepository.observeUser()
                .collectAsStateWithLifecycle(initialValue = null)
            val isAnon = user?.isAnonymous ?: true

            ProfileScreen(
                settings = ProfileSettings(
                    displayName = user?.name ?: "You",
                    handle = "anon-1742",
                    // Rank stays 0 ("Unranked") until the user claims their account
                    // and plays multiplayer — see docs/decisions.md (2026-05-14).
                    rank = if (isAnon) 0 else 1200,
                    xp = progression.totalXp,
                    handsPlayed = progression.handsPlayed,
                    isAnonymous = isAnon,
                    gameplaySpeed = GameplaySpeed.Normal,
                    appVersion = "0.1.0",
                    showQaMenu = BuildInfo.isDebug,
                ),
                onClaimAccount = {},
                onEditProfile = {},
                onChangeGameplaySpeed = {},
                onTapRank = { router.navigate(RankDetailSheetRoute()) },
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
