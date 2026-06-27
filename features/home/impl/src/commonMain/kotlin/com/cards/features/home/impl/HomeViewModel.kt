package com.dangerfield.cards.features.home.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.avatarBackgroundColorOrNull
import com.dangerfield.cards.libraries.identity.profile.avatarEmojiOrNull
import com.dangerfield.cards.libraries.identity.profile.displayNameOrNull
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.social.FriendRepository
import com.dangerfield.cards.libraries.social.RecentOpponentProfile
import com.dangerfield.cards.libraries.social.RecentOpponentsRepository
import com.dangerfield.cards.libraries.social.SendFriendRequestResult
import com.dangerfield.cards.libraries.social.SocialEnabled
import com.dangerfield.cards.libraries.ui.system.DialogIntroDelay
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class HomeViewModel(
    private val progressionRepository: ProgressionRepository,
    private val achievementRepository: AchievementRepository,
    private val chipsRepository: ChipsRepository,
    private val roomRepository: RoomRepository,
    private val profileRepository: ProfileRepository,
    private val recentOpponentsRepository: RecentOpponentsRepository,
    private val friendRepository: FriendRepository,
    private val progressionConfig: ProgressionConfig,
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
    socialEnabledConfig: SocialEnabled,
) : SEAViewModel<HomeState, HomeEvent, HomeAction>(
    initialStateArg = HomeState(socialEnabled = socialEnabledConfig())
) {

    private val homeLogger = KLog.withTag("HomeViewModel")

    private val isSocialEnabled = socialEnabledConfig()

    /**
     * Opponents the user has fired (or had auto-completed) a friend request to
     * this session. Drives the optimistic "Sent" flip on the recents shelf; an
     * id is removed again only if the request comes back rejected. Mutated
     * solely from the action loop, so it needs no synchronization.
     */
    private val requestedFriendIds = mutableSetOf<String>()

    init {
        // [recent-achievements-delay] If this fires every time you tap the
        // Home tab, the VM is being recreated (saveState/restoreState path
        // is broken). If it only fires once per app session, the VM is
        // retained as expected and the delay you're seeing is downstream
        // of the VM.
        homeLogger.i { "[recent-achievements-delay] HomeViewModel init — instance=${this.hashCode()}" }
        viewModelScope.launch {
            // Reactive active-room presence: the banner reflects the room
            // set the instant a join / forfeit lands, no manual refresh. The
            // collector is a pure projection (newest room → banner).
            roomRepository.observeActiveRooms().collect { rooms ->
                takeAction(HomeAction.ActiveRoomsChanged(rooms))
            }
        }
        viewModelScope.launch {
            progressionRepository.observeProgression().collect { progression ->
                takeAction(
                    HomeAction.ProgressionChanged(
                        levelProgressFor(progression.totalXp, progressionConfig.levelCurve()),
                    ),
                )
            }
        }
        viewModelScope.launch {
            // Full-screen level-up celebration, derived (not event-fired) so it
            // survives the table→home trip + process death and shows the net
            // level once on a multi-level jump. See decisions.md 2026-06-06.
            combine(
                progressionRepository.observeProgression(),
                appCache.updates,
            ) { progression, appData ->
                LevelCelebrationGate(
                    currentLevel = levelProgressFor(progression.totalXp, progressionConfig.levelCurve()).level,
                    watermark = appData.lastCelebratedLevel,
                )
            }
                .distinctUntilChanged()
                .collect { gate -> takeAction(HomeAction.EvaluateLevelUp(gate)) }
        }
        viewModelScope.launch {
            chipsRepository.observeBalance().collect { balance ->
                takeAction(HomeAction.ChipsChanged(balance))
            }
        }
        viewModelScope.launch {
            // Drive the "Recent unlocks" shelf on Home. The earned map
            // carries per-achievement unlock timestamps; we sort desc and
            // take the head so the shelf reads "what did I just do."
            // Strip auto-hides when the list is empty (fresh user).
            achievementRepository.observeProgress().collect { progress ->
                val items = progress.toRecentUnlocks(limit = 5)
                // [recent-achievements-delay] Every emission from the
                // upstream Room flow. If this only fires once per app
                // session, the flow is retained — the delay you see when
                // returning to Home is purely a re-render concern, not a
                // re-fetch.
                homeLogger.i {
                    "[recent-achievements-delay] observeProgress emission — " +
                        "items=${items.size} earnedKeys=${items.map { it.achievement.id.name }}"
                }
                takeAction(HomeAction.RecentUnlocksChanged(items))
            }
        }
        viewModelScope.launch {
            // Profile is the canonical source for the user's display
            // name + anon flag (`isAnonymous` mirrors the JWT claim served
            // by `/v1/me`). Previously this read from `UserRepository.User`,
            // which the agent never populated correctly — display name
            // stayed null forever on fresh installs.
            var lastFetchedUserId: String? = null
            profileRepository.observe().collect { profile ->
                takeAction(HomeAction.ProfileChanged(profile))
                // Seed the active-rooms set off the server only once we hold a
                // real session (Profile.Authenticated), and re-seed when the
                // authenticated identity changes — i.e. when self-heal flips
                // Fallback → Authenticated, or an account switch lands. Gating on
                // a real session keeps a session-less cold boot from 401ing the
                // /v1/me/active-rooms call (it used to fire unconditionally on
                // init). Launched off the action pipeline so the resulting
                // observeActiveRooms emission isn't consumed re-entrantly.
                if (profile is Profile.Authenticated && profile.id != lastFetchedUserId) {
                    lastFetchedUserId = profile.id
                    launch { roomRepository.getActiveRooms() }
                    // Social graph is descoped behind SocialEnabled — don't fetch
                    // recents / the friend inbox when the surfaces are hidden.
                    if (isSocialEnabled) {
                        launch { recentOpponentsRepository.refresh() }
                        // Friend graph is account-bound — only a claimed account has
                        // an inbox, so skip the fetch for an anonymous session.
                        if (!profile.isAnonymous) launch { friendRepository.refreshIncomingRequests() }
                    }
                }
            }
        }
        if (isSocialEnabled) {
            viewModelScope.launch {
                // Recently-played-with shelf. The repo resolves bare opponent ids to
                // public profiles; we just project them into state (applying any
                // optimistic "Sent" flips this session). Refresh is kicked once a real
                // session lands, in the profile collector above.
                recentOpponentsRepository.observe().collect { opponents ->
                    takeAction(HomeAction.RecentOpponentsChanged(opponents))
                }
            }
            viewModelScope.launch {
                // Pending inbound friend-request count → the Friends strip badge.
                // The list is the same one the Profile inbox renders; Home only
                // needs the count. Refresh is kicked once a real session lands, in
                // the profile collector above.
                friendRepository.observeIncomingRequests().collect { requests ->
                    takeAction(HomeAction.IncomingRequestsChanged(requests.size))
                }
            }
        }
        viewModelScope.launch {
            appCache.updates.collect { data ->
                takeAction(HomeAction.TutorialBannerDismissedChanged(data.tutorialBannerDismissed))
            }
        }
        observeWelcomeGate()
    }

    /**
     * One-shot starter-grant welcome trigger.
     *
     * Combines two upstream signals — server profile and the [AppData]
     * "already seen" flag — and emits exactly one
     * [HomeEvent.OpenWelcomeDialog] event the first time both align. The
     * view layer responds by navigating to
     * [com.dangerfield.cards.features.home.WelcomeDialogRoute].
     *
     * Chip balance rides along as a *latest snapshot* — the dialog renders
     * with a placeholder when chips haven't hydrated yet, instead of
     * blocking the welcome on the wallet round-trip.
     *
     * Note we no longer gate on `isFirstEverSession`. That flag flipped to
     * `false` the first time the app backgrounded, which permanently locked
     * a user out of the welcome if their profile failed to load before
     * they backgrounded (e.g. /v1/me timed out on a Fly cold-boot →
     * Profile.Fallback → user backgrounds → flag flips → welcome never
     * fires again). `hasSeenStarterWelcome` is now the sole "have we shown
     * this user the welcome yet" signal, set only when the dialog
     * *actually opens*.
     *
     * Persists `hasSeenStarterWelcome=true` *at emit time* (optimistic), so
     * a process death between event and dismissal doesn't cause a re-show.
     * The user has the chips either way; missing the dialog on a crash is
     * fine.
     */
    private fun observeWelcomeGate() {
        viewModelScope.launch {
            combine(
                chipsRepository.observeBalance(),
                profileRepository.observe(),
                appCache.updates,
                chipsRepository.walletJustCreated,
            ) { chips, profile, appData, walletJustCreated ->
                WelcomeGate(
                    chips = chips,
                    profile = profile,
                    walletJustCreated = walletJustCreated,
                    didSeeInitialGrantInOnboarding = appData.didSeeInitialGrantInOnboarding,
                )
            }
                .distinctUntilChanged()
                .onEach { gate ->
                    homeLogger.i {
                        "welcomeGates: resolved=${gate.payload() != null} " +
                            "walletJustCreated=${gate.walletJustCreated} " +
                            "didSeeInOnboarding=${gate.didSeeInitialGrantInOnboarding} " +
                            "profile=${gate.profile.debugKind()} " +
                            "chips=${gate.chips}"
                    }
                }
                // Suspends until the first emission whose gates all align —
                // including a hydrated balance, since the dialog's whole job
                // is to reveal the authoritative number. No state churn
                // after — the cache flip below makes the predicate
                // unsatisfiable for the rest of this VM's life.
                .first { it.payload() != null }
                .let { gate ->
                    val payload = gate.payload()!!
                    // Monotonic: record that we've now shown the grant so it
                    // can't re-fire (even though walletJustCreated stays true
                    // for the rest of this session).
                    appCache.update { it.copy(didSeeInitialGrantInOnboarding = true) }
                    // Beat between Home rendering and the welcome popping;
                    // without it the dialog grabs focus before the user has
                    // oriented on the new screen. See `DialogIntroDelay`.
                    delay(DialogIntroDelay)
                    sendEvent(HomeEvent.OpenWelcomeDialog(payload))
                }
        }
    }

    override suspend fun handleAction(action: HomeAction) {
        when (action) {
            is HomeAction.Refresh -> roomRepository.getActiveRooms()
            is HomeAction.ActiveRoomsChanged -> action.applyActiveRooms(action.rooms)
            is HomeAction.Forfeit -> forfeit(action.code)
            is HomeAction.ProgressionChanged -> action.updateState {
                it.copy(levelProgress = action.progress)
            }
            is HomeAction.ChipsChanged -> action.updateState { it.copy(chips = action.balance) }
            is HomeAction.ProfileChanged -> action.applyProfile(action.profile)
            is HomeAction.RecentUnlocksChanged -> action.updateState {
                it.copy(recentAchievements = action.items)
            }
            is HomeAction.RecentOpponentsChanged -> action.updateState {
                it.copy(
                    recentOpponents = action.profiles.map { profile ->
                        profile.toRecentOpponent(requestSent = profile.id in requestedFriendIds)
                    },
                )
            }
            is HomeAction.IncomingRequestsChanged -> action.updateState {
                it.copy(pendingFriendRequests = action.count)
            }
            is HomeAction.AddFriend -> action.startAddFriend(action.opponentId)
            is HomeAction.FriendRequestFailed -> {
                requestedFriendIds -= action.opponentId
                action.setRequestSent(action.opponentId, sent = false)
            }
            is HomeAction.TutorialBannerDismissedChanged -> action.updateState {
                it.copy(tutorialBannerDismissed = action.dismissed)
            }
            is HomeAction.DismissTutorialBanner -> {
                appCache.update { it.copy(tutorialBannerDismissed = true) }
            }
            is HomeAction.EvaluateLevelUp -> {
                // `watermark == 0` is the unset sentinel: silently seed it to
                // the current level (no celebration) so a fresh install /
                // account switch / reinstall never blasts a celebration for a
                // level the user already had. Thereafter, a current level above
                // the watermark surfaces the overlay for the *current* level.
                val gate = action.gate
                when {
                    gate.watermark == 0 -> {
                        homeLogger.i {
                            "level-up celebration skipped because watermark unset " +
                                "(seeding to level ${gate.currentLevel})"
                        }
                        appCache.update { it.copy(lastCelebratedLevel = gate.currentLevel) }
                    }
                    gate.currentLevel > gate.watermark -> {
                        homeLogger.i {
                            "level-up celebration enqueued for level ${gate.currentLevel} " +
                                "(from watermark ${gate.watermark})"
                        }
                        action.updateState {
                            it.copy(
                                levelUpCelebration = gate.currentLevel,
                                levelUpRewards = crossedLevelRewards(
                                    fromExclusive = gate.watermark,
                                    toInclusive = gate.currentLevel,
                                ),
                            )
                        }
                    }
                    else ->
                        action.updateState {
                            it.copy(levelUpCelebration = null, levelUpRewards = emptyList())
                        }
                }
            }
            is HomeAction.MarkLevelUpShown -> {
                // Fired by the entry point the instant it navigates to the
                // routed celebration. Advance the watermark to the level we're
                // showing so the derived gate goes quiet and we can't re-navigate
                // (e.g. when Home resumes behind the celebration). Clearing the
                // state immediately is what makes the navigation idempotent — by
                // the time Home is visible again, there's nothing to re-fire.
                // Trade-off: a process death *while the celebration is on screen*
                // won't re-show it (the rewards are already granted, so missing
                // the reveal on a crash is fine — mirrors the welcome dialog).
                val reached = stateFlow.value.levelUpCelebration
                action.updateState { it.copy(levelUpCelebration = null, levelUpRewards = emptyList()) }
                if (reached != null) {
                    appCache.update { it.copy(lastCelebratedLevel = reached) }
                }
            }
        }
    }

    private suspend fun HomeAction.applyProfile(profile: Profile) {
        updateState {
            it.copy(
                // Display identity honors a locally-chosen (offline) name +
                // avatar so a Fallback user sees their onboarding choice on the
                // Home header instead of a placeholder. isAnonymous stays
                // Authenticated-only — a Fallback has no real account yet.
                userName = profile.displayNameOrNull,
                avatarEmoji = profile.avatarEmojiOrNull,
                avatarBackgroundColorHex = profile.avatarBackgroundColorOrNull,
                isAnonymous = (profile as? Profile.Authenticated)?.isAnonymous ?: true,
            )
        }
    }

    /**
     * Project the observed room set to the banner. A healthy steady state is
     * exactly one active room; if more than one slips through (a prior session
     * dropped without a clean tear-down) we surface the newest rather than a
     * stack of racing banners. The server's seat-grace timer reaps the stale
     * ones — we don't proactively leave them from here.
     */
    private suspend fun HomeAction.applyActiveRooms(rooms: List<Room>) {
        val keep = rooms.maxByOrNull { it.createdAtEpochMs }
        val summary = keep?.let { listOf(ActiveRoomSummary(it.code)) }.orEmpty()
        updateState { it.copy(activeRooms = summary) }
    }

    private suspend fun forfeit(code: String) {
        // The leave removes the room from the observed flow on success,
        // which clears the banner. On failure the flow is untouched, so the
        // room correctly stays visible — no optimistic drop to undo.
        appScope.async { roomRepository.leaveRoom(code) }.await()
    }

    /**
     * Optimistically flip the tile to "Sent" and fire the request off the
     * action loop so a slow round-trip doesn't stall the rest of Home. The
     * request only un-flips if the server rejects it (not played with / rate
     * limited / network) — a successful or auto-accepted request stays "Sent".
     */
    private suspend fun HomeAction.startAddFriend(opponentId: String) {
        if (opponentId in requestedFriendIds) return
        requestedFriendIds += opponentId
        setRequestSent(opponentId, sent = true)
        viewModelScope.launch {
            val stuck = when (friendRepository.sendRequest(opponentId)) {
                is SendFriendRequestResult.Requested,
                is SendFriendRequestResult.Accepted -> true
                else -> false
            }
            if (!stuck) takeAction(HomeAction.FriendRequestFailed(opponentId))
        }
    }

    private suspend fun HomeAction.setRequestSent(opponentId: String, sent: Boolean) {
        updateState { state ->
            state.copy(
                recentOpponents = state.recentOpponents.map { opponent ->
                    if (opponent.id == opponentId) opponent.copy(requestSent = sent) else opponent
                },
            )
        }
    }

    /**
     * Aggregate the prizes for every level newly crossed — `(fromExclusive,
     * toInclusive]` — into the at-most-two rows the celebration reveals: a single
     * summed chip prize and a single XP-boost row. The range mirrors the grant
     * range in [com.dangerfield.cards.libraries.cards.impl] so the reveal can't
     * claim a prize the granter didn't grant. Multi-level jumps (rare) collapse
     * to one tidy payout instead of a stack of "+chips" lines. Reads the same
     * [ProgressionConfig] the granter does, so reveal and grant never drift.
     */
    private fun crossedLevelRewards(fromExclusive: Int, toInclusive: Int): List<LevelReward> {
        val crossed = ((fromExclusive + 1)..toInclusive).flatMap { progressionConfig.rewardsForLevel(it) }
        val totalChips = crossed.filterIsInstance<LevelReward.Chips>().sumOf { it.amount }
        val hasBoost = crossed.any { it is LevelReward.XpBoost }
        val cosmetic = crossed.filterIsInstance<LevelReward.Cosmetic>().firstOrNull()
        return buildList {
            if (totalChips > 0) add(LevelReward.Chips(totalChips))
            if (hasBoost) add(LevelReward.XpBoost())
            if (cosmetic != null) add(cosmetic)
        }
    }
}

