package com.dangerfield.cards.features.profile.impl.edit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_edit_avatar_color_section
import cards.libraries.resources.generated.resources.profile_edit_avatar_section
import cards.libraries.resources.generated.resources.profile_edit_avatars_load_error
import cards.libraries.resources.generated.resources.profile_edit_avatars_loading
import cards.libraries.resources.generated.resources.profile_edit_display_name_error_invalid
import cards.libraries.resources.generated.resources.profile_edit_display_name_error_taken
import cards.libraries.resources.generated.resources.profile_edit_display_name_helper_range
import cards.libraries.resources.generated.resources.profile_edit_display_name_label
import cards.libraries.resources.generated.resources.profile_edit_featured_empty
import cards.libraries.resources.generated.resources.profile_edit_featured_section
import cards.libraries.resources.generated.resources.profile_edit_get_more_packs
import cards.libraries.resources.generated.resources.profile_edit_save_button
import cards.libraries.resources.generated.resources.profile_edit_save_button_progress
import cards.libraries.resources.generated.resources.profile_edit_subtitle
import cards.libraries.resources.generated.resources.profile_edit_tab_edit
import cards.libraries.resources.generated.resources.profile_edit_tab_view
import cards.libraries.resources.generated.resources.profile_edit_title
import cards.libraries.resources.generated.resources.profile_player_card_view_blurb
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.PlayerCardContent
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedal
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.avatarEmojiTypographyFor
import com.dangerfield.cards.libraries.ui.components.resolveAvatarBackground
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.horizontalScrollWithBar
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.screenHorizontalInsets
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Edit-profile UI: display name field + emoji avatar grid + save button.
 *
 * The avatar grid is a 5-wide [LazyVerticalGrid] inside the screen's
 * outer scroll. Each tile is a circular emoji; the selected tile gets a
 * thicker accent border. ~80 emojis = ~16 rows on phones — fits in the
 * outer scroll without nested-scrolling fights.
 *
 * Save is gated on `canSubmit` (dirty + valid + not submitting). Backing
 * out without saving discards changes.
 */
