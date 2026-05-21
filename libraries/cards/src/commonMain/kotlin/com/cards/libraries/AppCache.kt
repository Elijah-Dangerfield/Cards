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
    /**
     * Legacy: audio cue. The KMP audio path isn't wired in V1, so this
     * value is hidden from the picker and behaves like [Vibrate] until
     * sound support lands. See docs/backlog.md → audio infrastructure.
     */
    Sound(label = "Sound"),
    /** A short haptic pulse when it becomes the user's turn. */
    Vibrate(label = "Vibrate"),
}

@Serializable
data class AppData(
    // Onboarding
    val hasUserOnboarded: Boolean = false,

    // User actions
    val feedbacksGiven: Int = 0,
    val bugsReported: Int = 0,

    /** How fast the bots act. Multiplies all bot think/action delays. */
    val botSpeed: BotSpeed = BotSpeed.Normal,

    /** Cue played when it becomes the user's turn during a hand. */
    val turnFeedback: TurnFeedback = TurnFeedback.Vibrate,

    /** Epoch-ms — first observed by the review coordinator. 0 = uncaptured. */
    val reviewInstallAt: Long = 0L,

    /** Epoch-ms — last review prompt the coordinator forwarded to the platform. 0 = never. */
    val lastReviewPromptAt: Long = 0L,
)

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