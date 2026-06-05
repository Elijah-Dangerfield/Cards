package com.dangerfield.cards.features.onboarding.impl

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.isiOS
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.ui.components.AnimatedCountUpText
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.Card
import com.dangerfield.cards.libraries.ui.components.CardsFan
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.RotatingDial
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.StatusPill
import com.dangerfield.cards.libraries.ui.components.XpBadge
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.screenHorizontalInsets
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.onboarding_auth_error_debug_suffix
import cards.libraries.resources.generated.resources.onboarding_auth_error_guest_anonymous_disabled
import cards.libraries.resources.generated.resources.onboarding_auth_error_guest_captcha
import cards.libraries.resources.generated.resources.onboarding_auth_error_guest_failed
import cards.libraries.resources.generated.resources.onboarding_auth_error_guest_invalid_config
import cards.libraries.resources.generated.resources.onboarding_auth_error_oauth_failed
import cards.libraries.resources.generated.resources.onboarding_auth_error_oauth_network
import cards.libraries.resources.generated.resources.onboarding_auth_error_oauth_provider_not_enabled
import cards.libraries.resources.generated.resources.onboarding_step_progress
import cards.libraries.resources.generated.resources.onboarding_save_error_display_name_taken
import cards.libraries.resources.generated.resources.onboarding_save_error_invalid_display_name
import cards.libraries.resources.generated.resources.onboarding_how_card_chips_subtitle
import cards.libraries.resources.generated.resources.onboarding_how_card_chips_title
import cards.libraries.resources.generated.resources.onboarding_how_card_league_subtitle
import cards.libraries.resources.generated.resources.onboarding_how_card_league_title
import cards.libraries.resources.generated.resources.onboarding_how_card_play_subtitle
import cards.libraries.resources.generated.resources.onboarding_how_card_play_title
import cards.libraries.resources.generated.resources.onboarding_grant_chips_label
import cards.libraries.resources.generated.resources.onboarding_grant_cta
import cards.libraries.resources.generated.resources.onboarding_grant_eyebrow
import cards.libraries.resources.generated.resources.onboarding_grant_feature_friends_bots
import cards.libraries.resources.generated.resources.onboarding_grant_feature_play_money
import cards.libraries.resources.generated.resources.onboarding_grant_feature_tournaments
import cards.libraries.resources.generated.resources.onboarding_grant_footer
import cards.libraries.resources.generated.resources.onboarding_grant_offline_subtitle
import cards.libraries.resources.generated.resources.onboarding_grant_subtitle
import cards.libraries.resources.generated.resources.onboarding_how_continue_button
import cards.libraries.resources.generated.resources.onboarding_how_eyebrow
import cards.libraries.resources.generated.resources.onboarding_how_title
import cards.libraries.resources.generated.resources.onboarding_identity_avatar_placeholder
import cards.libraries.resources.generated.resources.onboarding_identity_continue_button
import cards.libraries.resources.generated.resources.onboarding_identity_edit_name_icon_desc
import cards.libraries.resources.generated.resources.onboarding_identity_more_packs_hint
import cards.libraries.resources.generated.resources.onboarding_identity_section_name
import cards.libraries.resources.generated.resources.onboarding_identity_section_pack
import cards.libraries.resources.generated.resources.onboarding_identity_subtitle
import cards.libraries.resources.generated.resources.onboarding_identity_title
import cards.libraries.resources.generated.resources.onboarding_welcome_continue_guest
import cards.libraries.resources.generated.resources.onboarding_welcome_continue_guest_progress
import cards.libraries.resources.generated.resources.onboarding_welcome_footer
import cards.libraries.resources.generated.resources.onboarding_welcome_sign_in
import cards.libraries.resources.generated.resources.onboarding_welcome_oauth_apple
import cards.libraries.resources.generated.resources.onboarding_welcome_oauth_google
import cards.libraries.resources.generated.resources.onboarding_welcome_oauth_in_flight
import cards.libraries.resources.generated.resources.onboarding_welcome_subtitle
import cards.libraries.resources.generated.resources.onboarding_welcome_title
import cards.libraries.resources.generated.resources.ui_top_bar_back_a11y
import org.jetbrains.compose.resources.stringResource

