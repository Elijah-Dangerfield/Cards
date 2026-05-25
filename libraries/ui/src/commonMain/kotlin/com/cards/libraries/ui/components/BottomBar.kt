package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.Elevation
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.libraries.ui.components.BottomBarSizes.BottomPadding
import com.dangerfield.cards.libraries.ui.components.BottomBarSizes.TopPadding
import com.dangerfield.cards.libraries.ui.components.BottomBarSizes.bottomSafeAreaPadding
import com.dangerfield.cards.libraries.ui.components.icon.Filled
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.icon.Outlined
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD1600
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A tab in the [AppBottomBar]. Each item owns two composable slots —
 * one for the selected state and one for unselected — and the bar
 * crossfades between them on selection change. Slots can render
 * anything (a tinted [Icon], an [AvatarCircle], etc.), so adding a new
 * tab shape doesn't require touching the bar layout itself.
 *
 * The slots run inside a [BadgedBox], so the badge composes on top of
 * whatever the slot renders for free.
 */
sealed class BottomBarItem(
    val title: String,
    val selectedContent: @Composable () -> Unit,
    val unselectedContent: @Composable () -> Unit,
    open val badgeAmount: Int,
    open val isSelected: Boolean,
) {

    data class Home(override val isSelected: Boolean, override val badgeAmount: Int = 0) :
        BottomBarItem(
            title = "Home",
            isSelected = isSelected,
            badgeAmount = badgeAmount,
            selectedContent = { BottomBarTabIcon(Icons.Home.Filled("Home"), selected = true) },
            unselectedContent = { BottomBarTabIcon(Icons.Home.Outlined("Home"), selected = false) },
        )

    data class Shop(override val isSelected: Boolean, override val badgeAmount: Int = 0) :
        BottomBarItem(
            title = "Shop",
            isSelected = isSelected,
            badgeAmount = badgeAmount,
            selectedContent = { BottomBarTabIcon(Icons.Shop.Filled("Shop Tab"), selected = true) },
            unselectedContent = { BottomBarTabIcon(Icons.Shop.Outlined("Shop Tab"), selected = false) },
        )

    data class Profile(
        override val isSelected: Boolean,
        override val badgeAmount: Int = 0,
        /**
         * When [avatarDisplayName] is non-null, the Profile tab renders
         * the user's [AvatarCircle] in place of the generic person icon.
         * Unauthenticated / pre-resolve states pass null and get the
         * icon fallback.
         */
        val avatarDisplayName: String? = null,
        val avatarEmoji: String? = null,
        val avatarBackgroundColor: String? = null,
    ) : BottomBarItem(
        title = "Profile",
        isSelected = isSelected,
        badgeAmount = badgeAmount,
        selectedContent = {
            if (avatarDisplayName != null) {
                ProfileAvatarTab(avatarDisplayName, avatarEmoji, avatarBackgroundColor, selected = true)
            } else {
                BottomBarTabIcon(Icons.Person.Filled("Profile Tab"), selected = true)
            }
        },
        unselectedContent = {
            if (avatarDisplayName != null) {
                ProfileAvatarTab(avatarDisplayName, avatarEmoji, avatarBackgroundColor, selected = false)
            } else {
                BottomBarTabIcon(Icons.Person.Outlined("Profile Tab"), selected = false)
            }
        },
    )
}

/**
 * Default rendering for an icon-based bottom bar tab. Subclasses pass
 * the icon resource (filled or outlined) along with whether the tab is
 * currently selected; this helper picks the text vs. textDisabled tint.
 * Exposed so future tabs don't need to re-derive the tint pair.
 */
@Composable
fun BottomBarTabIcon(icon: IconResource, selected: Boolean) {
    Icon(
        size = BottomBarSizes._IconSize,
        icon = icon,
        color = if (selected) AppTheme.colors.text else AppTheme.colors.textDisabled,
    )
}

/**
 * Profile-tab avatar rendering. The selection ring uses the same
 * text/textDisabled pair as [BottomBarTabIcon] so the avatar variant
 * reads as "this tab is selected" the same way an icon tab would.
 */
@Composable
private fun ProfileAvatarTab(
    displayName: String,
    emoji: String?,
    backgroundColorHex: String?,
    selected: Boolean,
) {
    val ringColor = if (selected) AppTheme.colors.text else AppTheme.colors.textDisabled
    AvatarCircle(
        name = displayName,
        emoji = emoji,
        backgroundColorHex = backgroundColorHex,
        size = BottomBarSizes._IconSize.dp,
        modifier = Modifier.border(
            width = 1.5.dp,
            color = ringColor.color,
            shape = CircleShape,
        ),
    )
}

