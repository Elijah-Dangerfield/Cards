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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.avatarEmojiTypographyFor
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.horizontalScrollWithBar
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
) {
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimension.D800),
            ) {
                Spacer(modifier = Modifier.height(Dimension.D200))
                IconButton(
                    icon = Icons.ArrowBack("Back"),
                    onClick = onBack,
                    iconColor = AppTheme.colors.onSurfacePrimary,
                )

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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(EditProfileAction.Submit) }),
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.isNameValid && state.displayName.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Dimension.D200))
                    Text(
                        text = "${EditProfileState.MIN_NAME_LENGTH}–${EditProfileState.MAX_NAME_LENGTH} characters",
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.onSurfaceSecondary,
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
                )

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

                state.error?.let {
                    Spacer(modifier = Modifier.height(Dimension.D500))
                    Text(
                        text = it,
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.danger,
                    )
                }

                Spacer(modifier = Modifier.height(Dimension.D800))

                Button(
                    onClick = { onAction(EditProfileAction.Submit) },
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isSubmitting) "Saving…" else "Save")
                }

                Spacer(modifier = Modifier.height(Dimension.D800))
            }
        }
    }
}

@Composable
private fun AvatarPreviewHero(emoji: String?, backgroundColorHex: String?) {
    val parsedColor = backgroundColorHex?.let {
        Catching { com.dangerfield.cards.libraries.ui.components.parseHexColor(it) }.getOrNull()
    }
    // Hero uses an explicit neutral fallback (not the name-seeded hue
    // AvatarCircle defaults to) so the disc doesn't flicker through
    // random colors while the emoji animates between picks.
    val bg = parsedColor ?: AppTheme.colors.surfaceSecondary.color
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
    onSelect: (String) -> Unit,
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

    packs.forEachIndexed { index, pack ->
        if (index > 0) Spacer(modifier = Modifier.height(Dimension.D700))
        // Only show the pack name when there's more than one — a single
        // "Starter pack" header would be visual noise for the common case
        // (no premium packs owned yet).
        if (packs.size > 1) {
            Text(
                text = pack.name,
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            Spacer(modifier = Modifier.height(Dimension.D300))
        }
        AvatarGrid(
            emojis = pack.emojis,
            selected = selected,
            enabled = enabled,
            onSelect = onSelect,
        )
    }
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
    // BoxWithConstraints so the emoji typography scales with the actual
    // measured tile width (depends on screen size + column count). Keeps
    // the picker's emoji-to-tile ratio aligned with the rest of the
    // avatar surfaces.
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .border(borderWidth, borderColor, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = emoji,
            typography = avatarEmojiTypographyFor(maxWidth),
        )
    }
}

private const val GRID_COLUMNS = 4

@Composable
private fun BackgroundColorPicker(
    palette: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    // First swatch represents "no override" / theme default — null in
    // state. Subsequent swatches are server-supplied hex strings. Scrolls
    // horizontally so a wide palette can't clip the last swatch off-screen.
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScrollWithBar(rememberScrollState()),
    ) {
        ColorSwatch(
            colorHex = null,
            isSelected = selected == null,
            enabled = enabled,
            onClick = { onSelect(null) },
        )
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
    colorHex: String?,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val parsed = colorHex?.let {
        Catching { com.dangerfield.cards.libraries.ui.components.parseHexColor(it) }.getOrNull()
    }
    val swatchColor = parsed ?: AppTheme.colors.surfaceSecondary.color
    val borderColor = if (isSelected) AppTheme.colors.accentPrimary.color else AppTheme.colors.border.color
    val borderWidth = if (isSelected) 3.dp else 1.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(swatchColor)
            .border(borderWidth, borderColor, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        // The "default" swatch shows a subtle dot to signal "no override"
        // — otherwise it could read as a missing tile against the surface.
        if (colorHex == null) {
            Text(
                text = "—",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
    }
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
                avatarPacks = listOf(
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
                avatarPacks = listOf(
                    AvatarPack("starter", "Starter pack", listOf("🦊", "🐱", "🐼", "🐯")),
                    AvatarPack("space", "Space pack", listOf("🚀", "🛸", "🌙", "⭐", "🪐", "☄️")),
                ),
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
                avatarPacks = listOf(
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