/**
 * Four-step onboarding flow driven by [OnboardingViewModel.state.step]:
 *   1. [WelcomeStep]   — guest / Apple / Google entry points
 *   2. [PickIdentityStep] — display name + starter-pack avatar picker
 *   3. [HowItWorksStep] — three-card explainer
 *   4. [StarterGrantStep] — celebratory starter chip-grant reveal
 *
 * The host owns the [Screen] shell + insets; each step is a pure
 * composable rendered inside it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
) {
    // System back (Android hardware/gesture, iOS swipe) mirrors the in-UI
    // Back button: it steps back through the flow and only falls through to
    // exit on the entry step, where there's nothing before it.
    BackHandler(enabled = state.step != OnboardingStep.Welcome) {
        onAction(OnboardingAction.Back)
    }
    
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(
                    paddingValues = padding,
                    includeHorizontalInsets = false,
                    includeImePadding = true,
                ),
        ) {
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    val forward = stepIndex(targetState) >= stepIndex(initialState)
                    val direction = if (forward) SlideDirection.Start else SlideDirection.End
                    (
                        fadeIn(tween(300, easing = LinearEasing)) +
                            slideIntoContainer(
                                animationSpec = tween(300, easing = EaseIn),
                                towards = direction,
                            )
                    ) togetherWith (
                        fadeOut(tween(300, easing = LinearEasing)) +
                            slideOutOfContainer(
                                animationSpec = tween(300, easing = EaseOut),
                                towards = direction,
                            )
                    )
                },
                label = "OnboardingStep",
            ) { step ->
                when (step) {
                    OnboardingStep.Welcome -> WelcomeStep(state, onAction)
                    OnboardingStep.PickIdentity -> PickIdentityStep(state, onAction)
                    OnboardingStep.HowItWorks -> HowItWorksStep(onAction)
                    OnboardingStep.StarterGrant -> StarterGrantStep(state, onAction)
                }
            }

            // The progress chip rides every counted step (see
            // CountedOnboardingSteps). The Welcome landing is the entry —
            // "not technically onboarding" — so it's the only step without a
            // "step N of N".
            if (state.step in CountedOnboardingSteps) {
                StepProgressChip(
                    step = state.step,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = Dimension.D300),
                )
            }
        }
    }
}

@Composable
private fun StepProgressChip(
    step: OnboardingStep,
    modifier: Modifier = Modifier,
) {
    // Position within the counted steps, 1-based. A step not in the list
    // never renders the chip (guarded at the call site), so the -1/+1 here
    // is only ever reached for a real member.
    val position = CountedOnboardingSteps.indexOf(step) + 1
    StatusPill(
        text = stringResource(
            Res.string.onboarding_step_progress,
            position,
            CountedOnboardingSteps.size,
        ),
        background = AppTheme.colors.surfaceHigh,
        foreground = AppTheme.colors.contentSecondary,
        typography = AppTheme.typography.Label.L300,
        modifier = modifier,
    )
}

// Single source of truth for the "step N of N" chip — both the denominator
// and each step's position derive from this list, so adding a page here
// keeps the counter accurate with no other edits. Ordered to match the
// flow. Welcome is the entry landing and is intentionally excluded; every
// other step (including the StarterGrant payoff) is counted.
private val CountedOnboardingSteps: List<OnboardingStep> = listOf(
    OnboardingStep.PickIdentity,
    OnboardingStep.HowItWorks,
    OnboardingStep.StarterGrant,
)

// ---------------------------------------------------------------------------
// Step 1 — Welcome
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeStep(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
) {
    // iOS hands off from the splash with cards already fanned, so the fan
    // animation would visually snap-and-replay. Android has no compose
    // splash, so animate the fan-out as the cards' entrance. Previews
    // skip the animation entirely and render the resting state so @Preview
    // pins are useful for design review.
    val inPreview = LocalInspectionMode.current
    // The intro reveal is a one-shot. Navigating to Sign in and back tears
    // this composable down and rebuilds it, so without a retained flag the
    // fan + content would re-animate from zero every return — the bottom
    // buttons vanishing for a beat before sliding back in. rememberSaveable
    // survives the nav round-trip, so on return we render the resting state.
    var hasRevealed by rememberSaveable { mutableStateOf(inPreview) }
    val initialFanProgress = if (hasRevealed || BuildInfo.isiOS()) 1f else 0f
    val initialReveal = if (hasRevealed) 1f else 0f
    val fanProgress = remember { Animatable(initialFanProgress) }
    val contentReveal = remember { Animatable(initialReveal) }
    LaunchedEffect(Unit) {
        if (hasRevealed) return@LaunchedEffect
        fanProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 700, easing = EaseOutCubic),
        )
        contentReveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 450, easing = EaseOutCubic),
        )
        hasRevealed = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenHorizontalInsets),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(0.8f))

        CardsFan(fanProgress = fanProgress.value)

        // Cards stay put; the rest of the page fades + slides in around
        // them. translationY uses the same EaseOutCubic curve as the fan
        // so the two motions feel like one continuous reveal.
        val reveal = contentReveal.value
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = reveal
                    translationY = (1f - reveal) * 24.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(Dimension.D1100))

            Text(
                text = stringResource(Res.string.onboarding_welcome_title),
                typography = AppTheme.typography.Display.D1300,
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.height(Dimension.D500))
            Text(
                text = stringResource(Res.string.onboarding_welcome_subtitle),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = reveal
                    translationY = (1f - reveal) * 24.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.authError?.let { error ->
                Text(
                    text = error.message(),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(Dimension.D400))
            }

            ButtonPrimary(
                onClick = { onAction(OnboardingAction.ContinueAsGuest) },
                enabled = !state.isAuthing && state.oauthInFlight == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.isAuthing) Res.string.onboarding_welcome_continue_guest_progress
                        else Res.string.onboarding_welcome_continue_guest,
                    ),
                )
            }

            if (state.showOAuthRow) {
                Spacer(modifier = Modifier.height(Dimension.D400))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimension.D400),
                ) {
                    if (state.appleEnabled) {
                        ButtonSecondary(
                            onClick = { onAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Apple)) },
                            enabled = state.oauthInFlight == null && !state.isAuthing,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (state.oauthInFlight == OAuthProvider.Apple) Res.string.onboarding_welcome_oauth_in_flight
                                    else Res.string.onboarding_welcome_oauth_apple,
                                ),
                            )
                        }
                    }
                    if (state.googleEnabled) {
                        ButtonSecondary(
                            onClick = { onAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Google)) },
                            enabled = state.oauthInFlight == null && !state.isAuthing,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (state.oauthInFlight == OAuthProvider.Google) Res.string.onboarding_welcome_oauth_in_flight
                                    else Res.string.onboarding_welcome_oauth_google,
                                ),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimension.D500))
            Text(
                text = stringResource(Res.string.onboarding_welcome_footer),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Dimension.D300))
            Button(
                onClick = { onAction(OnboardingAction.SignIn) },
                type = ButtonType.Ghost,
                style = ButtonStyle.Text,
                enabled = !state.isAuthing && state.oauthInFlight == null,
            ) {
                Text(stringResource(Res.string.onboarding_welcome_sign_in))
            }
            Spacer(modifier = Modifier.height(Dimension.D700))
        }
    }
}

// ---------------------------------------------------------------------------
// Step 2 — PickIdentity
// ---------------------------------------------------------------------------

@Composable
private fun PickIdentityStep(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenHorizontalInsets),
    ) {
        IconButton(
            icon = Icons.ArrowBack(stringResource(Res.string.ui_top_bar_back_a11y)),
            onClick = { onAction(OnboardingAction.Back) },
            iconColor = AppTheme.colors.content,
            modifier = Modifier.padding(top = Dimension.D300),
        )

        Spacer(modifier = Modifier.height(Dimension.D300))

        val avatarPlaceholder = stringResource(Res.string.onboarding_identity_avatar_placeholder)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                AvatarCircle(
                    name = state.displayName.ifBlank { avatarPlaceholder },
                    emoji = state.selectedEmoji,
                    backgroundColorHex = state.selectedBackgroundColor,
                    size = 96.dp,
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceRaised.color)
                        .border(2.dp, AppTheme.colors.background.color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon = Icons.Pencil(null),
                        size = IconSize.Smallest,
                        color = AppTheme.colors.contentSecondary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Dimension.D700))
        Text(
            text = stringResource(Res.string.onboarding_identity_title),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = stringResource(Res.string.onboarding_identity_subtitle),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Dimension.D900))

        SectionLabel(stringResource(Res.string.onboarding_identity_section_name))
        Spacer(modifier = Modifier.height(Dimension.D300))
        val editNameIconDesc = stringResource(Res.string.onboarding_identity_edit_name_icon_desc)
        OutlinedTextField(
            value = state.displayName,
            onValueChange = { onAction(OnboardingAction.DisplayNameChanged(it)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
            ),
            trailingIcon = {
                Icon(
                    icon = Icons.Pencil(editNameIconDesc),
                    size = IconSize.Small,
                    color = AppTheme.colors.contentSecondary,
                )
            },
            isError = state.saveError != null,
            supportingText = state.saveError?.let { error ->
                {
                    Text(
                        text = error.message(),
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.danger,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Dimension.D700))

        SectionLabel(stringResource(Res.string.onboarding_identity_section_pack))
        Spacer(modifier = Modifier.height(Dimension.D400))
        StarterPackGrid(
            avatars = state.starterPack,
            selectedEmoji = state.selectedEmoji,
            onSelect = { option ->
                onAction(
                    OnboardingAction.SelectAvatar(
                        emoji = option.emoji,
                        backgroundColorHex = option.backgroundColorHex,
                    ),
                )
            },
        )

        Spacer(modifier = Modifier.height(Dimension.D500))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon = Icons.Lock(null),
                size = IconSize.Smallest,
                color = AppTheme.colors.contentSecondary,
            )
            Spacer(modifier = Modifier.width(Dimension.D300))
            Text(
                text = stringResource(Res.string.onboarding_identity_more_packs_hint),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
        }

        Spacer(modifier = Modifier.weight(1f, fill = true))

        ButtonPrimary(
            onClick = { onAction(OnboardingAction.ContinueFromPickIdentity) },
            enabled = state.displayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.onboarding_identity_continue_button))
        }
        Spacer(modifier = Modifier.height(Dimension.D700))
    }
}

@Composable
private fun StarterPackGrid(
    avatars: List<AvatarOption>,
    selectedEmoji: String?,
    onSelect: (AvatarOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimension.D500)) {
        avatars.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
            ) {
                row.forEach { option ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AvatarTile(
                            option = option,
                            isSelected = option.emoji == selectedEmoji,
                            onClick = { onSelect(option) },
                        )
                    }
                }
                // Pad short trailing row so widths stay consistent.
                repeat(4 - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AvatarTile(
    option: AvatarOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val ringColor = if (isSelected) AppTheme.colors.accentPrimary.color else Color.Transparent
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .border(width = 3.dp, color = ringColor, shape = CircleShape)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AvatarCircle(
            name = option.emoji,
            emoji = option.emoji,
            backgroundColorHex = option.backgroundColorHex,
            size = 56.dp,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Label.L400,
        color = AppTheme.colors.contentSecondary,
    )
}

// ---------------------------------------------------------------------------
// Step 3 — HowItWorks
// ---------------------------------------------------------------------------

@Composable
private fun HowItWorksStep(onAction: (OnboardingAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenHorizontalInsets),
    ) {
        IconButton(
            icon = Icons.ArrowBack(stringResource(Res.string.ui_top_bar_back_a11y)),
            onClick = { onAction(OnboardingAction.Back) },
            iconColor = AppTheme.colors.content,
            modifier = Modifier.padding(top = Dimension.D300),
        )
        Spacer(modifier = Modifier.height(Dimension.D700))
        Text(
            text = stringResource(Res.string.onboarding_how_eyebrow),
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.contentSecondary,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = stringResource(Res.string.onboarding_how_title),
            typography = AppTheme.typography.Display.D1000,
            color = AppTheme.colors.content,
        )

        Spacer(modifier = Modifier.weight(1f))

        InfoCard(
            title = stringResource(Res.string.onboarding_how_card_play_title),
            subtitle = stringResource(Res.string.onboarding_how_card_play_subtitle),
        ) {
            EmojiTile(glyph = "🎴", tint = AppTheme.colors.poker.feltGreen.color)
        }
        Spacer(modifier = Modifier.height(Dimension.D500))
        InfoCard(
            title = stringResource(Res.string.onboarding_how_card_chips_title),
            subtitle = stringResource(Res.string.onboarding_how_card_chips_subtitle),
        ) {
            ChipCoin(
                size = 48.dp,
                textTypography = AppTheme.typography.Heading.H700,
            )
        }
        Spacer(modifier = Modifier.height(Dimension.D500))
        InfoCard(
            title = stringResource(Res.string.onboarding_how_card_league_title),
            subtitle = stringResource(Res.string.onboarding_how_card_league_subtitle),
        ) {
            XpBadge(fraction = 0.6f, size = 48.dp)
        }


        Spacer(modifier = Modifier.weight(1f))

        ButtonPrimary(
            onClick = { onAction(OnboardingAction.ContinueFromHowItWorks) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.onboarding_how_continue_button))
        }
        Spacer(modifier = Modifier.height(Dimension.D700))
    }
}

@Composable
private fun InfoCard(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                leading()
            }
            Spacer(modifier = Modifier.width(Dimension.D700))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(Dimension.D100))
                Text(
                    text = subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                )
            }
        }
    }
}

@Composable
private fun EmojiTile(glyph: String, tint: Color) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(Radii.R500.shape)
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
    }
}

// ---------------------------------------------------------------------------
// Step 4 — StarterGrant
// ---------------------------------------------------------------------------

@Composable
private fun StarterGrantStep(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenHorizontalInsets),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            icon = Icons.ArrowBack(stringResource(Res.string.ui_top_bar_back_a11y)),
            onClick = { onAction(OnboardingAction.Back) },
            iconColor = AppTheme.colors.content,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(top = Dimension.D300),
        )

        // The hero sits in the space between the fixed back button and the
        // pinned CTA: centered when it fits, scrollable on short screens so
        // the dial + pills never clip and the CTA stays reachable.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.height(Dimension.D700))

            Text(
                text = stringResource(Res.string.onboarding_grant_eyebrow),
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.poker.chipGold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(Dimension.D700))

            // The radiant centerpiece — a slow sun dial framing the chip coin.
            RotatingDial {
                ChipCoin(
                    size = 96.dp,
                    textTypography = AppTheme.typography.Display.D1100,
                )
            }

            Spacer(modifier = Modifier.height(Dimension.D700))

            // The chip slot: reveal the real, server-authoritative number once
            // it hydrates; show the "lands when you reconnect" line if the
            // grace window elapsed offline; keep a quiet fixed-height
            // placeholder while resolving so the layout doesn't jump. We never
            // render a number we didn't get from the server.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                val revealed = state.revealedChips
                when {
                    revealed != null -> Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedCountUpText(
                            amount = revealed,
                            typography = AppTheme.typography.Display.D1300.Italic,
                            color = AppTheme.colors.poker.chipGold,
                        )
                        Spacer(modifier = Modifier.width(Dimension.D300))
                        Text(
                            text = stringResource(Res.string.onboarding_grant_chips_label),
                            typography = AppTheme.typography.Heading.H800,
                            color = AppTheme.colors.contentSecondary,
                            modifier = Modifier.padding(bottom = Dimension.D200),
                        )
                    }
                    state.grantRevealTimedOut -> Text(
                        text = stringResource(Res.string.onboarding_grant_offline_subtitle),
                        typography = AppTheme.typography.Body.B600,
                        color = AppTheme.colors.contentSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimension.D500))
            Text(
                text = stringResource(Res.string.onboarding_grant_subtitle),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(Dimension.D700))
            GrantFeaturePills()

            Spacer(modifier = Modifier.height(Dimension.D700))
        }

        // Always enabled — never block the user on the wallet round-trip.
        ButtonPrimary(
            onClick = { onAction(OnboardingAction.Finish) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.onboarding_grant_cta))
        }
        Spacer(modifier = Modifier.height(Dimension.D400))
        Text(
            text = stringResource(Res.string.onboarding_grant_footer),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Dimension.D700))
    }
}

/**
 * The three positioning pills on the starter-grant page — two on top, one
 * centered below, mirroring the marketing layout. Static; purely a value-prop
 * reminder while the chip reveal plays.
 */
