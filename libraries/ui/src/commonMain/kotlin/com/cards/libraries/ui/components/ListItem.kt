package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.ProvideTextConfig
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD500
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.thenIf
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ListSection(
    items: List<ListSectionItem>,
    modifier: Modifier = Modifier,
    title: String? = null,
    supportingText: String? = null,
    backgroundColor: ColorResource = AppTheme.colors.surface,
    dividerColor: ColorResource = AppTheme.colors.border,
) {
    if (items.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        if (title != null) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H700
            )
            VerticalSpacerD200()
        }

        if (supportingText != null) {
            Text(
                text = supportingText,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary
            )
            VerticalSpacerD200()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.Card.shape)
                .background(backgroundColor.color)

        ) {
            items.forEachIndexed { index, item ->
                ListItem(
                    modifier = Modifier,
                    leadingContent = item.leadingContent,
                    headlineContent = {
                        Text(
                            text = item.headlineText,
                            typography = ListItemDefaults.headlineTypography()
                        )
                    },
                    supportingContent = item.supportingText?.let { text ->
                        {
                            Text(
                                text = text,
                                typography = ListItemDefaults.supportingTypography()
                            )
                        }
                    },
                    accessory = item.accessory,
                    enabled = item.enabled,
                    onClick = item.onClick,
                    showDivider = index != items.lastIndex,
                    dividerStartInset = item.dividerStartInset
                        ?: ListItemDefaults.dividerStartInset(item.leadingContent != null),
                    dividerColor = dividerColor
                )
            }
        }
    }
}

data class ListSectionItem(
    val headlineText: String,
    val supportingText: String? = null,
    val leadingContent: (@Composable () -> Unit)? = null,
    val accessory: ListItemAccessory = ListItemAccessory.Chevron,
    val onClick: (() -> Unit)? = null,
    val enabled: Boolean = true,
    val dividerStartInset: Dp? = null,
)

@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    accessory: ListItemAccessory = ListItemAccessory.Chevron,
    contentPadding: PaddingValues = ListItemDefaults.contentPadding(),
    showDivider: Boolean = false,
    dividerStartInset: Dp = ListItemDefaults.dividerStartInset(leadingContent != null),
    dividerColor: ColorResource = AppTheme.colors.borderStrong,
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Dimension.D1400)
                .thenIf(supportingContent != null) {
                    padding(vertical = Dimension.D400)
                }
                .then(
                    if (onClick != null) {
                        Modifier.bounceClick(
                            enabled = enabled,
                            onClick = onClick
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingContent != null) {
                Box(Modifier.padding(end = Dimension.D500)) {
                    leadingContent()
                }
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                ProvideTextConfig(
                    color = if (enabled) AppTheme.colors.content else AppTheme.colors.contentDisabled
                ) {
                    headlineContent()
                }

                if (supportingContent != null) {
                    VerticalSpacerD200()
                    ProvideTextConfig(
                        color = if (enabled) AppTheme.colors.contentSecondary else AppTheme.colors.contentDisabled
                    ) {
                        supportingContent()
                    }
                }
            }

            val hasAccessory = accessory !is ListItemAccessory.None

            if (hasAccessory) {
                HorizontalSpacerD500()
                Accessory(accessory = accessory, enabled = enabled)
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = dividerColor,
                modifier = Modifier.padding(start = dividerStartInset)
            )
        }
    }
}

sealed interface ListItemAccessory {
    data object None : ListItemAccessory
    data object Chevron : ListItemAccessory
    data class Icon(
        val icon: IconResource,
        val tint: ColorResource? = null,
        val size: IconSize = IconSize.Small,
    ) : ListItemAccessory

    data class Switch(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val enabled: Boolean = true,
    ) : ListItemAccessory

    data class Text(
        val text: String,
        val typography: TypographyResource? = null,
        val color: ColorResource? = null,
    ) : ListItemAccessory

    class Custom(
        val content: @Composable () -> Unit
    ) : ListItemAccessory
}

object ListItemDefaults {
    private val HorizontalPadding = Dimension.D500
    private val VerticalPadding = Dimension.D0

    fun contentPadding(): PaddingValues = PaddingValues(
        horizontal = HorizontalPadding,
        vertical = VerticalPadding
    )

    fun dividerStartInset(
        hasLeadingContent: Boolean,
        leadingContentWidth: Dp = IconSize.Small.dp
    ): Dp {
        val leadingInset = if (hasLeadingContent) {
            leadingContentWidth + Dimension.D500
        } else {
            0.dp
        }
        return Dimension.D500 + leadingInset
    }

    @Composable
    fun headlineTypography(): TypographyResource = AppTheme.typography.Body.B700.SemiBold

    @Composable
    fun supportingTypography(): TypographyResource = AppTheme.typography.Body.B500
}

@Composable
private fun Accessory(
    accessory: ListItemAccessory,
    enabled: Boolean
) {
    when (accessory) {
        ListItemAccessory.None -> Unit

        ListItemAccessory.Chevron ->
            Icon(
                icon = Icons.ChevronRight("Navigate"),
                color = AppTheme.colors.content,
                size = IconSize.Small
            )

        is ListItemAccessory.Icon ->
            Icon(
                icon = accessory.icon,
                color = accessory.tint ?: AppTheme.colors.content,
                size = accessory.size
            )

        is ListItemAccessory.Switch ->
            Switch(
                checked = accessory.checked,
                onCheckedChange = accessory.onCheckedChange,
                enabled = enabled && accessory.enabled
            )


        is ListItemAccessory.Text ->
            Text(
                text = accessory.text,
                typography = accessory.typography ?: AppTheme.typography.Body.B600,
                color = accessory.color
                    ?: if (enabled) AppTheme.colors.contentSecondary else AppTheme.colors.contentDisabled
            )

        is ListItemAccessory.Custom -> accessory.content()
    }
}

@Preview
@Composable
private fun ListSectionPreview() {
    PreviewContent {
        Box(Modifier.padding(horizontal = Dimension.D500)) {
            ListSection(
                title = "General",
                items = listOf(
                    ListSectionItem(
                        headlineText = "Edit Name",
                        leadingContent = { Icon(Icons.Pencil("Charity")) },
                        onClick = {}
                    ),
                    ListSectionItem(
                        headlineText = "Edit Donation",
                        supportingText = "Change the charity you support",
                        leadingContent = { Icon(Icons.Charity("Charity")) },
                        onClick = {}
                    ),
                    ListSectionItem(
                        headlineText = "Tip Jar",
                        leadingContent = { Icon(Icons.TipJar("Tip Jar")) },
                        accessory = ListItemAccessory.None,
                        onClick = {}
                    )
                )
            )
        }
    }
}

@Preview
@Composable
private fun ListSectionPreviewWithSwitches() {
    PreviewContent {
        var encouragement by remember { mutableStateOf(true) }
        var violations by remember { mutableStateOf(false) }

        Box(Modifier.padding(horizontal = Dimension.D500)) {
            ListSection(
                title = "Notifications",
                items = listOf(
                    ListSectionItem(
                        headlineText = "Encouragement",
                        accessory = ListItemAccessory.Switch(
                            checked = encouragement,
                            onCheckedChange = { encouragement = it }
                        )
                    ),
                    ListSectionItem(
                        headlineText = "Violations",
                        accessory = ListItemAccessory.Switch(
                            checked = violations,
                            onCheckedChange = { violations = it }
                        )
                    )
                )
            )
        }
    }
}


