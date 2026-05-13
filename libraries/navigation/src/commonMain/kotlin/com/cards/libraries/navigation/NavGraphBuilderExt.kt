package com.dangerfield.cards.libraries.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.ComposeNavigatorDestinationBuilder
import androidx.navigation.get
import com.dangerfield.cards.libraries.ui.components.dialog.DialogState
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetState
import com.dangerfield.cards.libraries.navigation.floatingwindow.DialogDestination
import com.dangerfield.cards.libraries.navigation.floatingwindow.FloatingWindowNavDestinationBuilder
import com.dangerfield.cards.libraries.navigation.floatingwindow.FloatingWindowNavigator
import com.dangerfield.cards.libraries.navigation.floatingwindow.BottomSheetDestination
import kotlin.jvm.JvmSuppressWildcards
import kotlin.reflect.KType
import kotlin.reflect.typeOf

inline fun <reified T : Route> NavGraphBuilder.screen(
    typeMap: Map<KType, @JvmSuppressWildcards NavType<*>> = emptyMap(),
    deepLinks: List<NavDeepLink> = emptyList(),
    noinline enterTransition:
    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
    EnterTransition?)? =
        null,
    noinline exitTransition:
    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
    ExitTransition?)? =
        null,
    noinline popEnterTransition:
    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
    EnterTransition?)? =
        enterTransition,
    noinline popExitTransition:
    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
    ExitTransition?)? =
        exitTransition,
    noinline sizeTransform:
    (AnimatedContentTransitionScope<NavBackStackEntry>.() -> @JvmSuppressWildcards
    SizeTransform?)? =
        null,
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
    destination(
        ComposeNavigatorDestinationBuilder(
            provider[ComposeNavigator::class],
            T::class,
            typeMap + mapOf(
                typeOf<AnimationType>() to serializableType<AnimationType>()
            ),
            content
        )
            .apply {
                deepLinks.forEach { deepLink -> deepLink(deepLink) }
                this.enterTransition = enterTransition
                this.exitTransition = exitTransition
                this.popEnterTransition = popEnterTransition
                this.popExitTransition = popExitTransition
                this.sizeTransform = sizeTransform
            }
    )
}

inline fun <reified T : Route> NavGraphBuilder.dialog(
    typeMap: Map<KType, @JvmSuppressWildcards NavType<*>> = emptyMap(),
    deepLinks: List<NavDeepLink> = emptyList(),
    noinline content: @Composable (NavBackStackEntry, DialogState) -> Unit
) {
    destination(
        FloatingWindowNavDestinationBuilder(
            provider[FloatingWindowNavigator::class],
            T::class,
            typeMap + mapOf(
                typeOf<AnimationType>() to serializableType<AnimationType>()
            )
        ) { backStackEntry ->
            DialogDestination(backStackEntry, content)
        }
            .apply {
                deepLinks.forEach { deepLink -> deepLink(deepLink) }
            }
    )
}


inline fun <reified T : Route> NavGraphBuilder.bottomSheet(
    typeMap: Map<KType, @JvmSuppressWildcards NavType<*>> = emptyMap(),
    deepLinks: List<NavDeepLink> = emptyList(),
    noinline content: @Composable (NavBackStackEntry, BottomSheetState) -> Unit
) {
    destination(
        FloatingWindowNavDestinationBuilder(
            provider[FloatingWindowNavigator::class],
            T::class,
            typeMap + mapOf(
                typeOf<AnimationType>() to serializableType<AnimationType>()
            )
        ) { backStackEntry ->
            BottomSheetDestination(backStackEntry, content)
        }
    ).apply {
        deepLinks.forEach { deepLink -> deepLink(deepLink) }
    }
}