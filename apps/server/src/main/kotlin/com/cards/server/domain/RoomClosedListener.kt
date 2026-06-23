package com.dangerfield.cards.server.domain

/**
 * Hook fired when a room is torn down (garbage-collected) by [RoomService] —
 * the last human left, the disconnect reaper freed the final seat, or the sweep
 * reclaimed it. The room's lifecycle is otherwise self-contained in the rooms
 * registry, but a few things hang off a room and must die with it:
 *
 *  - the room's [com.cards.server.game.GameSession] (its bot driver + turn-timer
 *    coroutines, its in-memory entry, its durable snapshot) — otherwise an
 *    abandoned table keeps a live session forever and a recycled code could
 *    resume a stranger's hand;
 *  - (Phase 4) the chip escrow — every seated human's stack is cashed back to
 *    their wallet before the table disappears.
 *
 * [RoomService] invokes this **after** the room is removed and **outside** its
 * mutex, so a listener is free to take the game-session / wallet locks without
 * risking a deadlock or holding the rooms lock across I/O. The room handed over
 * is the final snapshot at teardown (its full member list intact), so a listener
 * can still see who was seated.
 *
 * One contributed implementation wires the teardown; tests default to a no-op.
 */
fun interface RoomClosedListener {
    suspend fun onRoomClosed(room: Room)

    companion object {
        /** Does nothing — the default for direct (test) constructions of [RoomService]. */
        val NoOp: RoomClosedListener = RoomClosedListener { }
    }
}