@Composable
fun EditProfileScreen(
    state: EditProfileState,
    onAction: (EditProfileAction) -> Unit,
    onBack: () -> Unit,
    // Tapping "Get in shop" on a locked pack routes here with the
    // unlock product id, so the shop can open the purchase sheet for
    // it directly. Null = navigate to the shop tab with no deep-link.
    onNavigateToShop: (productId: String?) -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
        topBar = {
            TopBar(
                onNavigateBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(
                    paddingValues = padding,
                    includeHorizontalInsets = false,
                    includeImePadding = true,
                ),
        ) {
            EditProfileTabs(
                pagerState = pagerState,
                onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
                modifier = Modifier.padding(screenHorizontalInsets),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                if (page == VIEW_TAB) {
                    ViewTabContent(state)
                    return@HorizontalPager
                }
                // Edit tab — the form + floating Save bar.
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(screenHorizontalInsets)
                            // Bottom padding leaves enough room for the floating
                            // Save bar (~80dp) so the last form row scrolls
                            // clear instead of sitting under the button.
                            .padding(bottom = FloatingSaveBarReservedHeight),
                    ) {

                Spacer(modifier = Modifier.height(Dimension.D500))

                // Live preview: the chosen emoji blown up to "this is you"
                // size, animated on each pick so the picker feels connected
                // to a real artifact instead of an abstract grid. The
                // background tracks the selected color (or theme default).
                AvatarPreviewHero(
                    emoji = state.selectedAvatarEmoji,
                    backgroundColorHex = state.selectedAvatarBackgroundColor,
                )

                Spacer(modifier = Modifier.height(Dimension.D700))

                Text(
                    text = stringResource(Res.string.profile_edit_title),
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.content,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(Dimension.D300))
                Text(
                    text = stringResource(Res.string.profile_edit_subtitle),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(Dimension.D900))

                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = { onAction(EditProfileAction.DisplayNameChanged(it)) },
                    enabled = !state.isSubmitting,
                    singleLine = true,
                    isError = state.displayNameError != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(EditProfileAction.Submit) }),
                    label = { Text(stringResource(Res.string.profile_edit_display_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val rangeHelper = stringResource(
                    Res.string.profile_edit_display_name_helper_range,
                    EditProfileState.MIN_NAME_LENGTH,
                    EditProfileState.MAX_NAME_LENGTH,
                )
                val displayNameHelper = when {
                    state.displayNameError != null -> state.displayNameError.message()
                    !state.isNameValid && state.displayName.isNotEmpty() -> rangeHelper
                    else -> null
                }
                if (displayNameHelper != null) {
                    Spacer(modifier = Modifier.height(Dimension.D200))
                    Text(
                        text = displayNameHelper,
                        typography = AppTheme.typography.Body.B400,
                        color = if (state.displayNameError != null) {
                            AppTheme.colors.danger
                        } else {
                            AppTheme.colors.contentSecondary
                        },
                    )
                }

                // Color picker lives above the avatar grid so the user
                // sees "pick how you look" colors → emoji in reading
                // order, and the hero above re-tints as they change
                // either field. Big swatches per the "bubbly UI" goal.
                if (state.backgroundPalette.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Dimension.D900))
                    Text(
                        text = stringResource(Res.string.profile_edit_avatar_color_section),
                        typography = AppTheme.typography.Heading.H500,
                        color = AppTheme.colors.content,
                    )
                    Spacer(modifier = Modifier.height(Dimension.D400))
                    BackgroundColorPicker(
                        palette = state.backgroundPalette,
                        selected = state.selectedAvatarBackgroundColor,
                        enabled = !state.isSubmitting,
                        onSelect = { onAction(EditProfileAction.AvatarBackgroundColorSelected(it)) },
                    )
                }

                Spacer(modifier = Modifier.height(Dimension.D900))

                Text(
                    text = stringResource(Res.string.profile_edit_avatar_section),
                    typography = AppTheme.typography.Heading.H500,
                    color = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(Dimension.D400))

                AvatarPicker(
                    packs = state.avatarPacks,
                    selected = state.selectedAvatarEmoji,
                    isLoading = state.isLoadingAvatars,
                    loadError = state.avatarLoadError,
                    enabled = !state.isSubmitting,
                    hasMorePacksInShop = state.hasLockedAvatarPacks,
                    onSelect = { onAction(EditProfileAction.AvatarSelected(it)) },
                    onGetMorePacks = { onNavigateToShop(null) },
                )

                Spacer(modifier = Modifier.height(Dimension.D900))

                Text(
                    text = stringResource(Res.string.profile_edit_featured_section),
                    typography = AppTheme.typography.Heading.H500,
                    color = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(Dimension.D400))
                FeaturedBadgePicker(
                    badges = state.earnedBadges,
                    selectedIds = state.selectedFeaturedBadgeIds,
                    selectionFull = state.isFeaturedSelectionFull,
                    enabled = !state.isSubmitting,
                    onToggle = { onAction(EditProfileAction.ToggleFeaturedBadge(it)) },
                )
                    }

                    // Floating Save bar — sits over the bottom of the scroll
                    // surface so it's always reachable on long emoji grids
                    // without scrolling all the way down. The scroll's bottom
                    // padding (above) reserves enough space for the last row
                    // to clear this bar.
                    FloatingSaveBar(
                        isSubmitting = state.isSubmitting,
                        enabled = state.canSubmit,
                        onClick = { onAction(EditProfileAction.Submit) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

private const val VIEW_TAB = 1

/**
 * The Edit/View tabs: two plain text labels with a sliding accent indicator
 * underneath. The indicator tracks the pager continuously via
 * `currentPage + currentPageOffsetFraction`, so it follows a swipe in real
 * time and glides across when a label is tapped (the tap animates the
 * pager). Reads as text with a moving underline, not buttons.
 */
@Composable
private fun EditProfileTabs(
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Continuous scroll position: 0f at Edit, 1f at View, fractional mid-swipe.
    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, VIEW_TAB.toFloat())
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            EditProfileTabLabel(
                label = stringResource(Res.string.profile_edit_tab_edit),
                selected = position < 0.5f,
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f),
            )
            EditProfileTabLabel(
                label = stringResource(Res.string.profile_edit_tab_view),
                selected = position >= 0.5f,
                onClick = { onSelect(VIEW_TAB) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(Dimension.D200))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val tabWidth = maxWidth / 2
            val indicatorWidth = tabWidth * 0.5f
            Box(
                modifier = Modifier
                    .offset(x = tabWidth * position + (tabWidth - indicatorWidth) / 2)
                    .width(indicatorWidth)
                    .height(3.dp)
                    .clip(Radii.Round.shape)
                    .background(AppTheme.colors.accentPrimary.color),
            )
        }
    }
}

@Composable
private fun EditProfileTabLabel(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // No ripple / background — these read as text, not buttons.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = Dimension.D300),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Label.L500,
            color = if (selected) AppTheme.colors.content else AppTheme.colors.contentSecondary,
        )
    }
}

