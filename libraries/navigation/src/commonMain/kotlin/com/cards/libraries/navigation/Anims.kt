@file:Suppress("MagicNumber")

package com.dangerfield.cards.libraries.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry
import androidx.navigation.toRoute
import com.dangerfield.cards.libraries.core.Catching

fun AnimatedContentTransitionScope<NavBackStackEntry>.fadeInToStartAnim(): EnterTransition =
    fadeIn(
        animationSpec = tween(
            300, easing = LinearEasing
        )
    ) + slideIntoContainer(
        animationSpec = tween(300, easing = EaseIn),
        towards = AnimatedContentTransitionScope.SlideDirection.Start
    )

fun AnimatedContentTransitionScope<NavBackStackEntry>.fadeOutToStart(): ExitTransition =
    fadeOut(
        animationSpec = tween(
            300, easing = LinearEasing
        )
    ) + slideOutOfContainer(
        animationSpec = tween(300, easing = EaseOut),
        towards = AnimatedContentTransitionScope.SlideDirection.Start
    )

fun AnimatedContentTransitionScope<NavBackStackEntry>.noAnimExit(): ExitTransition =
    fadeOut(tween(durationMillis = 0))

fun AnimatedContentTransitionScope<NavBackStackEntry>.noAnimEnter(): EnterTransition =
    fadeIn(tween(durationMillis = 0))

fun AnimatedContentTransitionScope<NavBackStackEntry>.fadeInToEndAnim(): EnterTransition =
    fadeIn(
        animationSpec = tween(
            300, easing = LinearEasing
        )
    ) + slideIntoContainer(
        animationSpec = tween(300, easing = EaseIn),
        towards = AnimatedContentTransitionScope.SlideDirection.End
    )

fun AnimatedContentTransitionScope<NavBackStackEntry>.fadeOutToEndAnim(): ExitTransition =
    fadeOut(
        animationSpec = tween(
            300, easing = LinearEasing
        )
    ) + slideOutOfContainer(
        animationSpec = tween(300, easing = EaseOut),
        towards = AnimatedContentTransitionScope.SlideDirection.End
    )

fun AnimatedContentTransitionScope<NavBackStackEntry>.slideUpToEnterBottomSheet(): EnterTransition =
    fadeIn(
        animationSpec = tween(
            durationMillis = 300
        )
    ) + slideIntoContainer(
        animationSpec = tween(durationMillis = 300),
        towards = AnimatedContentTransitionScope.SlideDirection.Up
    )

fun AnimatedContentTransitionScope<NavBackStackEntry>.slideDownToExitBottomSheet(): ExitTransition =
    fadeOut(
        animationSpec = tween(
            durationMillis = 300
        )
    ) + slideOutOfContainer(
        animationSpec = tween(durationMillis = 300),
        towards = AnimatedContentTransitionScope.SlideDirection.Down
    )
