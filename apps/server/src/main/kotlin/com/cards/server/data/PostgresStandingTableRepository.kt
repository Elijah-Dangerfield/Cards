package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.StandingTableRegularsTable as Regulars
import com.dangerfield.cards.server.db.StandingTablesTable as Tables
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.StandingTable
import com.dangerfield.cards.server.domain.StandingTablePatch
import com.dangerfield.cards.server.domain.StandingTableRepository
import com.dangerfield.cards.server.domain.UserId
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Instant

@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class PostgresStandingTableRepository(private val database: Database) : StandingTableRepository {
    override suspend fun create(table: StandingTable) {
        database.transaction {
            Tables.insert {
                it[code] = table.code
                it[ownerUserId] = table.ownerUserId.value
                it[name] = table.name
                it[buyIn] = table.buyIn
                it[maxSeats] = table.maxSeats
                it[weekday] = table.weekday.ordinal.plus(1).toShort()
                it[localTime] = java.time.LocalTime.of(table.localTime.hour, table.localTime.minute)
                it[timeZone] = table.timeZone.id
                it[durationMinutes] = table.durationMinutes
                it[feltProductId] = table.feltProductId
                it[cardBackProductId] = table.cardBackProductId
                it[createdAt] = table.createdAt.toJavaInstant()
                it[updatedAt] = table.updatedAt.toJavaInstant()
                it[archivedAt] = table.archivedAt?.toJavaInstant()
            }
        }
    }
    override suspend fun find(code: String): StandingTable? = database.transaction {
        Tables.selectAll().where { Tables.code eq code }.singleOrNull()?.toStandingTable()
    }
    override suspend fun listForUser(userId: UserId): List<StandingTable> = database.transaction {
        val regularCodes = Regulars.selectAll().where { Regulars.userId eq userId.value }.map { it[Regulars.code] }
        Tables.selectAll().where {
            ((Tables.ownerUserId eq userId.value) or (Tables.code inList regularCodes)) and Tables.archivedAt.isNull()
        }.map { it.toStandingTable() }
    }
    override suspend fun countActiveOwnedBy(userId: UserId): Int = database.transaction {
        Tables.selectAll().where { (Tables.ownerUserId eq userId.value) and Tables.archivedAt.isNull() }.count().toInt()
    }
    override suspend fun update(code: String, patch: StandingTablePatch): StandingTable? = database.transaction {
        val changed = Tables.update({ (Tables.code eq code) and Tables.archivedAt.isNull() }) {
            patch.name?.let { value -> it[name] = value }
            patch.buyIn?.let { value -> it[buyIn] = value }
            patch.maxSeats?.let { value -> it[maxSeats] = value }
            patch.weekday?.let { value -> it[weekday] = (value.ordinal + 1).toShort() }
            patch.localTime?.let { value -> it[localTime] = java.time.LocalTime.of(value.hour, value.minute) }
            patch.timeZone?.let { value -> it[timeZone] = value.id }
            patch.durationMinutes?.let { value -> it[durationMinutes] = value }
            it[updatedAt] = patch.updatedAt.toJavaInstant()
        }
        if (changed == 0) null else Tables.selectAll().where { Tables.code eq code }.single().toStandingTable()
    }
    override suspend fun archive(code: String, now: Instant) {
        database.transaction { Tables.update({ Tables.code eq code }) { it[archivedAt] = now.toJavaInstant(); it[updatedAt] = now.toJavaInstant() } }
    }
    override suspend fun upsertRegular(code: String, userId: UserId, now: Instant) {
        database.transaction {
            Regulars.upsert(Regulars.code, Regulars.userId, onUpdate = { it[Regulars.lastJoinedAt] = now.toJavaInstant() }) {
                it[Regulars.code] = code
                it[Regulars.userId] = userId.value
                it[firstJoinedAt] = now.toJavaInstant()
                it[lastJoinedAt] = now.toJavaInstant()
            }
        }
    }
    override suspend fun removeRegular(code: String, userId: UserId) {
        database.transaction { Regulars.deleteWhere { (Regulars.code eq code) and (Regulars.userId eq userId.value) } }
    }
    override suspend fun regularCount(code: String): Int = database.transaction {
        Regulars.selectAll().where { Regulars.code eq code }.count().toInt()
    }
    override suspend fun exists(code: String): Boolean = database.transaction {
        Tables.selectAll().where { Tables.code eq code }.any()
    }
}

private fun ResultRow.toStandingTable(): StandingTable = StandingTable(
    code = this[Tables.code], ownerUserId = UserId(this[Tables.ownerUserId]), name = this[Tables.name],
    buyIn = this[Tables.buyIn], maxSeats = this[Tables.maxSeats], weekday = DayOfWeek.entries[this[Tables.weekday].toInt() - 1],
    localTime = this[Tables.localTime].let { LocalTime(it.hour, it.minute) }, timeZone = TimeZone.of(this[Tables.timeZone]),
    durationMinutes = this[Tables.durationMinutes], feltProductId = this[Tables.feltProductId], cardBackProductId = this[Tables.cardBackProductId],
    createdAt = this[Tables.createdAt].toKotlinInstant(), updatedAt = this[Tables.updatedAt].toKotlinInstant(), archivedAt = this[Tables.archivedAt]?.toKotlinInstant(),
)
