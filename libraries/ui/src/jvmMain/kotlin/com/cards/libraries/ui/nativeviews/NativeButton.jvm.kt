package com.dangerfield.cards.libraries.ui.nativeviews

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun NativeButton(
    onClick: () -> Unit,
    modifier: Modifier
) {
    Button(onClick = onClick, modifier = modifier) {
        Text(text = "Native Button")
    }
}
