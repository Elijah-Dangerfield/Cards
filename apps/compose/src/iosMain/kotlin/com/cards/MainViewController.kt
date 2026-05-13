package com.dangerfield.cards

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import com.dangerfield.cards.libraries.ui.nativeviews.LocalNativeViewFactory
import com.dangerfield.cards.libraries.ui.nativeviews.NativeViewFactory
import platform.UIKit.UIViewController

fun MainViewController(
    appComponent: IosAppComponent,
): UIViewController {
    return ComposeUIViewController {
        CompositionLocalProvider(LocalNativeViewFactory provides appComponent.nativeViewFactory) {
            App(appComponent = appComponent)
        }
    }
}
