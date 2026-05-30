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

private const val FORM_SUBTITLE =
    "Text field, switch, checkbox, radio. Focus reads as gold; selected = accentPrimary; disabled is " +
        "the muted ramp. Disabled is the only non-default interaction state (touch app)."

/** The forms page body. Reused by [DesignSystemPreview]. */
@Composable
internal fun FormCatalogBody() {
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

@Preview(widthDp = 1000, heightDp = 1100)
@Composable
private fun FormCatalog() {
    CatalogPage(title = "Forms", subtitle = FORM_SUBTITLE) { FormCatalogBody() }
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
