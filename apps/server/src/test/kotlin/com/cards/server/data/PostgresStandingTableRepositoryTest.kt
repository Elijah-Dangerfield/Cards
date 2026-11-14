package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.StandingTableRegularsTable
import com.dangerfield.cards.server.db.StandingTablesTable
import com.dangerfield.cards.server.domain.StandingTablePatch
import com.dangerfield.cards.server.domain.sampleStandingTable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresStandingTableRepositoryTest : DatabaseTest() {
    @After fun clear() { database.blockingTransaction { StandingTableRegularsTable.deleteAll(); StandingTablesTable.deleteAll() } }

    @Test fun roundTripOwnedRegularAndArchive() = runTest {
        val repo = PostgresStandingTableRepository(database)
        val owner = seedAuthUser(); val regular = seedAuthUser()
        val table = sampleStandingTable(owner = owner)
        repo.create(table)
        assertEquals(table, repo.find(table.code))
        assertEquals(1, repo.countActiveOwnedBy(owner))
        assertEquals(listOf(table), repo.listForUser(owner))
        assertEquals(emptyList(), repo.listForUser(regular))
        repo.upsertRegular(table.code, regular, table.createdAt)
        repo.upsertRegular(table.code, regular, table.updatedAt)
        assertEquals(1, repo.regularCount(table.code))
        assertEquals(listOf(table), repo.listForUser(regular))
        val updated = table.copy(name = "Friday Poker")
        repo.update(table.code, StandingTablePatch(name = updated.name, updatedAt = updated.updatedAt))
        assertEquals(updated, repo.find(table.code))
        repo.archive(table.code, table.updatedAt)
        assertTrue(repo.exists(table.code))
        assertEquals(emptyList(), repo.listForUser(owner))
        assertEquals(emptyList(), repo.listForUser(regular))
        assertEquals(0, repo.countActiveOwnedBy(owner))
    }
    @Test fun disjointConcurrentPatchesPreserveBothFieldsAndArchiveWins() = runTest {
        val repo = PostgresStandingTableRepository(database)
        val table = sampleStandingTable(owner = seedAuthUser())
        repo.create(table)
        val start = CompletableDeferred<Unit>()
        val writes = listOf(
            async { start.await(); repo.update(table.code, StandingTablePatch(name = "New name", updatedAt = table.updatedAt)) },
            async { start.await(); repo.update(table.code, StandingTablePatch(durationMinutes = 180, updatedAt = table.updatedAt)) },
        )
        start.complete(Unit)
        writes.awaitAll()
        val saved = repo.find(table.code)!!
        assertEquals("New name", saved.name)
        assertEquals(180, saved.durationMinutes)
        repo.archive(table.code, table.updatedAt)
        assertEquals(null, repo.update(table.code, StandingTablePatch(name = "Too late", updatedAt = table.updatedAt)))
        assertEquals("New name", repo.find(table.code)?.name)
    }
    @Test fun deletingUserCascadesAndLeavingRemovesOnlyRegular() = runTest {
        val repo = PostgresStandingTableRepository(database)
        val owner = seedAuthUser(); val regular = seedAuthUser()
        val table = sampleStandingTable(owner = owner)
        repo.create(table); repo.upsertRegular(table.code, regular, table.createdAt)
        repo.removeRegular(table.code, regular)
        assertEquals(0, repo.regularCount(table.code))
        assertTrue(repo.exists(table.code))
        database.blockingTransaction { TransactionManager.current().exec("DELETE FROM auth.users WHERE id = '${owner.value}'") }
        assertFalse(repo.exists(table.code))
    }
    @Test fun sweepingStaleRoomsDoesNotDeleteStandingTable() = runTest {
        val repo = PostgresStandingTableRepository(database)
        val table = sampleStandingTable(owner = seedAuthUser())
        repo.create(table)
        PostgresRoomStore(database).deleteStaleRooms(table.createdAt, emptySet())
        assertTrue(repo.exists(table.code))
    }
}
