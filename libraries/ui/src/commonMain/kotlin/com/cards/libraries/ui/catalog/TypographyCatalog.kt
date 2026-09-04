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

private const val TYPE_SUBTITLE =
    "Display = serif (the felt headline). Heading / Label / Body / Caption = sans. Higher number = " +
        "bigger. Modifiers chain off any token: .Italic, .Bold, .SemiBold, …"

/** The type page body. Split into two halves so [DesignSystemPreview] can lay it out in two
 *  columns; call this combined version for a single-column page. */
@Composable
internal fun TypographyCatalogBody() {
    TypographyCatalogBodyHeadlines()
    TypographyCatalogBodyText()
}

/** First half — the big stuff: serif Display + sans Heading. */
@Composable
internal fun TypographyCatalogBodyHeadlines() {
    val t = AppTheme.typography

    CatalogSection(
        "Display · serif",
        "Hero headlines only. The italic is the dialog & bottom-sheet title signature.",
    ) {
        TypeRow("Display.D1500", t.Display.D1500, "Royal flush")
        TypeRow("Display.D1300", t.Display.D1300, "Royal flush")
        TypeRow("Display.D1100", t.Display.D1100, "Royal flush")
        TypeRow("Display.D1000", t.Display.D1000, "Royal flush")
        TypeRow("Display.D900", t.Display.D900, "Royal flush")
        TypeRow("Display.D900.Italic", t.Display.D900.Italic, "Daily reward")
        TypeRow("Display.D800.Italic", t.Display.D800.Italic, "Leave table?")
    }

    CatalogSection(
        "Heading · sans",
        "Section and screen titles. Step down the scale as the heading nests deeper.",
    ) {
        TypeRow("Heading.H1100", t.Heading.H1100, "Tournaments")
        TypeRow("Heading.H900", t.Heading.H900, "Tournaments")
        TypeRow("Heading.H800", t.Heading.H800, "Tournaments")
        TypeRow("Heading.H700", t.Heading.H700, "Tournaments")
        TypeRow("Heading.H600", t.Heading.H600, "Tournaments")
        TypeRow("Heading.H500", t.Heading.H500, "Tournaments")
        TypeRow("Heading.H400", t.Heading.H400, "Tournaments")
    }
}

/** Second half — running text: sans Body, Label, Caption. */
@Composable
internal fun TypographyCatalogBodyText() {
    val t = AppTheme.typography

    CatalogSection(
        "Body · sans",
        "Running prose — descriptions, dialog bodies, explainers. Optimized for reading, not labels.",
    ) {
        TypeRow("Body.B700", t.Body.B700, "Blinds are forced bets that rotate each hand.")
        TypeRow("Body.B600", t.Body.B600, "Blinds are forced bets that rotate each hand.")
        TypeRow("Body.B500", t.Body.B500, "Blinds are forced bets that rotate each hand.")
        TypeRow("Body.B400", t.Body.B400, "Blinds are forced bets that rotate each hand.")
    }

    CatalogSection(
        "Label · sans (UI elements)",
        "Buttons, chips, tabs — tight line height tuned for single-line UI, not paragraphs.",
    ) {
        TypeRow("Label.L800", t.Label.L800, "Continue")
        TypeRow("Label.L700", t.Label.L700, "Continue")
        TypeRow("Label.L600", t.Label.L600, "Continue")
        TypeRow("Label.L500", t.Label.L500, "Continue")
        TypeRow("Label.L400", t.Label.L400, "Continue")
        TypeRow("Label.L300", t.Label.L300, "Continue")
    }

    CatalogSection(
        "Caption · sans (metadata)",
        "The smallest type — timestamps, counts, helper text. Pair with contentSecondary / Tertiary.",
    ) {
        TypeRow("Caption.C400", t.Caption.C400, "42m remaining")
        TypeRow("Caption.C300", t.Caption.C300, "42m remaining")
        TypeRow("Caption.C200", t.Caption.C200, "42m remaining")
    }
}

@Preview(widthDp = 1100, heightDp = 2400)
@Composable
private fun TypographyCatalog() {
    CatalogPage(title = "Typography", subtitle = TYPE_SUBTITLE) { TypographyCatalogBody() }
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
