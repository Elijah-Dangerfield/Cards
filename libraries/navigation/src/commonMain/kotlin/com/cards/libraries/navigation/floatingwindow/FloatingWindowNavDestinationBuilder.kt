package com.dangerfield.cards.libraries.navigation.floatingwindow

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestinationBuilder
import androidx.navigation.NavDestinationDsl
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import kotlin.jvm.JvmSuppressWildcards
import kotlin.reflect.KClass
import kotlin.reflect.KType

/** DSL for constructing a new [FloatingWindowNavigator.Destination] */
@NavDestinationDsl
public class FloatingWindowNavDestinationBuilder :
    NavDestinationBuilder<FloatingWindowNavigator.Destination> {

    private val floatingWindowNavigator: FloatingWindowNavigator

    private val content: @Composable ((NavBackStackEntry) -> Unit)


    /**
     * DSL for constructing a new [ComposeNavigator.Destination]
     *
     * @param navigator navigator used to create the destination
     * @param route the destination's unique route from a [KClass]
     * @param typeMap map of destination arguments' kotlin type [KType] to its respective custom
     *   [NavType]. May be empty if [route] does not use custom NavTypes.
     * @param content composable for the destination
     */
    public constructor(
        navigator: FloatingWindowNavigator,
        route: KClass<*>,
        typeMap: Map<KType, @JvmSuppressWildcards NavType<*>>,
        content: @Composable (NavBackStackEntry) -> Unit
    ) : super(navigator, route, typeMap) {
        this.floatingWindowNavigator = navigator
        this.content = content
    }

    override fun instantiateDestination(): FloatingWindowNavigator.Destination {
        return FloatingWindowNavigator.Destination(floatingWindowNavigator, content)
    }
}
