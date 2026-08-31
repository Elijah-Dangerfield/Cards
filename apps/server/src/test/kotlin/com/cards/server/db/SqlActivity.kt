package com.dangerfield.cards.server.db

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.sql.statements.StatementContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the SQL a block of repository code actually issues, so a test can
 * assert on the *shape* of a query plan rather than on wall-clock time.
 *
 * The one thing that made ENG-45 a five-minute request was cost that scaled
 * with the batch size — a Testcontainer on localhost is fast enough to hide
 * that behind a timing assertion, but the statement count can't hide it.
 *
 * Wired in through Exposed's [GlobalStatementInterceptor] ServiceLoader hook
 * (`src/test/resources/META-INF/services/…`), which is the only way to see
 * inside a transaction a repository opens for itself. Counting is JVM-wide,
 * so [reset] immediately before the call under test.
 *
 * That JVM-wide count is only safe because the server suite runs in one JVM,
 * sequentially — nothing sets `maxParallelForks` and there is no
 * `junit-platform.properties`. Turning on parallel execution will make every
 * assertion against these counters flake against unrelated classes' SQL;
 * scope the counting per-transaction before you do.
 */
object SqlActivity {

    private val statements = AtomicInteger()
    private val commits = AtomicInteger()

    val statementCount: Int get() = statements.get()
    val commitCount: Int get() = commits.get()

    fun reset() {
        statements.set(0)
        commits.set(0)
    }

    internal fun recordStatement() {
        statements.incrementAndGet()
    }

    internal fun recordCommit() {
        commits.incrementAndGet()
    }
}

class SqlActivityInterceptor : GlobalStatementInterceptor {
    override fun beforeExecution(transaction: Transaction, context: StatementContext) {
        SqlActivity.recordStatement()
    }

    override fun beforeCommit(transaction: Transaction) {
        SqlActivity.recordCommit()
    }
}
