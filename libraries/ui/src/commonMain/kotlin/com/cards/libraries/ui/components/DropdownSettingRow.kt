package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import androidx.compose.ui.tooling.preview.Preview

/**
 * A single settings row whose value is picked via a small dropdown menu
 * anchored to the row — the overflow-menu pattern. Use this when a setting
 * has a fixed handful of options and a dedicated route would feel
 * overweight.
 *
 * Generic over the option type. Pair with `ListSection`-style sections (it
 * matches the visual rhythm of `ListSectionItem` because it composes
 * `ListItem` underneath).
 */
@Composable
fun <T> DropdownSettingRow(
    headlineText: String,
    supportingText: String?,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ListItem(
            headlineContent = { Text(text = headlineText) },
            supportingContent = supportingText?.let { { Text(text = it) } },
            accessory = ListItemAccessory.Text(text = label(selected)),
            onClick = { expanded = true },
            showDivider = showDivider,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                BasicDropdownMenuItem(
                    text = { Text(text = label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Preview
@Composable
private fun DropdownSettingRowPreview() {
    var selected by remember { mutableStateOf("Normal") }
    PreviewContent {
        DropdownSettingRow(
            headlineText = "Bot speed",
            supportingText = "How fast the bots act",
            options = listOf("Slow", "Normal", "Fast"),
            selected = selected,
            onSelect = { selected = it },
            label = { it },
        )
    }
}
