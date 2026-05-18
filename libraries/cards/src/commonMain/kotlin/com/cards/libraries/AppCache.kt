package com.dangerfield.cards.libraries.cards

import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.versionedJsonSerializer
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * In-memory + persistent cache for app-wide state that doesn't need to be in the database.
 */
@Serializable
enum class BotSpeed(val label: String, val multiplier: Double) {
    /** Bots take their time. Use for learning / coaching mode. */
    Slow(label = "Slow", multiplier = 1.5),
    /** Default — calibrated for poker pacing. */
    Normal(label = "Normal", multiplier = 1.0),
    /** Bots snap. Use to grind volume. */
    Fast(label = "Fast", multiplier = 0.55),
}

@Serializable
enum class TurnFeedback(val label: String) {
    /** No cue when it becomes the user's turn. */
    Mute(label = "Mute"),
    /** A short tone when it becomes the user's turn. */
    Sound(label = "Sound"),
    /** A short haptic pulse when it becomes the user's turn. */
    Vibrate(label = "Vibrate"),
}

@Serializable
data class AppData(
    // Onboarding
    val hasUserOnboarded: Boolean = false,

    // Screen visits - automatically tracked for any TrackableRoute
    val screenVisits: Map<String, Int> = emptyMap(),

    // User actions
    val feedbacksGiven: Int = 0,
    val bugsReported: Int = 0,

    // One-time-dialog suppression flags. Add new flags as boolean fields here
    // (rather than a generic Set<String>) so each flag is type-checked at the
    // call site and documented by its field name.
    /** When true, the bust dialog on the bot table is skipped — the player
     *  goes straight to the next hand with no modal. Set when the user ticks
     *  "Don't show me this again." */
    val skipBustDialog: Boolean = false,

    /** When true, the "leave bot game" confirmation is skipped — back press
     *  exits the session immediately. Set when the user ticks "Don't show
     *  this again" in the confirmation dialog. */
    val skipLeaveBotsConfirm: Boolean = false,

    /** How fast the bots act. Multiplies all bot think/action delays. */
    val botSpeed: BotSpeed = BotSpeed.Normal,

    /** Cue played when it becomes the user's turn during a hand. */
    val turnFeedback: TurnFeedback = TurnFeedback.Sound,
) {
    /**
     * Get the visit count for a screen by its tracking key.
     */
    fun getVisitCount(trackingKey: String): Int = screenVisits[trackingKey] ?: 0
    
    /**
     * Increment the visit count for a screen.
     */
    fun incrementVisit(trackingKey: String): AppData = copy(
        screenVisits = screenVisits + (trackingKey to (getVisitCount(trackingKey) + 1))
    )
}

interface AppCache : Cache<AppData>

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppCache::class)
@Inject
class AppCacheImpl(
    cacheFactory: CacheFactory
) : AppCache, Cache<AppData> by cacheFactory.persistent(
    name = "app_data",
    serializer = versionedJsonSerializer(
        defaultValue = { AppData() },
    )
)