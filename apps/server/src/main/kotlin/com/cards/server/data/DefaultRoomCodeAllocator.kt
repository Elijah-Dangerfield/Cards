package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.RoomCodeAllocator
import com.dangerfield.cards.server.domain.RoomStore
import com.dangerfield.cards.server.domain.StandingTableRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.random.Random

@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class DefaultRoomCodeAllocator(
    private val random: Random = Random.Default,
    private val store: RoomStore,
    private val standingTables: StandingTableRepository,
) : RoomCodeAllocator {
    private val mutex = Mutex()
    // A failed write-through can leave a live room only in memory until its next mutation.
    private val allocatedCodes = mutableSetOf<String>()

    // Hold the allocation lock through the insert, so room and table creation cannot race.
    override suspend fun <T> withCode(inMemoryCodes: Set<String>, create: suspend (String) -> T): T = mutex.withLock {
        repeat(50) {
            val code = buildString(InMemoryRoomService.CODE_LENGTH) {
                repeat(InMemoryRoomService.CODE_LENGTH) {
                    append(InMemoryRoomService.CODE_ALPHABET[random.nextInt(InMemoryRoomService.CODE_ALPHABET.length)])
                }
            }
            if (code !in allocatedCodes && code !in inMemoryCodes && !store.codeTaken(code) && !standingTables.exists(code)) {
                val result = create(code)
                if (result != null) allocatedCodes.add(code)
                return@withLock result
            }
        }
        error("Couldn't allocate a free room code after 50 attempts")
    }
}
