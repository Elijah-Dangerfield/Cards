package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.InventoryTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.OwnedItem
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed-backed inventory store. Idempotent on the (user_id,
 * product_id) primary key — duplicate inserts race-fail at the DB and
 * the read-after-write returns the original row, matching the
 * "first-purchase-wins" cost semantics.
 *
 * Read-then-write inside a single transaction so a concurrent writer
 * can't slip between the check and the insert. Postgres's default
 * READ COMMITTED isolation is fine here (we're not phantom-reading
 * across rows, just contending on a single PK).
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresInventoryRepository(
    private val database: Database,
) : InventoryRepository {

    override suspend fun listOwned(userId: UserId): List<OwnedItem> = database.transaction {
        InventoryTable
            .selectAll()
            .where { InventoryTable.userId eq userId.value }
            .map { it.toDomain() }
    }

    override suspend fun recordPurchase(
        userId: UserId,
        productId: String,
        costChipsAtPurchase: Long,
        purchasedAt: Instant,
    ): OwnedItem = upsert(
        userId = userId,
        productId = productId,
        costChipsAtPurchase = costChipsAtPurchase,
        acquiredAt = purchasedAt,
        source = AcquisitionSource.Purchased,
    )

    override suspend fun recordEarnedGrant(
        userId: UserId,
        productId: String,
        grantedAt: Instant,
    ): OwnedItem = upsert(
        userId = userId,
        productId = productId,
        // Earned grants have no chip-cost provenance. Stored as 0 so the
        // future "you paid X chips for this" surface can ignore zero
        // alongside the discriminator.
        costChipsAtPurchase = 0L,
        acquiredAt = grantedAt,
        source = AcquisitionSource.Earned,
    )

    private suspend fun upsert(
        userId: UserId,
        productId: String,
        costChipsAtPurchase: Long,
        acquiredAt: Instant,
        source: AcquisitionSource,
    ): OwnedItem = database.transaction {
        val existing = readRow(userId, productId)
        if (existing != null) return@transaction existing

        try {
            InventoryTable.insert {
                it[InventoryTable.userId] = userId.value
                it[InventoryTable.productId] = productId
                it[InventoryTable.costChipsAtPurchase] = costChipsAtPurchase
                it[InventoryTable.purchasedAt] = acquiredAt.toJavaInstant()
                it[InventoryTable.acquisitionSource] = source.wire
            }
            OwnedItem(
                productId = productId,
                costChipsAtPurchase = costChipsAtPurchase,
                purchasedAt = acquiredAt,
                acquisitionSource = source,
            )
        } catch (e: ExposedSQLException) {
            // Concurrent writer beat us between the read and the insert.
            // Re-read and return whatever they wrote — first-grant-wins
            // is preserved (matches the historical "first-purchase-wins"
            // before the earned path landed).
            if (e.isUniqueViolation()) {
                readRow(userId, productId) ?: throw e
            } else {
                throw e
            }
        }
    }

    override suspend fun deleteAllForUser(userId: UserId) {
        database.transaction {
            InventoryTable.deleteWhere { InventoryTable.userId eq userId.value }
        }
    }

    private fun readRow(userId: UserId, productId: String): OwnedItem? =
        InventoryTable
            .selectAll()
            .where { (InventoryTable.userId eq userId.value) and (InventoryTable.productId eq productId) }
            .singleOrNull()
            ?.toDomain()

    private fun org.jetbrains.exposed.sql.ResultRow.toDomain(): OwnedItem = OwnedItem(
        productId = this[InventoryTable.productId],
        costChipsAtPurchase = this[InventoryTable.costChipsAtPurchase],
        purchasedAt = this[InventoryTable.purchasedAt].toKotlinInstant(),
        acquisitionSource = AcquisitionSource.fromWire(this[InventoryTable.acquisitionSource]),
    )

    private fun ExposedSQLException.isUniqueViolation(): Boolean {
        val sqlState = (cause as? java.sql.SQLException)?.sqlState
            ?: (this as? java.sql.SQLException)?.sqlState
        return sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE
    }

    companion object {
        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
