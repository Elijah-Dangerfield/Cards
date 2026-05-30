package com.dangerfield.cards.libraries.rooms

import kotlinx.coroutines.flow.Flow

/**
 * Live handle to a room's WebSocket. Exposes two read flows + a write
 * channel, all backed by one underlying connection.
 *
 *  - [connection] — lobby-shaped state ([RoomConnection]). Replays the
 *    latest emission to new collectors so a screen that mounts
 *    mid-session immediately sees the current room.
 *  - [gameplayFrames] — raw gameplay frames from the server (state
 *    snapshots, events, intent acks). The room's gameplay session
 *    consumes this; the lobby ignores it.
 *  - [send] — write a [ClientFrame] to the server. Suspends until the
 *    underlying socket accepts the bytes; throws if the connection is
 *    not currently open.
 *
 * Lifecycle: handles for the same room code share one underlying WS
 * across the app. The socket opens when either flow is first collected
 * and closes (after a small linger) when no flow has any active
 * collector. This means the lobby + gameplay screen can both hold their
 * own handle without spinning up duplicate connections, and navigating
 * between them never tears the socket down.
 */
interface RoomConnectionHandle {
    val connection: Flow<RoomConnection>
    val gameplayFrames: Flow<GameplayFrame>
    suspend fun send(frame: ClientFrame)
}
