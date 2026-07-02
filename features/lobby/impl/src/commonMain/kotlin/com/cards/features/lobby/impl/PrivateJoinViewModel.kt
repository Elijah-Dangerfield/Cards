package com.dangerfield.cards.features.lobby.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.RoomRepository
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/** Room codes are 6-char alphanumeric (server `InMemoryRoomService.CODE_LENGTH`). */
private const val RoomCodeLength = 6

/**
 * Drives the "Join by code" screen (ROOM-5). The code is validated here, on the
 * input screen, by attempting the join in place — so an unknown code surfaces an
 * inline error and the screen never moves. Only a successful join navigates on to
 * the seated lobby (which re-attaches idempotently via the prefilled code; the
 * server returns `alreadyJoined`).
 *
 * The earlier funnel pushed the lobby first and bounced back on a 404, which read
 * as a navigation event to the user — two route batches for a typo. Validating
 * before navigating keeps the input + keyboard put and shows the error in place.
 */
@Inject
class PrivateJoinViewModel(
    @Assisted private val rejectedCode: String?,
    private val rooms: RoomRepository,
) : SEAViewModel<PrivateJoinState, PrivateJoinEvent, PrivateJoinAction>(
    initialStateArg = rejectedCode?.uppercase()?.takeIf { it.isNotBlank() }?.let { code ->
        // Deep-link bounce: a tapped invite carried a dead code. Seed the field +
        // the inline error so the user lands here ready to fix it, without a
        // re-join round-trip (the lobby already 404'd on the way in).
        PrivateJoinState(code = code, error = PrivateJoinError.RoomNotFound(code))
    } ?: PrivateJoinState(),
) {

    private val logger = KLog.withTag("PrivateJoinVM")

    override suspend fun handleAction(action: PrivateJoinAction) {
        when (action) {
            is PrivateJoinAction.CodeChanged -> action.updateState {
                it.copy(code = action.value.uppercase().take(RoomCodeLength), error = null)
            }

            PrivateJoinAction.Submit -> action.run {
                val current = state
                if (current.joining) return@run
                val code = current.code.trim()
                if (code.length != RoomCodeLength) {
                    updateState { it.copy(error = PrivateJoinError.BlankCode) }
                    return@run
                }
                updateState { it.copy(joining = true, error = null) }
                when (val outcome = rooms.joinRoom(code)) {
                    is JoinRoomOutcome.Success -> {
                        updateState { it.copy(joining = false) }
                        sendEvent(PrivateJoinEvent.NavigateToLobby(code))
                    }
                    JoinRoomOutcome.NotFound -> updateState {
                        it.copy(joining = false, error = PrivateJoinError.RoomNotFound(code))
                    }
                    JoinRoomOutcome.Full -> updateState {
                        it.copy(joining = false, error = PrivateJoinError.RoomFull)
                    }
                    JoinRoomOutcome.NotJoinable -> updateState {
                        it.copy(joining = false, error = PrivateJoinError.RoomNotAcceptingPlayers)
                    }
                    is JoinRoomOutcome.OverBalance -> updateState {
                        it.copy(joining = false, error = PrivateJoinError.OverBalance(outcome.message))
                    }
                    is JoinRoomOutcome.NotSignedIn -> updateState {
                        // The repo only produces this for a confirmed account
                        // problem; offline arrives as NetworkError.
                        it.copy(joining = false, error = PrivateJoinError.NotSignedIn)
                    }
                    is JoinRoomOutcome.NetworkError -> updateState {
                        it.copy(joining = false, error = PrivateJoinError.NetworkError)
                    }
                    is JoinRoomOutcome.Unknown -> {
                        logger.w(outcome.cause) { "Join room failed" }
                        updateState { it.copy(joining = false, error = PrivateJoinError.UnknownError) }
                    }
                }
            }
        }
    }
}

data class PrivateJoinState(
    val code: String = "",
    val joining: Boolean = false,
    val error: PrivateJoinError? = null,
) {
    val canSubmit: Boolean get() = !joining && code.length == RoomCodeLength
}

sealed interface PrivateJoinAction {
    data class CodeChanged(val value: String) : PrivateJoinAction
    data object Submit : PrivateJoinAction
}

sealed interface PrivateJoinEvent {
    /** The code resolved to a real room and the join succeeded; hand off to the seated lobby. */
    data class NavigateToLobby(val code: String) : PrivateJoinEvent
}

/** Inline error surfaced under the code field — the screen never navigates on one. */
sealed interface PrivateJoinError {
    data object BlankCode : PrivateJoinError
    data class RoomNotFound(val code: String) : PrivateJoinError
    data object RoomFull : PrivateJoinError
    data object RoomNotAcceptingPlayers : PrivateJoinError
    data class OverBalance(val message: String) : PrivateJoinError
    data object NotSignedIn : PrivateJoinError
    data object NetworkError : PrivateJoinError
    data object UnknownError : PrivateJoinError
}