@Composable
private fun GrantFeaturePills() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimension.D400),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimension.D400)) {
            GrantFeaturePill("🃏", stringResource(Res.string.onboarding_grant_feature_play_money))
            GrantFeaturePill("👥", stringResource(Res.string.onboarding_grant_feature_friends_bots))
        }
        GrantFeaturePill("🏆", stringResource(Res.string.onboarding_grant_feature_tournaments))
    }
}

@Composable
private fun GrantFeaturePill(emoji: String, label: String) {
    StatusPill(
        background = AppTheme.colors.surfaceHigh,
        contentPadding = PaddingValues(
            horizontal = Dimension.D500,
            vertical = Dimension.D300,
        ),
        horizontalSpacing = Dimension.D200,
    ) {
        Text(text = emoji, typography = AppTheme.typography.Body.B600)
        Text(
            text = label,
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.content,
        )
    }
}

private fun stepIndex(step: OnboardingStep): Int = when (step) {
    OnboardingStep.Welcome -> 0
    OnboardingStep.PickIdentity -> 1
    OnboardingStep.HowItWorks -> 2
    OnboardingStep.StarterGrant -> 3
}

@Composable
private fun OnboardingAuthError.message(): String {
    val main = stringResource(mainMessageKey())
    val debug = debugDetails()
    return if (BuildInfo.isDebug && !debug.isNullOrEmpty()) {
        main + stringResource(Res.string.onboarding_auth_error_debug_suffix, debug)
    } else {
        main
    }
}

