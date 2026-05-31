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

internal const val SWITCH_SUBTITLE = "Immediate on/off setting (no save step). On = accentPrimary track."
internal const val CHECKBOX_SUBTITLE = "Multi-select, or a single opt-in. Checked fill = accentPrimary."
internal const val RADIO_SUBTITLE = "Pick exactly one from a small set. Selected ring = accentPrimary."
internal const val TEXT_FIELD_SUBTITLE = "Single- or multi-line input. Focus edge = accentPrimary; error = danger."

/** All form controls — used by [DesignSystemPreview]. Each control also has its own body below so a
 *  component file can preview just itself. */
@Composable
internal fun FormCatalogBody() {
    TextFieldCatalogBody()
    SwitchCatalogBody()
    CheckboxCatalogBody()
    RadioCatalogBody()
}

@Composable
internal fun TextFieldCatalogBody() {
    CatalogSection(
        "Text field",
        "Single- or multi-line input. States: rest, placeholder (empty), error (danger edge), disabled.",
    ) {
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
}

@Composable
internal fun SwitchCatalogBody() {
    CatalogSection(
        "Switch",
        "Immediate on/off setting (no save step). On = accentPrimary track.",
    ) {
        ControlRow {
            Labeled("On") { Switch(checked = true, onCheckedChange = {}) }
            Labeled("Off") { Switch(checked = false, onCheckedChange = {}) }
            Labeled("On · disabled") { Switch(checked = true, onCheckedChange = {}, enabled = false) }
            Labeled("Off · disabled") { Switch(checked = false, onCheckedChange = {}, enabled = false) }
        }
    }
}

@Composable
internal fun CheckboxCatalogBody() {
    CatalogSection(
        "Checkbox",
        "Multi-select, or a single opt-in (terms, \"don't show again\"). Checked fill = accentPrimary.",
    ) {
        ControlRow {
            Labeled("Checked") { Checkbox(checked = true, onCheckedChange = {}) }
            Labeled("Unchecked") { Checkbox(checked = false, onCheckedChange = {}) }
            Labeled("Checked · disabled") { Checkbox(checked = true, onCheckedChange = {}, enabled = false) }
            Labeled("Unchecked · disabled") { Checkbox(checked = false, onCheckedChange = {}, enabled = false) }
        }
    }
}

@Composable
internal fun RadioCatalogBody() {
    CatalogSection(
        "Radio",
        "Pick exactly one from a small set. Selected ring = accentPrimary; unselected = borderStrong.",
    ) {
        ControlRow {
            Labeled("Selected") { RadioButton(selected = true, onClick = {}) }
            Labeled("Unselected") { RadioButton(selected = false, onClick = {}) }
            Labeled("Selected · disabled") { RadioButton(selected = true, onClick = {}, enabled = false) }
            Labeled("Unselected · disabled") { RadioButton(selected = false, onClick = {}, enabled = false) }
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