/**
 * The "View" tab — a live [PlayerCard] reflecting the user's current
 * (possibly unsaved) selection, so editing and "what others see" stay in
 * one place. Featured badges arrive with the featured-badge selection work.
 */
@Composable
private fun ViewTabContent(state: EditProfileState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenHorizontalInsets)
            .padding(vertical = Dimension.D700),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AvatarCircle(
            name = state.displayName.ifBlank { state.initialDisplayName.orEmpty() },
            size = Dimension.D1900,
            emoji = state.selectedAvatarEmoji,
            backgroundColorHex = state.selectedAvatarBackgroundColor,
            animationsEnabled = false,
        )
        Spacer(modifier = Modifier.height(Dimension.D500))
        PlayerCardContent(
            name = state.displayName.ifBlank { state.initialDisplayName.orEmpty() },
            level = state.level,
            equippedTitle = state.equippedTitle,
            permanentBadgeEmoji = state.permanentBadgeEmoji,
            featuredBadges = state.featuredBadges,
        )
        Spacer(modifier = Modifier.height(Dimension.D700))
        Text(
            text = stringResource(Res.string.profile_player_card_view_blurb),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val FloatingSaveBarReservedHeight = 96.dp

@Composable
private fun FloatingSaveBar(
    isSubmitting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(screenHorizontalInsets)
            .padding(vertical = Dimension.D500),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (isSubmitting) {
                    stringResource(Res.string.profile_edit_save_button_progress)
                } else {
                    stringResource(Res.string.profile_edit_save_button)
                }
            )
        }
    }
}

@Composable
private fun AvatarPreviewHero(emoji: String?, backgroundColorHex: String?) {
    // Same resolver every other avatar surface uses — Welcome dialog
    // bubble, Profile screen AvatarCircle, picker swatch preview. Single
    // source of truth so the hero color can never drift from what the
    // user sees elsewhere.
    val bg = resolveAvatarBackground(backgroundColorHex)
    val previewSize = 112.dp
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(previewSize)
                .clip(CircleShape)
                .background(bg),
        ) {
            AnimatedContent(
                targetState = emoji ?: " ",
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.75f)) togetherWith fadeOut()
                },
                label = "avatar-preview",
            ) { current ->
                Text(
                    text = current,
                    typography = avatarEmojiTypographyFor(previewSize),
                )
            }
        }
    }
}

