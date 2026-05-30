package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The full type scale, family by family, rendered live. Display is the serif felt headline (and
 * its italic is the dialog/sheet title signature); Heading/Label/Body/Caption are the sans ramp.
 */
@Preview(widthDp = 1100, heightDp = 2400)
@Composable
private fun TypographyCatalog() {
    CatalogPage(
        title = "Typography",
        subtitle = "Display = serif (felt headline). Heading / Label / Body / Caption = sans. Modifiers: .Italic, .Bold, .SemiBold, …",
    ) {
        val t = AppTheme.typography

        CatalogSection("Display · serif") {
            TypeRow("Display.D1500", t.Display.D1500, "Royal flush")
            TypeRow("Display.D1300", t.Display.D1300, "Royal flush")
            TypeRow("Display.D1100", t.Display.D1100, "Royal flush")
            TypeRow("Display.D1000", t.Display.D1000, "Royal flush")
            TypeRow("Display.D900", t.Display.D900, "Royal flush")
            TypeRow("Display.D900.Italic", t.Display.D900.Italic, "Daily reward")
            TypeRow("Display.D800.Italic", t.Display.D800.Italic, "Leave table?")
        }

        CatalogSection("Heading · sans") {
            TypeRow("Heading.H1100", t.Heading.H1100, "Tournaments")
            TypeRow("Heading.H900", t.Heading.H900, "Tournaments")
            TypeRow("Heading.H800", t.Heading.H800, "Tournaments")
            TypeRow("Heading.H700", t.Heading.H700, "Tournaments")
            TypeRow("Heading.H600", t.Heading.H600, "Tournaments")
            TypeRow("Heading.H500", t.Heading.H500, "Tournaments")
            TypeRow("Heading.H400", t.Heading.H400, "Tournaments")
        }

        CatalogSection("Body · sans") {
            TypeRow("Body.B700", t.Body.B700, "Blinds are forced bets that rotate each hand.")
            TypeRow("Body.B600", t.Body.B600, "Blinds are forced bets that rotate each hand.")
            TypeRow("Body.B500", t.Body.B500, "Blinds are forced bets that rotate each hand.")
            TypeRow("Body.B400", t.Body.B400, "Blinds are forced bets that rotate each hand.")
        }

        CatalogSection("Label · sans (UI elements)") {
            TypeRow("Label.L800", t.Label.L800, "Continue")
            TypeRow("Label.L700", t.Label.L700, "Continue")
            TypeRow("Label.L600", t.Label.L600, "Continue")
            TypeRow("Label.L500", t.Label.L500, "Continue")
            TypeRow("Label.L400", t.Label.L400, "Continue")
            TypeRow("Label.L300", t.Label.L300, "Continue")
        }

        CatalogSection("Caption · sans (metadata)") {
            TypeRow("Caption.C400", t.Caption.C400, "42m remaining")
            TypeRow("Caption.C300", t.Caption.C300, "42m remaining")
            TypeRow("Caption.C200", t.Caption.C200, "42m remaining")
        }
    }
}

@Composable
private fun TypeRow(token: String, typography: TypographyResource, sample: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimension.D700),
    ) {
        Text(
            text = token,
            typography = AppTheme.typography.Caption.C300,
            color = AppTheme.colors.contentTertiary,
            modifier = Modifier.width(180.dp),
        )
        Column { Text(text = sample, typography = typography) }
    }
}
