package com.dangerfield.cards.features.profile.impl

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.onboarding.OnboardingRoute
import com.dangerfield.cards.features.onboarding.SignUpRoute
import com.dangerfield.cards.features.profile.BugReportRoute
import com.dangerfield.cards.features.profile.FeedbackRoute
import com.dangerfield.cards.features.profile.impl.account.AccountActionsAction
import com.dangerfield.cards.features.profile.impl.account.AccountActionsEvent
import com.dangerfield.cards.features.profile.impl.account.AccountActionsViewModel
import com.dangerfield.cards.features.profile.impl.account.ClaimAccountEvent
import com.dangerfield.cards.features.profile.impl.account.ClaimAccountScreen
import com.dangerfield.cards.features.profile.impl.account.ClaimAccountViewModel
import com.dangerfield.cards.features.profile.impl.account.DeleteAccountEvent
import com.dangerfield.cards.features.profile.impl.account.DeleteAccountScreen
import com.dangerfield.cards.features.profile.impl.account.DeleteAccountViewModel
import com.dangerfield.cards.features.profile.impl.bugreport.BugReportScreen
import com.dangerfield.cards.features.profile.impl.bugreport.BugReportViewModel
import com.dangerfield.cards.features.profile.impl.edit.EditProfileEvent
import com.dangerfield.cards.features.profile.impl.edit.EditProfileScreen
import com.dangerfield.cards.features.profile.impl.edit.EditProfileViewModel
import com.dangerfield.cards.features.profile.impl.feedback.FeedbackScreen
import com.dangerfield.cards.features.profile.impl.feedback.FeedbackViewModel
import com.dangerfield.cards.features.profile.impl.items.MyItemsScreen
import com.dangerfield.cards.features.profile.impl.items.MyItemsViewModel
import com.dangerfield.cards.features.profile.impl.notifications.NotificationsScreen
import com.dangerfield.cards.features.profile.ClaimAccountRoute
import com.dangerfield.cards.features.profile.DeleteAccountRoute
import com.dangerfield.cards.features.profile.EditProfileRoute
import com.dangerfield.cards.features.profile.MyItemsRoute
import com.dangerfield.cards.features.profile.NotificationsRoute
import com.dangerfield.cards.features.profile.ProfileRoute
import com.dangerfield.cards.features.profile.QaMenuRoute
import com.dangerfield.cards.features.progression.RankDetailSheetRoute
import com.dangerfield.cards.features.progression.StatsRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.config.AppConfigRepository
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.flowroutines.ObserveEvents
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Profile + every sub-route reachable from it (edit profile, my items,
 * delete account, claim account, feedback, bug report, QA menu). Bot speed
 * + turn-feedback toggles live inline on [ProfileScreen] — no sub-route.
 * Privacy + terms hand off to the system browser via [Router.openWebLink].
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class ProfileFeatureEntryPoint(
    private val appConfigRepository: AppConfigRepository,
    private val configOverrideRepository: ConfigOverrideRepository,
    private val progressionRepository: ProgressionRepository,
    private val feedbackViewModelFactory: () -> FeedbackViewModel,
    private val bugReportViewModelFactory: (logId: String?, errorCode: Int?, contextMessage: String?) -> BugReportViewModel,
    private val accountActionsViewModelFactory: () -> AccountActionsViewModel,
    private val deleteAccountViewModelFactory: () -> DeleteAccountViewModel,
    private val editProfileViewModelFactory: () -> EditProfileViewModel,
    private val claimAccountViewModelFactory: () -> ClaimAccountViewModel,
    private val myItemsViewModelFactory: () -> MyItemsViewModel,
    private val profileRepository: ProfileRepository,
    private val appCache: AppCache,
    private val userMessageRepository: UserMessageRepository,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<ProfileRoute> {
            val progression by progressionRepository.observeProgression()
                .collectAsStateWithLifecycle(initialValue = Progression.Empty)
            val unreadNotificationCount by userMessageRepository.observeUnreadInboxCount()
                .collectAsStateWithLifecycle(initialValue = 0)
            // Profile (display name + avatar + anon flag) is the canonical
            // source. `null` while ProfileRepository's first emission is
            // still resolving — the header renders with safe defaults
            // until it lands.
            val profile by profileRepository.observe()
                .collectAsStateWithLifecycle(initialValue = null)
            val authenticated = profile as? Profile.Authenticated
            val isAnon = authenticated?.isAnonymous ?: true
            val appData by appCache.updates.collectAsState(initial = AppData())
            val scope = rememberCoroutineScope()

            val accountActionsVm: AccountActionsViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel { accountActionsViewModelFactory() }
            val accountActionsState by accountActionsVm.stateFlow.collectAsStateWithLifecycle()

            accountActionsVm.ObserveEvents { event ->
                when (event) {
                    AccountActionsEvent.SignedOut -> router.navigate(
                        OnboardingRoute(),
                        NavigationOptions(launchSingleTop = true, clearBackStack = true),
                    )
                }
            }

            ProfileScreen(
                settings = ProfileSettings(
                    displayName = authenticated?.displayName ?: "You",
                    avatarEmoji = authenticated?.avatarEmoji,
                    avatarBackgroundColor = authenticated?.avatarBackgroundColor,
                    // Rank stays 0 ("Unranked") until the user claims their account
                    // and plays multiplayer — see docs/decisions.md (2026-05-14).
                    rank = if (isAnon) 0 else 1200,
                    xp = progression.totalXp,
                    isAnonymous = isAnon,
                    botSpeed = appData.botSpeed,
                    turnFeedback = appData.turnFeedback,
                    appVersion = "0.1.0",
                    unreadNotificationCount = unreadNotificationCount,
                    showQaMenu = BuildInfo.isDebug,
                ),
                onClaimAccount = { router.navigate(ClaimAccountRoute()) },
                onEditProfile = { router.navigate(EditProfileRoute()) },
                onOpenMyItems = { router.navigate(MyItemsRoute()) },
                onOpenNotifications = { router.navigate(NotificationsRoute()) },
                onBotSpeedChange = { speed ->
                    scope.launch { appCache.update { it.copy(botSpeed = speed) } }
                },
                onTurnFeedbackChange = { feedback ->
                    scope.launch { appCache.update { it.copy(turnFeedback = feedback) } }
                },
                onTapRank = { router.navigate(RankDetailSheetRoute()) },
                onTapXp = { router.navigate(StatsRoute()) },
                onSendFeedback = { router.navigate(FeedbackRoute()) },
                onReportBug = { router.navigate(BugReportRoute()) },
                onPrivacyPolicy = { router.openWebLink(PRIVACY_POLICY_URL) },
                onTermsOfService = { router.openWebLink(TERMS_OF_SERVICE_URL) },
                onDeleteAccount = { router.navigate(DeleteAccountRoute()) },
                onSignOut = { accountActionsVm.takeAction(AccountActionsAction.ConfirmSignOut) },
                isSigningOut = accountActionsState.isSigningOut,
                onOpenQaMenu = { router.navigate(QaMenuRoute()) },
            )
        }

        screen<QaMenuRoute> {
            val profile by profileRepository.observe()
                .collectAsStateWithLifecycle(initialValue = null)
            val userId = (profile as? Profile.Authenticated)?.id
            QaMenuScreen(
                configStream = appConfigRepository.configStream(),
                initialConfig = appConfigRepository.config(),
                overrideRepository = configOverrideRepository,
                onBack = { router.goBack() },
                userId = userId,
            )
        }

        screen<EditProfileRoute> {
            val viewModel: EditProfileViewModel = viewModel { editProfileViewModelFactory() }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()
            viewModel.ObserveEvents { event ->
                when (event) {
                    EditProfileEvent.Saved -> router.goBack()
                }
            }
            EditProfileScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
                onNavigateToShop = { productId ->
                    router.switchTab(ShopRoute(pendingProductId = productId))
                },
            )
        }

        screen<DeleteAccountRoute> {
            val viewModel: DeleteAccountViewModel = viewModel { deleteAccountViewModelFactory() }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()
            viewModel.ObserveEvents { event ->
                when (event) {
                    DeleteAccountEvent.Deleted -> router.navigate(
                        OnboardingRoute(),
                        NavigationOptions(launchSingleTop = true, clearBackStack = true),
                    )
                }
            }
            DeleteAccountScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
            )
        }

        screen<NotificationsRoute> {
            NotificationsScreen(
                repository = userMessageRepository,
                onBack = { router.goBack() },
                onDeepLinkTap = { url -> router.openWebLink(url) },
            )
        }

        screen<MyItemsRoute> {
            val viewModel: MyItemsViewModel = viewModel { myItemsViewModelFactory() }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()
            MyItemsScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
            )
        }

        screen<ClaimAccountRoute> {
            val viewModel: ClaimAccountViewModel = viewModel { claimAccountViewModelFactory() }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()
            viewModel.ObserveEvents { event ->
                when (event) {
                    ClaimAccountEvent.Claimed -> router.goBack()
                    ClaimAccountEvent.SwitchedAccounts -> router.navigate(
                        OnboardingRoute(),
                        NavigationOptions(launchSingleTop = true, clearBackStack = true),
                    )
                }
            }
            ClaimAccountScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
                onContinueWithEmail = { router.navigate(SignUpRoute()) },
            )
        }


        screen<FeedbackRoute> {
            val viewModel: FeedbackViewModel = viewModel { feedbackViewModelFactory() }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
            FeedbackScreen(
                state = state,
                onAction = viewModel::takeAction,
            )
        }

        screen<BugReportRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<BugReportRoute>()
            val viewModel: BugReportViewModel = viewModel {
                bugReportViewModelFactory(route.logId, route.errorCode, route.contextMessage)
            }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
            BugReportScreen(
                state = state,
                onAction = viewModel::takeAction,
            )
        }
    }

    private companion object {
        const val PRIVACY_POLICY_URL = "https://cards.dangerfield.com/privacy"
        const val TERMS_OF_SERVICE_URL = "https://cards.dangerfield.com/terms"
    }
}
