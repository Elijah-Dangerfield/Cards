package com.dangerfield.cards.features.home

import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * One-shot "your play style is unlocked" celebration, shown the first time the
 * user crosses the play-style sample threshold (PROG-6). Routed like the
 * starter-grant welcome so it has its own back-stack lifecycle: the home VM
 * fires `OpenPlayStyleUnlocked` once its arbiter resolves this while Home is
 * settled, the entry point navigates here, and dismissal pops the dialog.
 *
 * Arg-less — the whole message is static copy; the CTA routes to Stats where the
 * unlocked radar lives.
 */
@Serializable
class PlayStyleUnlockedRoute : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)
