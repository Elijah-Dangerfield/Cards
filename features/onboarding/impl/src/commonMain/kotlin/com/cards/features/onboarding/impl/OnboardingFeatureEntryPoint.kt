package com.dangerfield.cards.features.onboarding.impl

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.onboarding.OnboardingRoute
import com.dangerfield.cards.features.onboarding.SignInRoute
import com.dangerfield.cards.features.onboarding.SignUpRoute
import com.dangerfield.cards.features.onboarding.VerifyEmailRoute
import com.dangerfield.cards.libraries.flowroutines.ObserveEvents
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class OnboardingFeatureEntryPoint(
    private val onboardingViewModelFactory: () -> OnboardingViewModel,
    private val signInViewModelFactory: () -> SignInViewModel,
    private val signUpViewModelFactory: () -> SignUpViewModel,
    private val verifyEmailViewModelFactory: (email: String) -> VerifyEmailViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<OnboardingRoute> {
            val viewModel: OnboardingViewModel = viewModel { onboardingViewModelFactory() }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value

            viewModel.ObserveEvents { event ->
                when (event) {
                    OnboardingEvent.NavigateToHome -> router.enterTab(HomeRoute())
                }
            }

            OnboardingScreen(
                state = state,
                onAction = viewModel::takeAction,
                onSignIn = { router.navigate(SignInRoute()) },
                onComplete = {},
            )
        }

        screen<SignInRoute> {
            val viewModel: SignInViewModel = viewModel { signInViewModelFactory() }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value

            viewModel.ObserveEvents { event ->
                when (event) {
                    SignInEvent.NavigateToHome -> router.enterTab(HomeRoute())
                    is SignInEvent.NavigateToVerifyEmail -> router.navigate(
                        VerifyEmailRoute(event.email),
                    )
                }
            }

            SignInScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
                onCreateAccount = { router.navigate(SignUpRoute()) },
            )
        }

        screen<SignUpRoute> {
            val viewModel: SignUpViewModel = viewModel { signUpViewModelFactory() }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value

            viewModel.ObserveEvents { event ->
                when (event) {
                    is SignUpEvent.NavigateToVerifyEmail -> router.navigate(
                        VerifyEmailRoute(event.email),
                    )
                }
            }

            SignUpScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
                onSignIn = { router.goBack() },
            )
        }

        screen<VerifyEmailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<VerifyEmailRoute>()
            val viewModel: VerifyEmailViewModel = viewModel { verifyEmailViewModelFactory(route.email) }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value

            viewModel.ObserveEvents { event ->
                when (event) {
                    VerifyEmailEvent.NavigateToHome -> router.enterTab(HomeRoute())
                    VerifyEmailEvent.NavigateBackToSignIn -> router.navigate(
                        SignInRoute(),
                        NavigationOptions(launchSingleTop = true, clearBackStack = true),
                    )
                }
            }

            VerifyEmailScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
            )
        }
    }
}
