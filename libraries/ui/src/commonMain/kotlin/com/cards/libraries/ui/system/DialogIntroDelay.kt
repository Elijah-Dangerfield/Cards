package com.dangerfield.cards.libraries.ui.system

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Breathing room between a navigation and a dialog that pops on top of
 * the destination. Without it, dialogs fired from a screen's onLoad /
 * onAdvance feel like an interruption — the user hasn't finished
 * orienting on the new screen before the modal grabs focus.
 *
 * Currently routed through by:
 *  - `HomeViewModel.observeWelcomeGate` (starter-grant welcome dialog).
 *  - `TutorialFeatureEntryPoint.onAchievementUnlocked` (tutorial-complete
 *    achievement celebration).
 *
 * Don't use for dialogs that are explicit user requests (the user
 * tapped a "?" to open an explainer) — those should land instantly.
 */
val DialogIntroDelay: Duration = 1.seconds