@Composable
fun AppBottomBar(
    modifier: Modifier = Modifier,
    items: List<BottomBarItem>,
    onItemClick: (BottomBarItem) -> Unit
) {
    val shadowColor = AppTheme.colors.backgroundOverlay.withAlpha(0.25f).color

    val selectedIndex = items.indexOfFirst { it.isSelected }.coerceAtLeast(0)
    Surface(
        modifier = modifier.fillMaxWidth()
            .dropShadow(RectangleShape) {
                this.radius = 10f
                this.offset = Offset(0f, -10f)
                this.color = shadowColor
            },
        elevation = Elevation.BottomBar,
        color = AppTheme.colors.surfacePrimary,
        contentColor = AppTheme.colors.onBackground,
        border = null,
        radius = Radii.None,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bottomSafeAreaPadding()
                .padding(horizontal = BottomBarSizes.RowHorizontalPadding),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .bounceClick { onItemClick(item) },
                    contentAlignment = Alignment.Center
                ) {
                    MagnifyingBottomBarItem(
                        item = item,
                        isSelected = index == selectedIndex,
                        modifier = Modifier.padding(top = TopPadding, bottom = BottomPadding),
                    )
                }
            }
        }
    }
}

/**
 * Reserves bottom space equal to the app's [AppBottomBar] resting
 * height so trailing content (footer text, last CTA) stays visible
 * regardless of whether the bar is animating in or out.
 *
 * Use as the last child of a scrolling Column on tab screens (Home,
 * Shop, Profile). Non-tab screens hide the bar entirely and shouldn't
 * reserve it.
 *
 * A constant reservation (rather than reading live `paddingValues`)
 * keeps scroll position stable while the bar animates — content
 * doesn't reflow as the bar slides on or off screen.
 */
@Composable
fun BottomBarSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(BottomBarSizes.BottomBarVerticalHeight + Dimension.D500)
            .bottomSafeAreaPadding()
    )
}

@Composable
private fun MagnifyingBottomBarItem(
    item: BottomBarItem,
    isSelected: Boolean,
    modifier: Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scale"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.scale(scale),
            contentAlignment = Alignment.Center
        ) {
            BadgedBox(
                badgeTranslation = DpOffset(x = (-5).dp, y = (5).dp),
                badge = {
                    if (item.badgeAmount > 0) {
                        BottomBarBadge(
                            count = item.badgeAmount
                        )
                    }
                }
            ) {
                // Crossfade between the two slots so selection feels
                // smooth regardless of what each slot renders (a
                // filled-vs-outlined icon, an avatar with/without
                // ring, etc.). The old code interpolated icon color
                // directly; Crossfade is the generalized version that
                // works for any slot content.
                Crossfade(
                    targetState = isSelected,
                    animationSpec = tween(220),
                    label = "tabContent",
                ) { selected ->
                    if (selected) item.selectedContent() else item.unselectedContent()
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomBarBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    Badge(
        modifier = modifier,
        containerColor = AppTheme.colors.accentPrimary.color,
        contentColor = AppTheme.colors.onSurfacePrimary.color
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = Dimension.D200,
                vertical = Dimension.D25
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                typography = AppTheme.typography.Caption.C200.SemiBold,
            )
        }
    }
}

@Preview(heightDp = 200)
@Composable
private fun BottomBarPreviewHome() {
    PreviewContent(
        backgroundColor = ColorResource.White
    ) {
        Column() {
            VerticalSpacerD1600()

            AppBottomBar(

                items = listOf(
                    BottomBarItem.Home(isSelected = true),
                    BottomBarItem.Shop(isSelected = false),
                    BottomBarItem.Profile(isSelected = false),
                ),
                onItemClick = {},
            )
            VerticalSpacerD1600()

        }
    }
}



@Preview(heightDp = 200)
@Composable
private fun BottomBarPreviewActivity() {
    PreviewContent(
        backgroundColor = ColorResource.White
    ) {
        Column() {
            VerticalSpacerD1600()

            AppBottomBar(
                items = listOf(
                    BottomBarItem.Home(isSelected = false),
                    BottomBarItem.Shop(isSelected = true, badgeAmount = 3),
                    BottomBarItem.Profile(isSelected = false),
                ),
                onItemClick = {},
            )
            VerticalSpacerD1600()


        }
    }
}


