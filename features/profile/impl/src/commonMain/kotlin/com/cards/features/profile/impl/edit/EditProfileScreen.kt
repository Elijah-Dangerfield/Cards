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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.avatarEmojiTypographyFor
import com.dangerfield.cards.libraries.ui.components.resolveAvatarBackground
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
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
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
        topBar = {
            TopBar(
                onNavigateBack = onBack
            )
        }
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
                    text = "Edit profile",
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.onSurfacePrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(Dimension.D300))
                Text(
                    text = "Other players see this on the table.",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
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
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val displayNameHelper = when {
                    state.displayNameError != null -> state.displayNameError
                    !state.isNameValid && state.displayName.isNotEmpty() ->
                        "${EditProfileState.MIN_NAME_LENGTH}–${EditProfileState.MAX_NAME_LENGTH} characters"
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
                            AppTheme.colors.onSurfaceSecondary
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
                        text = "Avatar color",
                        typography = AppTheme.typography.Heading.H500,
                        color = AppTheme.colors.onSurfacePrimary,
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
                    text = "Avatar",
                    typography = AppTheme.typography.Heading.H500,
                    color = AppTheme.colors.onSurfacePrimary,
                )
                Spacer(modifier = Modifier.height(Dimension.D400))

                AvatarPicker(
                    packs = state.avatarPacks,
                    selected = state.selectedAvatarEmoji,
                    isLoading = state.isLoadingAvatars,
                    loadError = state.avatarLoadError,
                    enabled = !state.isSubmitting,
                    onSelect = { onAction(EditProfileAction.AvatarSelected(it)) },
                    onUnlockPack = { productId -> onNavigateToShop(productId) },
                )

                state.error?.let {
                    Spacer(modifier = Modifier.height(Dimension.D500))
                    Text(
                        text = it,
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.danger,
                    )
                }
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
            Text(if (isSubmitting) "Saving…" else "Save")
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
    packs: List<AvatarPackDisplay>,
    selected: String?,
    isLoading: Boolean,
    loadError: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onUnlockPack: (productId: String) -> Unit,
) {
    if (isLoading && packs.isEmpty()) {
        Text(
            text = "Loading…",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimension.D800),
        )
        return
    }

    if (loadError) {
        Text(
            text = "Avatars couldn't load. You can still save your current avatar.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
            modifier = Modifier.padding(bottom = Dimension.D400),
        )
    }

    packs.forEachIndexed { index, display ->
        if (index > 0) Spacer(modifier = Modifier.height(Dimension.D700))
        val pack = display.pack
        // Show the pack name whenever there are multiple packs OR any
        // pack is locked — locked rows need a header so the "Get in
        // shop" CTA has somewhere to live, and so the user can read
        // the pack's name before deciding to buy.
        val showHeader = packs.size > 1 || display.isLocked
        if (showHeader) {
            PackHeader(
                name = pack.name,
                isLocked = display.isLocked,
                onUnlock = pack.unlockProductId?.let { id -> { onUnlockPack(id) } },
            )
            Spacer(modifier = Modifier.height(Dimension.D300))
        }
        AvatarGrid(
            emojis = pack.emojis,
            selected = selected,
            enabled = enabled && !display.isLocked,
            isLocked = display.isLocked,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun PackHeader(
    name: String,
    isLocked: Boolean,
    onUnlock: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (isLocked) "🔒  $name" else name,
            typography = AppTheme.typography.Label.L500,
            color = if (isLocked) AppTheme.colors.onSurfaceSecondary else AppTheme.colors.onSurfaceSecondary,
            modifier = Modifier.weight(1f),
        )
        if (isLocked && onUnlock != null) {
            Button(
                onClick = onUnlock,
                style = ButtonStyle.Outlined,
                size = ButtonSize.Small,
            ) {
                Text("Get in shop")
            }
        }
    }
}

@Composable
private fun AvatarGrid(
    emojis: List<String>,
    selected: String?,
    enabled: Boolean,
    isLocked: Boolean = false,
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
                        isLocked = isLocked,
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
    isLocked: Boolean,
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
        // avatar surfaces. Clipped to a circle so the emoji + dim alpha
        // overlay stay inside the tile shape — the lock badge sits on
        // the unclipped outer box below so its full chip is visible.
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceSecondary.color)
                .border(borderWidth, borderColor, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Text(
                text = emoji,
                typography = avatarEmojiTypographyFor(maxWidth),
                modifier = if (isLocked) Modifier.alpha(0.35f) else Modifier,
            )
        }
        if (isLocked) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surfacePrimary.color),
            ) {
                Text(
                    text = "🔒",
                    typography = AppTheme.typography.Label.L400,
                )
            }
        }
    }
}

private const val GRID_COLUMNS = 4

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
private fun EditProfileScreenPreview_PremiumPackLocked() {
    // Pins the new locked-pack visual: every server pack still
    // renders, premium packs the user doesn't own show with a
    // dimmed grid + 🔒 stamp + "Get in shop" CTA in the header.
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
                displayNameError = "That name is taken. Try another.",
            ),
            onAction = {},
            onBack = {},
        )
    }
}
