package com.dangerfield.cards.server.domain

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Durable backing for the room registry (todo.md §B2). [InMemoryRoomService]
 * is a write-through cache in front of this: it writes the full [Room] snapshot
 * here on every mutation and hydrates from here on a cache miss, so room codes +
 * membership survive a server restart.
 *
 * Snapshot semantics (mirrors the B0 `room_sessions` store): [save] persists the
 * whole room and its current member set, replacing whatever was there. The
 * in-memory map stays authoritative while a room is loaded; this store is only
 * read when memory has no entry for the code.
 */
interface RoomStore {
    /** Persist the room and its full member set, replacing any prior snapshot. */
    suspend fun save(room: Room)

    /** Remove the room and (via cascade) its members. */
    suspend fun delete(code: String)

    /** Hydrate a single room, or null if no durable row exists for [code]. */
    suspend fun load(code: String): Room?

    /**
     * Delete persisted rooms created before [olderThan] whose code is NOT in
     * [keepCodes], returning the count removed. [keepCodes] is the set of rooms
     * still live in the in-memory authority — they own their own durability and
     * must never be swept out from under a running game. This reclaims the rooms
     * that B2 persistence would otherwise leak: a process death leaves their rows
     * behind with no in-memory owner, and (unlike the in-flight reaper) nothing
     * else ever deletes them. Runs from the admin sweep cron. Returns 0 for
     * stores with no durable backing.
     */
    suspend fun deleteStaleRooms(olderThan: Instant, keepCodes: Set<String>): Int
}

/**
 * No-op store for unit tests + any wiring that doesn't want Postgres in the
 * loop. [load] always misses, so an [InMemoryRoomService] backed by this behaves
 * exactly like the pre-B2 in-memory-only service.
 */
@OptIn(ExperimentalTime::class)
object NoOpRoomStore : RoomStore {
    override suspend fun save(room: Room) = Unit
    override suspend fun delete(code: String) = Unit
    override suspend fun load(code: String): Room? = null
    override suspend fun deleteStaleRooms(olderThan: Instant, keepCodes: Set<String>): Int = 0
}
