package com.dangerfield.cards.features.home.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class HomeViewModel(
    private val progressionRepository: ProgressionRepository,
    private val chipsRepository: ChipsRepository,
    private val roomRepository: RoomRepository,
    private val profileRepository: ProfileRepository,
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
) : SEAViewModel<HomeState, HomeEvent, HomeAction>(
    initialStateArg = HomeState()
) {

    private val homeLogger = KLog.withTag("HomeViewModel")

    init {
        takeAction(HomeAction.LoadActiveRooms)
        viewModelScope.launch {
            progressionRepository.observeProgression().collect { progression ->
                takeAction(HomeAction.XpChanged(progression.totalXp))
            }
        }
        viewModelScope.launch {
            chipsRepository.observeBalance().collect { balance ->
                takeAction(HomeAction.ChipsChanged(balance))
            }
        }
        viewModelScope.launch {
            // Profile is the canonical source for the user's display
            // name + anon flag (`isAnonymous` mirrors the JWT claim served
            // by `/v1/me`). Previously this read from `UserRepository.User`,
            // which the agent never populated correctly — display name
            // stayed null forever on fresh installs.
            profileRepository.observe().collect { profile ->
                takeAction(HomeAction.ProfileChanged(profile))
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
            ) { chips, profile, appData ->
                WelcomeGate(
                    chips = chips,
                    profile = profile,
                    hasSeenStarterWelcome = appData.hasSeenStarterWelcome,
                )
            }
                .distinctUntilChanged()
                .onEach { gate ->
                    homeLogger.i {
                        "welcomeGates: resolved=${gate.payload() != null} " +
                            "hasSeenStarterWelcome=${gate.hasSeenStarterWelcome} " +
                            "profile=${gate.profile.debugKind()} " +
                            "chips=${gate.chips}"
                    }
                }
                // Suspends until the first emission whose non-chip gates
                // align. No state churn after — the cache flip below makes
                // the predicate unsatisfiable for the rest of this VM's
                // life.
                .first { it.payload() != null }
                .let { gate ->
                    val payload = gate.payload()!!
                    appCache.update { it.copy(hasSeenStarterWelcome = true) }
                    sendEvent(HomeEvent.OpenWelcomeDialog(payload))
                }
        }
    }

    override suspend fun handleAction(action: HomeAction) {
        when (action) {
            is HomeAction.Refresh -> action.loadActiveRooms()
            is HomeAction.LoadActiveRooms -> action.loadActiveRooms()
            is HomeAction.Forfeit -> action.forfeit(action.code)
            is HomeAction.XpChanged -> action.updateState { it.copy(xp = action.totalXp) }
            is HomeAction.ChipsChanged -> action.updateState { it.copy(chips = action.balance) }
            is HomeAction.ProfileChanged -> action.applyProfile(action.profile)
        }
    }

    private suspend fun HomeAction.applyProfile(profile: Profile) {
        val auth = profile as? Profile.Authenticated
        updateState {
            it.copy(
                userName = auth?.displayName,
                avatarEmoji = auth?.avatarEmoji,
                avatarBackgroundColorHex = auth?.avatarBackgroundColor,
                isAnonymous = auth?.isAnonymous ?: true,
            )
        }
    }

    private suspend fun HomeAction.loadActiveRooms() {
        val rooms = when (val outcome = roomRepository.getActiveRooms()) {
            is GetActiveRoomsOutcome.Success -> reconcileToSingleRoom(outcome.rooms)
            is GetActiveRoomsOutcome.NotSignedIn,
            is GetActiveRoomsOutcome.NetworkError,
            is GetActiveRoomsOutcome.Unknown -> emptyList()
        }
        updateState { it.copy(activeRooms = rooms) }
    }

    private fun reconcileToSingleRoom(rooms: List<Room>): List<ActiveRoomSummary> {
        if (rooms.size <= 1) return rooms.map { ActiveRoomSummary(it.code) }
        val sortedNewestFirst = rooms.sortedByDescending { it.createdAtEpochMs }
        val keep = sortedNewestFirst.first()
        val stale = sortedNewestFirst.drop(1)
        homeLogger.w {
            "Multi-active-room recovery: keeping ${keep.code}, leaving ${stale.size} stale room(s): " +
                stale.joinToString { it.code }
        }
        stale.forEach { stale ->
            appScope.launch { roomRepository.leaveRoom(stale.code) }
        }
        return listOf(ActiveRoomSummary(keep.code))
    }

    private suspend fun HomeAction.forfeit(code: String) {
        updateState { it.copy(activeRooms = it.activeRooms.filterNot { room -> room.code == code }) }
        val outcome = appScope.async { roomRepository.leaveRoom(code) }.await()
        when (outcome) {
            is LeaveRoomOutcome.Success,
            is LeaveRoomOutcome.NotFound,
            is LeaveRoomOutcome.NotInRoom -> Unit
            is LeaveRoomOutcome.NetworkError,
            is LeaveRoomOutcome.Unknown -> loadActiveRooms()
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
    val hasSeenStarterWelcome: Boolean,
) {
    fun payload(): WelcomePayload? {
        if (hasSeenStarterWelcome) return null
        val auth = profile as? Profile.Authenticated ?: return null
        return WelcomePayload(
            displayName = auth.displayName,
            avatarEmoji = auth.avatarEmoji,
            avatarBackgroundColorHex = auth.avatarBackgroundColor,
            chips = chips,
        )
    }
}

/**
 * Eager payload for the welcome route — the non-chip fields have resolved
 * by gate-fire time so the dialog paints on first frame. Chips ride along
 * as a snapshot and may be null on slow networks; the dialog falls back to
 * a placeholder rather than blocking the welcome on the wallet round-trip.
 */
data class WelcomePayload(
    val displayName: String,
    val avatarEmoji: String,
    val avatarBackgroundColorHex: String?,
    val chips: Long?,
)

data class HomeState(
    val userName: String? = null,
    val avatarEmoji: String? = null,
    val avatarBackgroundColorHex: String? = null,
    val xp: Long = 0,
    /** `null` while the first chip sync hasn't hydrated the local row.
     *  HomeScreen hides / placeholder-renders the chip badge while null
     *  rather than flashing a guessed value. */
    val chips: Long? = null,
    val isAnonymous: Boolean = true,
    val activeRooms: List<ActiveRoomSummary> = emptyList(),
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
    data object LoadActiveRooms : HomeAction
    data class Forfeit(val code: String) : HomeAction
    data class XpChanged(val totalXp: Long) : HomeAction
    data class ChipsChanged(val balance: Long?) : HomeAction
    data class ProfileChanged(val profile: Profile) : HomeAction
}