@Composable
private fun AvatarPicker(
    packs: List<AvatarPack>,
    selected: String?,
    isLoading: Boolean,
    loadError: Boolean,
    enabled: Boolean,
    hasMorePacksInShop: Boolean,
    onSelect: (String) -> Unit,
    onGetMorePacks: () -> Unit,
) {
    if (isLoading && packs.isEmpty()) {
        Text(
            text = stringResource(Res.string.profile_edit_avatars_loading),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimension.D800),
        )
        return
    }

    if (loadError) {
        Text(
            text = stringResource(Res.string.profile_edit_avatars_load_error),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            modifier = Modifier.padding(bottom = Dimension.D400),
        )
    }

    packs.forEachIndexed { index, pack ->
        if (index > 0) Spacer(modifier = Modifier.height(Dimension.D700))
        // Only name packs once there's more than one to disambiguate — a
        // lone starter pack reads fine headerless.
        if (packs.size > 1) {
            PackHeader(name = pack.name)
            Spacer(modifier = Modifier.height(Dimension.D300))
        }
        AvatarGrid(
            emojis = pack.emojis,
            selected = selected,
            enabled = enabled,
            onSelect = onSelect,
        )
    }

    // Single discovery link to the Shop in place of the old in-picker
    // marketplace — the picker stays a wardrobe; buying happens in the Shop.
    if (hasMorePacksInShop) {
        Spacer(modifier = Modifier.height(Dimension.D700))
        Button(
            onClick = onGetMorePacks,
            type = ButtonType.Secondary,
            style = ButtonStyle.Outlined,
            size = ButtonSize.Small,
        ) {
            Text(stringResource(Res.string.profile_edit_get_more_packs))
        }
    }
}

