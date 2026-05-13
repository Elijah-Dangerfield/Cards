package com.dangerfield.cards.libraries.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Convenience method for only compseing logic if the parameters are not null
 */
@Composable
fun <A, B, T> allOrNone(one: A?, two: B?, block: (A, B) -> T): T? = if (one != null && two != null) block(one, two) else null


@Composable
fun Int.pxToDp() = with(LocalDensity.current) { this@pxToDp.toDp() }


fun PagerState.animateToNextPage(scope: CoroutineScope) {
    scope.launch {
        animateScrollToPage((currentPage + 1).coerceIn(0, (pageCount - 1) ))
    }
}

fun PagerState.animateToPreviousPage(scope: CoroutineScope) {
    scope.launch {
        animateScrollToPage((currentPage - 1).coerceIn(0, (pageCount - 1) ))
    }
}

fun PagerState.isOnFirstPage() = currentPage == 0

fun PagerState.isOnLastPage() = currentPage == pageCount - 1