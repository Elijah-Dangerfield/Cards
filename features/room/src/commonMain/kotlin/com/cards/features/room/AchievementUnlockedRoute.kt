package com.dangerfield.cards.features.room

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * Floating-window destination that shows the "achievement unlocked"
 * celebration as a popup over whatever screen triggered it. The
 * tutorial completion is the first consumer, but the route is
 * deliberately generic so future unlock moments (level-up, first
 * bust, bot whisperer capstone) can navigate here without rebuilding
 * the celebration UI.
 *
 * Carries the achievement id by name (the [AchievementId.name] of
 * the enum constant, e.g. "TUTORIAL_COMPLETE"). Receiving side
 * resolves it back to the `Achievement` via `AllAchievementsById`
 * at render time so we don't have to serialize the full
 * `Achievement` data class through nav-args.
 *
 * Lives in `:features:room` for now because the tutorial is the
 * only producer; should move to a dedicated `:features:achievement`
 * (or similar) module once a second feature wants to fire unlocks
 * from a non-room surface.
 */
@Serializable
class AchievementUnlockedRoute(val achievementId: String) : Route()