private fun OnboardingAuthError.mainMessageKey() = when (this) {
    OnboardingAuthError.OAuthProviderNotEnabled -> Res.string.onboarding_auth_error_oauth_provider_not_enabled
    OnboardingAuthError.OAuthNetworkError -> Res.string.onboarding_auth_error_oauth_network
    OnboardingAuthError.OAuthFailed -> Res.string.onboarding_auth_error_oauth_failed
    is OnboardingAuthError.AnonymousSignInDisabled -> Res.string.onboarding_auth_error_guest_anonymous_disabled
    is OnboardingAuthError.CaptchaRequired -> Res.string.onboarding_auth_error_guest_captcha
    is OnboardingAuthError.InvalidConfig -> Res.string.onboarding_auth_error_guest_invalid_config
    is OnboardingAuthError.GuestSignInFailed -> Res.string.onboarding_auth_error_guest_failed
}

private fun OnboardingAuthError.debugDetails(): String? = when (this) {
    OnboardingAuthError.OAuthProviderNotEnabled,
    OnboardingAuthError.OAuthNetworkError,
    OnboardingAuthError.OAuthFailed,
    -> null
    is OnboardingAuthError.AnonymousSignInDisabled -> debugDetails
    is OnboardingAuthError.CaptchaRequired -> debugDetails
    is OnboardingAuthError.InvalidConfig -> debugDetails
    is OnboardingAuthError.GuestSignInFailed -> debugDetails
}

