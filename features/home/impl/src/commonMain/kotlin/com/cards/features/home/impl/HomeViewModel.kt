package com.dangerfield.cards.features.home.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.PlayStyleRepository
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.features.home.impl.notification.GetHomeScreenNotification
import com.dangerfield.cards.features.home.impl.notification.HomeNotification
import com.dangerfield.cards.features.home.impl.notification.HomeNotificationSnapshot
import com.dangerfield.cards.features.home.impl.notification.seedsNeeded
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val playStyleRepository: PlayStyleRepository,
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

    // True while Home is the foreground screen. Gates whether a live balance change
    // updates the "last seen" baseline (see the chips collector), and whether a
    // pending blocking notification is allowed to present. Mutated only from the
    // action loop. See [HomeAction.ScreenResumed] / [HomeAction.ScreenPaused].
    private var homeResumed = false

    // The most recent notification snapshot the arbiter reasons over. Latched so a
    // notification that resolves while Home is off-screen can be re-presented the
    // instant Home settles again. Mutated only from the action loop.
    private var latestNotificationSnapshot: HomeNotificationSnapshot? = null

    // One-shot latches so a re-entrant snapshot (or a resume) can't double-fire the
    // welcome / play-style events after they've already been sent this VM's life.
    private var welcomePresented = false
    private var playStyleUnlockPresented = false

    init {
        viewModelScope.launch {
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
        observeHomeNotifications()
        viewModelScope.launch {
            chipsRepository.observeBalance().collect { balance ->
                takeAction(HomeAction.ChipsChanged(balance))
                // Reconcile the odometer against the value the user last saw on
                // every change — not just on resume — so a change that lands while
                // Home is already foregrounded (e.g. the leave-game sync responding
                // after navigation) still animates instead of snapping.
                takeAction(HomeAction.ReconcileChipReveal)
            }
        }
        viewModelScope.launch {
            chipsRepository.isReconciling.collect { reconciling ->
                takeAction(HomeAction.ChipsReconcilingChanged(reconciling))
            }
        }
        viewModelScope.launch {
            achievementRepository.observeProgress().collect { progress ->
                takeAction(HomeAction.RecentUnlocksChanged(progress.toRecentUnlocks(limit = 5)))
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
    }

    /**
     * The single arbiter for every "when the user lands on Home, show X"
     * blocking moment (starter-grant welcome, level-up celebration, play-style
     * unlock). Each of these used to be gated independently — its own flag, its
     * own `combine`/`first`, its own race — and the level-up celebration lost
     * two ways (a fresh seed swallowing a real crossing, and being swept off
     * Home before it played). Folding them into one [HomeNotificationSnapshot]
     * → [GetHomeScreenNotification] pass puts priority and the seed-vs-crossing
     * rule in exactly one place.
     *
     * Discipline this enforces:
     * - **Seed, don't celebrate.** An unset watermark (fresh install / account
     *   switch) is seeded to the current state via [seedsNeeded] with no reveal.
     *   Seeding is kept out of [GetHomeScreenNotification] so a silent seed can
     *   never eat a real crossing.
     * - **Present only when settled.** A pending blocking notification is only
     *   handed to the view once Home is the foreground screen ([homeResumed]) —
     *   the same signal the chip odometer uses — so a celebration can't fire
     *   while the user is being swept elsewhere.
     * - **Advance the watermark only after a confirmed present.** For the level
     *   celebration the entry point fires [HomeAction.MarkLevelUpShown] at
     *   navigate-time; for welcome + play-style the mark happens here at present
     *   time. Either way the "we showed it" write follows the present, not
     *   precedes it.
     */
    private fun observeHomeNotifications() {
        viewModelScope.launch {
            homeNotificationSnapshots()
                .distinctUntilChanged()
                .collect { snapshot -> takeAction(HomeAction.EvaluateNotifications(snapshot)) }
        }
    }

    private fun homeNotificationSnapshots(): Flow<HomeNotificationSnapshot> =
        combine(
            progressionRepository.observeProgression(),
            chipsRepository.observeBalance(),
            profileRepository.observe(),
            playStyleRepository.observeOwnStyle(),
            appCache.updates,
            chipsRepository.walletJustCreated,
        ) { values ->
            val progression = values[0] as Progression
            val chips = values[1] as Long?
            val profile = values[2] as Profile
            val playStyle = values[3] as PlayStyleAxes?
            val appData = values[4] as AppData
            val walletJustCreated = values[5] as Boolean

            val currentLevel = levelProgressFor(progression.totalXp, progressionConfig.levelCurve()).level
            val auth = profile as? Profile.Authenticated
            HomeNotificationSnapshot(
                currentLevel = currentLevel,
                lastCelebratedLevel = appData.lastCelebratedLevel,
                crossedLevelRewards = crossedLevelRewards(
                    fromExclusive = appData.lastCelebratedLevel,
                    toInclusive = currentLevel,
                ),
                walletJustCreated = walletJustCreated,
                didSeeInitialGrantInOnboarding = appData.didSeeInitialGrantInOnboarding,
                welcomeIdentity = auth?.let {
                    HomeNotificationSnapshot.WelcomeIdentity(
                        displayName = it.displayName,
                        avatarEmoji = it.avatarEmoji,
                        avatarBackgroundColorHex = it.avatarBackgroundColor,
                    )
                },
                playStyleSampleSize = playStyle?.sampleSize,
                playStyleUnlockThreshold = PlayStyleAxes.MIN_SAMPLE,
                playStyleUnlockSeen = appData.playStyleUnlockSeen,
                chipBalance = chips,
                lastShownChipBalance = appData.lastShownChipBalance,
            )
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
            is HomeAction.ChipsReconcilingChanged -> action.updateState {
                it.copy(chipsReconciling = action.reconciling)
            }
            is HomeAction.ScreenResumed -> {
                homeResumed = true
                takeAction(HomeAction.ReconcileChipReveal)
                // Home just settled — flush any blocking notification that was
                // pending while we were off-screen (the exact case a celebration
                // used to be swept away in).
                action.presentPendingBlocking()
            }
            is HomeAction.ScreenPaused -> homeResumed = false
            is HomeAction.ReconcileChipReveal -> {
                // Only while Home is the foreground screen — otherwise leave the
                // baseline frozen at what the user last saw so the accumulated
                // change replays when they return. Reads the local source of truth
                // (no backend); arms the odometer to roll from last-seen to current
                // and advances the baseline so the same change can't replay twice.
                if (homeResumed) {
                    val current = chipsRepository.getBalance()
                    val lastShown = appCache.get().lastShownChipBalance
                    if (current != null && lastShown != current) {
                        if (lastShown != null) {
                            action.updateState {
                                it.copy(
                                    chips = current,
                                    chipsRevealFrom = lastShown,
                                    chipsRevealKey = it.chipsRevealKey + 1,
                                )
                            }
                        }
                        appCache.update { it.copy(lastShownChipBalance = current) }
                    }
                }
            }
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
            is HomeAction.EvaluateNotifications -> {
                latestNotificationSnapshot = action.snapshot
                // Seed unset watermarks to the current state with no reveal — a
                // fresh install / account switch adopts its level as the baseline
                // rather than blasting a celebration for a level it already had.
                // Kept out of the arbiter so a silent seed can't eat a real
                // crossing (the PROG-5 failure mode).
                action.snapshot.seedsNeeded().seedCelebratedLevel?.let { seedLevel ->
                    homeLogger.i { "home notification: seeding celebration watermark to level $seedLevel" }
                    appCache.update { it.copy(lastCelebratedLevel = seedLevel) }
                }
                action.presentPendingBlocking()
            }
            is HomeAction.MarkLevelUpShown -> {
                // Fired by the entry point the instant it navigates to the
                // routed celebration. Advance the watermark to the level we're
                // showing so the arbiter goes quiet and we can't re-navigate
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

    /**
     * Run the arbiter over the latest snapshot and present its single blocking
     * pick — but only while Home is settled ([homeResumed]). A pending
     * notification that resolves while the user is off Home stays latched in
     * [latestNotificationSnapshot] and flushes on the next [HomeAction.ScreenResumed].
     *
     * The "we showed it" write follows the present: level-up advances via
     * [HomeAction.MarkLevelUpShown] at navigate-time; welcome + play-style mark
     * their watermarks here, immediately after the event is sent, so they can't
     * re-fire.
     */
    private suspend fun HomeAction.presentPendingBlocking() {
        if (!homeResumed) return
        val snapshot = latestNotificationSnapshot ?: return
        when (val notification = GetHomeScreenNotification(snapshot)) {
            is HomeNotification.LevelUp -> {
                if (stateFlow.value.levelUpCelebration == notification.level) return
                homeLogger.i { "home notification: level-up celebration for level ${notification.level}" }
                updateState {
                    it.copy(
                        levelUpCelebration = notification.level,
                        levelUpRewards = notification.rewards,
                    )
                }
            }
            is HomeNotification.Welcome -> {
                if (welcomePresented) return
                welcomePresented = true
                homeLogger.i { "home notification: starter-grant welcome (chips=${notification.chips})" }
                // Monotonic mark first so a re-entrant snapshot can't double-fire.
                appCache.update { it.copy(didSeeInitialGrantInOnboarding = true) }
                delay(DialogIntroDelay)
                sendEvent(
                    HomeEvent.OpenWelcomeDialog(
                        WelcomePayload(
                            displayName = notification.displayName,
                            avatarEmoji = notification.avatarEmoji,
                            avatarBackgroundColorHex = notification.avatarBackgroundColorHex,
                            chips = notification.chips,
                        ),
                    ),
                )
            }
            is HomeNotification.PlayStyleUnlocked -> {
                if (playStyleUnlockPresented) return
                playStyleUnlockPresented = true
                homeLogger.i { "home notification: play-style unlocked" }
                appCache.update { it.copy(playStyleUnlockSeen = true) }
                delay(DialogIntroDelay)
                sendEvent(HomeEvent.OpenPlayStyleUnlocked)
            }
            null -> Unit
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
 * Eager payload for the welcome route. All fields — including the
 * authoritative chip balance — have resolved by present time, so the
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
    /** Baseline the chip odometer rolls *from* on the next reveal — the value the
     *  user last saw, used to replay a change that landed while they were away. */
    val chipsRevealFrom: Long? = null,
    /** Bumped each time a chip-change reveal is armed, so the odometer fires once
     *  per missed change rather than on every recomposition. */
    val chipsRevealKey: Int = 0,
    /** True while a wallet reconcile is in flight — the header renders the chip
     *  balance as "updating" so a pre-settlement value doesn't read as final. */
    val chipsReconciling: Boolean = false,
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

data class ActiveRoomSummary(
    val code: String,
)

sealed interface HomeEvent {
    data class OpenWelcomeDialog(val payload: WelcomePayload) : HomeEvent

    /** The user just crossed the play-style sample threshold — announce it once. */
    data object OpenPlayStyleUnlocked : HomeEvent
}

sealed interface HomeAction {
    data object Refresh : HomeAction
    data class ActiveRoomsChanged(val rooms: List<Room>) : HomeAction
    data class Forfeit(val code: String) : HomeAction
    data class ProgressionChanged(val progress: LevelProgress) : HomeAction
    data class ChipsChanged(val balance: Long?) : HomeAction
    data class ChipsReconcilingChanged(val reconciling: Boolean) : HomeAction

    /** Home became visible — start reconciling the chip odometer against what the
     *  user last saw, replaying any change they missed while away. */
    data object ScreenResumed : HomeAction

    /** Home went to the background — freeze the "last seen" baseline. */
    data object ScreenPaused : HomeAction

    /** Reconcile the odometer with the local source of truth: if the balance
     *  differs from what the user last saw (and Home is foregrounded), roll from
     *  last-seen to current and advance the baseline. */
    data object ReconcileChipReveal : HomeAction
    data class ProfileChanged(val profile: Profile) : HomeAction
    data class RecentUnlocksChanged(val items: List<RecentAchievement>) : HomeAction
    data class RecentOpponentsChanged(val profiles: List<RecentOpponentProfile>) : HomeAction
    data class IncomingRequestsChanged(val count: Int) : HomeAction
    data class AddFriend(val opponentId: String) : HomeAction
    data class FriendRequestFailed(val opponentId: String) : HomeAction
    data class TutorialBannerDismissedChanged(val dismissed: Boolean) : HomeAction
    data object DismissTutorialBanner : HomeAction

    /** A fresh notification snapshot resolved — seed unset watermarks and, if Home
     *  is settled, present the single highest-priority blocking notification. */
    data class EvaluateNotifications(val snapshot: HomeNotificationSnapshot) : HomeAction
    data object MarkLevelUpShown : HomeAction
}
