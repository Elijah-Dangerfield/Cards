package com.dangerfield.cards.features.room

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * Entry to the scripted "how to play poker in this app" tutorial. The
 * route carries no parameters — the script is fixed (see `TutorialScript`
 * in `:features:room:impl`). Reachable from the Home tutorial banner and
 * from Settings → "How to play".
 */
@Serializable
class TutorialRoute : Route()