@Preview(heightDp = 200)
@Composable
private fun BottomBarPreviewProfile() {
    PreviewContent(
        backgroundColor = ColorResource.White
    ) {
        Column() {

            VerticalSpacerD1600()

            AppBottomBar(
                items = listOf(
                    BottomBarItem.Home(isSelected = false, badgeAmount = 12),
                    BottomBarItem.Shop(isSelected = false, badgeAmount = 5),
                    BottomBarItem.Profile(isSelected = true),
                ),
                onItemClick = {},
            )

            VerticalSpacerD1600()
        }
    }
}

// Pins the avatar-variant path: when the Profile item carries a name +
// emoji + palette hex, the tab swaps the generic person icon for the
// user's AvatarCircle. Unselected here — ring uses textDisabled.
@Preview(heightDp = 200)
@Composable
private fun BottomBarPreviewProfileAvatar_Unselected() {
    PreviewContent(
        backgroundColor = ColorResource.White
    ) {
        Column {
            VerticalSpacerD1600()
            AppBottomBar(
                items = listOf(
                    BottomBarItem.Home(isSelected = true),
                    BottomBarItem.Shop(isSelected = false),
                    BottomBarItem.Profile(
                        isSelected = false,
                        avatarDisplayName = "Elijah",
                        avatarEmoji = "🦊",
                        avatarBackgroundColor = "#E48A58",
                    ),
                ),
                onItemClick = {},
            )
            VerticalSpacerD1600()
        }
    }
}

// Selected avatar — ring uses the text token, scale animates up. Also
// pins the badge stacking on top of the avatar (notifications still
// land on the Profile tab regardless of icon vs. avatar rendering).
@Preview(heightDp = 200)
@Composable
private fun BottomBarPreviewProfileAvatar_SelectedWithBadge() {
    PreviewContent(
        backgroundColor = ColorResource.White
    ) {
        Column {
            VerticalSpacerD1600()
            AppBottomBar(
                items = listOf(
                    BottomBarItem.Home(isSelected = false),
                    BottomBarItem.Shop(isSelected = false),
                    BottomBarItem.Profile(
                        isSelected = true,
                        badgeAmount = 4,
                        avatarDisplayName = "Elijah",
                        avatarEmoji = "🦊",
                        avatarBackgroundColor = "#E48A58",
                    ),
                ),
                onItemClick = {},
            )
            VerticalSpacerD1600()
        }
    }
}

// Pins the fallback branch: even if the Profile item *is* the avatar
// variant in shape, a missing displayName (pre-resolve / unauthenticated
// fallback) falls back to the person icon so the bar still renders.
@Preview(heightDp = 200)
@Composable
private fun BottomBarPreviewProfileAvatar_FallbackToIcon() {
    PreviewContent(
        backgroundColor = ColorResource.White
    ) {
        Column {
            VerticalSpacerD1600()
            AppBottomBar(
                items = listOf(
                    BottomBarItem.Home(isSelected = false),
                    BottomBarItem.Shop(isSelected = false),
                    BottomBarItem.Profile(
                        isSelected = true,
                        avatarDisplayName = null,
                        avatarEmoji = "🦊",
                        avatarBackgroundColor = "#E48A58",
                    ),
                ),
                onItemClick = {},
            )
            VerticalSpacerD1600()
        }
    }
}

object BottomBarSizes {
    val RowHorizontalPadding = Dimension.D700

    val TopPadding = Dimension.D600
    val BottomPadding = Dimension.D400

    val _IconSize = IconSize.AppBar

    @Composable
    fun Modifier.bottomSafeAreaPadding() = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))

    val BottomBarVerticalHeight: Dp =
        TopPadding + BottomPadding + _IconSize.dp
}

/**
 * Reports the height of the currently-visible [AppBottomBar] (without
 * system safe-area inset; consumers add that themselves). `0.dp` means
 * "no bar showing", which is the default for non-tab screens.
 *
 * Set by `AppNavigation` based on whether the bar slot is rendered.
 * Read by overlays (the snackbar host, in-app message banners, future
 * floating toasts) that need to float *above* the bar instead of being
 * occluded by it. Single source of truth: changing the bar height
 * propagates to every overlay automatically.
 */
val LocalAppBottomBarHeight: androidx.compose.runtime.ProvidableCompositionLocal<Dp> =
    androidx.compose.runtime.compositionLocalOf { 0.dp }

