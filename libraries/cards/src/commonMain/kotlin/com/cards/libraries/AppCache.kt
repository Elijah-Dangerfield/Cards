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
/**
 * The "Game speed" control — how long bots "think" before acting. Some players
 * like the pause (it reads as deliberation); others find it busywork. Fast
 * trims it. Animations are never touched, so the table stays smooth either way.
 */
@Serializable
enum class GameSpeed(val botThinkScale: Double) {
    Normal(botThinkScale = 1.0),
    Fast(botThinkScale = 0.5),
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

/**
 * One in-flight run through onboarding: the step the user last reached and
 * when the run started.
 *
 * [startedAtEpochMs] is wall-clock rather than a monotonic mark because the
 * whole point is to survive the process ending.
 */
@Serializable
data class OnboardingAttempt(
    val step: String,
    val startedAtEpochMs: Long,
)

@Serializable
data class AppData(
    // Onboarding
    val hasUserOnboarded: Boolean = false,

    /**
     * The onboarding run currently in flight, or null when onboarding isn't
     * running (never started, or finished).
     *
     * Exists so abandonment is settled at **re-entry** instead of at ViewModel
     * clear. System back on the Welcome step exits the app, which clears the
     * ViewModel — indistinguishable from quitting for good, so emitting there
     * counted users who came straight back and finished. That ran at roughly a
     * two-thirds false-positive rate in prod, and every event landed on
     * `welcome` purely because it's the only step where back leaves the app
     * (AUTH-31). Surviving in the cache lets the next launch decide, and it
     * closes the process-kill blind spot in the same move.
     */
    val onboardingAttempt: OnboardingAttempt? = null,

    // User actions
    val feedbacksGiven: Int = 0,
    val bugsReported: Int = 0,

    /** How long bots "think" before acting. See [GameSpeed]. */
    val gameSpeed: GameSpeed = GameSpeed.Normal,

    /** Cue played when it becomes the user's turn during a hand. */
    val turnFeedback: TurnFeedback = TurnFeedback.Vibrate,

    /**
     * Whether in-game achievement-unlock celebrations surface to the user
     * (the bot-mode celebration sheet, the inline showdown/bust rows, and
     * the tutorial-complete dialog). Default on; the user silences them in
     * Settings → Gameplay. Achievements are still **recorded and earned**
     * when off — only the reveal UI is suppressed, so the unlock still shows
     * up later in the achievements list.
     */
    val showAchievementPopups: Boolean = true,

    /**
     * How many times the "you can turn these off in Settings" footer has been
     * shown on the celebration sheet. The hint rides the first few
     * celebrations (capped in `PlayPokerViewModel`) so a new user learns the
     * toggle exists, then never shows again. Device-scoped — a discoverability
     * counter, not account state.
     */
    val achievementPopupHintShows: Int = 0,

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
     * Whether we've already shown this user their starter-grant number during
     * onboarding (the StarterGrant page). A **monotonic** fact — set true once,
     * never cleared within an account.
     *
     * Now scoped to one job: gating the Home welcome dialog's *chip-reveal
     * section*. When onboarding already revealed the number this stays true and
     * the Home dialog skips re-revealing it (falling to thanks / founding copy);
     * when the onboarding reveal degraded (offline / timed out) this is false and
     * the Home dialog performs the backup reveal. It no longer gates whether the
     * dialog shows at all — [welcomeSeen] owns that.
     */
    val didSeeInitialGrantInOnboarding: Boolean = false,

    /**
     * Whether the one-time Home welcome dialog has been presented to this
     * account. False on a fresh install; flips true the moment the dialog is
     * shown on a settled Home, after which it never shows again for this
     * identity. This — not [didSeeInitialGrantInOnboarding] — is the dialog's
     * once-per-user gate, so the founding-member copy reaches existing early
     * players (whose grant was long ago revealed) exactly once too.
     *
     * Account-scoped (see [resetAccountScoped]): a fresh continue-as-guest or an
     * account switch earns its own welcome rather than inheriting the previous
     * user's "already seen". Reinstall / sign-out clears it, so the dialog can
     * re-show once for the same person — an accepted cost of keeping the seen
     * state on-device rather than server-tracked.
     */
    val welcomeSeen: Boolean = false,

    /**
     * Whether the user has dismissed the Home-screen "new here?" tutorial
     * banner. False on a fresh install — the banner shows above the header
     * inviting the user into the 2-minute scripted walkthrough. Flips to true
     * the first time the user taps the X, and survives backgrounding for that
     * same identity. Reset on an account change / sign-out (see
     * [resetAccountScoped]) so a fresh continue-as-guest is re-offered the
     * walkthrough rather than inheriting the previous user's dismissal. The
     * tutorial itself remains accessible from Settings → "How to play"
     * regardless.
     */
    val tutorialBannerDismissed: Boolean = false,

    /**
     * Whether the richer one-time "finishing your account" explainer dialog has
     * been shown. False on a fresh install — the first time guest-account
     * creation is left pending (signed up offline / network blip) the dialog
     * surfaces once to reassure the user and explain what's on hold. Flips to
     * true when they dismiss it, after which the thin `AccountSetupBanner` is the
     * standing reminder. Device-scoped discoverability, like
     * [tutorialBannerDismissed] — not account state.
     */
    val accountSetupExplainerSeen: Boolean = false,

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

    /**
     * Product id of an item the Profile tab should scroll to and pulse on
     * next composition — a one-shot, cross-tab "spotlight this" signal. Set
     * by the Shop after a successful redeem (the snackbar action switches to
     * the Profile tab), consumed + cleared by the Profile bookshelf. Null
     * when there's nothing to highlight.
     *
     * Tab-root route args don't survive `restoreState`, so the signal rides
     * this shared cache instead of a navigation argument. It persists, so
     * the Profile clears it immediately on consume to avoid re-pulsing on a
     * later cold start.
     */
    val pendingProfileHighlight: String? = null,

    /**
     * Highest level for which the full-screen Home level-up celebration has
     * already been shown. `0` is the *unset* sentinel — on the first
     * progression emission after this lands (fresh install, account switch,
     * reinstall) the watermark is silently seeded to the user's current level
     * **without** celebrating, so we never blast a celebration for a level the
     * user already had. Thereafter, whenever the derived level
     * (`levelProgressFor(totalXp).level`) exceeds this watermark on Home, the
     * celebration shows for the *current* level (a multi-level jump shows once)
     * and the watermark advances to it.
     *
     * Deliberately separate from any reward watermark (`highestLevelRewarded`)
     * — the celebration is a UI moment that may be missed/dismissed, whereas a
     * reward grant must be exactly-once regardless of whether the user saw the
     * celebration. See `docs/decisions.md` 2026-06-06 (level-up celebration).
     */
    val lastCelebratedLevel: Int = 0,

    /**
     * Epoch-ms at which the active XP boost window expires, or `null` if no
     * boost is (or has ever been) active. Lighting a stashed boost (see
     * [xpBoostOwnedCount]) set-or-extends this timestamp, and `XpCalculator`
     * awards double while `now < this`. Persisted so an active boost survives a
     * restart; account-scoped (a purchased per-user consumable) so it doesn't
     * leak across an account switch.
     */
    val xpBoostExpiresAtEpochMs: Long? = null,

    /**
     * The chip balance the user last actually *saw* on Home. Persisted so the
     * odometer can roll from it to the current balance whenever they return — a
     * win or loss that landed while they were on another screen still animates,
     * instead of the number having silently changed in the background. Compared
     * against the local source of truth on resume (no backend hit). Null until the
     * first time Home records a baseline.
     */
    val lastShownChipBalance: Long? = null,

    /**
     * How many **inactive** XP boosts the user owns but hasn't lit yet. Buying
     * one in the shop or being gifted one at level-up increments this; lighting
     * one (opening the [xpBoostExpiresAtEpochMs] window) decrements it. Boosts
     * are a uniform 5-minute consumable, so the stash is a plain count rather
     * than a per-boost duration. Account-scoped like the window.
     */
    val xpBoostOwnedCount: Int = 0,

    /**
     * Highest level whose **reward** (from the `level → reward` table) has been
     * granted. Like [lastCelebratedLevel], `0` is the unset sentinel: the first
     * progression emission seeds it to the current level **without** granting,
     * so a fresh install / account switch / reinstall never retro-grants
     * rewards for levels already held. Thereafter, crossing a rewarded level
     * grants its prize exactly once and advances this watermark.
     *
     * **Separate** from [lastCelebratedLevel] on purpose: the celebration is a
     * UI moment that may be missed/dismissed, whereas a reward grant must be
     * exactly-once independent of whether the user saw the celebration. Chip
     * rewards are additionally keyed (`levelup_<level>`) at the wallet ledger so
     * they survive retries even within a watermark window.
     */
    val highestLevelRewarded: Int = 0,

    /**
     * Whether the one-time "your play style is unlocked" Home celebration has
     * been shown. False until the user crosses the play-style sample threshold
     * ([com.dangerfield.cards.libraries.cards.PlayStyleAxes.MIN_SAMPLE] hands)
     * *and* the celebration actually surfaces on a settled Home — flips true
     * only after a confirmed present, so a crossing that happens while the user
     * is off Home replays when they return rather than being silently consumed.
     * Account-scoped (see [resetAccountScoped]): a fresh account earns its own
     * unlock moment. Distinct from the play-style data itself, which is derived
     * server-side and cached separately.
     */
    val playStyleUnlockSeen: Boolean = false,

    /**
     * Whether the out-of-chips sheet has been shown for the *current*
     * below-buy-in episode. Flips true after a confirmed present on a settled
     * Home; flips back to false once the balance recovers to at least the
     * Casual buy-in, so the next drop under it is offered the sheet again —
     * once per episode, never once per Home visit. Account-scoped (see
     * [resetAccountScoped]): the next account has its own balance and its own
     * episodes.
     */
    val outOfChipsSeen: Boolean = false,

    /**
     * The app version we last showed an "update available" prompt for, blank
     * when we never have. Keyed by version rather than a boolean so someone who
     * skips a feature release still gets asked once on the next one, instead of
     * either being nagged every launch or silenced forever.
     *
     * Device-scoped, not account-scoped: which build is installed has nothing
     * to do with who is signed in, so it deliberately survives an account
     * switch (see [resetAccountScoped]).
     */
    val lastPromptedUpdateVersion: String = "",

    /**
     * The `LegalUrls.LEGAL_VERSION` the user last accepted by proceeding past
     * the onboarding Welcome step (the passive "by continuing, you agree to
     * Terms + Privacy" consent). `0` means no acceptance has been recorded.
     * Re-recorded on every onboarding pass, so an account switch that walks
     * Welcome again refreshes it; comparing it against the live
     * `LegalUrls.LEGAL_VERSION` is the seam a future "Terms changed,
     * re-accept" gate keys off.
     */
    val acceptedLegalVersion: Int = 0,

    /**
     * Epoch-ms when [acceptedLegalVersion] was recorded, or `null` if no
     * acceptance has been recorded. The timestamp half of the consent audit
     * record (accepted version + when).
     */
    val legalConsentAcceptedAt: Long? = null,

    /**
     * [AchievementId] names of achievements earned during a real-chip
     * multiplayer game that had no in-game celebration surface to show them
     * (a real-chip hand that finished without the local player busting shows
     * its result on the felt, not in a dialog, so the at-table achievement
     * reveal never fires there). They queue here at hand-end and are drained by
     * Home, which celebrates them on the next settled return so the player still
     * learns what they earned (PROG-13). A bust surfaces its unlocks inline in
     * the [MultiplayerBustDialog], so those are never enqueued — no double-fire.
     * Ordered oldest-first; deduped. Account-scoped (achievements are
     * account-bound), so it clears on [resetAccountScoped].
     */
    val pendingHomeAchievementIds: List<String> = emptyList(),
)

/**
 * True iff the app has never recorded a session-end on this install — i.e.,
 * the user has never backgrounded the app. The session-end marker is set by
 * the foreground/background lifecycle listener, so an abrupt process kill
 * (e.g. swiping away on iOS before backgrounding) leaves this true; a small
 * UX cost for keeping the lifecycle plumbing trivial.
 */
fun AppData.isFirstEverSession(): Boolean = lastSessionEndedAt == null

/**
 * Reset the **account-scoped** fields back to defaults while preserving every
 * device-scoped setting (bot speed, sound, hints, install id, onboarding-seen,
 * review timers…). Used whenever the active user changes (account switch or
 * sign-out / delete) so the next account doesn't inherit the previous one's state.
 *
 * This is one [UserScopedClearer] in the dump the auth layer runs on a user
 * change: DB tables are wiped by `UserScopedDaoCleaner`, the profile caches by
 * `UserScopedProfileCacheCleaner`, and this covers the account-scoped fields
 * that live in [AppData]. Add any new account-scoped field here.
 */
fun AppData.resetAccountScoped(): AppData = copy(
    // Leaving this true across sign-out would suppress the starter-grant reveal
    // for the *next* account the user signs up for.
    didSeeInitialGrantInOnboarding = false,
    // The welcome dialog is a first-run moment — a fresh account (incl. a
    // continue-as-guest after a delete) earns its own, rather than inheriting the
    // previous user's "already seen" and never being welcomed.
    welcomeSeen = false,
    // Back to the unset sentinel so the next account silently re-seeds the
    // celebration watermark to *its* level rather than inheriting the previous
    // user's — otherwise a switch into a higher-level account would either
    // skip its celebrations or fire a spurious one.
    lastCelebratedLevel = 0,
    // A purchased per-user consumable — the next account starts with no boost
    // (neither an active window nor anything stashed).
    xpBoostExpiresAtEpochMs = null,
    xpBoostOwnedCount = 0,
    // Unset sentinel so the next account silently seeds to its own level rather
    // than retro-granting level rewards on switch-in.
    highestLevelRewarded = 0,
    // The "new here?" Home banner is a first-run teaching affordance. A full
    // sign-out -> continue-as-guest is a deliberate fresh start, so the next
    // identity should be re-offered the walkthrough rather than inheriting the
    // previous user's dismissal (AUTH-6). Distinct from a within-account device
    // persistence: the flag still survives backgrounding for the *same* user.
    tutorialBannerDismissed = false,
    // The play-style unlock is a first-run milestone reveal — a fresh account
    // earns its own once it crosses the sample threshold, rather than inheriting
    // the previous user's "already saw it".
    playStyleUnlockSeen = false,
    // Episode state for the previous account's balance — the next account's
    // wallet is a different number entirely.
    outOfChipsSeen = false,
    // "New items" Shop dot is a per-account discoverability signal — a fresh
    // account (incl. a continue-as-guest after a delete) should see the dot
    // until it lands on Shop, not inherit the previous user's "already seen"
    // set (AUTH-27).
    shopSeenProductIds = emptySet(),
    // The Home odometer's roll-from baseline is the previous account's balance;
    // the next account's wallet is a different number, so clear it and let Home
    // re-baseline rather than animating a bogus jump on first return.
    lastShownChipBalance = null,
    // Achievements are account-bound, so a queue of MP unlocks awaiting their
    // Home celebration belongs to the previous account — drop it so the next
    // account never sees a celebration for something it didn't earn.
    pendingHomeAchievementIds = emptyList(),
    // An unfinished onboarding run belongs to the identity that started it.
    // Carried across, the next account's first launch would settle a marker it
    // never wrote and report an abandonment nobody performed (AUTH-31).
    onboardingAttempt = null,
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