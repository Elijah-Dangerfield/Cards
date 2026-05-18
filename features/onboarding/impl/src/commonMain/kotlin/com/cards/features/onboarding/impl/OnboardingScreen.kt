package com.dangerfield.cards.features.onboarding.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import kotlinx.coroutines.launch

/**
 * Three-page intro pager + a "Sign in" affordance in the top-right that
 * opens a "Coming Soon" sheet (Apple/Google claim ships in Phase 3.1; the
 * affordance exists in V1 to set expectations).
 *
 * Pager pages: hardcoded — keep V1 small and predictable. When the
 * content needs to be localized + AB-testable, lift the slides into a
 * `:libraries:config` value.
 *
 * Final page "Get Started" calls the VM, which posts /v1/identity, flips
 * `hasUserOnboarded`, and emits [OnboardingEvent.NavigateToHome].
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
    onSignIn: () -> Unit,
    onComplete: () -> Unit,
) {
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OnboardingPagerContent(
                state = state,
                onAction = onAction,
                onSignIn = onSignIn,
                onComplete = onComplete,
            )
        }
    }
}

@Composable
private fun OnboardingPagerContent(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
    onSignIn: () -> Unit,
    onComplete: () -> Unit,
) {
    val slides = remember { OnboardingSlide.all }
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(onSignIn = onSignIn)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            SlideContent(slide = slides[page])
        }

        PageIndicator(
            count = slides.size,
            currentIndex = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimension.D400),
        )

        BottomCta(
            isLastPage = pagerState.currentPage == slides.lastIndex,
            isInitializing = state.isInitializing,
            error = state.error,
            onNext = {
                scope.launch {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                }
            },
            onFinish = { onAction(OnboardingAction.Finish) },
            onDismissError = { onAction(OnboardingAction.DismissError) },
        )
    }

    // `onComplete` kept on the contract for future hooks (analytics,
    // confetti, etc.) — referenced here so the lint doesn't bark.
    @Suppress("UNUSED_EXPRESSION")
    onComplete
}

@Composable
private fun TopBar(onSignIn: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimension.D400, vertical = Dimension.D200),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onSignIn,
            style = ButtonStyle.Text,
        ) {
            Text(text = "Sign in")
        }
    }
}

@Composable
private fun SlideContent(slide: OnboardingSlide) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimension.D800),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = slide.emoji,
            typography = AppTheme.typography.Display.D900,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Dimension.D800))
        Text(
            text = slide.title,
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.onSurfacePrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Dimension.D400))
        Text(
            text = slide.body,
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PageIndicator(
    count: Int,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val isActive = index == currentIndex
            Surface(
                shape = CircleShape,
                color = if (isActive) {
                    AppTheme.colors.onSurfacePrimary.color
                } else {
                    AppTheme.colors.surfaceTertiary.color
                },
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (isActive) 10.dp else 8.dp),
            ) {}
        }
    }
}

@Composable
private fun BottomCta(
    isLastPage: Boolean,
    isInitializing: Boolean,
    error: String?,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimension.D800, vertical = Dimension.D400),
    ) {
        if (error != null) {
            Text(
                text = error,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Dimension.D200))
        }

        Button(
            onClick = {
                when {
                    error != null -> {
                        onDismissError()
                        onFinish()
                    }
                    isLastPage -> onFinish()
                    else -> onNext()
                }
            },
            enabled = !isInitializing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label = when {
                isInitializing -> "One moment…"
                error != null -> "Try again"
                isLastPage -> "Get started"
                else -> "Next"
            }
            Text(text = label)
        }
    }
}

/** The three slides we ship in V1. Hardcoded English; localize when we localize the app. */
internal data class OnboardingSlide(
    val emoji: String,
    val title: String,
    val body: String,
) {
    companion object {
        val all: List<OnboardingSlide> = listOf(
            OnboardingSlide(
                emoji = "🃏",
                title = "Welcome to Cards",
                body = "No-limit Texas Hold'em poker, built for friends and quick sessions against sharp bots.",
            ),
            OnboardingSlide(
                emoji = "🤝",
                title = "Play with friends",
                body = "Spin up a private table, share a 6-letter code, and deal hands together. Anywhere.",
            ),
            OnboardingSlide(
                emoji = "🏆",
                title = "Earn chips, climb ranks",
                body = "Every hand pushes XP up. Win pots, unlock cosmetics, and grow your stack over time.",
            ),
        )
    }
}
