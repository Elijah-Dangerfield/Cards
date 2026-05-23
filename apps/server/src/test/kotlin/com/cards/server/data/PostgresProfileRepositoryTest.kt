package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.ProfilesTable
import com.dangerfield.cards.server.domain.AvatarGenerator
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UsernameGenerator
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class PostgresProfileRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction { ProfilesTable.deleteAll() }
    }

    @Test
    fun findOrCreate_firstCall_createsFreshProfile() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()

        val profile = repo.findOrCreate(userId)

        assertEquals(userId, profile.userId)
        assertTrue(profile.displayName.isNotBlank())
        assertTrue(profile.avatarEmoji.isNotBlank())
        assertEquals(profile.createdAt, profile.updatedAt)
    }

    @Test
    fun findOrCreate_isIdempotentForSameUserId() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()

        val first = repo.findOrCreate(userId)
        val second = repo.findOrCreate(userId)

        assertEquals(first.userId, second.userId)
        assertEquals(first.displayName, second.displayName)
        assertEquals(first.avatarEmoji, second.avatarEmoji)
    }

    @Test
    fun findById_returnsNullForUnknownUser() = runTest {
        val repo = newRepository()
        assertNull(repo.findById(UserId(UUID.randomUUID())))
    }

    @Test
    fun findById_returnsExistingProfile() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()
        repo.findOrCreate(userId)

        val found = repo.findById(userId)
        assertNotNull(found)
        assertEquals(userId, found.userId)
    }

    @Test
    fun usernameCollision_recoversByRetrying() = runTest {
        // Force a collision: first profile takes "Twin-Ace-100", second
        // tries the same name (collision), then "Twin-Ace-200" succeeds.
        val gen = ScriptedUsernameGenerator(
            listOf("Twin-Ace-100", "Twin-Ace-100", "Twin-Ace-200"),
        )
        val repo = newRepository(usernameGenerator = gen)

        val first = repo.findOrCreate(seedAuthUser())
        val second = repo.findOrCreate(seedAuthUser())

        assertEquals("Twin-Ace-100", first.displayName)
        assertEquals("Twin-Ace-200", second.displayName)
    }

    @Test
    fun differentUserIds_getDifferentProfiles() = runTest {
        val repo = newRepository()
        val a = repo.findOrCreate(seedAuthUser())
        val b = repo.findOrCreate(seedAuthUser())

        assertNotEquals(a.userId, b.userId)
        assertNotEquals(a.displayName, b.displayName)
    }

    @Test
    fun delete_removesExistingProfile() = runTest {
        val repo = newRepository()
        val userId = seedAuthUser()
        repo.findOrCreate(userId)
        assertNotNull(repo.findById(userId))

        repo.delete(userId)

        assertNull(repo.findById(userId))
    }

    @Test
    fun delete_isIdempotentWhenProfileNotPresent() = runTest {
        val repo = newRepository()
        // No row to begin with — should not throw.
        repo.delete(UserId(UUID.randomUUID()))
    }

    private fun newRepository(
        usernameGenerator: UsernameGenerator = AdjectiveNounUsernameGenerator(),
        avatarGenerator: AvatarGenerator = EmojiAvatarGenerator(),
        clock: Clock = Clock.System,
    ): PostgresProfileRepository = PostgresProfileRepository(
        database = database,
        usernameGenerator = usernameGenerator,
        avatarGenerator = avatarGenerator,
        clock = clock,
    )

    private class ScriptedUsernameGenerator(names: List<String>) : UsernameGenerator {
        private val iter = names.iterator()
        override fun random(): String =
            if (iter.hasNext()) iter.next() else error("exhausted scripted names")
    }
}
