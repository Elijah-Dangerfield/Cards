package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpStatusCode
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

    override suspend fun createRoom(maxSeats: Int?): CreateRoomOutcome = try {
        val response = api.create(CreateRoomRequestDto(maxSeats = maxSeats))
        val body = response.body<CreateRoomResponseDto>()
        CreateRoomOutcome.Success(body.room.toDomain())
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
        JoinRoomOutcome.Success(room = body.room.toDomain(), alreadyJoined = body.alreadyJoined)
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
        LeaveRoomOutcome.Success
    } catch (e: ClientRequestException) {
        when (e.response.status) {
            HttpStatusCode.NotFound -> LeaveRoomOutcome.NotFound
            HttpStatusCode.Conflict -> LeaveRoomOutcome.NotInRoom
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
        GetActiveRoomsOutcome.Success(rooms = body.rooms.map { it.toDomain() })
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

    override fun connect(code: String): RoomConnectionHandle = socket.connect(code)

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
