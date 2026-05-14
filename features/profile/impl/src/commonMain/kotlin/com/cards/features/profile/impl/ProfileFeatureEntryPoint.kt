package com.dangerfield.cards.features.profile.impl

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.home.FeedbackRoute
import com.dangerfield.cards.features.profile.BugReportRoute
import com.dangerfield.cards.features.profile.ClaimAccountRoute
import com.dangerfield.cards.features.profile.DeleteAccountRoute
import com.dangerfield.cards.features.profile.EditProfileRoute
import com.dangerfield.cards.features.profile.GameplaySpeedRoute
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

/**
 * Profile + every sub-route reachable from it. Sub-routes that aren't built
 * yet (edit profile, gameplay speed, delete account, claim account) register
 * a [PlaceholderScreen] so navigation works end-to-end. Privacy + terms hand
 * off to the system browser via [Router.openWebLink].
 */
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
                onClaimAccount = { router.navigate(ClaimAccountRoute()) },
                onEditProfile = { router.navigate(EditProfileRoute()) },
                onChangeGameplaySpeed = { router.navigate(GameplaySpeedRoute()) },
                onTapRank = { router.navigate(RankDetailSheetRoute()) },
                onTapXp = { router.navigate(XpDetailSheetRoute()) },
                onSendFeedback = { router.navigate(FeedbackRoute()) },
                onReportBug = { router.navigate(BugReportRoute()) },
                onPrivacyPolicy = { router.openWebLink(PRIVACY_POLICY_URL) },
                onTermsOfService = { router.openWebLink(TERMS_OF_SERVICE_URL) },
                onDeleteAccount = { router.navigate(DeleteAccountRoute()) },
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

        screen<EditProfileRoute> {
            PlaceholderScreen(
                title = "Edit profile",
                body = "Choose a display name and avatar. Lands with Phase 3 auth — for now your handle stays anonymous.",
                onBack = { router.goBack() },
            )
        }

        screen<GameplaySpeedRoute> {
            PlaceholderScreen(
                title = "Gameplay speed",
                body = "Pick how fast cards and chips animate. Coming soon — for now it's locked to Normal.",
                onBack = { router.goBack() },
            )
        }

        screen<DeleteAccountRoute> {
            PlaceholderScreen(
                title = "Delete account",
                body = "Wipe your local data and start fresh. Lands with Phase 3 auth so we can clear server-side state too.",
                onBack = { router.goBack() },
            )
        }

        screen<ClaimAccountRoute> {
            PlaceholderScreen(
                title = "Claim your account",
                body = "Sign in with Apple or Google to save your XP and chips across devices, and unlock multiplayer. Coming with Phase 3 auth.",
                onBack = { router.goBack() },
            )
        }
    }

    private companion object {
        const val PRIVACY_POLICY_URL = "https://cards.dangerfield.com/privacy"
        const val TERMS_OF_SERVICE_URL = "https://cards.dangerfield.com/terms"
    }
}
