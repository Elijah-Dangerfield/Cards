package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.typography.TypographyResource
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.makeBold
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun BoldPrefixedText(
    modifier: Modifier = Modifier,
    boldText: String,
    regularText: String,
    textAlign: TextAlign = TextAlign.Start,
    typography: TypographyResource = AppTheme.typography.Body.B700
) {
    val string = "$boldText $regularText".makeBold(boldText)

    Row(modifier = modifier) {
       Text(
           textAlign = textAlign,
           text = string,
           typography = typography
       )
    }
}

@Preview
@Composable
fun BoldPrefixedTextPreview() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.text.BoldPrefixedText(
            boldText = "Role: ",
            regularText = "Spy",
            typography = AppTheme.typography.Body.B700
        )
    }
}