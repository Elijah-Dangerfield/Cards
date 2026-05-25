package com.dangerfield.cards.features.onboarding.impl

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.Card
import com.dangerfield.cards.libraries.ui.components.CardsFan
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.XpBadge
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii

/**
 * Three-step onboarding flow driven by [OnboardingViewModel.state.step]:
 *   1. [WelcomeStep]   — guest / Apple / Google entry points
 *   2. [PickIdentityStep] — display name + starter-pack avatar picker
 *   3. [HowItWorksStep] — three-card explainer
 *
 * The host owns the [Screen] shell + insets; each step is a pure
 * composable rendered inside it.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
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
            when (state.step) {
                OnboardingStep.Welcome -> WelcomeStep(state, onAction)
                OnboardingStep.PickIdentity -> PickIdentityStep(state, onAction)
                OnboardingStep.HowItWorks -> HowItWorksStep(onAction)
            }
        }
    }
}

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
    val initialFanProgress = if (inPreview || BuildInfo.isiOS()) 1f else 0f
    val initialReveal = if (inPreview) 1f else 0f
    val fanProgress = remember { Animatable(initialFanProgress) }
    val contentReveal = remember { Animatable(initialReveal) }
    LaunchedEffect(Unit) {
        if (inPreview) return@LaunchedEffect
        fanProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 700, easing = EaseOutCubic),
        )
        contentReveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 450, easing = EaseOutCubic),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimension.D800),
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
                text = "Cards",
                typography = AppTheme.typography.Display.D1300,
                color = AppTheme.colors.text,
            )
            Spacer(modifier = Modifier.height(Dimension.D500))
            Text(
                text = "Poker with your friends.\nPractice with bots when they're busy.",
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.textSecondary,
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
            if (state.authError != null) {
                Text(
                    text = state.authError,
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
                Text(if (state.isAuthing) "One moment…" else "Continue as guest")
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
                            Text(if (state.oauthInFlight == OAuthProvider.Apple) "…" else " Apple")
                        }
                    }
                    if (state.googleEnabled) {
                        ButtonSecondary(
                            onClick = { onAction(OnboardingAction.SignInWithOAuth(OAuthProvider.Google)) },
                            enabled = state.oauthInFlight == null && !state.isAuthing,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.oauthInFlight == OAuthProvider.Google) "…" else "G  Google")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimension.D500))
            Text(
                text = "No account required to play.\nSign in later if you want to save your progress.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
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
            .padding(horizontal = Dimension.D800),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimension.D300),
            horizontalArrangement = Arrangement.End,
        ) {
            ButtonGhost(onClick = { onAction(OnboardingAction.Skip) }) {
                Text("Skip ›")
            }
        }

        Spacer(modifier = Modifier.height(Dimension.D300))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                AvatarCircle(
                    name = state.displayName.ifBlank { "?" },
                    emoji = state.selectedEmoji,
                    backgroundColorHex = state.selectedBackgroundColor,
                    size = 96.dp,
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceSecondary.color)
                        .border(2.dp, AppTheme.colors.background.color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = AppTheme.colors.onSurfaceSecondary.color,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Dimension.D700))
        Text(
            text = "This is you",
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.onSurfacePrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = "Other players see this on the table. Change anything you like — or just keep going.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Dimension.D900))

        SectionLabel("DISPLAY NAME")
        Spacer(modifier = Modifier.height(Dimension.D300))
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
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit name",
                    tint = AppTheme.colors.onSurfaceSecondary.color,
                    modifier = Modifier.size(18.dp),
                )
            },
            isError = state.saveError != null,
            supportingText = state.saveError?.let { error ->
                {
                    Text(
                        text = error,
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.danger,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Dimension.D700))

        SectionLabel("STARTER PACK")
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
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = AppTheme.colors.onSurfaceSecondary.color,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(Dimension.D300))
            Text(
                text = "More packs in the shop",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }

        Spacer(modifier = Modifier.weight(1f, fill = true))

        ButtonPrimary(
            onClick = { onAction(OnboardingAction.ContinueFromPickIdentity) },
            enabled = !state.isSavingProfile && state.displayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isSavingProfile) "Saving…" else "Continue")
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
            .border(width = 3.dp, color = ringColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onClick,
            type = ButtonType.Ghost,
            style = ButtonStyle.Text,
            modifier = Modifier.fillMaxSize(),
        ) {}
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
        color = AppTheme.colors.onSurfaceSecondary,
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
            .padding(horizontal = Dimension.D800),
    ) {
        Spacer(modifier = Modifier.height(Dimension.D900))
        Text(
            text = "LAST THING",
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = "How Cards works.",
            typography = AppTheme.typography.Display.D1000,
            color = AppTheme.colors.onSurfacePrimary,
        )

        Spacer(modifier = Modifier.weight(1f))

        InfoCard(
            title = "Play three ways",
            subtitle = "Bots, strangers, or friends.",
        ) {
            EmojiTile(glyph = "🎴", tint = Color(0xFF3F5B45))
        }
        Spacer(modifier = Modifier.height(Dimension.D500))
        InfoCard(
            title = "Chips fund the table",
            subtitle = "Earn at play. Spend in the shop.",
        ) {
            ChipCoin(
                size = 48.dp,
                textTypography = AppTheme.typography.Heading.H700,
            )
        }
        Spacer(modifier = Modifier.height(Dimension.D500))
        InfoCard(
            title = "Climb the league",
            subtitle = "Top 7 of 30 promote each week.",
        ) {
            XpBadge(fraction = 0.6f, size = 48.dp)
        }


        Spacer(modifier = Modifier.weight(1f))

        ButtonPrimary(
            onClick = { onAction(OnboardingAction.Finish) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Take a seat")
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
                    color = AppTheme.colors.onSurfacePrimary,
                )
                Spacer(modifier = Modifier.height(Dimension.D100))
                Text(
                    text = subtitle,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.onSurfaceSecondary,
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
            color = AppTheme.colors.onSurfacePrimary,
        )
    }
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
                selectedBackgroundColor = "#E48A58",
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