/**
 * Snapshot of all four welcome-dialog preconditions. Lifted to a value
 * type so the trace log can show every gate's current value on a single
 * line and so [payload] is the only place the "all aligned" rule lives.
 */
private data class WelcomeGate(
    val chips: Long?,
    val profile: Profile?,
    val walletJustCreated: Boolean,
    val didSeeInitialGrantInOnboarding: Boolean,
) {
    fun payload(): WelcomePayload? {
        // Fire only for a brand-new wallet we haven't already revealed in
        // onboarding. The "just created" half is live + server-sourced, so a
        // pre-existing account never triggers this — no cross-switch leak.
        if (!walletJustCreated || didSeeInitialGrantInOnboarding) return null
        val auth = profile as? Profile.Authenticated ?: return null
        // Require a hydrated balance — the dialog's whole purpose is to
        // reveal the authoritative number, so we wait for it rather than
        // flashing a placeholder.
        val chips = chips ?: return null
        return WelcomePayload(
            displayName = auth.displayName,
            avatarEmoji = auth.avatarEmoji,
            avatarBackgroundColorHex = auth.avatarBackgroundColor,
            chips = chips,
        )
    }
}

/**
 * Eager payload for the welcome route. All fields — including the
 * authoritative chip balance — have resolved by gate-fire time, so the
 * dialog paints the real number on first frame.
 */
