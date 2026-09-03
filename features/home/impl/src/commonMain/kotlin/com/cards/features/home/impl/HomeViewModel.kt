package com.dangerfield.cards.features.home.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.AppUpdateSource
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Catching
import kotlinx.coroutines.flow.MutableStateFlow
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.PlayStyleRepository
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.features.home.impl.notification.GetHomeScreenNotification
import com.dangerfield.cards.features.home.impl.notification.HomeNotification
import com.dangerfield.cards.features.home.impl.notification.HomeNotificationSnapshot
import com.dangerfield.cards.features.home.impl.notification.outOfChipsResetNeeded
import com.dangerfield.cards.features.home.impl.notification.seedsNeeded
import com.dangerfield.cards.libraries.gameplay.StakeTier
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.OnboardingStarterGrant
import com.dangerfield.cards.libraries.identity.WelcomeFoundingMemberUntil
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.avatarBackgroundColorOrNull
import com.dangerfield.cards.libraries.identity.profile.avatarEmojiOrNull
import com.dangerfield.cards.libraries.identity.profile.displayNameOrNull
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
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
import kotlin.time.Clock
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
    private val onboardingStarterGrant: OnboardingStarterGrant,
    private val foundingMemberUntil: WelcomeFoundingMemberUntil,
    private val clock: Clock,
    private val appCache: AppCache,
    private val appUpdateSource: AppUpdateSource,
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

    // Same synchronous-latch role as [highestCelebrationPresented], but for the
    // out-of-chips sheet: the persisted [AppData.outOfChipsSeen] advances through
    // an async appCache write at present time, and a snapshot computed before
    // that write lands would re-derive the same OutOfChips. Unlike the welcome
    // latch this one *resets* — when the balance recovers past the buy-in the
    // episode closes and the next shortfall may legitimately present again.
    private var outOfChipsPresentedThisEpisode = false
    private var updatePromptPresented = false

    /**
     * Latest version the store will give this user, or null until (and unless)
     * the check answers. Checked once per process, not per Home visit: the
     * answer can't change underneath a running app, and this must never sit
     * between the user and a Home screen they asked for.
     */
    private val latestStoreVersion = MutableStateFlow<String?>(null)

    // Highest level whose celebration we've already presented this VM's life.
    // The persisted [AppData.lastCelebratedLevel] is the durable watermark, but
    // it's advanced through an async appCache round-trip at mark-shown time; a
    // snapshot computed just before that write lands still carries the old
    // watermark and would re-derive the same LevelUp after the state was cleared,
    // firing the celebration twice (PROG-8, CARDS-7N). This in-memory latch closes
    // that window synchronously — a level presented once never presents again.
    private var highestCelebrationPresented = 0

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
        checkForUpdate()
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
    private fun checkForUpdate() {
        appScope.launch {
            latestStoreVersion.value = Catching { appUpdateSource.latestAvailableVersion() }
                .onFailure { homeLogger.d(it) { "Update check failed; not prompting." } }
                .getOrNull()
                ?.toString()
        }
    }

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
            profileRepository.observeAccountJustCreated(),
            latestStoreVersion,
        ) { values ->
            val progression = values[0] as Progression
            val chips = values[1] as Long?
            val profile = values[2] as Profile
            val playStyle = values[3] as PlayStyleAxes?
            val appData = values[4] as AppData
            val accountJustCreated = values[5] as Boolean
            val storeVersion = values[6] as String?

            val currentLevel = levelProgressFor(progression.totalXp, progressionConfig.levelCurve()).level
            val auth = profile as? Profile.Authenticated
            HomeNotificationSnapshot(
                currentLevel = currentLevel,
                lastCelebratedLevel = appData.lastCelebratedLevel,
                crossedLevelRewards = crossedLevelRewards(
                    fromExclusive = appData.lastCelebratedLevel,
                    toInclusive = currentLevel,
                ),
                accountJustCreated = accountJustCreated,
                didSeeInitialGrantInOnboarding = appData.didSeeInitialGrantInOnboarding,
                welcomeSeen = appData.welcomeSeen,
                // Resolved here, against the device clock, so the arbiter stays a
                // pure clock-free function. Only selects the dialog's copy, so a
                // spun-back wall clock just prolongs a thank-you (see the config).
                inFoundingWindow = foundingMemberUntil.isActiveAt(clock.now().toEpochMilliseconds()),
                starterGrant = onboardingStarterGrant.amountOrNull(),
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
                outOfChipsSeen = appData.outOfChipsSeen,
                casualBuyIn = StakeTier.Casual.buyIn,
                pendingAchievementIds = appData.pendingHomeAchievementIds,
                installedVersion = BuildInfo.versionName,
                latestStoreVersion = storeVersion,
                lastPromptedUpdateVersion = appData.lastPromptedUpdateVersion,
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
                    // Keep the in-memory latch in step with the durable seed so a
                    // freshly-seeded account never re-celebrates a level it already had.
                    highestCelebrationPresented = maxOf(highestCelebrationPresented, seedLevel)
                    appCache.update { it.copy(lastCelebratedLevel = seedLevel) }
                }
                // Balance recovered past the buy-in — close the out-of-chips
                // episode so the *next* shortfall presents again. Mirrors the
                // seed writes above: maintenance of persisted markers lives
                // here, out of the pure arbiter.
                if (action.snapshot.outOfChipsResetNeeded()) {
                    homeLogger.i { "home notification: out-of-chips episode closed (balance recovered)" }
                    outOfChipsPresentedThisEpisode = false
                    appCache.update { it.copy(outOfChipsSeen = false) }
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
                    homeLogger.i { "home notification: level-up celebration consumed for level $reached" }
                    appCache.update { it.copy(lastCelebratedLevel = reached) }
                }
            }
            is HomeAction.MarkAchievementCelebrationShown -> {
                // The player dismissed the celebration — drain the queue (persisted
                // + state) so it never replays and the arbiter can present the next
                // pending blocking notification (e.g. a level-up from the same game).
                action.updateState { it.copy(achievementCelebration = emptyList()) }
                appCache.update { it.copy(pendingHomeAchievementIds = emptyList()) }
            }
        }
    }

    private fun List<String>.toEarnedAchievements(): List<EarnedAchievement> =
        mapNotNull { name ->
            val id = achievementIdsByName[name] ?: return@mapNotNull null
            AllAchievementsById[id]?.let { EarnedAchievement(achievement = it, earnedAtEpochMs = 0L) }
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
                // Synchronous idempotency: a stale snapshot re-deriving this level
                // after mark-shown cleared the state can't fire it again (PROG-8).
                if (notification.level <= highestCelebrationPresented) return
                if (stateFlow.value.levelUpCelebration == notification.level) return
                highestCelebrationPresented = notification.level
                homeLogger.i { "home notification: level-up celebration enqueued for level ${notification.level}" }
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
                homeLogger.i {
                    "home notification: welcome dialog " +
                        "(founding=${notification.isFounding}, reveal=${notification.grantReveal != null})"
                }
                // Fire the backup-reveal event only when we're actually revealing a
                // real number here — i.e. onboarding's reveal degraded and this is
                // the one place the user learns their starter chips. Keeps the
                // funnel one query: a `grant_reveal_degraded` with no later
                // `home_backup` means they never saw their starter chips at all.
                (notification.grantReveal as? HomeNotification.Welcome.GrantReveal.Exact)?.let { reveal ->
                    homeLogger.logEvent(
                        "onboarding.grant_revealed",
                        "surface" to "home_backup",
                        "amount" to reveal.chips,
                    )
                }
                // Mark seen first so a re-entrant snapshot can't double-fire while
                // the write lands.
                appCache.update { it.copy(welcomeSeen = true) }
                delay(DialogIntroDelay)
                sendEvent(HomeEvent.OpenWelcomeDialog(notification.toPayload()))
            }
            is HomeNotification.AchievementsEarned -> {
                // Already showing this batch — a re-entrant snapshot (a different
                // flow emitting before the drain write lands) can't re-present it.
                // The drain (MarkAchievementCelebrationShown) clears both the state
                // and the persisted queue, so the arbiter goes quiet afterwards and
                // a genuinely new batch from a later game presents again.
                if (stateFlow.value.achievementCelebration.isNotEmpty()) return
                val earned = notification.achievementIds.toEarnedAchievements()
                if (earned.isEmpty()) return
                homeLogger.i { "home notification: MP achievement celebration (${earned.size})" }
                updateState { it.copy(achievementCelebration = earned) }
            }
            is HomeNotification.PlayStyleUnlocked -> {
                if (playStyleUnlockPresented) return
                playStyleUnlockPresented = true
                homeLogger.i { "home notification: play-style unlocked" }
                appCache.update { it.copy(playStyleUnlockSeen = true) }
                delay(DialogIntroDelay)
                sendEvent(HomeEvent.OpenPlayStyleUnlocked)
            }
            is HomeNotification.UpdateAvailable -> {
                if (updatePromptPresented) return
                updatePromptPresented = true
                homeLogger.i { "home notification: update available (${notification.latestVersion})" }
                homeLogger.logEvent(
                    "app.update_prompt_shown",
                    "installed" to BuildInfo.versionName,
                    "latest" to notification.latestVersion,
                )
                // Mark before presenting, same as the others: a re-entrant
                // snapshot must not double-fire while the write lands.
                appCache.update { it.copy(lastPromptedUpdateVersion = notification.latestVersion) }
                delay(DialogIntroDelay)
                sendEvent(HomeEvent.OpenUpdateAvailable(notification.latestVersion))
            }
            is HomeNotification.OutOfChips -> {
                if (outOfChipsPresentedThisEpisode) return
                outOfChipsPresentedThisEpisode = true
                homeLogger.i {
                    "home notification: out of chips (balance=${notification.balance}, buyIn=${notification.casualBuyIn})"
                }
                homeLogger.logEvent(
                    "economy.out_of_chips_shown",
                    "balance" to notification.balance,
                    "context" to "home",
                )
                // Monotonic-within-the-episode mark first, same as welcome — a
                // re-entrant snapshot can't double-fire while the write lands.
                appCache.update { it.copy(outOfChipsSeen = true) }
                delay(DialogIntroDelay)
                sendEvent(
                    HomeEvent.OpenOutOfChipsSheet(
                        balance = notification.balance,
                        casualBuyIn = notification.casualBuyIn,
                    ),
                )
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
        // room correctly stays visible — but the user just confirmed a
        // destructive dialog, so a held seat needs an explicit error too
        // (ROOM-17).
        when (val outcome = appScope.async { roomRepository.leaveRoom(code) }.await()) {
            is LeaveRoomOutcome.Success,
            LeaveRoomOutcome.NotFound,
            LeaveRoomOutcome.NotInRoom,
                -> Unit
            is LeaveRoomOutcome.NetworkError -> sendEvent(HomeEvent.ForfeitFailed)
            is LeaveRoomOutcome.Unknown -> {
                homeLogger.w(outcome.cause) { "Forfeit leave failed" }
                sendEvent(HomeEvent.ForfeitFailed)
            }
        }
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
 * Eager payload for the welcome route, flattened to primitives so it survives
 * the trip through [WelcomeDialogRoute]'s serializable nav args. [grantChips] is
 * the exact figure to reveal (null = none); [grantPending] asks for the "chips
 * landing soon" treatment instead; [isFounding] layers on the founding-member
 * copy and its review / feedback actions.
 */
data class WelcomePayload(
    val displayName: String,
    val avatarEmoji: String,
    val avatarBackgroundColorHex: String?,
    val grantChips: Long?,
    val grantPending: Boolean,
    val isFounding: Boolean,
)

private fun HomeNotification.Welcome.toPayload(): WelcomePayload = WelcomePayload(
    displayName = displayName,
    avatarEmoji = avatarEmoji,
    avatarBackgroundColorHex = avatarBackgroundColorHex,
    grantChips = (grantReveal as? HomeNotification.Welcome.GrantReveal.Exact)?.chips,
    grantPending = grantReveal is HomeNotification.Welcome.GrantReveal.Pending,
    isFounding = isFounding,
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
    /** Non-empty when the achievement-celebration sheet should show for unlocks
     *  earned in a real-chip MP game that had no at-table reveal (PROG-13). The
     *  entry point observes this, presents the shared [AchievementCelebrationSheet],
     *  and fires [HomeAction.MarkAchievementCelebrationShown] on dismiss to drain
     *  the queue. Reconstructed from the persisted id queue, so it's plain UI
     *  state, not persisted itself. */
    val achievementCelebration: List<EarnedAchievement> = emptyList(),
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

private val achievementIdsByName: Map<String, AchievementId> =
    AchievementId.entries.associateBy { it.name }

data class ActiveRoomSummary(
    val code: String,
)

sealed interface HomeEvent {
    data class OpenWelcomeDialog(val payload: WelcomePayload) : HomeEvent

    /** The user just crossed the play-style sample threshold — announce it once. */
    data object OpenPlayStyleUnlocked : HomeEvent

    /** The balance dropped under the Casual buy-in — offer the ways back once per episode. */
    data class OpenOutOfChipsSheet(val balance: Long, val casualBuyIn: Long) : HomeEvent

    /** A newer store version is worth mentioning. Lowest-priority blocking notification. */
    data class OpenUpdateAvailable(val latestVersion: String) : HomeEvent

    /** The confirmed Forfeit's leave call failed — the seat is still held, say so. */
    data object ForfeitFailed : HomeEvent
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

    /** The MP achievement celebration was dismissed — drain the queue. */
    data object MarkAchievementCelebrationShown : HomeAction
}
