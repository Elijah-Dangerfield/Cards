package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun RoomCode(code: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Text(code, modifier = modifier.clickable(onClick = onCopy), typography = AppTheme.typography.Display.D1000.Italic,
        color = AppTheme.colors.accentPrimary, textAlign = TextAlign.Center)
}

@Preview
@Composable
private fun RoomCodePreview_Copyable() { PreviewContent { RoomCode("K7M2QX", {}) } }
