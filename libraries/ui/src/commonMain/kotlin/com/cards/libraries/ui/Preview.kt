package com.dangerfield.cards.libraries.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.AppBottomBar
import com.dangerfield.cards.libraries.ui.components.BottomBarItem
import com.dangerfield.cards.libraries.ui.components.dialog.DialogHost
import com.dangerfield.cards.libraries.ui.components.dialog.LocalDialogHostState
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogHostState
import com.dangerfield.cards.libraries.ui.snackbar.LocalSnackbarHostState
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarHost
import com.dangerfield.cards.libraries.ui.snackbar.rememberSnackbarHostState
import com.dangerfield.cards.libraries.ui.system.LocalAppState
import com.dangerfield.cards.libraries.ui.system.LocalBuildInfo
import com.dangerfield.cards.libraries.ui.system.LocalClock
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.fixed
import com.dangerfield.cards.system.AppThemeProvider
import com.dangerfield.cards.system.background
import com.dangerfield.cards.system.color.defaultColors
import com.dangerfield.cards.system.thenIf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlin.time.Instant

sealed class PreviewBottomBar(val render: @Composable () -> Unit) {

    object None: PreviewBottomBar({})

    object Home : PreviewBottomBar({
        AppBottomBar(
            items = listOf(
                BottomBarItem.Home(isSelected = true),
                BottomBarItem.Shop(isSelected = false),
                BottomBarItem.Profile(isSelected = false),
            ),
            onItemClick = {},
        )
    })

    object Shop : PreviewBottomBar({
        AppBottomBar(
            items = listOf(
                BottomBarItem.Home(isSelected = false),
                BottomBarItem.Shop(isSelected = true),
                BottomBarItem.Profile(isSelected = false),
            ),
            onItemClick = {},
        )
    })

    object Profile : PreviewBottomBar({
        AppBottomBar(
            items = listOf(
                BottomBarItem.Home(isSelected = false),
                BottomBarItem.Shop(isSelected = false),
                BottomBarItem.Profile(isSelected = true),
            ),
            onItemClick = {},
        )
    })
}

/**
 * A composable that is suitable as the root for any composable preview
 *
 * It will set up the theme and some suitable defaults like a background color.
 */
@Composable
fun PreviewContent(
    modifier: Modifier = Modifier,
    appState: AppState = PreviewAppState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    backgroundColor: ColorResource? = defaultColors.background,
    bottomBar: PreviewBottomBar = PreviewBottomBar.None,
    content: @Composable () -> Unit,
) {
    val dialogHostState = rememberDialogHostState()
    val snackbarHostState = rememberSnackbarHostState()
    CompositionLocalProvider(
        LocalAppState provides appState,
        LocalClock provides Clock.fixed(Instant.parse("2023-01-01T00:00:00Z")),
        LocalBuildInfo provides BuildInfo,
        LocalDialogHostState provides dialogHostState,
        LocalSnackbarHostState provides snackbarHostState,
    ) {
        AppThemeProvider {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .thenIf(backgroundColor != null) { background(backgroundColor!!) }
                    .padding(contentPadding),
            ) {
                content()
                Box(Modifier.align(Alignment.BottomCenter)) {
                    bottomBar.render()
                }

                DialogHost(
                    modifier = Modifier.matchParentSize(),
                    hostState = dialogHostState
                )

                SnackbarHost(
                    modifier = Modifier.matchParentSize(),
                    hostState = snackbarHostState,
                )
            }
        }
    }
}


val PreviewAppState = object : AppState {
    override val isOffline: StateFlow<Boolean> = MutableStateFlow(false)

    override val isBlockActive: StateFlow<Boolean> = MutableStateFlow(false)
}