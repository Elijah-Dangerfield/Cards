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

    /**
     * Whether the user has acknowledged the swipe-up-to-fold gesture on
     * their hole cards. False keeps a confirmation dialog as a safety net
     * (the gesture is *discoverable* the first few times); flips to true
     * the moment the user ticks "Don't show this again" in that dialog
     * — after which the gesture folds silently.
     */
    val swipeFoldGestureAck: Boolean = false,

    /**
     * Whether the user has ever flipped the player info tile to view
     * their win/lose odds (only meaningful once they own
     * `tool_win_odds`). False on a fresh install — at the start of each
     * play session the tile does a one-shot discoverability wiggle so
     * the gesture is teachable. Flips to true the first time the user
     * taps to flip, after which the wiggle never plays again on any
     * device tied to this account.
     */
    val winOddsFlipHintSeen: Boolean = false,

    /**
     * Whether we still owe this user the starter-grant reveal. Set true by
     * [com.dangerfield.cards.libraries.cards.ChipsRepository]'s sync when the
     * server reports a wallet was *just created* (`walletCreated`) — i.e. a
     * brand-new account whose starter grant was seeded this instant. Flipped
     * false once we've shown the number (the onboarding StarterGrant page, or
     * the Home welcome dialog as a fallback).
     *
     * Default false is self-migrating and fixes the old re-fire bug: a
     * returning user on a fresh install already has a server wallet, so
     * `walletCreated` never comes back true and the reveal stays suppressed.
     */
    val requiresGrantInfo: Boolean = false,

    /**
     * Whether the user has dismissed the Home-screen tutorial banner.
     * False on a fresh install — the banner shows above the header
     * inviting the user into the 2-minute scripted walkthrough. Flips
     * to true the first time the user taps the X. The tutorial itself
     * remains accessible from Settings → "How to play" regardless.
     */
    val tutorialBannerDismissed: Boolean = false,

    /**
     * Epoch-ms of when the previous session backgrounded, or `null` if
     * the app has never been backgrounded on this install. Used as the
     * single source of truth for "is this a fresh install" — see
     * [isFirstEverSession]. Replaces a dedicated `SessionRepository` +
     * `SessionEntity` table that existed for this single signal in V1.
     */
    val lastSessionEndedAt: Long? = null,

    /**
     * Stable-identity keys of opponents whose table-side emoji blasts the
     * user has muted. Set via the tap-avatar surface in the play-poker
     * screen and consulted before rendering an inbound blast animation.
     *
     * Keying is opponent-side display name today (the only inbound source
     * shipping in V1 is single-player bots, whose names are stable per
     * personality). Forward-compatible: when MP/reactive-bot blasts land,
     * the same set filters them — the key just needs to identify the
     * emitter the same way.
     */
    val mutedEmojiPlayerKeys: Set<String> = emptySet(),

    /**
     * Product ids the user has already seen on the Shop tab. Powers the
     * "new items" dot on the bottom-nav Shop badge — if the catalog
     * holds any id not in this set, the dot shows. Replaced with the
     * current catalog id set on every Shop tab open (so retired ids
     * prune automatically); empty on fresh install so a brand-new user
     * sees the dot until they land on Shop for the first time.
     */
    val shopSeenProductIds: Set<String> = emptySet(),

    /**
     * Per-install identifier generated on first launch and sent as the
     * `X-Install-Id` header on every authenticated request. Null until
     * the install-id provider seeds it (one-shot, persistent). Used by
     * the server for L1 orphan-account cleanup — see
     * docs/recovery-and-orphaned-accounts.md.
     *
     * Stored as a string (UUID canonical form) so the JSON serializer
     * doesn't need a Uuid-aware adapter on every cache read.
     */
    val installId: String? = null,
)

/**
 * True iff the app has never recorded a session-end on this install — i.e.,
 * the user has never backgrounded the app. The session-end marker is set by
 * the foreground/background lifecycle listener, so an abrupt process kill
 * (e.g. swiping away on iOS before backgrounding) leaves this true; a small
 * UX cost for keeping the lifecycle plumbing trivial.
 */
fun AppData.isFirstEverSession(): Boolean = lastSessionEndedAt == null

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