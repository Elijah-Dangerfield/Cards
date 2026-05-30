package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonAccent
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.Dimension

internal const val BUTTON_SUBTITLE =
    "type is the only emphasis semantic (Primary > Secondary > Ghost; Danger is destructive). style " +
        "picks the treatment; accent recolors a filled Primary. Filled + enabled buttons get the 3D lip."

/** The button page body. Reused by [DesignSystemPreview]. */
@Composable
internal fun ButtonCatalogBody() {
    CatalogSection(
        "Filled — emphasis ladder",
        "The default treatment. One Primary per screen (the main CTA); Secondary for the supporting action; Danger for destructive.",
    ) {
        ButtonRow {
            Button(type = ButtonType.Primary, onClick = {}) { Text("Primary") }
            Button(type = ButtonType.Secondary, onClick = {}) { Text("Secondary") }
            Button(type = ButtonType.Ghost, onClick = {}) { Text("Ghost") }
            Button(type = ButtonType.Danger, onClick = {}) { Text("Danger") }
        }
    }

    CatalogSection(
        "Outlined",
        "One step down in emphasis. Good on busy surfaces, or as the secondary action next to a filled Primary.",
    ) {
        ButtonRow {
            Button(type = ButtonType.Primary, style = ButtonStyle.Outlined, onClick = {}) { Text("Primary") }
            Button(type = ButtonType.Secondary, style = ButtonStyle.Outlined, onClick = {}) { Text("Secondary") }
            Button(type = ButtonType.Danger, style = ButtonStyle.Outlined, onClick = {}) { Text("Danger") }
        }
    }

    CatalogSection(
        "Text",
        "Lowest weight — inline links and dismiss actions (\"Not now\", \"Forgot password?\").",
    ) {
        ButtonRow {
            Button(type = ButtonType.Primary, style = ButtonStyle.Text, onClick = {}) { Text("Primary") }
            Button(type = ButtonType.Secondary, style = ButtonStyle.Text, onClick = {}) { Text("Secondary") }
            Button(type = ButtonType.Ghost, style = ButtonStyle.Text, onClick = {}) { Text("Ghost") }
            Button(type = ButtonType.Danger, style = ButtonStyle.Text, onClick = {}) { Text("Danger") }
        }
    }

    CatalogSection(
        "Accent",
        "A filled Primary recolored — only for the rare screen with two primary-level CTAs in different brand colors.",
    ) {
        ButtonRow {
            Button(type = ButtonType.Primary, accent = ButtonAccent.Primary, onClick = {}) { Text("Primary") }
            Button(type = ButtonType.Primary, accent = ButtonAccent.Secondary, onClick = {}) { Text("Secondary") }
            Button(type = ButtonType.Primary, accent = ButtonAccent.Tertiary, onClick = {}) { Text("Tertiary") }
        }
    }

    CatalogSection(
        "Disabled",
        "The only non-default interaction state (this is a touch app). Fill flattens, lip drops, color mutes.",
    ) {
        ButtonRow {
            Button(type = ButtonType.Primary, enabled = false, onClick = {}) { Text("Primary") }
            Button(type = ButtonType.Secondary, style = ButtonStyle.Outlined, enabled = false, onClick = {}) { Text("Outlined") }
            Button(type = ButtonType.Ghost, style = ButtonStyle.Text, enabled = false, onClick = {}) { Text("Text") }
            Button(type = ButtonType.Danger, enabled = false, onClick = {}) { Text("Danger") }
        }
    }

    CatalogSection(
        "Size ramp",
        "Large for primary CTAs and dialogs; Medium is the workhorse; Small / ExtraSmall for dense rows, chips, and inline actions.",
    ) {
        ButtonRow {
            Button(size = ButtonSize.Large, onClick = {}) { Text("Large") }
            Button(size = ButtonSize.Medium, onClick = {}) { Text("Medium") }
            Button(size = ButtonSize.Small, onClick = {}) { Text("Small") }
            Button(size = ButtonSize.ExtraSmall, onClick = {}) { Text("ExtraSmall") }
        }
    }
}

@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) { content() }
}
