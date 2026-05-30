package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.Switch
import com.dangerfield.cards.libraries.ui.components.checkbox.Checkbox
import com.dangerfield.cards.libraries.ui.components.radio.RadioButton
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Form controls in every state. Focus now reads as gold, selected radios/switches/checkboxes use
 * the accent, and disabled is the only other interaction state (this is a touch app).
 */
@Preview(widthDp = 1000, heightDp = 1100)
@Composable
private fun FormCatalog() {
    CatalogPage(
        title = "Forms",
        subtitle = "Text field, switch, checkbox, radio. Selected = accentPrimary; disabled = the muted ramp.",
    ) {
        CatalogSection("Text field") {
            Column(
                modifier = Modifier.width(420.dp),
                verticalArrangement = Arrangement.spacedBy(Dimension.D500),
            ) {
                OutlinedTextField(value = "Phil Ivey", onValueChange = {})
                OutlinedTextField(value = "", onValueChange = {}, placeholder = { Text("Display name") })
                OutlinedTextField(value = "not-an-email", onValueChange = {}, isError = true)
                OutlinedTextField(value = "Read only", onValueChange = {}, enabled = false)
            }
        }

        CatalogSection("Switch") {
            ControlRow {
                Labeled("On") { Switch(checked = true, onCheckedChange = {}) }
                Labeled("Off") { Switch(checked = false, onCheckedChange = {}) }
                Labeled("On · disabled") { Switch(checked = true, onCheckedChange = {}, enabled = false) }
                Labeled("Off · disabled") { Switch(checked = false, onCheckedChange = {}, enabled = false) }
            }
        }

        CatalogSection("Checkbox") {
            ControlRow {
                Labeled("Checked") { Checkbox(checked = true, onCheckedChange = {}) }
                Labeled("Unchecked") { Checkbox(checked = false, onCheckedChange = {}) }
                Labeled("Checked · disabled") { Checkbox(checked = true, onCheckedChange = {}, enabled = false) }
                Labeled("Unchecked · disabled") { Checkbox(checked = false, onCheckedChange = {}, enabled = false) }
            }
        }

        CatalogSection("Radio") {
            ControlRow {
                Labeled("Selected") { RadioButton(selected = true, onClick = {}) }
                Labeled("Unselected") { RadioButton(selected = false, onClick = {}) }
                Labeled("Selected · disabled") { RadioButton(selected = true, onClick = {}, enabled = false) }
                Labeled("Unselected · disabled") { RadioButton(selected = false, onClick = {}, enabled = false) }
            }
        }
    }
}

@Composable
private fun ControlRow(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D800),
    ) { content() }
}

@Composable
private fun Labeled(label: String, control: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimension.D300),
    ) {
        control()
        Text(
            text = label,
            typography = AppTheme.typography.Caption.C300,
            color = AppTheme.colors.contentSecondary,
        )
    }
}
