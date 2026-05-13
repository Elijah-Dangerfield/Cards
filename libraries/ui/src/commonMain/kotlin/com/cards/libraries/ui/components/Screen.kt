package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.text.LocalTextConfig

@Composable
fun Screen(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    containerColor: Color = AppTheme.colors.background.color,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {

    val contentColorResource = ColorResource.FromColor(contentColor, "")

    CompositionLocalProvider(
        LocalContentColor provides contentColorResource,
        androidx.compose.material3.LocalContentColor provides contentColorResource.color,
        LocalTextConfig provides LocalTextConfig.current.copy(color = contentColorResource)
    ) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            containerColor = containerColor,
            contentColor = contentColor,
            contentWindowInsets = contentWindowInsets
        ) { paddingValues ->
            Box(
                Modifier.fillMaxSize(),
                propagateMinConstraints = true) {
                content(paddingValues)
            }
        }
    }
}
