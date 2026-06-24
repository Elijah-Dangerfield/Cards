package com.dangerfield.cards.libraries.navigation

import kotlinx.serialization.Serializable

@Serializable
data class BlockingErrorRoute(
    val title: String,
    val subtitle: String,
    val errorCode: Int? = null,
    val logId: String? = null,
    val contextMessage: String? = null,
) : Route(
    enter = AnimationType.FadeIn,
    exit = AnimationType.FadeOut,
    popExit = AnimationType.FadeOut,
), NavigableWhileBlocked

@Serializable
data class SessionExpiredRoute(
    val wasAnonymous: Boolean = false,
) : Route(
    enter = AnimationType.FadeIn,
    exit = AnimationType.FadeOut,
    popExit = AnimationType.FadeOut,
), NavigableWhileBlocked

@Serializable
data class ErrorDialogRoute(
    val title: String,
    val subtitle: String,
    val actionTitle: String,
    val action: ErrorDialogAction = ErrorDialogAction.Dismiss,
    val errorCode: Int? = null,
    val logId: String? = null,
    val contextMessage: String? = null,
) : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)

@Serializable
sealed interface ErrorDialogAction {
    @Serializable
data object Dismiss : ErrorDialogAction

    @Serializable
data object GoBack : ErrorDialogAction

    @Serializable
data class Navigate(val route: Route) : ErrorDialogAction
}
