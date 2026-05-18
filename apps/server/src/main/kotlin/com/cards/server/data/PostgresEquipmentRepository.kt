package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.db.EquipmentTable
import com.dangerfield.cards.server.db.toJavaInstant
import com.dangerfield.cards.server.db.toKotlinInstant
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.EquipmentRepository
import com.dangerfield.cards.server.domain.EquippedItem
import com.dangerfield.cards.server.domain.UserId
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed-backed equipment store. Last-write-wins semantics are enforced
 * by comparing the incoming op's [Instant] against the persisted row's
 * `updated_at` — older ops are silently rejected (the route surfaces the
 * persisted row as authoritative).
 *
 * Postgres's lack of a portable upsert means we read-then-write in two
 * statements; both happen inside one transaction so a concurrent writer
 * can't slip in between (Exposed's default isolation level is `READ COMMITTED`
 * which is fine for our cardinality — equipment changes per user are
 * infrequent and tiny).
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresEquipmentRepository(
    private val database: Database,
) : EquipmentRepository {

    override suspend fun listEquipped(userId: UserId): List<EquippedItem> = database.transaction {
        EquipmentTable
            .selectAll()
            .where { EquipmentTable.userId eq userId.value }
            .map { row ->
                EquippedItem(
                    productId = row[EquipmentTable.productId],
                    updatedAt = row[EquipmentTable.updatedAt].toKotlinInstant(),
                )
            }
    }

    override suspend fun equip(
        userId: UserId,
        productId: String,
        newUpdatedAt: Instant,
    ): EquippedItem = database.transaction {
        val existing = readRow(userId, productId)
        when {
            existing == null -> {
                EquipmentTable.insert {
                    it[EquipmentTable.userId] = userId.value
                    it[EquipmentTable.productId] = productId
                    it[EquipmentTable.updatedAt] = newUpdatedAt.toJavaInstant()
                }
                EquippedItem(productId, newUpdatedAt)
            }
            existing.updatedAt >= newUpdatedAt -> existing
            else -> {
                EquipmentTable.update({
                    (EquipmentTable.userId eq userId.value) and (EquipmentTable.productId eq productId)
                }) { it[EquipmentTable.updatedAt] = newUpdatedAt.toJavaInstant() }
                EquippedItem(productId, newUpdatedAt)
            }
        }
    }

    override suspend fun unequip(
        userId: UserId,
        productId: String,
        opUpdatedAt: Instant,
    ): EquippedItem? = database.transaction {
        val existing = readRow(userId, productId) ?: return@transaction null
        if (existing.updatedAt > opUpdatedAt) {
            // Newer equip beat this unequip — keep the row and surface
            // the persisted state so the client reconciles.
            return@transaction existing
        }
        EquipmentTable.deleteWhere {
            (EquipmentTable.userId eq userId.value) and (EquipmentTable.productId eq productId)
        }
        null
    }

    private fun readRow(userId: UserId, productId: String): EquippedItem? =
        EquipmentTable
            .selectAll()
            .where { (EquipmentTable.userId eq userId.value) and (EquipmentTable.productId eq productId) }
            .singleOrNull()
            ?.let { row ->
                EquippedItem(
                    productId = row[EquipmentTable.productId],
                    updatedAt = row[EquipmentTable.updatedAt].toKotlinInstant(),
                )
            }
}
