package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable

@Composable
expect fun rememberMicrophonePermissionLauncher(
    onResult: (granted: Boolean) -> Unit
): PermissionLauncher

interface PermissionLauncher {
    fun launch()
}
