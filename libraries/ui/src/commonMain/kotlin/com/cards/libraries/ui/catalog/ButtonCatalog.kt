package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonAccent
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The button matrix: emphasis (type) × treatment (style) × state, plus the accent recolor and the
 * size ramp. Filled, enabled buttons show the hard 3D "lip" (press them in interactive preview to
 * see the face drop). Outlined / Text / disabled stay flat.
 */
@Preview(widthDp = 1100, heightDp = 1500)
@Composable
private fun ButtonCatalog() {
    CatalogPage(
        title = "Buttons",
        subtitle = "type = the only emphasis semantic. accent recolors a filled Primary. Filled+enabled get the 3D lip.",
    ) {
        CatalogSection("Filled — the emphasis ladder") {
            ButtonRow {
                Button(type = ButtonType.Primary, onClick = {}) { Text("Primary") }
                Button(type = ButtonType.Secondary, onClick = {}) { Text("Secondary") }
                Button(type = ButtonType.Ghost, onClick = {}) { Text("Ghost") }
                Button(type = ButtonType.Danger, onClick = {}) { Text("Danger") }
            }
        }

        CatalogSection("Outlined") {
            ButtonRow {
                Button(type = ButtonType.Primary, style = ButtonStyle.Outlined, onClick = {}) { Text("Primary") }
                Button(type = ButtonType.Secondary, style = ButtonStyle.Outlined, onClick = {}) { Text("Secondary") }
                Button(type = ButtonType.Danger, style = ButtonStyle.Outlined, onClick = {}) { Text("Danger") }
            }
        }

        CatalogSection("Text") {
            ButtonRow {
                Button(type = ButtonType.Primary, style = ButtonStyle.Text, onClick = {}) { Text("Primary") }
                Button(type = ButtonType.Secondary, style = ButtonStyle.Text, onClick = {}) { Text("Secondary") }
                Button(type = ButtonType.Ghost, style = ButtonStyle.Text, onClick = {}) { Text("Ghost") }
                Button(type = ButtonType.Danger, style = ButtonStyle.Text, onClick = {}) { Text("Danger") }
            }
        }

        CatalogSection("Accent — a filled Primary recolored (the rare two-CTA case)") {
            ButtonRow {
                Button(type = ButtonType.Primary, accent = ButtonAccent.Primary, onClick = {}) { Text("Primary") }
                Button(type = ButtonType.Primary, accent = ButtonAccent.Secondary, onClick = {}) { Text("Secondary") }
                Button(type = ButtonType.Primary, accent = ButtonAccent.Tertiary, onClick = {}) { Text("Tertiary") }
            }
        }

        CatalogSection("Disabled") {
            ButtonRow {
                Button(type = ButtonType.Primary, enabled = false, onClick = {}) { Text("Primary") }
                Button(type = ButtonType.Secondary, style = ButtonStyle.Outlined, enabled = false, onClick = {}) { Text("Outlined") }
                Button(type = ButtonType.Ghost, style = ButtonStyle.Text, enabled = false, onClick = {}) { Text("Text") }
                Button(type = ButtonType.Danger, enabled = false, onClick = {}) { Text("Danger") }
            }
        }

        CatalogSection("Size ramp") {
            ButtonRow {
                Button(size = ButtonSize.Large, onClick = {}) { Text("Large") }
                Button(size = ButtonSize.Medium, onClick = {}) { Text("Medium") }
                Button(size = ButtonSize.Small, onClick = {}) { Text("Small") }
                Button(size = ButtonSize.ExtraSmall, onClick = {}) { Text("ExtraSmall") }
            }
        }
    }
}

@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) { content() }
}
