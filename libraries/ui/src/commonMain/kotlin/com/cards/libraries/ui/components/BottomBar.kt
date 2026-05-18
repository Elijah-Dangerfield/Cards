package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.dangerfield.cards.libraries.ui.system.color.animateColorResourceAsState
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD1600
import org.jetbrains.compose.ui.tooling.preview.Preview

sealed class BottomBarItem(
    val title: String,
    val selectedIcon: @Composable () -> IconResource,
    val unselectedIcon: @Composable () -> IconResource,
    open val badgeAmount: Int,
    open val isSelected: Boolean,
) {

    data class Home(override val isSelected: Boolean, override val badgeAmount: Int = 0) :
        BottomBarItem(
            title = "Home",
            isSelected = isSelected,
            selectedIcon = { Icons.Home.Filled("Home") },
            unselectedIcon = { Icons.Home.Outlined("Home") },
            badgeAmount = badgeAmount
        )

    data class Shop(override val isSelected: Boolean, override val badgeAmount: Int = 0) :
        BottomBarItem(
            title = "Shop",
            isSelected = isSelected,
            selectedIcon = { Icons.Shop.Filled("Shop Tab") },
            unselectedIcon = { Icons.Shop.Outlined("Shop Tab") },
            badgeAmount = badgeAmount
        )

    data class Profile(override val isSelected: Boolean, override val badgeAmount: Int = 0) :
        BottomBarItem(
            title = "Profile",
            isSelected = isSelected,
            selectedIcon = { Icons.Person.Filled("Profile Tab") },
            unselectedIcon = { Icons.Person.Outlined("Profile Tab") },
            badgeAmount = badgeAmount
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

// used by screens with the bottom bar + scrolling to ensure all content is visible.
@Composable
fun BottomBarSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(BottomBarSizes.BottomBarVerticalHeight + Dimension.D500) // adds just a little so content isnt right up against the bar
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
        targetValue = if (isSelected) 1.2f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scale"
    )

    val iconColor by animateColorResourceAsState(
        targetValue = if (isSelected) {
            AppTheme.colors.text
        } else {
            AppTheme.colors.textDisabled
        },
        animationSpec = tween(220),
        label = "iconColor"
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
                badge = {
                    if (item.badgeAmount > 0) {
                        BottomBarBadge(item.badgeAmount)
                    }
                }
            ) {
                Box(
                    modifier = Modifier,
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        size = BottomBarSizes._IconSize,
                        icon = if (isSelected) item.selectedIcon() else item.unselectedIcon(),
                        color = iconColor
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomBarBadge(count: Int) {
    Badge(
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
 * Reserves bottom space equal to the app's [AppBottomBar] resting height so
 * trailing content (footer text, last CTA) stays visible regardless of whether
 * the bar is animating in or out.
 *
 * Use as the last child of a scrolling Column on tab screens (Home, Shop,
 * Profile). Non-tab screens hide the bar entirely and shouldn't reserve it.
 *
 * A constant reservation (rather than reading live `paddingValues`) keeps
 * scroll position stable while the bar animates — content doesn't reflow as
 * the bar slides on or off screen.
 */
@Composable
fun BottomBarSpacer() {
    Spacer(modifier = Modifier.height(BottomBarSizes.BottomBarVerticalHeight))
}
