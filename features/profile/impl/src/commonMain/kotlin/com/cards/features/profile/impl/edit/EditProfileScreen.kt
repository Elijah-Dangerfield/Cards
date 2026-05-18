package com.dangerfield.cards.features.profile.impl.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
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
                Button(onClick = onBack, style = ButtonStyle.Text) {
                    Text("← Back")
                }
                Spacer(modifier = Modifier.height(Dimension.D700))

                Text(
                    text = "Edit profile",
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.onSurfacePrimary,
                )
                Spacer(modifier = Modifier.height(Dimension.D300))
                Text(
                    text = "Other players see this on the table and the leaderboard.",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
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

                AvatarGrid(
                    pack = state.avatarPack,
                    selected = state.selectedAvatarEmoji,
                    isLoading = state.isLoadingAvatars,
                    loadError = state.avatarLoadError,
                    enabled = !state.isSubmitting,
                    onSelect = { onAction(EditProfileAction.AvatarSelected(it)) },
                )

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
private fun AvatarGrid(
    pack: List<String>,
    selected: String?,
    isLoading: Boolean,
    loadError: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (isLoading && pack.isEmpty()) {
        Text(
            text = "Loading…",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimension.D800),
        )
        return
    }

    if (loadError && pack.size <= 1) {
        Text(
            text = "Avatars couldn't load. You can still save your current avatar.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
            modifier = Modifier.padding(bottom = Dimension.D400),
        )
    }

    // 5-wide grid with intrinsic height — the outer Column is scrollable,
    // so this grid renders all its rows at once. Compose can't have a
    // scrolling LazyVerticalGrid inside a verticalScroll, so we size by
    // row count: ceil(pack.size / 5) × tileHeight.
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        userScrollEnabled = false,
        modifier = Modifier
            .fillMaxWidth()
            .height(((pack.size + GRID_COLUMNS - 1) / GRID_COLUMNS * TILE_HEIGHT_DP).dp),
        verticalArrangement = Arrangement.spacedBy(Dimension.D300),
        horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
    ) {
        items(pack) { emoji ->
            AvatarTile(
                emoji = emoji,
                isSelected = emoji == selected,
                enabled = enabled,
                onClick = { onSelect(emoji) },
            )
        }
    }
}

@Composable
private fun AvatarTile(
    emoji: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) AppTheme.colors.accentPrimary.color else AppTheme.colors.border.color
    val borderWidth = if (isSelected) 3.dp else 1.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .border(borderWidth, borderColor, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = emoji,
            typography = AppTheme.typography.Heading.H700,
        )
    }
}

private const val GRID_COLUMNS = 5
private const val TILE_HEIGHT_DP = 60
