package com.dangerfield.cards.server.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject

/**
 * Exposed column type that maps a Postgres `JSONB` column to a Kotlin
 * `String`. Writes wrap the value in a `PGobject(type="jsonb")` so the
 * JDBC driver sends it as the right SQL type (text→jsonb has no
 * implicit cast in Postgres); reads pull `PGobject.value` back out as
 * a plain string. Callers serialize/deserialize at the application
 * layer — same shape `products.title_by_locale` reads use, with the
 * write path filled in.
 */
private class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value.orEmpty()
        is String -> value
        else -> value.toString()
    }

    override fun notNullValueToDB(value: String): Any =
        PGobject().apply {
            type = "jsonb"
            this.value = value
        }
}

/**
 * Register a `JSONB` column whose values are exchanged as `String`.
 * Use this for any column declared `JSONB` in Flyway — `text(...)`
 * works for read-only projections but the JDBC driver rejects writes
 * because there's no text→jsonb implicit cast.
 */
fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())
