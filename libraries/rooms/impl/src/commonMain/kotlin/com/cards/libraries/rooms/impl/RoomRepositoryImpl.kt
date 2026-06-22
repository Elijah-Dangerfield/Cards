package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.rooms.AddBotOutcome
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.RemoveBotOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Glue between [RoomApi] (HTTP) + [RoomSocket] (WebSocket). Status-code
 * mapping lives here so the UI layer only sees sealed outcome types.
 *
 * The auth-related outcomes ([CreateRoomOutcome.NotSignedIn] etc.) fire
 * when the bearer refresh chain runs out of tokens (Ktor's Auth plugin
 * throws on a final 401). UI maps to "please sign in again."
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class RoomRepositoryImpl(
    private val api: RoomApi,
    private val socket: RoomSocket,
) : RoomRepository {

    private val activeRooms = MutableStateFlow<List<Room>>(emptyList())

    override fun observeActiveRooms(): Flow<List<Room>> = activeRooms.asStateFlow()

    override suspend fun createRoom(maxSeats: Int?, buyIn: Long?): CreateRoomOutcome = try {
        val response = api.create(CreateRoomRequestDto(maxSeats = maxSeats, buyIn = buyIn))
        val body = response.body<CreateRoomResponseDto>()
        val room = body.room.toDomain()
        upsertActiveRoom(room)
        CreateRoomOutcome.Success(room)
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.BadRequest ->
                CreateRoomOutcome.InvalidMaxSeats(extractMessage(e) ?: "maxSeats must be 2..9")
            HttpStatusCode.Unauthorized -> CreateRoomOutcome.NotSignedIn(e)
            else -> CreateRoomOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        CreateRoomOutcome.NetworkError(e)
    } catch (e: ServerResponseException) {
        CreateRoomOutcome.Unknown(e)
    } catch (e: Throwable) {
        CreateRoomOutcome.NetworkError(e)
    }

    override suspend fun joinRoom(code: String): JoinRoomOutcome = try {
        val response = api.join(code)
        val body = response.body<JoinRoomResponseDto>()
        val room = body.room.toDomain()
        upsertActiveRoom(room)
        JoinRoomOutcome.Success(room = room, alreadyJoined = body.alreadyJoined)
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.NotFound -> JoinRoomOutcome.NotFound
            HttpStatusCode.Conflict -> when (extractCode(e)) {
                "room_full" -> JoinRoomOutcome.Full
                "room_not_joinable" -> JoinRoomOutcome.NotJoinable
                else -> JoinRoomOutcome.Unknown(e)
            }
            HttpStatusCode.Unauthorized -> JoinRoomOutcome.NotSignedIn(e)
            else -> JoinRoomOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        JoinRoomOutcome.NetworkError(e)
    } catch (e: ServerResponseException) {
        JoinRoomOutcome.Unknown(e)
    } catch (e: Throwable) {
        JoinRoomOutcome.NetworkError(e)
    }

    override suspend fun leaveRoom(code: String): LeaveRoomOutcome = try {
        api.leave(code)
        removeActiveRoom(code)
        LeaveRoomOutcome.Success
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.NotFound -> {
                removeActiveRoom(code)
                LeaveRoomOutcome.NotFound
            }
            HttpStatusCode.Conflict -> {
                removeActiveRoom(code)
                LeaveRoomOutcome.NotInRoom
            }
            else -> LeaveRoomOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        LeaveRoomOutcome.NetworkError(e)
    } catch (e: Throwable) {
        LeaveRoomOutcome.NetworkError(e)
    }

    override suspend fun getActiveRooms(): GetActiveRoomsOutcome = try {
        val response = api.listActive()
        val body = response.body<ActiveRoomsResponseDto>()
        val rooms = body.rooms.map { it.toDomain() }
        activeRooms.value = rooms
        GetActiveRoomsOutcome.Success(rooms = rooms)
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.Unauthorized -> GetActiveRoomsOutcome.NotSignedIn(e)
            else -> GetActiveRoomsOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        GetActiveRoomsOutcome.NetworkError(e)
    } catch (e: ServerResponseException) {
        GetActiveRoomsOutcome.Unknown(e)
    } catch (e: Throwable) {
        GetActiveRoomsOutcome.NetworkError(e)
    }

    override suspend fun addBot(code: String, seatIndex: Int?): AddBotOutcome = try {
        val response = api.addBot(code, AddBotRequestDto(seatIndex = seatIndex))
        val room = response.body<AddBotResponseDto>().room.toDomain()
        upsertActiveRoom(room)
        AddBotOutcome.Success(room)
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.NotFound -> AddBotOutcome.NotFound
            HttpStatusCode.Forbidden -> AddBotOutcome.NotHost
            HttpStatusCode.Conflict -> when (extractCode(e)) {
                "room_full" -> AddBotOutcome.Full
                "room_not_joinable" -> AddBotOutcome.NotJoinable
                else -> AddBotOutcome.Unknown(e)
            }
            else -> AddBotOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        AddBotOutcome.NetworkError(e)
    } catch (e: ServerResponseException) {
        AddBotOutcome.Unknown(e)
    } catch (e: Throwable) {
        AddBotOutcome.NetworkError(e)
    }

    override suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome = try {
        api.removeBot(code, botUserId)
        RemoveBotOutcome.Success
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.NotFound -> RemoveBotOutcome.NotFound
            HttpStatusCode.Forbidden -> RemoveBotOutcome.NotHost
            else -> RemoveBotOutcome.Unknown(e)
        }
    } catch (e: HttpRequestTimeoutException) {
        RemoveBotOutcome.NetworkError(e)
    } catch (e: Throwable) {
        RemoveBotOutcome.NetworkError(e)
    }

    override fun connect(code: String): RoomConnectionHandle = socket.connect(code)

    private fun upsertActiveRoom(room: Room) = activeRooms.update { current ->
        current.filterNot { it.code == room.code } + room
    }

    private fun removeActiveRoom(code: String) = activeRooms.update { current ->
        current.filterNot { it.code == code }
    }

    private suspend fun extractMessage(e: ClientRequestException): String? = try {
        e.response.body<ProblemEnvelopeDto>().error.message
    } catch (_: Throwable) {
        null
    }

    private suspend fun extractCode(e: ClientRequestException): String? = try {
        e.response.body<ProblemEnvelopeDto>().error.code
    } catch (_: Throwable) {
        null
    }
}