data class WelcomePayload(
    val displayName: String,
    val avatarEmoji: String,
    val avatarBackgroundColorHex: String?,
    val chips: Long,
)

data class HomeState(
    val userName: String? = null,
    val avatarEmoji: String? = null,
    val avatarBackgroundColorHex: String? = null,
    /** Level + XP-into-level snapshot. Drives the header LevelPill's
     *  ring + label. Defaults to a fresh-user shape so the pill always
     *  renders something before progression has hydrated. */
    val levelProgress: LevelProgress = LevelProgress(
        level = 1,
        totalXp = 0,
        xpAtLevelStart = 0,
        xpForNextLevel = 100,
    ),
    /** `null` while the first chip sync hasn't hydrated the local row.
     *  HomeScreen hides / placeholder-renders the chip badge while null
     *  rather than flashing a guessed value. */
    val chips: Long? = null,
    val isAnonymous: Boolean = true,
    val activeRooms: List<ActiveRoomSummary> = emptyList(),
    /** Most-recent achievement unlocks (newest first), capped at 5. Empty
     *  for fresh users — the Home shelf auto-hides in that case. */
    val recentAchievements: List<RecentAchievement> = emptyList(),
    /** Recently-played-with opponents (newest first) for the social shelf.
     *  Empty until the first resolve lands; the strip renders its
     *  friend-via-play empty state in that case. */
    val recentOpponents: List<RecentOpponent> = emptyList(),
    /** Count of pending inbound friend requests, driving the Friends strip's
     *  "N friend requests" badge. The list itself is rendered by the Profile
     *  inbox; Home only needs the count, so the badge tap routes to Profile. */
    val pendingFriendRequests: Int = 0,
    /** Whether the user has dismissed the tutorial banner. Mirrors
     *  `AppData.tutorialBannerDismissed`; false means the banner shows
     *  above the home header.
     *
     *  Defaults to `true` (banner hidden) so we don't flash the banner
     *  on every Home visit before the AppCache emission lands and
     *  reveals the user already dismissed it. First-time users get
     *  `false` from the cache after hydration, which triggers the
     *  AnimatedVisibility enter — same animation, but inviting instead
     *  of jarring. */
    val tutorialBannerDismissed: Boolean = true,
    /** Non-null when the routed full-screen level-up celebration should fire for
     *  this level. Derived from the `AppData.lastCelebratedLevel` watermark vs the
     *  current level, so it survives the table→home trip + process death; the Home
     *  entry point observes it, navigates to `LevelUpRoute`, and immediately fires
     *  [HomeAction.MarkLevelUpShown] (which advances the watermark and clears this,
     *  keeping the navigation idempotent). */
    val levelUpCelebration: Int? = null,
    /** Prizes revealed in the level-up celebration — aggregated across every
     *  level crossed since the last celebration (chips summed, boost de-duped
     *  to one row). Empty when the reached level(s) grant nothing. Mirrors what
     *  `LevelUpRewardGranter` already granted; this is the reveal, not the
     *  grant. Cleared alongside [levelUpCelebration]. */
    val levelUpRewards: List<LevelReward> = emptyList(),
    /** Master gate for the social shelves (friends + recently-played-with).
     *  Mirrors the `social.enabled` app-config flag, default off (SOC-2) —
     *  when false the Home social surfaces don't render at all. */
    val socialEnabled: Boolean = false,
)

