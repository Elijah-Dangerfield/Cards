package com.dangerfield.cards.libraries.navigation

import com.dangerfield.cards.libraries.ui.snackbar.SnackBarPresenter
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog

data class NavigationOptions(
    val clearBackStack: Boolean = false,
    val launchSingleTop: Boolean = false,
    val restoreState: Boolean = false,
)

interface Router {

    fun navigate(route: Route, options: NavigationOptions = NavigationOptions())

    /**
     * Compile-time guard: a [TabRoute] must go through [switchTab] so the bottom-bar
     * tabs' saved back stacks stay aligned. Pushing one with plain [navigate] mis-roots
     * the new entry under the current tab's stack and breaks subsequent tab swaps.
     */
    @Deprecated(
        message = "TabRoute must go through switchTab() so the tab back stacks stay aligned.",
        replaceWith = ReplaceWith("switchTab(route)"),
        level = DeprecationLevel.ERROR,
    )
    fun navigate(route: TabRoute, options: NavigationOptions = NavigationOptions()): Nothing =
        throw UnsupportedOperationException("Compile-time guard only — use switchTab(route).")

    fun goBack()

    fun popBackTo(route: Route, inclusive: Boolean)

    /**
     * Switch to a top-level destination from *inside* the tab system, saving the current
     * tab's stack and restoring any previously-saved stack for the target. Use this for
     * bottom-bar taps OR a feature that needs to deep-link into a different tab (e.g.
     * Edit Profile → Shop). Plain [navigate] of a [TabRoute] won't compile.
     */
    fun switchTab(route: TabRoute)

    /**
     * Enter the tab system fresh from *outside* of it — onboarding / sign-in completion.
     * Clears the back stack so the user can't navigate back into the pre-tab flow.
     * Use [switchTab] instead once already inside the tab system.
     */
    fun enterTab(route: TabRoute)

    fun openWebLink(url: String)
}

fun <T> Catching<T>.blockingScreenOnError(
    router: Router,
    title: String = "This is super embarrassing",
    subtitle: String = "Our intern Ryan seems to have left a bug in the app. Sorry, you'll need to kill and restart the app.",
    logId: String? = null,
    includeErrorMessage: Boolean = false,
): Catching<T> = this.onFailure {
    val errorCode = it.toKnownErrorCode()
    val resolvedLogId = logId ?: KLog.e(it)?.raw
    router.navigate(
        BlockingErrorRoute(
            title = title,
            subtitle = subtitle,
            errorCode = errorCode,
            logId = resolvedLogId,
            contextMessage = it.message.takeIf { includeErrorMessage }
        )
    )
}

fun <T> Catching<T>.dialogOnError(
    router: Router,
    title: String = "Oops something went wrong",
    subtitle: String = "Please try again",
    actionTitle: String = "Okay",
    action: ErrorDialogAction = ErrorDialogAction.Dismiss,
    logId: String? = null,
    includeErrorMessage: Boolean = false,
    ): Catching<T> = this.onFailure {
    val errorCode = it.toKnownErrorCode()
    val resolvedLogId = logId ?: KLog.e(it)?.raw
    router.navigate(
        ErrorDialogRoute(
            title = title,
            subtitle = subtitle,
            actionTitle = actionTitle,
            action = action,
            errorCode = errorCode,
            logId = resolvedLogId,
            contextMessage = it.message.takeIf { includeErrorMessage }
        )
    )
}

fun <T> Catching<T>.toastOnError(
    title: String = "Oops something went wrong",
    subtitle: String = "Please try again",
): Catching<T> = this.onFailure {
    SnackBarPresenter.show(
        title = title,
        message = subtitle,
    )
}