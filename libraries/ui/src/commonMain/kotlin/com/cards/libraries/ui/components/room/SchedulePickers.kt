package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_schedule_am
import cards.libraries.resources.generated.resources.ui_schedule_pm
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun WeekdayPicker(selected: DayOfWeek, onSelect: (DayOfWeek) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimension.D100)) {
        DayOfWeek.entries.forEach { day ->
            val label = stringResource(weekdayNameResource(day))
            Button(
                onClick = { onSelect(day) },
                type = if (day == selected) ButtonType.Primary else ButtonType.Secondary,
                size = ButtonSize.Small,
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = label
                    this.selected = day == selected
                },
            ) {
                Text(label.take(1), typography = AppTheme.typography.Label.L300)
            }
        }
    }
}

@Composable
fun TimeOfDayPicker(time: LocalTime, onChange: (LocalTime) -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimension.D300)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
        ) {
            Button(
                onClick = { onChange(LocalTime((time.hour + 23) % 24, time.minute)) },
                type = ButtonType.Secondary,
                size = ButtonSize.Small,
                enabled = enabled,
            ) {
                Text("−")
            }
            Text(
                text = standingTimeLabel(time),
                modifier = Modifier.weight(1f),
                typography = AppTheme.typography.Heading.H700,
            )
            Button(
                onClick = { onChange(LocalTime((time.hour + 1) % 24, time.minute)) },
                type = ButtonType.Secondary,
                size = ButtonSize.Small,
                enabled = enabled,
            ) {
                Text("+")
            }
            Button(
                onClick = { onChange(LocalTime((time.hour + 12) % 24, time.minute)) },
                type = ButtonType.Secondary,
                style = ButtonStyle.Outlined,
                size = ButtonSize.Small,
                enabled = enabled,
            ) {
                Text(stringResource(if (time.hour < 12) Res.string.ui_schedule_am else Res.string.ui_schedule_pm))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimension.D200)) {
            listOf(0, 15, 30, 45).forEach { minute ->
                Button(
                    onClick = { onChange(LocalTime(time.hour, minute)) },
                    type = if (minute == time.minute) ButtonType.Primary else ButtonType.Secondary,
                    size = ButtonSize.Small,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(minute.toString().padStart(2, '0'))
                }
            }
        }
    }
}

@Preview
@Composable
private fun WeekdayPickerPreview_Thursday() {
    PreviewContent { WeekdayPicker(DayOfWeek.THURSDAY, {}) }
}

@Preview
@Composable
private fun TimeOfDayPickerPreview_Evening() {
    PreviewContent { TimeOfDayPicker(LocalTime(20, 0), {}) }
}