/**
 * Inputs the level-up gate derives from — the user's current derived level and
 * the persisted "last celebrated" watermark. Lifted to a value type so the
 * `combine` emits a single `distinctUntilChanged`-able value.
 */
data class LevelCelebrationGate(
    val currentLevel: Int,
    val watermark: Int,
)

/**
 * Pluck the most-recently-earned achievements from [AchievementProgress]
 * and shape them for the Home shelf. Carries the full [Achievement] so
 * the rendering can reuse the shared
 * [com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedal].
 * Unknown ids (would only happen if a row in the local DB references an
 * id we no longer ship) are dropped.
 */
private fun AchievementProgress.toRecentUnlocks(limit: Int): List<RecentAchievement> =
    earned.entries
        .sortedByDescending { it.value }
        .asSequence()
        .mapNotNull { (id, earnedAt) ->
            val achievement = AllAchievementsById[id] ?: return@mapNotNull null
            RecentAchievement(
                achievement = achievement,
                earnedAtEpochMs = earnedAt,
            )
        }
        .take(limit)
        .toList()

private fun RecentOpponentProfile.toRecentOpponent(requestSent: Boolean): RecentOpponent =
    RecentOpponent(
        id = id,
        displayName = displayName,
        emoji = avatarEmoji,
        avatarBackgroundColorHex = avatarBackgroundColorHex,
        requestSent = requestSent,
    )

