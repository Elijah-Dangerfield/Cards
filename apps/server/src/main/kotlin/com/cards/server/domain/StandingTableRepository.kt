package com.dangerfield.cards.server.domain

import kotlin.time.Instant

interface StandingTableRepository {
    suspend fun create(table: StandingTable)
    suspend fun find(code: String): StandingTable?
    suspend fun listForUser(userId: UserId): List<StandingTable>
    suspend fun countActiveOwnedBy(userId: UserId): Int
    suspend fun update(code: String, patch: StandingTablePatch): StandingTable?
    suspend fun archive(code: String, now: Instant)
    suspend fun upsertRegular(code: String, userId: UserId, now: Instant)
    suspend fun removeRegular(code: String, userId: UserId)
    suspend fun regularCount(code: String): Int
    suspend fun exists(code: String): Boolean
}

object NoOpStandingTableRepository : StandingTableRepository {
    override suspend fun create(table: StandingTable) = error("Standing tables not configured")
    override suspend fun find(code: String): StandingTable? = null
    override suspend fun listForUser(userId: UserId): List<StandingTable> = emptyList()
    override suspend fun countActiveOwnedBy(userId: UserId): Int = 0
    override suspend fun update(code: String, patch: StandingTablePatch): StandingTable? = null
    override suspend fun archive(code: String, now: Instant) = Unit
    override suspend fun upsertRegular(code: String, userId: UserId, now: Instant) = Unit
    override suspend fun removeRegular(code: String, userId: UserId) = Unit
    override suspend fun regularCount(code: String): Int = 0
    override suspend fun exists(code: String): Boolean = false
}
