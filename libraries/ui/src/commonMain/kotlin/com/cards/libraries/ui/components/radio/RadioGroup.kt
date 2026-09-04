package com.dangerfield.cards.libraries.ui.components.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.bounceClick
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.PreviewContent
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.enums.EnumEntries

/**
 * A radio group that manages selection state for a list of radio items.
 * The entire content area of each item is clickable, not just the radio button.
 *
 * @param selectedIndex The currently selected item index
 * @param onItemSelected Callback when an item is selected
 * @param modifier Modifier for the radio group container
 * @param direction Layout direction (Vertical or Horizontal)
 * @param radioButtons DSL block for defining radio items
 */
@Composable
fun RadioGroup(
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    direction: LayoutDirection = LayoutDirection.Vertical,
    radioButtons: RadioGroupScope.() -> Unit,
) {
    val scope = RadioGroupScopeImpl()
    scope.radioButtons()

    DirectionWrapper(modifier, direction) {
        scope.items.forEachIndexed { index, item ->
            Box(
                modifier = Modifier
                    .then(if (direction == LayoutDirection.Vertical) Modifier.fillMaxWidth() else Modifier)
                    .bounceClick { onItemSelected(index) }
            ) {
                item(index == selectedIndex, index)
            }
        }
    }
}

/**
 * A radio group for enum values that manages selection state.
 * The entire content area of each item is clickable, not just the radio button.
 *
 * @param enumEntries The enum entries to display
 * @param selected The currently selected enum value
 * @param onItemSelected Callback when an item is selected
 * @param modifier Modifier for the radio group container
 * @param direction Layout direction (Vertical or Horizontal)
 * @param item Composable content for each enum item, receives the enum value and selection state
 */
@Composable
fun <T : Enum<T>> EnumRadioGroup(
    enumEntries: EnumEntries<T>,
    selected: T?,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    direction: LayoutDirection = LayoutDirection.Vertical,
    item: @Composable (value: T, isSelected: Boolean) -> Unit
) {
    DirectionWrapper(modifier, direction) {
        enumEntries.forEach { enumValue ->
            Box(
                modifier = Modifier
                    .then(if (direction == LayoutDirection.Vertical) Modifier.fillMaxWidth() else Modifier)
                    .bounceClick { onItemSelected(enumValue) }
            ) {
                item(enumValue, enumValue == selected)
            }
        }
    }
}

@Composable
private fun DirectionWrapper(
    modifier: Modifier = Modifier,
    direction: LayoutDirection = LayoutDirection.Vertical,
    content: @Composable () -> Unit
) {
    if (direction == LayoutDirection.Vertical) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
        }
    }
}

enum class LayoutDirection { Vertical, Horizontal }

private class RadioGroupScopeImpl : RadioGroupScope {
    val items = mutableListOf<@Composable (isSelected: Boolean, index: Int) -> Unit>()

    override fun Item(content: @Composable (isSelected: Boolean, index: Int) -> Unit) {
        items.add(content)
    }

    override fun Item(content: @Composable (isSelected: Boolean) -> Unit) {
        items.add { isSelected, index ->
            content(isSelected)
        }
    }
}

interface RadioGroupScope {
    fun Item(content: @Composable (isSelected: Boolean, index: Int,) -> Unit)

    fun Item(content: @Composable (isSelected: Boolean) -> Unit)
}

// Previews demonstrating usage patterns

private enum class NotificationPreference {
    ALL,
    MENTIONS_ONLY,
    OFF
}

@Preview
@Composable
private fun PreviewRadioGroupVertical() {
    PreviewContent {
        var selectedIndex by remember { mutableStateOf(0) }
        
        RadioGroup(
            selectedIndex = selectedIndex,
            onItemSelected = { selectedIndex = it }
        ) {
            Item { isSelected, index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Option One")
                }
            }
            Item { isSelected, index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Option Two")
                }
            }

            Item { isSelected, index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Option Three")
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewRadioGroupHorizontal() {
    PreviewContent {
        var selectedIndex by remember { mutableStateOf(1) }
        
        RadioGroup(
            selectedIndex = selectedIndex,
            onItemSelected = { selectedIndex = it },
            direction = LayoutDirection.Horizontal
        ) {
            Item { isSelected, index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Yes")
                }
            }
            Item { isSelected, index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text("No")
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewEnumRadioGroup() {
    PreviewContent {
        var selected by remember { mutableStateOf<NotificationPreference>(NotificationPreference.MENTIONS_ONLY) }
        
        EnumRadioGroup(
            enumEntries = NotificationPreference.entries,
            selected = selected,
            onItemSelected = { selected = it }
        ) { value, isSelected ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isSelected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when (value) {
                        NotificationPreference.ALL -> "All notifications"
                        NotificationPreference.MENTIONS_ONLY -> "Mentions only"
                        NotificationPreference.OFF -> "Off"
                    }
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewEnumRadioGroupWithDescriptions() {
    PreviewContent {
        var selected by remember { mutableStateOf<NotificationPreference>(NotificationPreference.ALL) }
        
        EnumRadioGroup(
            enumEntries = NotificationPreference.entries,
            selected = selected,
            onItemSelected = { selected = it }
        ) { value, isSelected ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isSelected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = when (value) {
                            NotificationPreference.ALL -> "All notifications"
                            NotificationPreference.MENTIONS_ONLY -> "Mentions only"
                            NotificationPreference.OFF -> "Off"
                        }
                    )
                    Text(
                        text = when (value) {
                            NotificationPreference.ALL -> "Get notified for every activity"
                            NotificationPreference.MENTIONS_ONLY -> "Only when someone mentions you"
                            NotificationPreference.OFF -> "Don't send any notifications"
                        }
                    )
                }
            }
        }
    }
}