private fun Profile?.debugKind(): String = when (this) {
    null -> "null"
    is Profile.Authenticated -> if (isAnonymous) "Authenticated(anon)" else "Authenticated"
    is Profile.Fallback -> "Fallback"
}

data class ActiveRoomSummary(
    val code: String,
)

sealed interface HomeEvent {
    data class OpenWelcomeDialog(val payload: WelcomePayload) : HomeEvent
}

sealed interface HomeAction {
    data object Refresh : HomeAction
    data class ActiveRoomsChanged(val rooms: List<Room>) : HomeAction
    data class Forfeit(val code: String) : HomeAction
    data class ProgressionChanged(val progress: LevelProgress) : HomeAction
    data class ChipsChanged(val balance: Long?) : HomeAction
    data class ProfileChanged(val profile: Profile) : HomeAction
    data class RecentUnlocksChanged(val items: List<RecentAchievement>) : HomeAction
    data class RecentOpponentsChanged(val profiles: List<RecentOpponentProfile>) : HomeAction
    data class IncomingRequestsChanged(val count: Int) : HomeAction
    data class AddFriend(val opponentId: String) : HomeAction
    data class FriendRequestFailed(val opponentId: String) : HomeAction
    data class TutorialBannerDismissedChanged(val dismissed: Boolean) : HomeAction
    data object DismissTutorialBanner : HomeAction
    data class EvaluateLevelUp(val gate: LevelCelebrationGate) : HomeAction
    data object MarkLevelUpShown : HomeAction
}
