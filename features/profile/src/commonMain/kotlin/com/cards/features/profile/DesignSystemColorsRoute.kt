package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * In-app design-system color catalog, reached from the QA menu. Renders the same
 * color swatches the IDE `@Preview`s show, but on a real device so colors can be
 * eyeballed in context.
 *
 * Parameterless `class` (not `data object`) per the iOS navigator requirement —
 * a `data object` route SIGSEGVs at navigate-time.
 */
@Serializable
class DesignSystemColorsRoute : Route()