@Composable
private fun OnboardingSaveError.message(): String = when (this) {
    OnboardingSaveError.DisplayNameTaken ->
        stringResource(Res.string.onboarding_save_error_display_name_taken)
    OnboardingSaveError.InvalidDisplayName ->
        stringResource(Res.string.onboarding_save_error_invalid_display_name)
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OnboardingScreenPreview_Welcome() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(step = OnboardingStep.Welcome),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(widthDp = 800, heightDp = 380)
@Composable
private fun OnboardingScreenPreview_Landscape() {
    // Phone-landscape lens on the HowItWorks step — the tallest onboarding
    // content, most at risk of clipping on a short, wide canvas. Pins it for
    // review before any landscape layout work lands.
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(step = OnboardingStep.HowItWorks),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OnboardingScreenPreview_Welcome_OAuthEnabled() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(
                step = OnboardingStep.Welcome,
                googleEnabled = true,
                appleEnabled = true,
            ),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OnboardingScreenPreview_PickIdentity() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(
                step = OnboardingStep.PickIdentity,
                displayName = "QuietAce72",
                selectedEmoji = "🦊",
                selectedBackgroundColor = "#ff6b35",
                // Default starterPack = the hardcoded onboarding pack.
            ),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OnboardingScreenPreview_HowItWorks() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(step = OnboardingStep.HowItWorks),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OnboardingScreenPreview_StarterGrant_Revealed() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(step = OnboardingStep.StarterGrant, revealedChips = 10_500L),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OnboardingScreenPreview_StarterGrant_Offline() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(step = OnboardingStep.StarterGrant, grantRevealTimedOut = true),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OnboardingScreenPreview_Welcome_OAuthInFlight() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(
                step = OnboardingStep.Welcome,
                googleEnabled = true,
                appleEnabled = true,
                isAuthing = true,
                oauthInFlight = com.dangerfield.cards.libraries.identity.auth.OAuthProvider.Google,
            ),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OnboardingScreenPreview_Welcome_AuthError() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(
                step = OnboardingStep.Welcome,
                googleEnabled = true,
                appleEnabled = true,
                authError = OnboardingAuthError.GuestSignInFailed(debugDetails = null),
            ),
            onAction = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OnboardingScreenPreview_PickIdentity_SaveError() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        OnboardingScreen(
            state = OnboardingState(
                step = OnboardingStep.PickIdentity,
                displayName = "QuietAce72",
                selectedEmoji = "🦊",
                selectedBackgroundColor = "#ff6b35",
                saveError = OnboardingSaveError.DisplayNameTaken,
            ),
            onAction = {},
        )
    }
}
