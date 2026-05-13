package com.dangerfield.cards.libraries.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lightweight alias for the app's circular progress indicator so call sites can
 * express intent ("loading") without duplicating styling details.
 */
@Composable
fun CircularLoadingIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier)
}
