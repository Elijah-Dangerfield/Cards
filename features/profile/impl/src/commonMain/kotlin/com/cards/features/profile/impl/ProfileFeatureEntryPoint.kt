package com.dangerfield.cards.features.profile.impl

import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.profile.ProfileRoute
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
class ProfileFeatureEntryPoint : FeatureEntryPoint {
    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<ProfileRoute> {
            ProfileScreen(
                settings = ProfileSettings(
                    displayName = "You",
                    handle = "anon-1742",
                    rank = 1200,
                    handsPlayed = 0,
                    isAnonymous = true,
                    gameplaySpeed = GameplaySpeed.Normal,
                    appVersion = "0.1.0",
                ),
                onClaimAccount = {},
                onEditProfile = {},
                onChangeGameplaySpeed = {},
                onSendFeedback = {},
                onReportBug = {},
                onPrivacyPolicy = {},
                onTermsOfService = {},
                onDeleteAccount = {},
            )
        }
    }
}
