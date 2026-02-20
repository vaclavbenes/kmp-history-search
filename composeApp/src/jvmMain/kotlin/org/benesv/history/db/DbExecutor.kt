package org.benesv.history.db

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.benesv.history.api.app.Favicons
import org.benesv.history.api.app.History
import org.benesv.history.api.app.Tokens
import org.benesv.history.core.Log
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * A wrapper around database transactions that handles synchronization and error handling.
 * Encapsulates the database instance and mutex to simplify repository code.
 */
class DbExecutor(
    private val database: Database,
    private val mutex: Mutex = Mutex(),
) {
    /**
     * Executes a database query within a transaction, ensuring thread-safe access.
     * @param block The database operations to execute within the transaction context.
     * @return The result of the transaction block.
     */
    suspend fun <T> query(block: Transaction.() -> T): T =
        mutex.withLock {
            transaction(db = database) { block() }
        }

    suspend fun initSchema() {
        query {
            Log.i("Initializing database schema")
            SchemaUtils.createMissingTablesAndColumns(History, Favicons, Tokens)
        }
    }
}