@Composable
private fun PackHeader(name: String) {
    Text(
        text = name,
        typography = AppTheme.typography.Label.L500,
        color = AppTheme.colors.contentSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AvatarGrid(
    emojis: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    // Plain chunked Column-of-Rows. A LazyVerticalGrid inside a vertical
    // scroll can't measure itself — it needs a bounded height — and any
    // height we calculate has to account for inter-row spacing, which
    // depends on the parent width. Rows-of-weighted-cells lay out without
    // any of that arithmetic and scroll naturally with the outer Column.
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimension.D300),
        modifier = Modifier.fillMaxWidth(),
    ) {
        emojis.chunked(GRID_COLUMNS).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { emoji ->
                    AvatarTile(
                        emoji = emoji,
                        isSelected = emoji == selected,
                        enabled = enabled,
                        onClick = { onSelect(emoji) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad the last row so a partial row's tiles don't stretch
                // across the full width.
                repeat(GRID_COLUMNS - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AvatarTile(
    emoji: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) AppTheme.colors.accentPrimary.color else AppTheme.colors.border.color
    val borderWidth = if (isSelected) 3.dp else 1.dp
    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        // BoxWithConstraints so the emoji typography scales with the actual
        // measured tile width (depends on screen size + column count). Keeps
        // the picker's emoji-to-tile ratio aligned with the rest of the
        // avatar surfaces.
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceRaised.color)
                .border(borderWidth, borderColor, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Text(
                text = emoji,
                typography = avatarEmojiTypographyFor(maxWidth),
            )
        }
    }
}

private const val GRID_COLUMNS = 4

/**
 * The featured-badge picker: a grid of the user's earned medals. Tapping
 * toggles a badge into the featured set (capped at 3). Selected medals get
 * an accent ring; once the cap is hit, unselected medals dim + disable so
 * the user must deselect one to swap. Empty earned set → a one-line hint.
 */
@Composable
private fun FeaturedBadgePicker(
    badges: List<Achievement>,
    selectedIds: List<String>,
    selectionFull: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    if (badges.isEmpty()) {
        Text(
            text = stringResource(Res.string.profile_edit_featured_empty),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            modifier = Modifier.padding(vertical = Dimension.D400),
        )
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimension.D400),
        modifier = Modifier.fillMaxWidth(),
    ) {
        badges.chunked(FEATURED_GRID_COLUMNS).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D400),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { badge ->
                    val isSelected = badge.id.name in selectedIds
                    FeaturedBadgeTile(
                        badge = badge,
                        isSelected = isSelected,
                        // Selected tiles stay tappable (to deselect / swap);
                        // unselected tiles disable once the cap is reached.
                        enabled = enabled && (isSelected || !selectionFull),
                        onClick = { onToggle(badge.id.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(FEATURED_GRID_COLUMNS - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private const val FEATURED_GRID_COLUMNS = 4

@Composable
private fun FeaturedBadgeTile(
    badge: Achievement,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ring = if (isSelected) {
        Modifier.border(3.dp, AppTheme.colors.accentPrimary.color, CircleShape)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .then(ring)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled || isSelected) 1f else 0.4f)
            .padding(Dimension.D200),
        contentAlignment = Alignment.Center,
    ) {
        AchievementMedal(
            achievement = badge,
            earned = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BackgroundColorPicker(
    palette: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    // Server-supplied palette only — every fresh profile lands with a
    // real color, so a "no color" swatch would surface a state the user
    // can't reach for new accounts and shouldn't be able to opt back into.
    // Scrolls horizontally so a wide palette can't clip off-screen.
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScrollWithBar(rememberScrollState()),
    ) {
        palette.forEach { hex ->
            ColorSwatch(
                colorHex = hex,
                isSelected = selected?.equals(hex, ignoreCase = true) == true,
                enabled = enabled,
                onClick = { onSelect(hex) },
            )
        }
    }
}

@Composable
private fun ColorSwatch(
    colorHex: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Larger swatches (was 40dp) for the "big bubbly UI" goal —
    // each circle is now a generous tap target and reads more like a
    // chip than a UI button. The picker scrolls horizontally if the
    // palette is wider than the screen, so the size doesn't constrain
    // how many colors the server can ship.
    val swatchColor = resolveAvatarBackground(colorHex)
    val borderColor = if (isSelected) AppTheme.colors.accentPrimary.color else AppTheme.colors.border.color
    val borderWidth = if (isSelected) 3.dp else 1.dp
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(swatchColor)
            .border(borderWidth, borderColor, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun EditProfileDisplayNameError.message(): String = when (this) {
    EditProfileDisplayNameError.Taken ->
        stringResource(Res.string.profile_edit_display_name_error_taken)
    EditProfileDisplayNameError.Invalid ->
        stringResource(Res.string.profile_edit_display_name_error_invalid)
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EditProfileScreenPreview_Loaded() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        EditProfileScreen(
            state = EditProfileState(
                initialDisplayName = "Elijah",
                displayName = "Elijah",
                initialAvatarEmoji = "🦊",
                selectedAvatarEmoji = "🦄",
                allAvatarPacks = listOf(
                    AvatarPack(
                        id = "starter",
                        name = "Starter pack",
                        emojis = listOf("🦊", "🐱", "🐼", "🐯", "🦄", "🐲", "🦁", "🐸"),
                    ),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EditProfileScreenPreview_TwoPacksOwned() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        EditProfileScreen(
            state = EditProfileState(
                initialDisplayName = "Elijah",
                displayName = "ElijahNew",
                initialAvatarEmoji = "🦊",
                selectedAvatarEmoji = "🚀",
                allAvatarPacks = listOf(
                    AvatarPack("starter", "Starter pack", listOf("🦊", "🐱", "🐼", "🐯")),
                    AvatarPack(
                        id = "space",
                        name = "Space pack",
                        emojis = listOf("🚀", "🛸", "🌙", "⭐", "🪐", "☄️"),
                        unlockProductId = "avatars_space",
                    ),
                ),
                ownedProductIds = setOf("avatars_space"),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EditProfileScreenPreview_UnownedPacksLinkToShop() {
    // Pins the wardrobe-not-storefront behavior: only the owned starter
    // pack renders in the picker; the two unowned premium packs are
    // filtered out and surface as the single "Get more avatar packs in
    // the Shop →" link beneath the grid.
    com.dangerfield.cards.libraries.ui.PreviewContent {
        EditProfileScreen(
            state = EditProfileState(
                initialDisplayName = "Elijah",
                displayName = "Elijah",
                initialAvatarEmoji = "🦊",
                selectedAvatarEmoji = "🦊",
                allAvatarPacks = listOf(
                    AvatarPack("starter", "Starter pack", listOf("🦊", "🐱", "🐼", "🐯", "🦄", "🐲", "🦁", "🐸")),
                    AvatarPack(
                        id = "animals",
                        name = "Animal Avatars",
                        emojis = listOf("🐶", "🐯", "🐼", "🦊", "🐻", "🦁", "🐸", "🦒"),
                        unlockProductId = "avatars_animals",
                    ),
                    AvatarPack(
                        id = "fantasy",
                        name = "Fantasy Avatars",
                        emojis = listOf("🧙", "🧚", "🧛", "🧜", "🦄", "🐉", "🧞", "🐲"),
                        unlockProductId = "avatars_fantasy",
                    ),
                ),
                ownedProductIds = emptySet(),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EditProfileScreenPreview_Loading() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        EditProfileScreen(
            state = EditProfileState(
                initialDisplayName = "Elijah",
                displayName = "Elijah",
                initialAvatarEmoji = "🦊",
                selectedAvatarEmoji = "🦊",
                isLoadingAvatars = true,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EditProfileScreenPreview_LongPalette() {
    // Pins the horizontal-scroll fix on BackgroundColorPicker — a palette
    // wider than the screen must scroll rather than clip the last swatch.
    com.dangerfield.cards.libraries.ui.PreviewContent {
        EditProfileScreen(
            state = EditProfileState(
                initialDisplayName = "Elijah",
                displayName = "Elijah",
                initialAvatarEmoji = "🦊",
                selectedAvatarEmoji = "🦊",
                allAvatarPacks = listOf(
                    AvatarPack("starter", "Starter pack", listOf("🦊", "🐱", "🐼", "🐯", "🦄", "🐲", "🦁", "🐸")),
                ),
                backgroundPalette = listOf(
                    "#E45858", "#E48A58", "#E4B458", "#A8E458", "#58E47C",
                    "#58E4D2", "#5894E4", "#7458E4", "#C658E4", "#E458B0",
                    "#E4585A", "#E4A0B4",
                ),
                selectedAvatarBackgroundColor = "#58E47C",
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EditProfileScreenPreview_Submitting() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        EditProfileScreen(
            state = EditProfileState(
                initialDisplayName = "Elijah",
                displayName = "ElijahNew",
                initialAvatarEmoji = "🦊",
                selectedAvatarEmoji = "🚀",
                allAvatarPacks = listOf(
                    AvatarPack("starter", "Starter pack", listOf("🦊", "🐱", "🐼", "🐯", "🦄", "🐲", "🦁", "🐸")),
                ),
                isSubmitting = true,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EditProfileScreenPreview_AvatarLoadError() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        EditProfileScreen(
            state = EditProfileState(
                initialDisplayName = "Elijah",
                displayName = "Elijah",
                initialAvatarEmoji = "🦊",
                selectedAvatarEmoji = "🦊",
                allAvatarPacks = listOf(
                    AvatarPack("starter", "Starter pack", listOf("🦊", "🐱", "🐼", "🐯")),
                ),
                avatarLoadError = true,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EditProfileScreenPreview_FeaturedBadges() {
    // Pins the featured-badge picker on the Edit tab: a grid of earned
    // medals with two selected (accent ring) and the rest tappable.
    com.dangerfield.cards.libraries.ui.PreviewContent {
        val earned = com.dangerfield.cards.libraries.cards.AllAchievements.take(7)
        EditProfileScreen(
            state = EditProfileState(
                initialDisplayName = "Elijah",
                displayName = "Elijah",
                initialAvatarEmoji = "🦊",
                selectedAvatarEmoji = "🦊",
                allAvatarPacks = listOf(
                    AvatarPack("starter", "Starter pack", listOf("🦊", "🐱", "🐼", "🐯")),
                ),
                earnedBadges = earned,
                selectedFeaturedBadgeIds = earned.take(2).map { it.id.name },
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EditProfileScreenPreview_DisplayNameTaken() {
    // Pins the inline "name is taken" error treatment on the name
    // field — the rejection surfaces here, not as a snackbar after
    // the user has already navigated away.
    com.dangerfield.cards.libraries.ui.PreviewContent {
        EditProfileScreen(
            state = EditProfileState(
                initialDisplayName = "Elijah",
                displayName = "TakenName",
                initialAvatarEmoji = "🦊",
                selectedAvatarEmoji = "🦊",
                allAvatarPacks = listOf(
                    AvatarPack("starter", "Starter pack", listOf("🦊", "🐱", "🐼", "🐯")),
                ),
                displayNameError = EditProfileDisplayNameError.Taken,
            ),
            onAction = {},
            onBack = {},
        )
    }
}
